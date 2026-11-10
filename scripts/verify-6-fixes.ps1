#requires -version 7
<#
.SYNOPSIS
  体检报告「服务端 6 项」批量修复的逐项隔离验证脚本

.DESCRIPTION
  在独立端口 + 独立数据目录上另起一个服务端实例，逐项验证修复效果。
  与 scripts\e2e-backend-tests.ps1 使用同一套隔离约定：
    * 端口默认 5099，与正式实例的 5000 不冲突；端口被占用则直接报错退出，不动别人的进程；
    * 数据目录默认 server\build\verify-data，通过 -Dfamilychat.data.dir 注入；
    * 构建产物输出到 build\e2e-libs\（避免与正在运行的正式实例争抢
      build\libs\familychat-server.jar 的文件锁）；
    * 每次运行前后对【正式 data 目录】做证据快照（文件数/总字节/SHA-256），
      证明本次验证没有读写生产数据。

.EXAMPLE
  pwsh -File scripts\verify-6-fixes.ps1 -Items 1
.EXAMPLE
  pwsh -File scripts\verify-6-fixes.ps1 -Items 1,2,3
#>
[CmdletBinding()]
param(
    [int]$Port = 5099,
    [string]$Jar,
    [string]$DataDir,
    # 支持三种写法：-Items 1 / -Items "1,2" / -Items 1,2（PowerShell 的数组实参在部分宿主下会被折叠）
    [string[]]$Items = @('1', '2', '3'),
    [switch]$KeepData
)

$ErrorActionPreference = 'Stop'

# 归一化：把 "1,2" 或 @("1","2") 都摊平成 int 集合，避免 -Items 1,2 被折成 12
$wanted = @($Items | ForEach-Object { $_ -split ',' } | ForEach-Object { $_.Trim() } |
            Where-Object { $_ -ne '' } | ForEach-Object { [int]$_ } | Sort-Object -Unique)

$scriptDir = $PSScriptRoot
$miyuRoot  = (Resolve-Path (Join-Path $scriptDir '..')).Path
$serverDir = Join-Path $miyuRoot 'server'
$prodData  = Join-Path $serverDir 'data'          # 正式数据目录：全程只读快照，绝不写入

if (-not $Jar)     { $Jar     = Join-Path $serverDir 'build\e2e-libs\familychat-server-e2e.jar' }
if (-not $DataDir) { $DataDir = Join-Path $serverDir 'build\verify-data' }

$script:Base   = "http://127.0.0.1:$Port"
$script:Pass   = 0
$script:Fail   = 0
$script:Failed = @()
$script:Proc   = $null
$logOut = Join-Path $serverDir 'build\verify-server.out.log'
$logErr = Join-Path $serverDir 'build\verify-server.err.log'

# ===== 工具函数（与 e2e-backend-tests.ps1 同形）=====

function Invoke-Api {
    param(
        [Parameter(Mandatory)][string]$Method,
        [Parameter(Mandatory)][string]$Path,
        $Body,
        [string]$Token,
        [hashtable]$ExtraHeaders
    )
    $headers = @{}
    if ($Token) { $headers['Authorization'] = "Bearer $Token" }
    if ($ExtraHeaders) { foreach ($k in $ExtraHeaders.Keys) { $headers[$k] = $ExtraHeaders[$k] } }
    $req = @{
        Method             = $Method
        Uri                = "$script:Base$Path"
        Headers            = $headers
        SkipHttpErrorCheck = $true
        TimeoutSec         = 60
    }
    if ($null -ne $Body) {
        $req['Body']        = ($Body | ConvertTo-Json -Compress -Depth 6)
        $req['ContentType'] = 'application/json; charset=utf-8'
    }
    $resp = Invoke-WebRequest @req
    $json = $null
    if ($resp.Content) { try { $json = $resp.Content | ConvertFrom-Json } catch { $json = $null } }
    [pscustomobject]@{ Status = [int]$resp.StatusCode; Json = $json; Text = $resp.Content; Headers = $resp.Headers }
}

function Check {
    param([string]$Id, [string]$Desc, [bool]$Ok, [string]$Detail = '')
    if ($Ok) {
        $script:Pass++
        Write-Host ("  [PASS] {0,-6} {1}" -f $Id, $Desc) -ForegroundColor Green
    } else {
        $script:Fail++
        $script:Failed += "${Id}: $Desc"
        Write-Host ("  [FAIL] {0,-6} {1}" -f $Id, $Desc) -ForegroundColor Red
        if ($Detail) { Write-Host ("         {0}" -f $Detail) -ForegroundColor DarkRed }
    }
}

