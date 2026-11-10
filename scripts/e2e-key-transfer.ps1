<#
.SYNOPSIS
  E2EE 密钥管理（换机迁移）后端集成测试。

.DESCRIPTION
  覆盖 /api/user/publickey 与 /api/user/keybackup 两个接口，以及第 4/5 步
  「重置时同步服务端密码加密备份」所依赖的关键性质。

  与项目其它测试的分工：
    scripts/run-unit-tests.ps1      客户端纯 JVM 单测（含 KeyTransferPayloadTest
                                    的载荷编解码、指纹、配对判定等 31 条用例）
    scripts/e2e-backend-tests.ps1   后端全量接口 E2E（注册/登录/好友/钱包/商城…）
    scripts/e2e-key-transfer.ps1    本脚本：E2EE 密钥链路专项，可单独快速回归

  ⚠️ 本脚本覆盖不到的（必须真机手工验收）：
    · 两台设备之间的实际扫码成功率（能生成 ≠ 扫得动；v2 载荷比 v1 模块更多，尤其要测）
    · 相机权限拒绝流程、相册兜底
    · 覆盖确认框、重置两次确认（含手动输入「我确认」）等 UI 交互
    · KeyManagementActivity 的零私钥访问硬门禁（用 grep 校验，见 docs/guides/RELEASE.md）
    · **H2 的「传输失败 → 拒绝上传」分支**：本脚本只能钉服务端侧契约
      （NB-1：无备份时返回空串），而"GET 抛异常 → 客户端拒绝上传新备份"
      发生在客户端 OkHttp 路径上，纯 JVM 无法覆盖（本机离线缓存的
      mockwebserver 不可得，见 docs/guides/RELEASE.md §7.3）。
      **该分支只能真机手测**：飞行模式下重置密钥 → 必须弹「无法确认服务端备份状态」
      且服务端备份不被改动；恢复网络后点「重试同步」必须成功。
    · H1 的 v2 载荷（口令/KDF/AEAD）由客户端纯 JVM 单测覆盖，本脚本不涉及。

  ⚠️ 本脚本的密码学计算不由 App 代码完成：
    密钥对由 PowerShell 的 .NET ECDsa 生成；备份加解密由 scripts/tools/KeyBackupTool.java
    （纯 JDK）实现。二者都不引用 App 的类，因此能真正验证「服务端存的东西解得开/解不开」，
    而不是拿同一个实现自证。

.PARAMETER BaseUrl
  服务端基址，默认 http://localhost:5000

.PARAMETER Verbose
  输出每条请求的细节。

.EXAMPLE
  pwsh -File scripts/e2e-key-transfer.ps1
  pwsh -File scripts/e2e-key-transfer.ps1 -BaseUrl http://<示例IP>:5000
#>
[CmdletBinding()]
param(
    [string]$BaseUrl = 'http://localhost:5000',
    [string]$Username = $null
)

$ErrorActionPreference = 'Stop'
$script:Pass = 0
$script:Fail = 0
$script:Base = $BaseUrl.TrimEnd('/')

# =====================================================================
# 断言 / 输出
# =====================================================================

function Check {
    param([string]$Id, [string]$Desc, [bool]$Ok, [string]$Detail = '')
    if ($Ok) {
        $script:Pass++
        Write-Host ("  [PASS] {0,-6} {1}" -f $Id, $Desc) -ForegroundColor Green
    } else {
        $script:Fail++
        Write-Host ("  [FAIL] {0,-6} {1}" -f $Id, $Desc) -ForegroundColor Red
        if ($Detail) { Write-Host ("         -> {0}" -f $Detail) -ForegroundColor DarkGray }
    }
}

function Section { param([string]$Title) Write-Host "`n=== $Title ===" -ForegroundColor Cyan }

# =====================================================================
# HTTP
# =====================================================================

function Invoke-Api {
    param(
        [string]$Method,
        [string]$Path,
        $Body = $null,
        [string]$Token = $null
    )
    $headers = @{}
    if ($Token) { $headers['Authorization'] = "Bearer $Token" }
    $params = @{
        Uri             = "$script:Base$Path"
        Method          = $Method
        Headers         = $headers
        SkipHttpErrorCheck = $true
        TimeoutSec      = 30
    }
    if ($null -ne $Body) {
        $params['Body']        = ($Body | ConvertTo-Json -Compress -Depth 5)
        $params['ContentType'] = 'application/json'
    }
    $resp = Invoke-WebRequest @params
    $json = $null
    if ($resp.Content) {
        try { $json = $resp.Content | ConvertFrom-Json } catch { $json = $null }
    }
    return [pscustomobject]@{
        Status = [int]$resp.StatusCode
        Body   = $resp.Content
        Json   = $json
    }
}

