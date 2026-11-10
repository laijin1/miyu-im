#requires -version 7
<#
.SYNOPSIS
  密语服务端接口级 E2E 测试（隔离实例，绝不触碰正式数据）

.DESCRIPTION
  在独立端口 + 独立数据目录上另起一个服务端实例，对完整 HTTP 接口链路做端到端验证：
    认证与注册校验 / JWT 鉴权与吊销 / 好友与越权(IDOR) / 钱包资金安全 /
    商城订单状态机 / 群权限 / 登录限流

  隔离保证：
    * 端口由 -Port 指定（默认 5099），与正式实例的 5000 不冲突；
    * 数据目录由 -DataDir 指定（默认 server\build\e2e-data），
      通过 -Dfamilychat.data.dir 注入，正式 data\ 目录不会被读写；
    * 若目标端口已被占用，脚本直接报错退出，不会去动别人的进程；
    * 构建产物输出到 build\e2e-libs\，避免与"正在运行的正式实例"争抢
      build\libs\familychat-server.jar 的文件锁（Windows 下 java -jar 会锁定该文件）。
    * 充值白名单由脚本注入 ADMIN_USER_IDS=1..20，不依赖调用者环境；
      未注入时充值类用例会全 403（预存在的 20 项红）。

.EXAMPLE
  pwsh -File scripts\e2e-backend-tests.ps1
.EXAMPLE
  pwsh -File scripts\e2e-backend-tests.ps1 -Port 5199 -KeepData
#>
[CmdletBinding()]
param(
    [int]$Port = 5099,
    [string]$Jar,
    [string]$DataDir,
    [switch]$SkipBuild,
    [switch]$KeepData
)

$ErrorActionPreference = 'Stop'

$scriptDir = $PSScriptRoot
$miyuRoot  = (Resolve-Path (Join-Path $scriptDir '..')).Path
$serverDir = Join-Path $miyuRoot 'server'

if (-not $Jar)     { $Jar     = Join-Path $serverDir 'build\e2e-libs\familychat-server-e2e.jar' }
if (-not $DataDir) { $DataDir = Join-Path $serverDir 'build\e2e-data' }

$script:Base   = "http://127.0.0.1:$Port"
$script:Pass   = 0
$script:Fail   = 0
$script:Failed = @()
$script:Proc   = $null
$logOut = Join-Path $serverDir 'build\e2e-server.out.log'
$logErr = Join-Path $serverDir 'build\e2e-server.err.log'

# ===== 工具函数 =====

function Invoke-Api {
    param(
        [Parameter(Mandatory)][string]$Method,
        [Parameter(Mandatory)][string]$Path,
        $Body,
        [string]$Token
    )
    $headers = @{}
    if ($Token) { $headers['Authorization'] = "Bearer $Token" }
    $req = @{
        Method             = $Method
        Uri                = "$script:Base$Path"
        Headers            = $headers
        SkipHttpErrorCheck = $true
        TimeoutSec         = 30
    }
    if ($null -ne $Body) {
        $req['Body']        = ($Body | ConvertTo-Json -Compress -Depth 6)
        $req['ContentType'] = 'application/json; charset=utf-8'
    }
    $resp = Invoke-WebRequest @req
    $json = $null
    if ($resp.Content) { try { $json = $resp.Content | ConvertFrom-Json } catch { $json = $null } }
    [pscustomobject]@{ Status = [int]$resp.StatusCode; Json = $json; Text = $resp.Content }
}

function Check {
    param([string]$Id, [string]$Desc, [bool]$Ok, [string]$Detail = '')
    if ($Ok) {
        $script:Pass++
        Write-Host ("  [PASS] {0,-5} {1}" -f $Id, $Desc) -ForegroundColor Green
    } else {
        $script:Fail++
        $script:Failed += "${Id}: $Desc"
        Write-Host ("  [FAIL] {0,-5} {1}" -f $Id, $Desc) -ForegroundColor Red
        if ($Detail) { Write-Host ("         {0}" -f $Detail) -ForegroundColor DarkRed }
    }
}

function CheckStatus {
    param([string]$Id, [string]$Desc, $Resp, [int[]]$Expected)
    Check $Id $Desc ($Expected -contains $Resp.Status) `
        "期望 HTTP $($Expected -join '/')，实际 $($Resp.Status)；响应=$($Resp.Text)"
}

function CheckTrue {
    param([string]$Id, [string]$Desc, [bool]$Ok, [string]$Detail = '')
    Check $Id $Desc $Ok $Detail
}

function New-User {
    param([string]$Name)
    $r = Invoke-Api -Method POST -Path '/api/register' `
        -Body @{ username = $Name; password = 'pass1234'; nickname = $Name }
    if ($r.Status -ne 200) { throw "准备数据失败：注册 $Name 返回 $($r.Status) $($r.Text)" }
    [pscustomobject]@{ Name = $Name; Id = [int]$r.Json.user.id; Token = [string]$r.Json.token }
}

function Get-PinHash {
    param([string]$Pin)
    $sha = [System.Security.Cryptography.SHA256]::Create()
    try { $bytes = $sha.ComputeHash([System.Text.Encoding]::UTF8.GetBytes($Pin)) }
    finally { $sha.Dispose() }
    ($bytes | ForEach-Object { $_.ToString('x2') }) -join ''
}

# H 套件用：合成 APK 的固定路径（必须在启动实例前确定，作为 -Dapk.path 传入）
$script:FakeApkPath = Join-Path $serverDir 'build\e2e-apk\fake.apk'
$script:FakeApkSize = 262144   # 256 KiB