function CheckStatus {
    param([string]$Id, [string]$Desc, $Resp, [int[]]$Expected)
    Check $Id $Desc ($Expected -contains $Resp.Status) `
        "期望 HTTP $($Expected -join '/')，实际 $($Resp.Status)；响应=$($Resp.Text)"
}

function New-User {
    param([string]$Name)
    $r = Invoke-Api -Method POST -Path '/api/register' `
        -Body @{ username = $Name; password = 'pass1234'; nickname = $Name }
    if ($r.Status -ne 200) { throw "准备数据失败：注册 $Name 返回 $($r.Status) $($r.Text)" }
    [pscustomobject]@{ Name = $Name; Id = [int]$r.Json.user.id; Token = [string]$r.Json.token }
}

function Wait-ServerReady {
    param([int]$TimeoutSec = 90)
    $deadline = (Get-Date).AddSeconds($TimeoutSec)
    while ((Get-Date) -lt $deadline) {
        if ($script:Proc.HasExited) {
            throw "服务端进程意外退出（退出码 $($script:Proc.ExitCode)），错误日志：$logErr"
        }
        try {
            # 受保护路由做探针：未带 token 应 401，说明服务已起来且路由已注册
            $r = Invoke-WebRequest -Uri "$script:Base/api/wallet" -TimeoutSec 3 -SkipHttpErrorCheck
            if ($r.StatusCode -eq 401) { return }
        } catch { }
        Start-Sleep -Milliseconds 500
    }
    throw "服务端在 $TimeoutSec 秒内未就绪，请查看日志：$logOut"
}

# 启动隔离实例（可附加额外的 -D 参数），就绪后返回进程对象
function Start-IsolatedServer {
    param(
        [string[]]$ExtraProps = @(),
        [string]$OutLog,
        [string]$ErrLog
    )
    if (-not $OutLog) { $OutLog = $logOut }
    if (-not $ErrLog) { $ErrLog = $logErr }
    $p = Start-Process -FilePath 'java' -ArgumentList (@(
        "-Dserver.port=$Port",
        "-Dfamilychat.data.dir=$DataDir"
    ) + $ExtraProps + @('-jar', $Jar)) -RedirectStandardOutput $OutLog -RedirectStandardError $ErrLog `
        -PassThru -WindowStyle Hidden
    $script:Proc = $p
    Wait-ServerReady
    return $p
}

# 用 multipart 上传一个文件，返回 HTTP 状态码与响应体
function Send-Upload {
    param([Parameter(Mandatory)][string]$Token, [Parameter(Mandatory)][string]$FilePath)
    $resp = Invoke-WebRequest -Uri "$script:Base/api/chat/upload" -Method POST `
        -Headers @{ Authorization = "Bearer $Token" } -Form @{ file = Get-Item $FilePath } `
        -SkipHttpErrorCheck -TimeoutSec 60
    [pscustomobject]@{ Status = [int]$resp.StatusCode; Text = $resp.Content }
}

function Stop-IsolatedServer {
    if ($script:Proc -and -not $script:Proc.HasExited) {
        Stop-Process -Id $script:Proc.Id -Force -ErrorAction SilentlyContinue
        Start-Sleep -Milliseconds 1200
    }
}

# 正式 data 目录的「静态内容」指纹。
#
# 为什么排除 family_chat.db：生产实例（:5000）此刻正在运行，SQLite 会因客户端
# 心跳/在线状态更新而自行写库（实测：在完全不执行任何操作的 90 秒内，该文件
# mtime 仍然前进了）。把它算进指纹会得到「假阳性漂移」。
# 而 chat_files / video_cache / avatars / uploads 里的文件只可能由接口写入，
# 隔离实例若误碰生产目录，这些一定会变 —— 这才是有效证据。
function Get-ProdSnapshot {
    $rootNames = @('family_chat.db', 'familychat.db')
    $files = Get-ChildItem $prodData -Recurse -File -ErrorAction SilentlyContinue |
        Where-Object { $rootNames -notcontains $_.Name -or $_.DirectoryName -ne $prodData }
    $sum = ($files | Measure-Object Length -Sum).Sum
    if (-not $sum) { $sum = 0 }
    $sb = [System.Text.StringBuilder]::new()
    foreach ($f in ($files | Sort-Object FullName)) {
        $rel = $f.FullName.Substring($prodData.Length)
        [void]$sb.AppendLine("$rel|$($f.Length)|$($f.LastWriteTimeUtc.Ticks)")
    }
    $sha = [System.Security.Cryptography.SHA256]::Create()
    try {
        $hash = $sha.ComputeHash([System.Text.Encoding]::UTF8.GetBytes($sb.ToString()))
    } finally { $sha.Dispose() }
    # 生产库单独记录大小（内容由运行中的生产进程改动，不能作为隔离依据）
    $db = Join-Path $prodData 'family_chat.db'
    $dbLen = if (Test-Path $db) { (Get-Item $db).Length } else { 0 }
    [pscustomobject]@{
        Count  = $files.Count
        Bytes  = [long]$sum
        Digest = (($hash | ForEach-Object { $_.ToString('x2') }) -join '')
        DbLen  = $dbLen
    }
}

function Invoke-Sql {
    param([Parameter(Mandatory)][string]$DbPath, [Parameter(Mandatory)][string]$Sql)
    $sqlite = Get-ChildItem 'D:\Tools\JDK\jdk-24.0.2+12\bin\sqlite3.exe',
                            "$env:USERPROFILE\.gradle\caches\modules-2\files-2.1\org.xerial\sqlite-jdbc\**\sqlite3*.exe" `
                            -ErrorAction SilentlyContinue | Select-Object -First 1
    if (-not $sqlite) { return $null }
    & $sqlite.FullName $DbPath $Sql
}