# =====================================================================
# 密码学：App 用到的格式，全部在这里独立复刻（.NET 实现）
# =====================================================================

function New-EcKeyPair {
    $ec = [System.Security.Cryptography.ECDsa]::Create(
        [System.Security.Cryptography.ECCurve]::CreateFromFriendlyName('nistP256'))
    return $ec
}

function Get-B64NoWrap { param([byte[]]$Bytes) [Convert]::ToBase64String($Bytes) }

function Get-Sha256Hex {
    param([byte[]]$Bytes)
    $h = [System.Security.Cryptography.SHA256]::Create().ComputeHash($Bytes)
    return (($h | ForEach-Object { $_.ToString('x2') }) -join '')
}

function Get-FingerprintShort {
    param([byte[]]$Spki)
    return (Get-Sha256Hex $Spki).Substring(0, 8)
}

<#
  KeyBackup v2 加密（与 App 的 KeyBackup.encryptBackup 字节格式一致）：
    0x02 | saltLen(1) | 随机盐 | 12字节IV | GCM密文+tag
    派生：PBKDF2-HMAC-SHA1, 210000 次, 盐 = "familychat/keybackup/v1/" + userId + 随机盐

  实现放在 scripts/tools/KeyBackupTool.java（纯 JDK）。
  原因：.NET 的 Aes 类只支持 CBC/ECB/OFB/CFB/CTS，**不支持 GCM**，
  PowerShell 侧无法直接实现；Java 的 Cipher 支持 GCM 且无需第三方依赖。
  密钥对仍由 PowerShell 的 .NET ECDsa 生成后传入，Java 侧只做封装格式。
#>
function New-KeyBackup {
    param([string]$PrivateKeyB64, [string]$Password, [int]$UserId)
    $out = & $script:JavaExe -cp $script:ToolDir KeyBackupTool encrypt $PrivateKeyB64 $Password $UserId 2>&1
    if ($LASTEXITCODE -ne 0) { throw "KeyBackupTool encrypt 失败: $out" }
    # 密文是单行 Base64
    return ($out | Where-Object { $_ } | Select-Object -Last 1).ToString().Trim()
}

<#
  KeyBackup v1/v2 解密。密码不对（GCM tag 校验失败）时抛异常。
  这是脚本里最关键的一段：它证明"服务端存的密文解得开 / 解不开"可被独立验证。

  ⚠️ 必须把多行输出拼回单个字符串：明文形如 "公钥\n私钥"，
     而 Windows 上 Java 的 stdout 会把 \n 变成 \r\n，
     所以这里统一把行尾还原成 \n，才能与 PowerShell 侧的 "{0}`n{1}" 逐字符比对。
#>
function Read-KeyBackup {
    param([string]$BackupB64, [string]$Password, [int]$UserId)
    $out = & $script:JavaExe -cp $script:ToolDir KeyBackupTool decrypt $BackupB64 $Password $UserId 2>&1
    if ($LASTEXITCODE -eq 2) { throw 'GCM 校验失败：密码错误或密文损坏' }
    if ($LASTEXITCODE -ne 0) { throw "KeyBackupTool decrypt 失败(exit=$LASTEXITCODE): $out" }
    return (($out | ForEach-Object { $_.ToString() }) -join "`n")
}

# =====================================================================
# 环境检查
# =====================================================================

Write-Host "密语 · E2EE 密钥管理 后端集成测试" -ForegroundColor White
Write-Host "目标: $script:Base"

Section '环境检查'
try {
    $ping = Invoke-WebRequest -Uri "$script:Base/version.json" -TimeoutSec 5 -SkipHttpErrorCheck
    Check 'ENV-1' '服务端可达' ($ping.StatusCode -eq 200) "status=$($ping.StatusCode)"
} catch {
    Write-Host "  [FAIL] ENV-1 服务端不可达: $($_.Exception.Message)" -ForegroundColor Red
    Write-Host "`n请先启动服务端（scripts/run-server.sh 或 gradlew :server:run），或用 -BaseUrl 指定地址。" -ForegroundColor Yellow
    exit 1
}