function New-FakeApk {
    param([int]$Size = $script:FakeApkSize)
    # 已存在且大小正确就不重建（幂等）
    if ((Test-Path $script:FakeApkPath) -and ((Get-Item $script:FakeApkPath).Length -eq $Size)) {
        return $script:FakeApkPath
    }
    New-Item -ItemType Directory -Force -Path (Split-Path -Parent $script:FakeApkPath) | Out-Null
    $buf = [byte[]]::new($Size)
    for ($i = 0; $i -lt $Size; $i++) { $buf[$i] = [byte]($i % 251) }
    [System.IO.File]::WriteAllBytes($script:FakeApkPath, $buf)
    return $script:FakeApkPath
}

# 字节级 HTTP 助手：返回 Status / Headers / Bytes（Invoke-WebRequest 会把响应体当文本解码，
# 无法做逐字节比对，故 /apk 相关用例走 HttpClient）
function Invoke-Http {
    param(
        [Parameter(Mandatory)][string]$Method,
        [Parameter(Mandatory)][string]$Url,
        [hashtable]$Headers
    )
    if (-not $script:HttpClient) {
        $handler = [System.Net.Http.HttpClientHandler]::new()
        $handler.UseProxy = $false
        $script:HttpClient = [System.Net.Http.HttpClient]::new($handler)
    }
    $req = [System.Net.Http.HttpRequestMessage]::new([System.Net.Http.HttpMethod]::new($Method), $Url)
    $resp = $null
    try {
        # 注意：@($null.Keys) 是长度 1 的数组（含一个 $null），必须显式判空，
        # 否则 $Headers[$null] 会抛「不能为空数组建立索引」
        if ($null -ne $Headers) {
            foreach ($k in @($Headers.Keys)) {
                $v = [string]$Headers[$k]
                if ($k -eq 'Range') {
                    # 合法区间走强类型 Range 头；非法值（H9 的 abc-def 等）Parse 会抛，
                    # 改走 TryAddWithoutValidation 原样上送，由服务端判定 416
                    try { $req.Headers.Range = [System.Net.Http.Headers.RangeHeaderValue]::Parse($v) }
                    catch { [void]$req.Headers.TryAddWithoutValidation('Range', $v) }
                } else {
                    [void]$req.Headers.TryAddWithoutValidation($k, $v)
                }
            }
        }
        $resp = $script:HttpClient.Send($req)
        $bytes = $resp.Content.ReadAsByteArrayAsync().GetAwaiter().GetResult()
        $h = @{}
        foreach ($x in $resp.Headers)         { $h[$x.Key] = ($x.Value -join ', ') }
        foreach ($x in $resp.Content.Headers) { $h[$x.Key] = ($x.Value -join ', ') }
        [pscustomobject]@{ Status = [int]$resp.StatusCode; Headers = $h; Bytes = $bytes }
    } finally {
        if ($resp) { $resp.Dispose() }
        $req.Dispose()
    }
}