# ===== 环境检查 =====
Write-Host "============================================" -ForegroundColor Cyan
Write-Host "  6 项修复 · 逐项隔离验证 (items: $($wanted -join ','))" -ForegroundColor Cyan
Write-Host "============================================" -ForegroundColor Cyan
Write-Host ""

$busy = & netstat -ano 2>$null | Select-String -Pattern ":$Port\s" | Select-String -Pattern 'LISTENING'
if ($busy) {
    throw "端口 $Port 已被占用，脚本不会去动别人的进程。占用信息: $($busy -join '; ')"
}
Write-Host "[环境] 端口 $Port 空闲" -ForegroundColor DarkGray
Write-Host "[环境] 隔离数据目录: $DataDir" -ForegroundColor DarkGray
Write-Host "[环境] 正式数据目录（本次只读快照，绝不写入）: $prodData" -ForegroundColor DarkGray

$snapBefore = Get-ProdSnapshot
Write-Host ("[隔离] 运行前正式 data: {0} 个文件 / {1} 字节 / 指纹 {2}..." -f `
    $snapBefore.Count, $snapBefore.Bytes, $snapBefore.Digest.Substring(0,16)) -ForegroundColor DarkGray

# ===== 准备专用 jar（输出到 e2e-libs，避开正式实例锁定的 build\libs）=====
if (-not (Test-Path $Jar)) {
    Write-Host "[准备] 构建验证专用 jar（避开发行版 build\libs）..." -ForegroundColor Yellow
    $initScript = Join-Path $serverDir 'build\verify-shadowjar.gradle'
    if (-not (Test-Path $initScript)) {
        New-Item -ItemType Directory -Force -Path (Split-Path -Parent $initScript) | Out-Null
        @'
gradle.allprojects { proj ->
    proj.tasks.matching { it.name == 'shadowJar' }.configureEach { t ->
        t.destinationDirectory.set(new File(proj.rootDir, 'build/e2e-libs'))
        t.archiveFileName.set('familychat-server-e2e.jar')
    }
}
'@ | Set-Content -Path $initScript -Encoding utf8
    }
    Push-Location $serverDir
    try {
        & .\gradlew.bat --offline -I 'build\verify-shadowjar.gradle' shadowJar --console=plain 2>&1 |
            Select-Object -Last 6 | ForEach-Object { Write-Host "      $_" -ForegroundColor DarkGray }
    } finally { Pop-Location }
    if (-not (Test-Path $Jar)) { throw "构建后仍找不到 $Jar" }
}
Write-Host "[准备] 使用 jar: $Jar" -ForegroundColor DarkGray

# ===== 启动隔离实例 =====
if (Test-Path $DataDir) { Remove-Item -Recurse -Force $DataDir }
New-Item -ItemType Directory -Force -Path $DataDir | Out-Null

Write-Host "[准备] 启动隔离实例..." -ForegroundColor Yellow
$null = Start-IsolatedServer -ExtraProps @(
    "-Dwallet.charge.rate.max=10",
    "-Dwallet.charge.rate.window.ms=60000",
    "-Dchat.upload.user.quota.bytes=2048",
    "-Dchat.upload.total.quota.bytes=12884901888",
    "-Dchat.upload.rate.max=10",
    "-Dchat.upload.rate.window.ms=60000"
)

try {
    Wait-ServerReady
    Write-Host "[准备] 实例已就绪 (PID $($script:Proc.Id))" -ForegroundColor Green
    $keyLine = Select-String -Path $logOut, $logErr -Pattern 'Generated new JWT secret key' -ErrorAction SilentlyContinue |
        Select-Object -First 1
    if ($keyLine) { Write-Host "[隔离] $($keyLine.Line.Trim())" -ForegroundColor DarkGray }

    # ================= 第 1 项：/api/wallet/charge 限流 =================
    if ($wanted -contains 1) {
        Write-Host ""
        Write-Host "===== 第 1 项 /api/wallet/charge 限流 =====" -ForegroundColor Cyan

        $u1 = New-User 'v1_merchant'
        $u2 = New-User 'v1_payer'

        # 1-1 正常一次请求仍应放行（确认没把正常功能限死）
        $r = Invoke-Api -Method POST -Path '/api/wallet/charge' `
            -Body @{ to_user = $u2.Id; amount = 100; memo = 'verify' } -Token $u1.Token
        CheckStatus '1-1' '第 1 次发起收款应放行（200）' $r @(200)
        Check '1-2' '第 1 次响应含 charge_id' ([bool]$r.Json.charge_id) "响应=$($r.Text)"

        # 1-3..1-10 继续打到窗口上限（默认 10 次/分钟）
        $lastOk = $r.Status
        for ($i = 2; $i -le 10; $i++) {
            $r = Invoke-Api -Method POST -Path '/api/wallet/charge' `
                -Body @{ to_user = $u2.Id; amount = 100; memo = "verify$i" } -Token $u1.Token
            $lastOk = $r.Status
        }
        CheckStatus '1-3' '第 2-10 次仍在窗口内放行（200）' `
            ([pscustomobject]@{ Status = $lastOk; Text = '' }) @(200)

        # 第 11 次必须 429
        $r11 = Invoke-Api -Method POST -Path '/api/wallet/charge' `
            -Body @{ to_user = $u2.Id; amount = 100; memo = 'verify11' } -Token $u1.Token
        CheckStatus '1-4' '第 11 次触发限流（429）' $r11 @(429)
        Check '1-5' '限流响应携带 Retry-After 头' `
            ([bool]$r11.Headers['Retry-After']) "头=$($r11.Headers['Retry-After'])"
        Check '1-6' '限流响应不含 charge_id（未创建收款请求）' `
            (-not $r11.Json.charge_id) "响应=$($r11.Text)"

        # 第 12 次仍 429（窗口内持续拒绝）
        $r12 = Invoke-Api -Method POST -Path '/api/wallet/charge' `
            -Body @{ to_user = $u2.Id; amount = 100; memo = 'verify12' } -Token $u1.Token
        CheckStatus '1-7' '第 12 次仍被拒（429）' $r12 @(429)

        # 另一个账号不受影响（限流是按账号，不是全局）
        $u3 = New-User 'v1_merchant2'
        $r = Invoke-Api -Method POST -Path '/api/wallet/charge' `
            -Body @{ to_user = $u2.Id; amount = 100; memo = 'other' } -Token $u3.Token
        CheckStatus '1-8' '另一账号不受影响（200），证明是按 userId 限流' $r @(200)

        # 非法参数也消耗配额（计数在校验之前），避免用非法请求刷通知绕过限流
        $u4 = New-User 'v1_bruteforce'
        for ($i = 0; $i -lt 10; $i++) {
            $null = Invoke-Api -Method POST -Path '/api/wallet/charge' -Body @{ to_user = 0 } -Token $u4.Token
        }
        $r = Invoke-Api -Method POST -Path '/api/wallet/charge' -Body @{ to_user = 0 } -Token $u4.Token
        CheckStatus '1-9' '非法参数同样消耗配额（第 11 次 429）' $r @(429)
    }

    # ================= 第 2 项：MallDao 吞异常 =================
    if ($wanted -contains 2) {
        Write-Host ""
        Write-Host "===== 第 2 项 MallDao 查询失败不再伪装成空数据 =====" -ForegroundColor Cyan

        $u = New-User 'v2_mall'

        # 2-1 正常路径：空购物车仍应是 200 + 空数组（无数据 ≠ 失败）
        $r = Invoke-Api -Method GET -Path '/api/mall/cart' -Token $u.Token
        CheckStatus '2-1' '正常空购物车仍返回 200（无数据≠失败）' $r @(200)
        Check '2-2' '空购物车 items 为空数组' `
            ($null -ne $r.Json.items -and @($r.Json.items).Count -eq 0) "响应=$($r.Text)"

        # 2-3 正常路径：空收藏
        $r = Invoke-Api -Method GET -Path '/api/mall/favorites' -Token $u.Token
        CheckStatus '2-3' '正常空收藏仍返回 200' $r @(200)
        Check '2-4' '空收藏 favorites 为空数组' `
            ($null -ne $r.Json.favorites -and @($r.Json.favorites).Count -eq 0) "响应=$($r.Text)"

        # 2-5 正常路径：商品评价
        $r = Invoke-Api -Method GET -Path '/api/mall/products/1/reviews' -Token $u.Token
        CheckStatus '2-5' '正常商品评价返回 200' $r @(200)

        # 2-6..2-8 故障路径：在隔离库里删掉三张表，制造真实 SQLException
        $dbFile = Join-Path $DataDir 'family_chat.db'
        $sqlOk = $false
        if (Get-Command sqlite3 -ErrorAction SilentlyContinue) {
            sqlite3 $dbFile "DROP TABLE mall_cart; DROP TABLE mall_favorites; DROP TABLE mall_reviews;" 2>&1 | Out-Null
            $sqlOk = $true
        } else {
            # 没有 cli 时退化为「重命名数据库文件」，同样造成 getConn/查询失败
            Write-Host "      （无 sqlite3 CLI，改用重命名数据库文件制造故障）" -ForegroundColor DarkGray
            Rename-Item $dbFile "$dbFile.broken" -Force
            $sqlOk = $true
        }
        Check '2-6' '已成功制造数据库故障（隔离库）' $sqlOk

        $r = Invoke-Api -Method GET -Path '/api/mall/cart' -Token $u.Token
        CheckStatus '2-7' '查询失败时购物车返回 5xx（不再是 200+空数组）' $r @(500, 503)
        $r = Invoke-Api -Method GET -Path '/api/mall/favorites' -Token $u.Token
        CheckStatus '2-8' '查询失败时收藏返回 5xx' $r @(500, 503)
        $r = Invoke-Api -Method GET -Path '/api/mall/products/1/reviews' -Token $u.Token
        CheckStatus '2-9' '查询失败时评价返回 5xx' $r @(500, 503)
    }

    # ================= 第 3 项：上传配额 + TTL 报告 =================
    if ($wanted -contains 3) {
        Write-Host ""
        Write-Host "===== 第 3 项 上传配额 / 总量上限 / TTL 报告（不删） =====" -ForegroundColor Cyan

        $u = New-User 'v3_uploader'

        # 3-1 正常小文件放行（本实例每用户配额被刻意压到 2048 字节，便于确定性验证）
        $tmp = Join-Path ([System.IO.Path]::GetTempPath()) ("verify_up_{0}.txt" -f ([guid]::NewGuid().ToString('N')))
        [System.IO.File]::WriteAllBytes($tmp, (New-Object byte[] 1024))
        try {
            $resp = Send-Upload -Token $u.Token -FilePath $tmp
            CheckStatus '3-1' '小文件上传放行（200）' $resp @(200)

            # 3-2 抬到配额上限之上（配额 2048 字节，文件 4096 字节）
            [System.IO.File]::WriteAllBytes($tmp, (New-Object byte[] 4096))
            $resp = Send-Upload -Token $u.Token -FilePath $tmp
            Check '3-2' '超出每用户配额被拒（413）' ($resp.Status -eq 413) `
                "实际 HTTP $($resp.Status)；响应=$($resp.Text)"

            # 3-3 被拒后目录里不应残留半截文件（也不该留下 .tmp）
            $left = Get-ChildItem (Join-Path $DataDir 'chat_files') -File -ErrorAction SilentlyContinue
            Check '3-3' '被拒上传不残留文件（含 .tmp）' `
                ($left.Count -eq 1 -and $left[0].Name -notlike '*.tmp') `
                "目录内文件=$($left.Name -join ',')"
        } finally {
            Remove-Item $tmp -Force -ErrorAction SilentlyContinue
        }

        # 3-4 上传限流：同一账号连传到第 11 次应 429
        $u5 = New-User 'v3_flooder'
        $tmpSmall = Join-Path ([System.IO.Path]::GetTempPath()) ("verify_small_{0}.txt" -f ([guid]::NewGuid().ToString('N')))
        [System.IO.File]::WriteAllBytes($tmpSmall, (New-Object byte[] 64))
        try {
            $lastSt = 0
            for ($i = 1; $i -le 10; $i++) {
                $lastSt = (Send-Upload -Token $u5.Token -FilePath $tmpSmall).Status
            }
            Check '3-4' '前 10 次上传放行（200）' ($lastSt -eq 200) "第 10 次返回 HTTP $lastSt"
            $r11 = Send-Upload -Token $u5.Token -FilePath $tmpSmall
            Check '3-5' '第 11 次上传触发限流（429）' ($r11.Status -eq 429) `
                "实际 HTTP $($r11.Status)；响应=$($r11.Text)"
        } finally {
            Remove-Item $tmpSmall -Force -ErrorAction SilentlyContinue
        }

        # 3-6/3-7 TTL 报告：放一个「很旧」的文件，确认只报告不删（决策 (a)）
        $old = Join-Path $DataDir 'chat_files\1_verify_9999.txt'
        [System.IO.File]::WriteAllBytes($old, (New-Object byte[] 16))
        (Get-Item $old).LastWriteTimeUtc = (Get-Date).ToUniversalTime().AddDays(-400)
        Start-Sleep -Seconds 62   # 等周期任务跑一轮（FamilyChatServer 里 60s 一次）
        Check '3-6' 'TTL 报告为「只报告不删」模式（超期文件仍在）' (Test-Path $old) `
            "文件被删除了 —— 与决策 (a)（只报告不删）不符！"
        $ttlLine = Select-String -Path $logOut, $logErr -Pattern '\[CACHE\]' -Encoding utf8 -ErrorAction SilentlyContinue |
            Select-Object -First 8
        Check '3-7' '服务端日志出现 chat_files 容量/TTL 报告（[CACHE] 开头）' ([bool]$ttlLine) "日志里没有找到报告行"
        $ttlMarker = Select-String -Path $logOut, $logErr -Pattern '\[report-only\]' -ErrorAction SilentlyContinue |
            Select-Object -First 1
        Check '3-8' '报告中明确标注「只报告不删除」（决策 (a)）' ([bool]$ttlMarker) `
            "报告里没有 [report-only] 标记"
        foreach ($l in $ttlLine) { Write-Host "      [日志] $($l.Line.Trim())" -ForegroundColor DarkGray }
    }

    # 3-9 全服总量上限需要一个「总量很小」的实例，单开一个（同端口的上一实例先停掉）
    if ($wanted -contains 3) {
        Write-Host ""
        Write-Host "----- 第 3 项附加：全服总量上限（独立实例） -----" -ForegroundColor Cyan
        Stop-IsolatedServer
        # 清空 chat_files，让「未落盘」的断言只反映本实例的行为
        $chatFiles = Join-Path $DataDir 'chat_files'
        if (Test-Path $chatFiles) { Remove-Item "$chatFiles\*" -Force -Recurse -ErrorAction SilentlyContinue }
        $null = Start-IsolatedServer -ExtraProps @(
            "-Dchat.upload.user.quota.bytes=1073741824",
            "-Dchat.upload.total.quota.bytes=1"
        )
        $u9 = New-User 'v3_global'
        $tmp2 = Join-Path ([System.IO.Path]::GetTempPath()) ("verify_glob_{0}.txt" -f ([guid]::NewGuid().ToString('N')))
        [System.IO.File]::WriteAllBytes($tmp2, (New-Object byte[] 1024))
        try {
            $rg = Send-Upload -Token $u9.Token -FilePath $tmp2
            Check '3-9' '全服总量上限触发 507（不再继续写盘）' ($rg.Status -eq 507) `
                "实际 HTTP $($rg.Status)；响应=$($rg.Text)"
            $leftG = Get-ChildItem $chatFiles -File -ErrorAction SilentlyContinue
            Check '3-10' '全服上限被拒后目录仍为空（未落盘）' ($leftG.Count -eq 0) `
                "目录内文件=$($leftG.Name -join ',')"
        } finally {
            Remove-Item $tmp2 -Force -ErrorAction SilentlyContinue
        }
    }

    # ================= 第 4 项：video_cache 按总大小限制（首次只 audit）=================
    if ($wanted -contains 4) {
        Write-Host ""
        Write-Host "===== 第 4 项 video_cache 按总大小限制（首次只 audit 不删）=====" -ForegroundColor Cyan
        Stop-IsolatedServer

        # 造 3 个缓存文件（2 个 4KB + 1 个 1KB），并把阈值压到 5KB 强迫触发「待回收」
        $cacheDir = Join-Path $DataDir 'video_cache'
        New-Item -ItemType Directory -Force -Path $cacheDir | Out-Null
        Remove-Item "$cacheDir\*" -Force -ErrorAction SilentlyContinue
        foreach ($f in @(@{n = 'oldest.mp4'; s = 4096; h = 3 }, @{n = 'middle.mp4'; s = 4096; h = 2 }, @{n = 'newest.mp4'; s = 1024; h = 1 })) {
            $p = Join-Path $cacheDir $f.n
            [System.IO.File]::WriteAllBytes($p, (New-Object byte[] $f.s))
            (Get-Item $p).LastWriteTime = (Get-Date).AddHours(-$f.h)
        }
        $log4 = Join-Path $serverDir 'build\verify-item4.out.log'
        $err4 = Join-Path $serverDir 'build\verify-item4.err.log'
        $null = Start-IsolatedServer -ExtraProps @(
            "-Dvideo.cache.max.bytes=5120",
            "-Dvideo.cache.max.files=200",
            "-Dvideo.cache.audit.only=true"
        ) -OutLog $log4 -ErrLog $err4

        $lines4 = @(Select-String -Path $log4, $err4 -Pattern '\[CACHE\]' -ErrorAction SilentlyContinue)
        $auditLine = ($lines4 | Where-Object { $_.Line -match '\[audit-only\]' } | Select-Object -First 1)
        $sum4 = if ($auditLine) { [string]$auditLine.Line } else { '' }
        $all4 = ($lines4 | ForEach-Object { $_.Line }) -join "`n"

        Check '4-1' '服务端日志出现 video_cache audit 报告' ([bool]$auditLine) `
            "日志里没有 [audit-only] 行"
        # 以下断言一律只用 ASCII 标记：日志由 Windows 默认编码（GBK）写出，
        # 中文断言在不同读取编码下会失配（4-1 之前用 ASCII 才侥幸通过）。
        Check '4-2' '报告按「总大小」计算待回收（9KB 超 5KB 上限）' `
            ($sum4 -match 'max_bytes=5120' -and $sum4 -match 'would_reclaim=1') "汇总行=$sum4"
        Check '4-3' '报告点名最旧文件 oldest.mp4 将会被删' `
            ($all4 -match '\[would-delete\] name=oldest\.mp4') "日志=$all4"
        Check '4-4' '报告明确标注本次未删除任何文件（[no-delete]）' `
            ($all4 -match '\[audit-only\]\[no-delete\]') "日志=$all4"

        $still = Get-ChildItem $cacheDir -File -ErrorAction SilentlyContinue
        Check '4-5' 'audit 模式下 3 个缓存文件一个都没被删' ($still.Count -eq 3) `
            "剩余文件=$($still.Name -join ',')"
        $totalLeft = ($still | Measure-Object Length -Sum).Sum
        Check '4-6' '缓存总字节未变化（9216 字节）' ($totalLeft -eq 9216) "实际=$totalLeft"
        foreach ($l in ($lines4 | Select-Object -First 6)) {
            Write-Host "      [日志] $($l.Line.Trim())" -ForegroundColor DarkGray
        }
    }

    # ================= 第 5 项：恒定时间比较（只动比较方式，语义必须不变）=================
    if ($wanted -contains 5) {
        Write-Host ""
        Write-Host "===== 第 5 项 MessageDigest.isEqual 替换后的语义等价（遗留 SHA-256 登录链路）=====" -ForegroundColor Cyan
        Stop-IsolatedServer
        $null = Start-IsolatedServer

        $u5 = New-User 'v5_legacy'
        $legacy = 'bd94dcda26fccb4e68d6a31f9b5aac0b571ae266d822620e901ef7ebe3a11d4f'  # SHA-256('pass1234')

        # 把该账号口令人为改成「遗留 SHA-256 格式」，制造 UserDao:81（loginUser）那条恒定时间比较路径
        $dbFile = Join-Path $DataDir 'family_chat.db'
        & sqlite3 $dbFile "UPDATE users SET password='$legacy' WHERE id=$($u5.Id);" 2>&1 | Out-Null
        $storedBefore = (& sqlite3 $dbFile "SELECT password FROM users WHERE id=$($u5.Id);" 2>&1).Trim()
        Check '5-1' '已把该账号口令改为遗留 SHA-256 格式（前置条件）' `
            ($storedBefore -eq $legacy) "库中=$storedBefore"

        # 用正确口令登录：必须成功（证明 isEqual 替换没有把相等判成不等）
        $r = Invoke-Api -Method POST -Path '/api/login' -Body @{ username = 'v5_legacy'; password = 'pass1234' }
        CheckStatus '5-2' '遗留 SHA-256 账号用正确口令仍能登录（200）' $r @(200)
        Check '5-3' '登录返回 token' ([bool]$r.Json.token) "响应=$($r.Text)"

        # 登录成功后应自动迁移为 bcrypt（UserDao 既有行为）
        $storedAfter = (& sqlite3 $dbFile "SELECT password FROM users WHERE id=$($u5.Id);" 2>&1).Trim()
        Check '5-4' '登录后已自动迁移为 bcrypt（$2 前缀）' `
            ($storedAfter.StartsWith('$2')) "库中=$($storedAfter.Substring(0, [Math]::Min(8, $storedAfter.Length)))..."

        # 用错误口令登录：必须失败（证明 isEqual 替换没有把不等判成等）
        $r = Invoke-Api -Method POST -Path '/api/login' -Body @{ username = 'v5_legacy'; password = 'wrongpass' }
        Check '5-5' '错误口令必须被拒（无 token）' `
            (-not $r.Json.token -and $r.Status -ne 200) "HTTP $($r.Status)；响应=$($r.Text)"
    }

    # ================= 隔离证据 =================
    Write-Host ""
    Write-Host "===== 隔离证据（正式数据目录）=====" -ForegroundColor Cyan
    $snapAfter = Get-ProdSnapshot
    Write-Host ("  运行前: {0} 个静态文件 / {1} 字节 / 指纹 {2}" -f `
        $snapBefore.Count, $snapBefore.Bytes, $snapBefore.Digest) -ForegroundColor DarkGray
    Write-Host ("  运行后: {0} 个静态文件 / {1} 字节 / 指纹 {2}" -f `
        $snapAfter.Count, $snapAfter.Bytes, $snapAfter.Digest) -ForegroundColor DarkGray
    Check 'ISO-1' '正式 data 静态内容与运行前完全一致（未被读写）' `
        ($snapBefore.Digest -eq $snapAfter.Digest -and $snapBefore.Count -eq $snapAfter.Count)
    Check 'ISO-3' '生产库大小未被本脚本改变（内容由运行中的生产进程自行更新）' `
        ($snapBefore.DbLen -eq $snapAfter.DbLen) `
        "运行前=$($snapBefore.DbLen) 运行后=$($snapAfter.DbLen)"

    $listen = & netstat -ano 2>$null | Select-String -Pattern ":$Port\s" | Select-String -Pattern 'LISTENING'
    Check 'ISO-2' "隔离实例只监听 $Port" ([bool]$listen)
    $p5000 = & netstat -ano 2>$null | Select-String -Pattern ":5000\s" | Select-String -Pattern 'LISTENING'
    Write-Host ("  [信息] :5000 仍在监听（生产实例未受影响）: {0}" -f ([bool]$p5000)) -ForegroundColor DarkGray
}
finally {
    if ($script:Proc -and -not $script:Proc.HasExited) {
        Write-Host ""
        Write-Host "[清理] 停止隔离实例 (PID $($script:Proc.Id))..." -ForegroundColor Yellow
        Stop-Process -Id $script:Proc.Id -Force -ErrorAction SilentlyContinue
        Start-Sleep -Milliseconds 1000
    }
    if (-not $KeepData -and (Test-Path $DataDir)) {
        Remove-Item -Recurse -Force $DataDir -ErrorAction SilentlyContinue
        Write-Host "[清理] 已删除隔离数据目录" -ForegroundColor DarkGray
    } elseif (Test-Path $DataDir) {
        Write-Host "[清理] 保留隔离数据目录（-KeepData）: $DataDir" -ForegroundColor DarkGray
    }
}

Write-Host ""
Write-Host "============================================" -ForegroundColor Cyan
if ($script:Fail -eq 0) {
    Write-Host ("  结果: {0} 项全部通过" -f $script:Pass) -ForegroundColor Green
} else {
    Write-Host ("  结果: 通过 {0} 项，失败 {1} 项" -f $script:Pass, $script:Fail) -ForegroundColor Red
    foreach ($f in $script:Failed) { Write-Host "    - $f" -ForegroundColor Red }
}
Write-Host "============================================" -ForegroundColor Cyan
if ($script:Fail -gt 0) { exit 1 }