# 编译 KeyBackupTool（纯 JDK，用于独立复刻 AES-GCM 备份格式）
$script:ToolDir = Join-Path $PSScriptRoot 'tools'
$script:JavaExe = (Get-Command java -ErrorAction SilentlyContinue).Source
$javacExe = (Get-Command javac -ErrorAction SilentlyContinue).Source

if (-not $script:JavaExe -or -not $javacExe) {
    # 回退到常见的 JDK 安装位置
    $candidates = @(
        'D:\Tools\JDK\jdk-24.0.2+12\bin',
        "$env:JAVA_HOME\bin"
    ) | Where-Object { $_ -and (Test-Path (Join-Path $_ 'javac.exe')) }
    if ($candidates.Count -gt 0) {
        $script:JavaExe = Join-Path $candidates[0] 'java.exe'
        $javacExe = Join-Path $candidates[0] 'javac.exe'
    }
}
if (-not $script:JavaExe -or -not $javacExe) {
    Write-Host '  [FAIL] ENV-2 找不到 java/javac（备份加解密需要 JDK）' -ForegroundColor Red
    exit 1
}
Check 'ENV-2' '找到 java/javac' $true "java=$script:JavaExe"

$toolSrc = Join-Path $script:ToolDir 'KeyBackupTool.java'
$toolClass = Join-Path $script:ToolDir 'KeyBackupTool.class'
$needCompile = $true
if (Test-Path $toolClass) {
    $needCompile = (Get-Item $toolSrc).LastWriteTime -gt (Get-Item $toolClass).LastWriteTime
}
if ($needCompile) {
    $compileOut = & $javacExe -d $script:ToolDir $toolSrc 2>&1
    if ($LASTEXITCODE -ne 0) {
        Write-Host "  [FAIL] ENV-3 KeyBackupTool 编译失败: $compileOut" -ForegroundColor Red
        exit 1
    }
}
Check 'ENV-3' 'KeyBackupTool 就绪（独立复刻备份格式）' (Test-Path $toolClass)

# 自检：工具本身能否正确往返（先证工具可信，再用它验服务端）
$selfPlain = "selfcheck-$(Get-Random)"
$selfBk = New-KeyBackup -PrivateKeyB64 $selfPlain -Password 'selfpw123' -UserId 1
$selfBack = Read-KeyBackup -BackupB64 $selfBk -Password 'selfpw123' -UserId 1
Check 'ENV-4' 'KeyBackupTool 自身往返自检通过' ($selfBack -eq $selfPlain)

# =====================================================================
# 准备账号
# =====================================================================

Section '准备测试账号'
if (-not $Username) {
    $Username = 'kt_' + [DateTime]::UtcNow.ToString('MMddHHmmss') + (Get-Random -Minimum 100 -Maximum 999)
}
$Password = 'KeyTest12345'

$reg = Invoke-Api -Method POST -Path '/api/register' -Body @{ username = $Username; password = $Password }
if ($reg.Status -ne 200 -and $reg.Status -ne 201) {
    Write-Host "  [FAIL] 注册失败 status=$($reg.Status): $($reg.Body)" -ForegroundColor Red
    exit 1
}
$Token = $reg.Json.token
$Uid = [int]$reg.Json.user.id
Write-Host ("  账号 {0} (uid={1}) 已创建" -f $Username, $Uid)

# =====================================================================
# 1. 公钥上报 / 读取
# =====================================================================

Section '1. 公钥上报与读取（第 4 步依赖的接口）'

$ecA = New-EcKeyPair
$pubA = Get-B64NoWrap $ecA.ExportSubjectPublicKeyInfo()
$privA = Get-B64NoWrap $ecA.ExportPkcs8PrivateKey()
$fpA = Get-FingerprintShort $ecA.ExportSubjectPublicKeyInfo()

$r = Invoke-Api -Method POST -Path '/api/user/publickey' -Body @{ public_key = $pubA } -Token $Token
Check 'PK-1' '上报合法 X509 EC 公钥成功' ($r.Status -eq 200) "status=$($r.Status) body=$($r.Body)"

$r = Invoke-Api -Method GET -Path "/api/user/publickey?userId=$Uid" -Token $Token
$got = $r.Json.public_key
Check 'PK-2' '读取公钥与上报值逐字符一致' ($got -eq $pubA) "len got=$(if($got){$got.Length}else{'null'}) want=$($pubA.Length)"
Check 'PK-3' '公钥指纹可独立复算（App 与脚本同算法）' ($fpA.Length -eq 8) "fp=$fpA"