# H 套件：/apk 端到端下载 + Range 分段（逐字节比对；Invoke-Api 会把响应体当文本解码，故走 Invoke-Http）
function Invoke-ApkTestSuite {
    Write-Host ""
    Write-Host "===== H. APK 下载（/apk 端到端 + Range 分段） =====" -ForegroundColor Cyan

    $apkUrl = "$script:Base/apk"

    # H1：fake.apk 尚未创建（启动前已清残留）-> 必须 404
    $r = Invoke-Http -Method GET -Url $apkUrl
    $h1Body = if ($r.Bytes.Length -gt 0) { [System.Text.Encoding]::UTF8.GetString($r.Bytes) } else { '' }
    Check 'H1' 'APK 文件不存在时 GET /apk 返回 404 + {"error":"APK文件未找到"}' `
        ($r.Status -eq 404 -and $h1Body -match '"error"\s*:\s*"APK文件未找到"') `
        "实际 HTTP $($r.Status)；体=$h1Body"

    # 合成 APK（256 KiB，byte[i]=i%251）；H2-H10 在它存在之后才请求
    $fakeApk = New-FakeApk
    $expect  = [System.IO.File]::ReadAllBytes($fakeApk)

    # H2：无 Range -> 整文件 200
    $r = Invoke-Http -Method GET -Url $apkUrl
    Check 'H2' 'GET /apk（无 Range）返回 200 + CL=262144 + Accept-Ranges: bytes + 体逐字节==文件' `
        ($r.Status -eq 200 -and $r.Headers['Content-Length'] -eq '262144' -and $r.Headers['Accept-Ranges'] -eq 'bytes' `
         -and [System.Linq.Enumerable]::SequenceEqual([byte[]]$r.Bytes, [byte[]]$expect)) `
        "HTTP=$($r.Status) CL=$($r.Headers['Content-Length']) Accept-Ranges=$($r.Headers['Accept-Ranges']) 体长=$($r.Bytes.Length)"

    # H3：bytes=0-1023 -> 前 1024B
    $r = Invoke-Http -Method GET -Url $apkUrl -Headers @{ Range = 'bytes=0-1023' }
    Check 'H3' 'Range: bytes=0-1023 返回 206 + Content-Range: bytes 0-1023/262144 + 体==前 1024B' `
        ($r.Status -eq 206 -and $r.Headers['Content-Range'] -eq 'bytes 0-1023/262144' `
         -and [System.Linq.Enumerable]::SequenceEqual([byte[]]$r.Bytes, [byte[]]($expect[0..1023]))) `
        "HTTP=$($r.Status) CR=$($r.Headers['Content-Range']) 体长=$($r.Bytes.Length)"

    # H4：bytes=-1024 后缀区间 -> 末 1024B
    $r = Invoke-Http -Method GET -Url $apkUrl -Headers @{ Range = 'bytes=-1024' }
    Check 'H4' 'Range: bytes=-1024（后缀区间）返回 206 + Content-Range: bytes 261120-262143/262144 + 体==末 1024B' `
        ($r.Status -eq 206 -and $r.Headers['Content-Range'] -eq 'bytes 261120-262143/262144' `
         -and [System.Linq.Enumerable]::SequenceEqual([byte[]]$r.Bytes, [byte[]]($expect[261120..262143]))) `
        "HTTP=$($r.Status) CR=$($r.Headers['Content-Range']) 体长=$($r.Bytes.Length)"

    # H5：bytes=0-262143 -> 整文件，仍是 206（显式 Range）
    $r = Invoke-Http -Method GET -Url $apkUrl -Headers @{ Range = 'bytes=0-262143' }
    Check 'H5' 'Range: bytes=0-262143 返回 206 + Content-Range: bytes 0-262143/262144 + 体==整文件' `
        ($r.Status -eq 206 -and $r.Headers['Content-Range'] -eq 'bytes 0-262143/262144' `
         -and [System.Linq.Enumerable]::SequenceEqual([byte[]]$r.Bytes, [byte[]]$expect)) `
        "HTTP=$($r.Status) CR=$($r.Headers['Content-Range']) 体长=$($r.Bytes.Length)"

    # H6：起点越界 -> 416
    $r = Invoke-Http -Method GET -Url $apkUrl -Headers @{ Range = 'bytes=262144-' }
    Check 'H6' 'Range: bytes=262144-（起点越界）返回 416 + Content-Range: bytes */262144 + 体为空' `
        ($r.Status -eq 416 -and $r.Headers['Content-Range'] -eq 'bytes */262144' -and $r.Bytes.Length -eq 0) `
        "HTTP=$($r.Status) CR=$($r.Headers['Content-Range']) 体长=$($r.Bytes.Length)"

    # H7：HEAD -> 200 + CL，且响应体 0 字节
    $r = Invoke-Http -Method HEAD -Url $apkUrl
    Check 'H7' 'HEAD /apk 返回 200 + Content-Length=262144 + 体 0 字节' `
        ($r.Status -eq 200 -and $r.Headers['Content-Length'] -eq '262144' -and $r.Bytes.Length -eq 0) `
        "HTTP=$($r.Status) CL=$($r.Headers['Content-Length']) 体长=$($r.Bytes.Length)"

    # H8：多区间暂不支持 -> 回退整文件 200（parseRange 的显式设计，不是错误）
    $r = Invoke-Http -Method GET -Url $apkUrl -Headers @{ Range = 'bytes=0-1,5-6' }
    Check 'H8' 'Range: bytes=0-1,5-6（多区间）回退 200 + CL=262144 + 体==整文件' `
        ($r.Status -eq 200 -and $r.Headers['Content-Length'] -eq '262144' `
         -and [System.Linq.Enumerable]::SequenceEqual([byte[]]$r.Bytes, [byte[]]$expect)) `
        "HTTP=$($r.Status) CL=$($r.Headers['Content-Length']) CR=$($r.Headers['Content-Range']) 体长=$($r.Bytes.Length)"

    # H9：三条非法 Range 都应 416（不再静默退化成 200）
    foreach ($bad in @('bytes=abc-def', 'bytes=500-100', 'bytes=abc')) {
        $r = Invoke-Http -Method GET -Url $apkUrl -Headers @{ Range = $bad }
        Check 'H9' "非法 Range '$bad' 返回 416 + Content-Range: bytes */262144" `
            ($r.Status -eq 416 -and $r.Headers['Content-Range'] -eq 'bytes */262144') `
            "HTTP=$($r.Status) CR=$($r.Headers['Content-Range']) 体长=$($r.Bytes.Length)"
    }

    # H10：开放结尾 bytes=262134- -> 末 10B
    $r = Invoke-Http -Method GET -Url $apkUrl -Headers @{ Range = 'bytes=262134-' }
    Check 'H10' 'Range: bytes=262134- 返回 206 + Content-Range: bytes 262134-262143/262144 + 体==末 10B' `
        ($r.Status -eq 206 -and $r.Headers['Content-Range'] -eq 'bytes 262134-262143/262144' `
         -and [System.Linq.Enumerable]::SequenceEqual([byte[]]$r.Bytes, [byte[]]($expect[262134..262143]))) `
        "HTTP=$($r.Status) CR=$($r.Headers['Content-Range']) 体长=$($r.Bytes.Length)"
}

function Wait-ServerReady {
    param([int]$TimeoutSec = 90)
    $deadline = (Get-Date).AddSeconds($TimeoutSec)
    while ((Get-Date) -lt $deadline) {
        if ($script:Proc.HasExited) {
            throw "服务端进程意外退出（退出码 $($script:Proc.ExitCode)），错误日志：$logErr"
        }
        try {
            # 用不依赖文件系统的受保护路由做探针：未带 token 时应答 401，说明服务已起来
            # 且路由已注册。（不用 GET / —— 下载页按 jar 位置解析路径，jar 换目录后会 404，
            # 那是历史 QA 报告里的"路径逻辑脆弱"问题，不适合当就绪探针。）
            $r = Invoke-WebRequest -Uri "$script:Base/api/wallet" -TimeoutSec 3 -SkipHttpErrorCheck
            if ($r.StatusCode -eq 401) { return }
        } catch { }
        Start-Sleep -Milliseconds 500
    }
    throw "服务端在 $TimeoutSec 秒内未就绪，请查看日志：$logOut"
}

# ===== 环境检查 =====

Write-Host "============================================" -ForegroundColor Cyan
Write-Host "  密语 服务端接口级 E2E 测试（隔离实例）" -ForegroundColor Cyan
Write-Host "============================================" -ForegroundColor Cyan
Write-Host ""