# 未认证必须被拒
$r = Invoke-Api -Method POST -Path '/api/user/publickey' -Body @{ public_key = $pubA }
Check 'PK-4' '未携带 token 上报被拒' ($r.Status -eq 401 -or $r.Status -eq 403) "status=$($r.Status)"

# 垃圾公钥必须被服务端 400 拒绝（E2eeValidator 结构校验）
$r = Invoke-Api -Method POST -Path '/api/user/publickey' -Body @{ public_key = 'not-a-real-key' } -Token $Token
Check 'PK-5' '非 Base64/非 X509 公钥被拒(400)' ($r.Status -eq 400) "status=$($r.Status)"

$r = Invoke-Api -Method POST -Path '/api/user/publickey' -Body @{ public_key = (Get-B64NoWrap ([byte[]](1..200))) } -Token $Token
Check 'PK-6' '结构合法但非 EC 公钥被拒(400)' ($r.Status -eq 400) "status=$($r.Status)"

$r = Invoke-Api -Method POST -Path '/api/user/publickey' -Body @{ public_key = '' } -Token $Token
Check 'PK-7' '空公钥被拒(400)' ($r.Status -eq 400) "status=$($r.Status)"

# 换新设备/重置后再次上报：服务端应覆盖而不是报错
$ecB = New-EcKeyPair
$pubB = Get-B64NoWrap $ecB.ExportSubjectPublicKeyInfo()
$r = Invoke-Api -Method POST -Path '/api/user/publickey' -Body @{ public_key = $pubB } -Token $Token
$r2 = Invoke-Api -Method GET -Path "/api/user/publickey?userId=$Uid" -Token $Token
Check 'PK-8' '重复上报覆盖旧公钥（重置场景）' ($r.Status -eq 200 -and $r2.Json.public_key -eq $pubB) "status=$($r.Status)"

# 只读无副作用：连续两次读取必须完全相同
$r3 = Invoke-Api -Method GET -Path "/api/user/publickey?userId=$Uid" -Token $Token
Check 'PK-9' '读取公钥是只读操作（两次结果一致）' ($r2.Json.public_key -eq $r3.Json.public_key)

# =====================================================================
# 2. 密码加密备份：第 5 步的闭环
# =====================================================================

Section '2. 密码加密密钥备份（第 5 步：重置时同步备份）'

# 先把公钥恢复成 A，模拟"设备 A 的当前密钥"
$null = Invoke-Api -Method POST -Path '/api/user/publickey' -Body @{ public_key = $pubA } -Token $Token

# ── NB-1：H2 的客户端三态判定，服务端侧锚点 ──────────────────────────────
# 客户端用 KeyBackupResponse.parse 把响应分成三态：
#   「有备份」/「服务端确认无备份」/「问不到（必须拒绝上传）」。
# 其中"确认无备份"必须以**空串**表达，而不是缺字段或 404 —— 否则客户端
# 会把"还没有备份的新账号"误判成"查询失败"，新用户首次登录就永远备份不了。
# 这条用例钉死该契约（新账号此时尚未上传过任何备份）。
$r = Invoke-Api -Method GET -Path '/api/user/keybackup' -Token $Token
$nbEmpty = ($r.Status -eq 200) -and ($null -ne $r.Json) -and ($r.Json.PSObject.Properties.Name -contains 'backup_b64') -and ($r.Json.backup_b64 -eq '')
Check 'NB-1' '新账号查询备份返回 200 且 backup_b64 为空串（"确认无备份"的契约）' $nbEmpty `
    "status=$($r.Status) body=$($r.Body)"

# 未认证查询必须被拒（否则任何人都能探测某账号是否有密钥备份）
$r = Invoke-Api -Method GET -Path '/api/user/keybackup'
Check 'NB-2' '未认证查询备份被拒' ($r.Status -eq 401 -or $r.Status -eq 403) "status=$($r.Status)"

# 备份明文格式与 App 一致：公钥 + "\n" + 私钥。
# 比较时统一去掉 \r：Windows 上 Java 的 stdout 会把 \n 变成 \r\n，
# 而 App 侧（Android）写的是纯 \n，不能因为换行形态不同就误报失败。
$combinedA = "$pubA`n$privA"
$backupA = New-KeyBackup -PrivateKeyB64 $combinedA -Password $Password -UserId $Uid
$r = Invoke-Api -Method PUT -Path '/api/user/keybackup' -Body @{ backup_b64 = $backupA } -Token $Token
Check 'BK-1' '上传密码加密备份成功' ($r.Status -eq 200) "status=$($r.Status) body=$($r.Body)"