$busy = & netstat -ano 2>$null | Select-String -Pattern ":$Port\s" | Select-String -Pattern 'LISTENING'
if ($busy) {
    throw "端口 $Port 已被占用，脚本不会去动别人的进程。请用 -Port 指定一个空闲端口。占用信息: $($busy -join '; ')"
}
Write-Host "[环境] 端口 $Port 空闲" -ForegroundColor DarkGray
Write-Host "[环境] 隔离数据目录: $DataDir" -ForegroundColor DarkGray
Write-Host "[环境] 正式数据目录（本次不会读写）: $(Join-Path $serverDir 'data')" -ForegroundColor DarkGray

# ===== 准备 E2E jar =====

if (-not (Test-Path $Jar)) {
    if ($SkipBuild) { throw "找不到 E2E jar: $Jar（去掉 -SkipBuild 以自动构建）" }
    Write-Host "[准备] 构建 E2E 专用 jar（避开正式实例占用的 build\libs）..." -ForegroundColor Yellow
    $initScript = Join-Path $serverDir 'build\e2e-shadowjar.gradle'
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
        & .\gradlew.bat -I 'build\e2e-shadowjar.gradle' shadowJar --console=plain 2>&1 |
            Select-Object -Last 6 | ForEach-Object { Write-Host "      $_" -ForegroundColor DarkGray }
    } finally { Pop-Location }
    if (-not (Test-Path $Jar)) { throw "构建后仍找不到 $Jar" }
}
Write-Host "[准备] 使用 jar: $Jar" -ForegroundColor DarkGray

# ===== 启动隔离实例 =====

if (Test-Path $DataDir) { Remove-Item -Recurse -Force $DataDir }
New-Item -ItemType Directory -Force -Path $DataDir | Out-Null
# H1 要求确定性 404：清掉上一轮遗留的合成 APK（H 套件在 H1 之后才创建它）
Remove-Item -LiteralPath $script:FakeApkPath -Force -ErrorAction SilentlyContinue

# 充值白名单：隔离实例是全新一次性 DB，前 20 个 id 覆盖所有测试用户。
# 显式注入，不依赖调用者 shell 的环境变量。
$env:ADMIN_USER_IDS = (1..20 -join ',')
Write-Host "  [环境] ADMIN_USER_IDS=$env:ADMIN_USER_IDS"

Write-Host "[准备] 启动隔离实例..." -ForegroundColor Yellow
$script:Proc = Start-Process -FilePath 'java' -ArgumentList @(
    "-Dserver.port=$Port",
    "-Dfamilychat.data.dir=$DataDir",
    "-Dapk.path=$script:FakeApkPath",
    '-jar', $Jar
) -RedirectStandardOutput $logOut -RedirectStandardError $logErr -PassThru -WindowStyle Hidden