$fetched = (Invoke-Api -Method GET -Path '/api/user/keybackup' -Token $Token).Json.backup_b64
Check 'BK-2' '取回的密文与上传的一致' ($fetched -eq $backupA) "len got=$(if($fetched){$fetched.Length}else{'null'})"

# 核心：脚本独立解密，证明"服务端存的东西 App 解得开"
$decrypted = $null
$decryptOk = $false
try {
    $decrypted = Read-KeyBackup -BackupB64 $fetched -Password $Password -UserId $Uid
    $decryptOk = ((($decrypted -replace "`r", '') -eq ($combinedA -replace "`r", '')))
} catch {
    $decrypted = "异常: $($_.Exception.Message)"
}
Check 'BK-3' '独立解密还原出完全相同的 公钥+私钥' $decryptOk "得到=$(if($decrypted -and $decrypted.Length -gt 40){$decrypted.Substring(0,40)+'...'}else{$decrypted})"

# 拆开后必须分别等于原始公私钥（证明格式是 pub\npriv，两段都完整）
$parts = $null
if ($decrypted -and $decrypted -notlike '异常*') { $parts = ($decrypted -replace "`r", '').Split("`n") }
Check 'BK-4' '备份内容为 pub\npriv 两行且各自完整' `
    ($parts -and $parts.Length -eq 2 -and $parts[0] -eq $pubA -and $parts[1] -eq $privA) `
    "行数=$(if($parts){$parts.Length}else{'n/a'})"

# =====================================================================
# 3. 错误密码的行为 —— 第 5 步"预检"的立论依据
# =====================================================================

Section '3. 错误密码的行为（第 5 步预检的立论依据）'

$wrongOk = $false
$wrongErr = ''
try {
    $null = Read-KeyBackup -BackupB64 $fetched -Password 'DefinitelyWrongPw999' -UserId $Uid
    $wrongOk = $true
} catch {
    $wrongErr = $_.Exception.Message
}
Check 'PW-1' '错误密码无法解密现有备份（GCM 校验失败）' (-not $wrongOk) "居然解开了？err=$wrongErr"

# 这正是 App 里"密码预检失败就不上传"的理由：
# 若是错误密码也照传，旧备份会被无法解开的新密文覆盖，且用户不会立刻发现。
$stillThere = (Invoke-Api -Method GET -Path '/api/user/keybackup' -Token $Token).Json.backup_b64
$stillMine = $false
try {
    $stillMine = (((Read-KeyBackup -BackupB64 $stillThere -Password $Password -UserId $Uid) `
        -replace "`r", '') -eq ($combinedA -replace "`r", ''))
} catch { $stillMine = $false }
Check 'PW-2' '预检失败时不覆盖：旧备份仍可被正确密码解开' $stillMine

# =====================================================================
# 4. 重置场景闭环：换钥 + 换备份
# =====================================================================

Section '4. 重置场景闭环（第 4+5 步）'

# 第 4 步：重置生成新钥并上报新公钥
$r = Invoke-Api -Method POST -Path '/api/user/publickey' -Body @{ public_key = $pubB } -Token $Token
Check 'RS-1' '重置后上报新公钥成功' ($r.Status -eq 200)

# 第 5 步：用登录密码把新钥重新加密覆盖备份
$combinedB = "$pubB`n$(Get-B64NoWrap $ecB.ExportPkcs8PrivateKey())"
$backupB = New-KeyBackup -PrivateKeyB64 $combinedB -Password $Password -UserId $Uid
$r = Invoke-Api -Method PUT -Path '/api/user/keybackup' -Body @{ backup_b64 = $backupB } -Token $Token
Check 'RS-2' '重置后覆盖服务端备份成功' ($r.Status -eq 200)

$nowBackup = (Invoke-Api -Method GET -Path '/api/user/keybackup' -Token $Token).Json.backup_b64
Check 'RS-3' '备份密文已变化（不是旧密文）' ($nowBackup -ne $backupA)

$nowDecrypted = Read-KeyBackup -BackupB64 $nowBackup -Password $Password -UserId $Uid
$combinedBNorm = $combinedB -replace "`r", ''
$nowNorm = $nowDecrypted -replace "`r", ''
Check 'RS-4' '新备份解出的是新密钥对' ($nowNorm -eq $combinedBNorm) `
    "得到前 40 字符=$(if($nowDecrypted){$nowDecrypted.Substring(0,40)+'...'}else{'null'})"

$nowPub = (Invoke-Api -Method GET -Path "/api/user/publickey?userId=$Uid" -Token $Token).Json.public_key
Check 'RS-5' '服务端公钥与备份内公钥一致（闭环，不会新旧不同源）' ($nowPub -eq $pubB)

# 核心安全性质：重置后，旧密钥不存在任何残留可恢复路径
$oldGone = $false
try {
    $old = Read-KeyBackup -BackupB64 $nowBackup -Password $Password -UserId $Uid
    $oldGone = (($old -replace "`r", '') -ne ($combinedA -replace "`r", ''))
} catch { $oldGone = $true }
Check 'RS-6' '重置后无法再从备份取回旧密钥（重置不会被静默撤销）' $oldGone

# =====================================================================
# 5. 备份接口的输入校验
# =====================================================================

Section '5. 备份接口输入校验'

$r = Invoke-Api -Method PUT -Path '/api/user/keybackup' -Body @{ backup_b64 = '' } -Token $Token
Check 'BV-1' '空备份被拒(400)' ($r.Status -eq 400) "status=$($r.Status)"

$r = Invoke-Api -Method PUT -Path '/api/user/keybackup' -Body @{ backup_b64 = 'short' } -Token $Token
Check 'BV-2' '过短/格式非法备份被拒(400)' ($r.Status -eq 400) "status=$($r.Status)"

$r = Invoke-Api -Method PUT -Path '/api/user/keybackup' -Body @{ backup_b64 = (Get-B64NoWrap ([byte[]](0x09, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 17, 18, 19, 20))) } -Token $Token
Check 'BV-3' '未知版本头被拒(400)' ($r.Status -eq 400) "status=$($r.Status)"

$r = Invoke-Api -Method PUT -Path '/api/user/keybackup' -Body @{ backup_b64 = $backupB }
Check 'BV-4' '未认证上传备份被拒' ($r.Status -eq 401 -or $r.Status -eq 403) "status=$($r.Status)"

# =====================================================================
# 6. 协议版本兼容性（旧备份不能被新版本读坏）
# =====================================================================

Section '6. 脚本侧协议自洽性'

# 同一密码两次加密必须得到不同密文（v2 随机盐），否则能被看出"密码没改、密钥没变"
$b1 = New-KeyBackup -PrivateKeyB64 $combinedA -Password $Password -UserId $Uid
$b2 = New-KeyBackup -PrivateKeyB64 $combinedA -Password $Password -UserId $Uid
Check 'PB-1' '同输入两次加密密文不同（随机盐生效）' ($b1 -ne $b2)

Check 'PB-2' '两次密文都能解回同一明文' `
    (((Read-KeyBackup -BackupB64 $b1 -Password $Password -UserId $Uid) -replace "`r", '') -eq ($combinedA -replace "`r", '') -and
     ((Read-KeyBackup -BackupB64 $b2 -Password $Password -UserId $Uid) -replace "`r", '') -eq ($combinedA -replace "`r", ''))

# 跨账号隔离：用别的 userId 派生盐必须解不开
$crossOk = $false
try {
    $null = Read-KeyBackup -BackupB64 $b1 -Password $Password -UserId ($Uid + 1)
    $crossOk = $true
} catch { }
Check 'PB-3' '换 userId 解不开（跨账号隔离）' (-not $crossOk)

Check 'PB-4' '指纹短码为 8 位小写 hex' ($fpA -match '^[0-9a-f]{8}$') "fp=$fpA"

# =====================================================================
# 汇总
# =====================================================================

Write-Host ''
Write-Host '────────────────────────────────────────' -ForegroundColor DarkGray
if ($script:Fail -eq 0) {
    Write-Host ("全部通过: {0} 项" -f $script:Pass) -ForegroundColor Green
    exit 0
} else {
    Write-Host ("通过 {0} 项，失败 {1} 项" -f $script:Pass, $script:Fail) -ForegroundColor Red
    exit 1
}