try {
    Wait-ServerReady
    Write-Host "[准备] 实例已就绪 (PID $($script:Proc.Id))" -ForegroundColor Green

    # 隔离证据：JWT 密钥是在隔离目录里新生成的，不是正式目录里那把
    $keyLine = Select-String -Path $logOut, $logErr -Pattern 'Generated new JWT secret key' -ErrorAction SilentlyContinue |
        Select-Object -First 1
    if ($keyLine) { Write-Host "[隔离] $($keyLine.Line.Trim())" -ForegroundColor DarkGray }

    # ================= A. 认证与注册校验 =================
    Write-Host ""
    Write-Host "===== A. 认证与注册校验 =====" -ForegroundColor Cyan

    $r = Invoke-Api -Method POST -Path '/api/register' `
        -Body @{ username = 'alice'; password = 'pass1234'; nickname = '爱丽丝' }
    CheckStatus 'A1' '注册成功返回 200' $r @(200)
    CheckTrue 'A2' '注册响应含 token 与 user.id' `
        ([bool]$r.Json.token -and [bool]$r.Json.user.id) "响应=$($r.Text)"
    $aliceId    = [int]$r.Json.user.id
    $aliceToken = [string]$r.Json.token

    $r = Invoke-Api -Method POST -Path '/api/register' `
        -Body @{ username = 'alice'; password = 'pass1234'; nickname = 'x' }
    CheckStatus 'A3' '重复用户名返回 409' $r @(409)

    $r = Invoke-Api -Method POST -Path '/api/register' -Body @{ username = 'x'; password = 'pass1234' }
    CheckStatus 'A4' '用户名过短返回 400' $r @(400)

    $r = Invoke-Api -Method POST -Path '/api/register' -Body @{ username = 'bad name!'; password = 'pass1234' }
    CheckStatus 'A5' '用户名含非法字符返回 400' $r @(400)

    $r = Invoke-Api -Method POST -Path '/api/register' -Body @{ username = 'shortpw'; password = '123' }
    CheckStatus 'A6' '密码过短返回 400' $r @(400)

    $r = Invoke-Api -Method POST -Path '/api/register' -Body @{ username = ''; password = '' }
    CheckStatus 'A7' '空用户名/密码返回 400' $r @(400)

    $r = Invoke-Api -Method POST -Path '/api/login' -Body @{ username = 'alice'; password = 'wrongpass' }
    CheckStatus 'A8' '错误密码登录返回 401' $r @(401)

    $r = Invoke-Api -Method POST -Path '/api/login' -Body @{ username = 'alice'; password = 'pass1234' }
    CheckStatus 'A9' '正确密码登录返回 200' $r @(200)
    $aliceToken = [string]$r.Json.token

    # ================= B. JWT 鉴权与吊销 =================
    Write-Host ""
    Write-Host "===== B. JWT 鉴权与吊销 =====" -ForegroundColor Cyan

    $r = Invoke-Api -Method GET -Path '/api/wallet'
    CheckStatus 'B1' '未带 token 访问受保护接口返回 401' $r @(401)

    $r = Invoke-Api -Method GET -Path '/api/wallet' -Token 'not.a.real.token'
    CheckStatus 'B2' '伪造 token 返回 401' $r @(401)

    $r = Invoke-Api -Method GET -Path '/api/wallet' -Token $aliceToken
    CheckStatus 'B3' '合法 token 访问返回 200' $r @(200)

    $r = Invoke-Api -Method POST -Path '/api/user/change-password' `
        -Body @{ old_password = 'wrongpass'; new_password = 'newpass99' } -Token $aliceToken
    CheckStatus 'B4' '改密：旧密码错误返回 400' $r @(400)

    $r = Invoke-Api -Method POST -Path '/api/user/change-password' `
        -Body @{ old_password = 'pass1234'; new_password = '123' } -Token $aliceToken
    CheckStatus 'B5' '改密：新密码过短返回 400' $r @(400)

    $r = Invoke-Api -Method POST -Path '/api/user/change-password' `
        -Body @{ old_password = 'pass1234'; new_password = 'newpass99' } -Token $aliceToken
    CheckStatus 'B6' '改密成功返回 200' $r @(200)
    $aliceNewToken = [string]$r.Json.token

    $r = Invoke-Api -Method GET -Path '/api/wallet' -Token $aliceToken
    CheckStatus 'B7' '改密后旧 token 立即失效（401，token_version 递增）' $r @(401)

    $r = Invoke-Api -Method GET -Path '/api/wallet' -Token $aliceNewToken
    CheckStatus 'B8' '改密返回的新 token 可用' $r @(200)

    $r = Invoke-Api -Method POST -Path '/api/login' -Body @{ username = 'alice'; password = 'pass1234' }
    CheckStatus 'B9' '改密后旧密码登录失败（401）' $r @(401)

    $r = Invoke-Api -Method POST -Path '/api/login' -Body @{ username = 'alice'; password = 'newpass99' }
    CheckStatus 'B10' '改密后新密码登录成功' $r @(200)

    # ================= C. 好友与越权 (IDOR) =================
    Write-Host ""
    Write-Host "===== C. 好友与越权 (IDOR) =====" -ForegroundColor Cyan

    $carol = New-User 'carol'
    $dave  = New-User 'dave'
    $erin  = New-User 'erin'

    $r = Invoke-Api -Method GET -Path '/api/users/search?q=dave' -Token $carol.Token
    CheckStatus 'C1' '按用户名搜索用户返回 200' $r @(200)
    $foundDave = @($r.Json.users | Where-Object { [int]$_.id -eq $dave.Id }).Count -gt 0
    CheckTrue 'C1b' '搜索结果包含目标用户' $foundDave "响应=$($r.Text)"

    $r = Invoke-Api -Method POST -Path '/api/friends/request' -Body @{ friend_id = $dave.Id } -Token $carol.Token
    CheckStatus 'C2' '发起好友请求返回 200' $r @(200)

    $r = Invoke-Api -Method GET -Path '/api/friends/requests' -Token $dave.Token
    CheckStatus 'C3' '接收方可查到待处理好友请求' $r @(200)
    $pending = @($r.Json.requests)
    $fsId = if ($pending.Count -gt 0) { [int]$pending[0].friendship_id } else { -1 }
    CheckTrue 'C3b' '待处理请求含 friendship_id' ($fsId -gt 0) "响应=$($r.Text)"

    $r = Invoke-Api -Method POST -Path "/api/friends/request/$fsId/accept" -Token $erin.Token
    CheckStatus 'C4' '第三方越权【接受】好友请求被拒（403，历史 IDOR 回归）' $r @(403)

    $r = Invoke-Api -Method POST -Path "/api/friends/request/$fsId/reject" -Token $erin.Token
    CheckStatus 'C5' '第三方越权【拒绝】好友请求被拒（403）' $r @(403)

    $r = Invoke-Api -Method POST -Path "/api/friends/request/$fsId/accept" -Token $dave.Token
    CheckStatus 'C6' '接收方本人接受好友请求成功' $r @(200)

    $r = Invoke-Api -Method GET -Path '/api/friends' -Token $carol.Token
    $isFriend = @($r.Json.friends | Where-Object { [int]$_.id -eq $dave.Id }).Count -gt 0
    CheckTrue 'C7' '接受后双方成为好友' $isFriend "响应=$($r.Text)"

    # ================= D. 钱包与资金安全 =================
    Write-Host ""
    Write-Host "===== D. 钱包与资金安全 =====" -ForegroundColor Cyan

    $pinHash      = Get-PinHash '123456'
    $wrongPinHash = Get-PinHash '000000'

    $r = Invoke-Api -Method GET -Path '/api/wallet' -Token $carol.Token
    CheckStatus 'D1' '查询钱包返回 200' $r @(200)
    CheckTrue 'D1b' '初始余额为 0 且 pay_set=0' `
        (([long]$r.Json.balance -eq 0) -and ([int]$r.Json.pay_set -eq 0)) "响应=$($r.Text)"

    $r = Invoke-Api -Method PUT -Path '/api/wallet/password' -Body @{ pin_hash = 'abc' } -Token $carol.Token
    CheckStatus 'D2' '支付密码格式非法（非 64 位 hex）返回 400' $r @(400)

    $r = Invoke-Api -Method PUT -Path '/api/wallet/password' -Body @{ pin_hash = $pinHash } -Token $carol.Token
    CheckStatus 'D3' '设置支付密码成功' $r @(200)

    $r = Invoke-Api -Method POST -Path '/api/wallet/recharge' -Body @{ amount = 0 } -Token $carol.Token
    CheckStatus 'D4' '充值金额为 0 返回 400' $r @(400)

    # 单次上限 100 元（10000 分），故分 5 次；D5 断言的是最后一次 200，D5b 仍是累计余额 50000
    for ($i = 1; $i -le 5; $i++) {
        $r = Invoke-Api -Method POST -Path '/api/wallet/recharge' -Body @{ amount = 10000 } -Token $carol.Token
    }
    CheckStatus 'D5' '充值 50000 分成功' $r @(200)
    CheckTrue 'D5b' '充值后余额为 50000' ([long]$r.Json.balance -eq 50000) "balance=$($r.Json.balance)"

    # dave 侧准备：设支付密码 + 充值
    $null = Invoke-Api -Method PUT  -Path '/api/wallet/password' -Body @{ pin_hash = $pinHash } -Token $dave.Token
    # 单次上限 100 元（10000 分），故分 10 次；D6 断言的是累计余额 100000
    for ($i = 1; $i -le 10; $i++) {
        $r = Invoke-Api -Method POST -Path '/api/wallet/recharge' -Body @{ amount = 10000 } -Token $dave.Token
    }
    CheckTrue 'D6' 'dave 充值后余额为 100000' ([long]$r.Json.balance -eq 100000) "balance=$($r.Json.balance)"

    $r = Invoke-Api -Method POST -Path '/api/wallet/transfer' `
        -Body @{ to_user = $carol.Id; amount = 100; memo = '自己'; pin_hash = $pinHash } -Token $carol.Token
    CheckStatus 'D7' '给自己转账返回 400' $r @(400)

    $r = Invoke-Api -Method POST -Path '/api/wallet/transfer' `
        -Body @{ to_user = $erin.Id; amount = 100; memo = '陌生人'; pin_hash = $pinHash } -Token $carol.Token
    CheckStatus 'D8' '给非好友转账返回 403（仅好友可转）' $r @(403)

    $r = Invoke-Api -Method POST -Path '/api/wallet/transfer' `
        -Body @{ to_user = $dave.Id; amount = 100; memo = '错密码'; pin_hash = $wrongPinHash } -Token $carol.Token
    CheckStatus 'D9' '支付密码错误返回 403' $r @(403)

    $r = Invoke-Api -Method POST -Path '/api/wallet/transfer' `
        -Body @{ to_user = $dave.Id; amount = 999999; memo = '没钱'; pin_hash = $pinHash } -Token $carol.Token
    CheckStatus 'D10' '余额不足返回 400' $r @(400)

    $r = Invoke-Api -Method POST -Path '/api/wallet/transfer' `
        -Body @{ to_user = $dave.Id; amount = 30000; memo = '午饭钱'; pin_hash = $pinHash } -Token $carol.Token
    CheckStatus 'D11' '好友转账成功' $r @(200)
    $tid = [long]$r.Json.message.transfer_id
    CheckTrue 'D11b' '转账返回 transfer_id' ($tid -gt 0) "响应=$($r.Text)"

    $rc = Invoke-Api -Method GET -Path '/api/wallet' -Token $carol.Token
    $rd = Invoke-Api -Method GET -Path '/api/wallet' -Token $dave.Token
    CheckTrue 'D12' '发起方立即扣款、接收方在收款前不入账（资金冻结）' `
        (([long]$rc.Json.balance -eq 20000) -and ([long]$rd.Json.balance -eq 100000)) `
        "carol=$($rc.Json.balance) dave=$($rd.Json.balance)"

    $r = Invoke-Api -Method GET -Path "/api/wallet/transfer/$tid" -Token $erin.Token
    CheckStatus 'D13' '无关第三方查看转账详情被拒（403，防遍历 tid）' $r @(403)

    $r = Invoke-Api -Method GET -Path "/api/wallet/transfer/$tid" -Token $dave.Token
    CheckStatus 'D14' '转账接收方可查看详情' $r @(200)

    $r = Invoke-Api -Method POST -Path "/api/wallet/transfer/$tid/accept" -Token $erin.Token
    CheckStatus 'D15' '无关第三方尝试收款被拒（403）' $r @(403)

    $r = Invoke-Api -Method POST -Path "/api/wallet/transfer/$tid/accept" -Token $dave.Token
    CheckStatus 'D16' '接收方收款成功' $r @(200)
    $rd = Invoke-Api -Method GET -Path '/api/wallet' -Token $dave.Token
    CheckTrue 'D16b' '收款后接收方余额为 130000' ([long]$rd.Json.balance -eq 130000) "dave=$($rd.Json.balance)"

    $r = Invoke-Api -Method POST -Path "/api/wallet/transfer/$tid/accept" -Token $dave.Token
    CheckStatus 'D17' '重复收款被拒（防重复入账）' $r @(403)

    $r = Invoke-Api -Method POST -Path "/api/wallet/transfer/$tid/refund" -Token $carol.Token
    CheckStatus 'D18' '已收款后发起方撤回被拒（403）' $r @(403)

    $r = Invoke-Api -Method POST -Path '/api/wallet/transfer' `
        -Body @{ to_user = $dave.Id; amount = 5000; memo = '撤回测试'; pin_hash = $pinHash } -Token $carol.Token
    $tid2 = [long]$r.Json.message.transfer_id
    $r = Invoke-Api -Method POST -Path "/api/wallet/transfer/$tid2/refund" -Token $carol.Token
    CheckStatus 'D19' '待收款转账发起方可撤回' $r @(200)
    $rc = Invoke-Api -Method GET -Path '/api/wallet' -Token $carol.Token
    CheckTrue 'D19b' '撤回后发起方余额恢复为 20000' ([long]$rc.Json.balance -eq 20000) "carol=$($rc.Json.balance)"

    $r = Invoke-Api -Method GET -Path '/api/wallet/bills' -Token $carol.Token
    CheckStatus 'D20' '查询钱包账单返回 200' $r @(200)
    CheckTrue 'D20b' '账单非空' (@($r.Json.bills).Count -gt 0) "响应=$($r.Text)"

    # ================= E. 商城订单状态机 =================
    Write-Host ""
    Write-Host "===== E. 商城订单状态机 =====" -ForegroundColor Cyan

    $frank = New-User 'frank'   # 卖家
    $grace = New-User 'grace'   # 买家

    # 白名单必须覆盖充值用例的三个账号（否则 D5/D6/E9 会 403）
    $adminList = ($env:ADMIN_USER_IDS -split ',') | ForEach-Object { [int]$_ }
    foreach ($u in @(@('carol', $carol.Id), @('dave', $dave.Id), @('grace', $grace.Id))) {
        CheckTrue "SETUP-ADMIN-$($u[0])" `
            "充值白名单覆盖 $($u[0]) (id=$($u[1]))" `
            ($adminList -contains $u[1])
    }

    $r = Invoke-Api -Method POST -Path '/api/mall/products' `
        -Body @{ name = 'E2E保温杯'; category = '生活'; price = 1000; stock = 3; description = '测试商品'; image_url = '' } `
        -Token $frank.Token
    CheckStatus 'E1' '卖家发布商品成功' $r @(200)
    $prodId = [int]$r.Json.product_id

    $orderBody = @{
        product_id = $prodId; quantity = 1
        receiver_name = '张三'; receiver_phone = '13800138000'; receiver_address = '某市某路1号'
    }

    $r = Invoke-Api -Method POST -Path '/api/mall/orders' -Body $orderBody -Token $frank.Token
    CheckStatus 'E2' '卖家购买自己的商品被拒（400）' $r @(400)

    $bad = @{ product_id = $prodId; quantity = 1; receiver_name = ''; receiver_phone = '138'; receiver_address = '某地' }
    $r = Invoke-Api -Method POST -Path '/api/mall/orders' -Body $bad -Token $grace.Token
    CheckStatus 'E3' '收货信息不完整返回 400' $r @(400)

    $r = Invoke-Api -Method POST -Path '/api/mall/orders' `
        -Body @{ product_id = $prodId; quantity = 2; receiver_name = '张三'; receiver_phone = '13800138000'; receiver_address = '某市某路1号' } `
        -Token $grace.Token
    CheckStatus 'E4' '买家下单 2 件成功' $r @(200)
    $oid = [int]$r.Json.order.id
    CheckTrue 'E4b' '下单返回订单 id' ($oid -gt 0) "响应=$($r.Text)"

    $r = Invoke-Api -Method GET -Path "/api/mall/products/$prodId" -Token $grace.Token
    CheckTrue 'E5' '下单 2 件后库存 3 → 1' ([int]$r.Json.product.stock -eq 1) "响应=$($r.Text)"

    $r = Invoke-Api -Method POST -Path '/api/mall/orders' `
        -Body @{ product_id = $prodId; quantity = 2; receiver_name = '张三'; receiver_phone = '13800138000'; receiver_address = '某市某路1号' } `
        -Token $grace.Token
    CheckStatus 'E6' '库存不足时下单被拒（400，防超卖）' $r @(400)

    $r = Invoke-Api -Method POST -Path "/api/mall/orders/$oid/ship" `
        -Body @{ shipping_company = '顺丰'; tracking_no = 'SF123' } -Token $frank.Token
    CheckStatus 'E7' '未付款订单不可发货（400）' $r @(400)

    $r = Invoke-Api -Method POST -Path "/api/mall/orders/$oid/pay" -Body @{ pin_hash = $pinHash } -Token $grace.Token
    CheckStatus 'E8' '未设支付密码时支付失败（400/403）' $r @(400, 403)

    $null = Invoke-Api -Method PUT  -Path '/api/wallet/password' -Body @{ pin_hash = $pinHash } -Token $grace.Token
    # 单次上限 100 元（10000 分）；E 段订单只需 2000 分，故充 1 次 10000
    $null = Invoke-Api -Method POST -Path '/api/wallet/recharge' -Body @{ amount = 10000 } -Token $grace.Token

    $r = Invoke-Api -Method POST -Path "/api/mall/orders/$oid/pay" -Body @{ pin_hash = $pinHash } -Token $grace.Token
    CheckStatus 'E9' '买家支付订单成功' $r @(200)

    $r = Invoke-Api -Method POST -Path "/api/mall/orders/$oid/ship" `
        -Body @{ shipping_company = '顺丰'; tracking_no = '' } -Token $frank.Token
    CheckStatus 'E10' '空运单号发货被拒（400）' $r @(400)

    $r = Invoke-Api -Method POST -Path "/api/mall/orders/$oid/ship" `
        -Body @{ shipping_company = '顺丰'; tracking_no = 'SF123456' } -Token $frank.Token
    CheckStatus 'E11' '卖家正常发货成功' $r @(200)

    $r = Invoke-Api -Method POST -Path "/api/mall/orders/$oid/confirm" -Token $grace.Token
    CheckStatus 'E12' '买家确认收货成功' $r @(200)

    $r = Invoke-Api -Method POST -Path "/api/mall/orders/$oid/refund" -Body @{ reason = '后悔了' } -Token $grace.Token
    CheckStatus 'E13' '已完成订单申请退款被拒（400）' $r @(400)

    $r = Invoke-Api -Method GET -Path "/api/mall/orders/$oid" -Token $grace.Token
    CheckStatus 'E14' '买家可查看自己的订单详情' $r @(200)

    $r = Invoke-Api -Method GET -Path "/api/mall/orders/$oid" -Token $erin.Token
    CheckStatus 'E15' '无关第三方查看他人订单被拒（403）' $r @(403)

    # ================= F. 群权限 =================
    Write-Host ""
    Write-Host "===== F. 群权限 =====" -ForegroundColor Cyan

    $heidi = New-User 'heidi'   # 群主
    $ivan  = New-User 'ivan'    # 群成员
    $judy  = New-User 'judy'    # 非成员

    # heidi 与 ivan 先成为好友（建群要求成员是好友）
    $null = Invoke-Api -Method POST -Path '/api/friends/request' -Body @{ friend_id = $ivan.Id } -Token $heidi.Token
    $r    = Invoke-Api -Method GET -Path '/api/friends/requests' -Token $ivan.Token
    $fsId2 = [int]@($r.Json.requests)[0].friendship_id
    $r    = Invoke-Api -Method POST -Path "/api/friends/request/$fsId2/accept" -Token $ivan.Token
    CheckTrue 'F1' '前置：heidi 与 ivan 建立好友关系' ($r.Status -eq 200) "响应=$($r.Text)"

    $r = Invoke-Api -Method POST -Path '/api/groups' -Body @{ name = 'E2E群'; member_ids = @($ivan.Id) } -Token $heidi.Token
    CheckStatus 'F2' '创建群成功' $r @(200)
    $gid = [int]$r.Json.group.id
    CheckTrue 'F2b' '建群返回群 id' ($gid -gt 0) "响应=$($r.Text)"

    $r = Invoke-Api -Method POST -Path '/api/groups' -Body @{ name = '空群'; member_ids = @() } -Token $heidi.Token
    CheckStatus 'F3' '不含成员的建群被拒（400）' $r @(400)

    $r = Invoke-Api -Method POST -Path '/api/groups' -Body @{ name = '拉陌生人'; member_ids = @($judy.Id) } -Token $heidi.Token
    CheckStatus 'F4' '把非好友拉进群被拒（403）' $r @(403)

    $r = Invoke-Api -Method POST -Path "/api/groups/$gid/messages" -Body @{ content = '群里第一条消息' } -Token $heidi.Token
    CheckStatus 'F5' '群成员发消息成功' $r @(200)
    $gmid = [int]$r.Json.message.id
    CheckTrue 'F5b' '发消息返回消息 id' ($gmid -gt 0) "响应=$($r.Text)"

    $r = Invoke-Api -Method GET -Path "/api/groups/$gid/messages" -Token $ivan.Token
    CheckStatus 'F6' '群成员可读群消息' $r @(200)

    $r = Invoke-Api -Method GET -Path "/api/groups/$gid/messages" -Token $judy.Token
    CheckStatus 'F7' '非群成员读群消息被拒（403）' $r @(403)

    $r = Invoke-Api -Method POST -Path "/api/groups/$gid/messages" -Body @{ content = '我不是成员' } -Token $judy.Token
    CheckStatus 'F8' '非群成员发群消息被拒（403）' $r @(403)

    # 跨群撤回：ivan 另建群 B 并拉 heidi 进群，heidi 用群 B 的路径撤回群 A 的消息
    $r = Invoke-Api -Method POST -Path '/api/groups' -Body @{ name = 'E2E群B'; member_ids = @($heidi.Id) } -Token $ivan.Token
    $gidB = [int]$r.Json.group.id
    CheckTrue 'F9' '前置：ivan 另建群 B 并拉 heidi 进群' ($gidB -gt 0) "响应=$($r.Text)"

    $r = Invoke-Api -Method POST -Path "/api/groups/$gidB/messages/$gmid/recall" -Token $heidi.Token
    CheckTrue 'F10' '跨群撤回被拒（success=false）' (-not $r.Json.success) "响应=$($r.Text)"

    $r = Invoke-Api -Method POST -Path "/api/groups/$gid/messages/$gmid/recall" -Token $heidi.Token
    CheckTrue 'F11' '本群发送者可撤回自己的消息' ($r.Status -eq 200 -and $r.Json.success) "响应=$($r.Text)"

    $r = Invoke-Api -Method POST -Path "/api/groups/$gid/messages/$gmid/recall" -Token $heidi.Token
    CheckTrue 'F12' '重复撤回失败（success=false）' (-not $r.Json.success) "响应=$($r.Text)"

    # ================= G. 登录限流 =================
    Write-Host ""
    Write-Host "===== G. 登录限流 =====" -ForegroundColor Cyan

    $null = Invoke-Api -Method POST -Path '/api/register' -Body @{ username = 'rlprobe'; password = 'pass1234' }
    $hit429 = $false
    $lastStatus = 0
    $attempts = 0
    for ($i = 2; $i -le 11; $i++) {
        $attempts++
        $r = Invoke-Api -Method POST -Path '/api/login' -Body @{ username = 'rlprobe'; password = 'wrongpass' }
        $lastStatus = $r.Status
        if ($lastStatus -eq 429) { $hit429 = $true; break }
    }
    CheckTrue 'G1' '窗口内超过 10 次后触发 429 限流' $hit429 `
        "连续 $attempts 次尝试未触发 429，最后一次返回 $lastStatus"

    # ================= H. APK 下载（/apk 端到端 + Range 分段） =================
    Invoke-ApkTestSuite
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

# ===== 汇总 =====
Write-Host ""
Write-Host "============================================" -ForegroundColor Cyan
if ($script:Fail -eq 0) {
    Write-Host ("  结果: {0} 项全部通过" -f $script:Pass) -ForegroundColor Green
} else {
    Write-Host ("  结果: 通过 {0} 项，失败 {1} 项" -f $script:Pass, $script:Fail) -ForegroundColor Red
    foreach ($f in $script:Failed) { Write-Host "    - $f" -ForegroundColor Red }
}
Write-Host "============================================" -ForegroundColor Cyan
exit ([int]($script:Fail -gt 0))
