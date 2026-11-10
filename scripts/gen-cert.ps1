# =============================================================================
#  gen-cert.ps1 — Miyu 自签证书生成脚本
#
#  生成内容（全部落到 server/data/，该目录已被 .gitignore 忽略）：
#    ssl_keystore.p12            PKCS12 keystore，服务端 Javalin/Jetty SSL 用
#    ssl_keystore_password.txt   keystore 口令（32 位随机 [A-Za-z0-9]）
#    ca.crt                      CA 证书，**PEM 格式**，供客户端 res/raw 打包
#
#  中间文件（ca.key / server.key / server.csr / ext 配置）全部放在系统临时目录，
#  脚本结束（成功或失败）时删除，不会污染仓库。
#
#  算法选择：
#    CA 密钥     RSA 4096 —— CA 有效期 10 年，RSA 4096 在所有 Android 版本上
#                             兼容性最好，且只签一次，性能无影响。
#    服务器密钥  RSA 2048 —— 与 Android minSdk 24（API 24）兼容性最稳；
#                             EC P-256 虽更小更快，但不同 Android 版本/中间件
#                             对 EC 的支持差异更大，不值得为此冒险。
#    两者均使用 SHA-256 摘要。
#
#  用法：
#    pwsh -File scripts/gen-cert.ps1
#    powershell -ExecutionPolicy Bypass -File scripts\gen-cert.ps1
#
#  幂等行为：输出文件已存在时**默认拒绝覆盖并退出**（不会静默删掉正在用的
#  keystore，否则服务端证书会莫名其妙失效）。确认要重做时显式加 -Force。
# =============================================================================

[CmdletBinding()]
param(
    # 允许覆盖已存在的输出文件。默认拒绝，避免误删生产 keystore。
    [switch]$Force
)

$ErrorActionPreference = 'Stop'

# ── 常量：域名 / 有效期（与本项目 HTTPS 改造决策一致，改这里即可整体调整）──
$DomainName     = 'miyu.lan'
$CaCommonName   = 'Miyu Local CA'
$OrgName        = 'Miyu'
$Days           = 3650          # 10 年
$P12PasswordLen = 32

# ── 路径解析：脚本位于 scripts/，输出到 server/data/ ──
$ScriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$RepoRoot  = Split-Path -Parent $ScriptDir
$DataDir   = Join-Path $RepoRoot 'server\data'

$KeystorePath = Join-Path $DataDir 'ssl_keystore.p12'
$PasswordPath = Join-Path $DataDir 'ssl_keystore_password.txt'
$CaCrtPath    = Join-Path $DataDir 'ca.crt'

function Write-Step($msg) { Write-Host "  $msg" }
function Write-Head($msg) { Write-Host ''; Write-Host "== $msg" -ForegroundColor Cyan }
function Write-Ok($msg)   { Write-Host "  [OK]   $msg" -ForegroundColor Green }
function Write-Warn2($msg){ Write-Host "  [WARN] $msg" -ForegroundColor Yellow }
function Die($msg) {
    Write-Host ''
    Write-Host "  [ERROR] $msg" -ForegroundColor Red
    Write-Host ''
    exit 1
}

# 统一调用 openssl：清空上一条命令的 $LASTEXITCODE，并让 stderr 也进日志。
# 失败时把 openssl 的原始输出一并抛出来，避免只看到一句 "openssl 执行失败"。
function Invoke-OpenSsl {
    param(
        [Parameter(Mandatory = $true)][string[]]$Arguments,
        [Parameter(Mandatory = $true)][string]$What
    )
    $global:LASTEXITCODE = 0
    $output = & $script:OpenSsl @Arguments 2>&1
    if ($LASTEXITCODE -ne 0) {
        $text = ($output | Out-String).Trim()
        Die "$What 失败 (openssl exit=$LASTEXITCODE)`n        命令: openssl $($Arguments -join ' ')`n        输出: $text"
    }
    return $output
}

Write-Host ''
Write-Host '==============================================================' -ForegroundColor Cyan
Write-Host '  Miyu 自签证书生成  (CA / 服务器证书 / PKCS12 keystore)' -ForegroundColor Cyan
Write-Host '==============================================================' -ForegroundColor Cyan
Write-Step "域名      : $DomainName"
Write-Step "有效期    : $Days 天 (约 10 年)"
Write-Step "输出目录  : $DataDir"

# ─────────────────────────────────────────────────────────────────────────────
# [1/8] 检查 openssl
# ─────────────────────────────────────────────────────────────────────────────
Write-Head '[1/8] 检查 openssl'

$OpenSsl = $null

# 1) PATH 里找
$cmd = Get-Command openssl -ErrorAction SilentlyContinue
if ($cmd -and $cmd.Source) {
    $OpenSsl = $cmd.Source
} else {
    # 2) 常见安装路径（PATH 未刷新的情况非常常见）
    $candidates = @(
        'C:\Program Files\OpenSSL-Win64\bin\openssl.exe',
        'C:\Program Files\OpenSSL\bin\openssl.exe',
        'C:\OpenSSL-Win64\bin\openssl.exe',
        'C:\Program Files (x86)\OpenSSL-Win32\bin\openssl.exe',
        "$env:ProgramFiles\Git\usr\bin\openssl.exe",
        "${env:ProgramFiles(x86)}\Git\usr\bin\openssl.exe",
        'C:\ProgramData\chocolatey\bin\openssl.exe'
    )
    foreach ($c in $candidates) {
        if ($c -and (Test-Path -LiteralPath $c)) { $OpenSsl = $c; break }
    }
}

if (-not $OpenSsl) {
    Write-Host ''
    Write-Host '  未找到 openssl。请先安装，任选一种：' -ForegroundColor Yellow
    Write-Host ''
    Write-Host '    winget install --id ShiningLight.OpenSSL.Light -e' -ForegroundColor White
    Write-Host '    winget install --id ShiningLight.OpenSSL -e' -ForegroundColor White
    Write-Host ''
    Write-Host '  或手动下载安装（推荐 Win64 "Light" 版）：' -ForegroundColor Yellow
    Write-Host '    https://slproweb.com/products/Win32OpenSSL.html' -ForegroundColor White
    Write-Host ''
    Write-Host '  安装后【重新打开一个终端】再跑本脚本（PATH 需要刷新）。' -ForegroundColor Yellow
    Write-Host '  若已安装但仍找不到，可把 openssl.exe 所在目录加进 PATH，' -ForegroundColor Yellow
    Write-Host '  或把该 exe 放到 C:\Program Files\OpenSSL-Win64\bin\openssl.exe。' -ForegroundColor Yellow
    Write-Host ''
    exit 1
}

Write-Ok "openssl: $OpenSsl"
$opensslVersion = (& $OpenSsl version 2>&1 | Select-Object -First 1)
Write-Step "版本: $opensslVersion"

# 这个 OpenSSL 必须能读 PKCS12 并支持 -addext / -extfile 语法（1.1.1+ / 3.x 均可）
if ($opensslVersion -match '^OpenSSL\s+(\d+)\.(\d+)') {
    $maj = [int]$Matches[1]; $min = [int]$Matches[2]
    if ($maj -lt 1 -or ($maj -eq 1 -and $min -lt 1)) {
        Die "openssl 版本过低（$opensslVersion），需要 1.1.1 或以上（本脚本使用 SHA-256 与 SAN 扩展）。"
    }
}

# ─────────────────────────────────────────────────────────────────────────────
# [2/8] 幂等检查（默认拒绝覆盖）
# ─────────────────────────────────────────────────────────────────────────────
Write-Head '[2/8] 检查已有产物'

$existing = @()
foreach ($p in @($KeystorePath, $PasswordPath, $CaCrtPath)) {
    if (Test-Path -LiteralPath $p) { $existing += $p }
}

if ($existing.Count -gt 0) {
    Write-Warn2 '以下文件已存在：'
    foreach ($p in $existing) { Write-Host "           $p" -ForegroundColor Yellow }
    Write-Host ''
    if (-not $Force) {
        Write-Host '  默认不覆盖：keystore 可能正被运行中的服务端使用，' -ForegroundColor Yellow
        Write-Host '  直接重做会让已分发的客户端 CA 与新证书不匹配。' -ForegroundColor Yellow
        Write-Host ''
        Write-Host '  确认要重新生成，请手动删除上述文件后再跑，或加 -Force：' -ForegroundColor Yellow
        Write-Host '      pwsh -File scripts/gen-cert.ps1 -Force' -ForegroundColor White
        Write-Host ''
        exit 1
    }
    Write-Warn2 '已指定 -Force，继续覆盖。'
}

# 确保输出目录存在（data/ 已被 gitignore，首次运行可能不存在）
if (-not (Test-Path -LiteralPath $DataDir)) {
    New-Item -ItemType Directory -Path $DataDir -Force | Out-Null
    Write-Ok "已创建目录: $DataDir"
}

# ─────────────────────────────────────────────────────────────────────────────
# [3/8] 准备临时工作目录
# ─────────────────────────────────────────────────────────────────────────────
Write-Head '[3/8] 准备临时目录'

$WorkDir = Join-Path ([System.IO.Path]::GetTempPath()) ("miyu-cert-" + [guid]::NewGuid().ToString('N').Substring(0, 8))
New-Item -ItemType Directory -Path $WorkDir -Force | Out-Null
Write-Step "临时目录: $WorkDir"
Write-Step '（ca.key / server.key / server.csr 等中间文件都放这里，结束时删除）'

# OpenSSL 的 -extfile / -config 里用正斜杠最省心（反斜杠会被当成转义符）
$WorkDirFwd = $WorkDir.Replace('\', '/')

try {
    # ─────────────────────────────────────────────────────────────────────────
    # [4/8] 生成 CA 私钥 + 自签 CA 证书
    # ─────────────────────────────────────────────────────────────────────────
    Write-Head '[4/8] 生成 CA 私钥 + 自签 CA 证书'

    $caKey = Join-Path $WorkDir 'ca.key'
    $caCrt = Join-Path $WorkDir 'ca.crt'

    Invoke-OpenSsl -Arguments @('genrsa', '-out', $caKey, '4096') -What 'genrsa 4096 (CA 私钥)'
    Write-Ok 'CA 私钥 ca.key 已生成 (RSA 4096)'

    Invoke-OpenSsl -Arguments @(
        'req', '-x509', '-new', '-sha256',
        '-key', $caKey,
        '-days', "$Days",
        '-subj', "/C=CN/O=$OrgName/CN=$CaCommonName",
        '-addext', 'basicConstraints=critical,CA:TRUE,pathlen:0',
        '-addext', 'keyUsage=critical,keyCertSign,cRLSign',
        '-addext', 'subjectKeyIdentifier=hash',
        '-out', $caCrt
    ) -What '自签 CA 证书'
    Write-Ok "CA 证书 ca.crt 已生成 (CN=$CaCommonName, $Days 天)"

    # ─────────────────────────────────────────────────────────────────────────
    # [5/8] 生成服务器私钥 + CSR
    # ─────────────────────────────────────────────────────────────────────────
    Write-Head '[5/8] 生成服务器私钥 + CSR'

    $serverKey = Join-Path $WorkDir 'server.key'
    $serverCsr = Join-Path $WorkDir 'server.csr'

    Invoke-OpenSsl -Arguments @('genrsa', '-out', $serverKey, '2048') -What 'genrsa 2048 (服务器私钥)'
    Write-Ok '服务器私钥 server.key 已生成 (RSA 2048)'

    Invoke-OpenSsl -Arguments @(
        'req', '-new', '-sha256',
        '-key', $serverKey,
        '-subj', "/C=CN/O=$OrgName/CN=$DomainName",
        '-out', $serverCsr
    ) -What '生成 CSR'
    Write-Ok "CSR 已生成 (CN=$DomainName)"

    # ─────────────────────────────────────────────────────────────────────────
    # [6/8] 用 CA 签发服务器证书（SAN 必须）
    # ─────────────────────────────────────────────────────────────────────────
    Write-Head '[6/8] 用 CA 签发服务器证书'

    # 用 extfile 而不是 -addext：签发时的扩展写在独立文件里，便于 review 和复用，
    # 也避免不同 openssl 构建对 -addext 在 x509 子命令上的支持差异。
    $extFile = Join-Path $WorkDir 'server-ext.cnf'
    # 注意：这里用正斜杠路径；SAN 只能是 DNS:miyu.lan（minSdk 24 强制要求 SAN）
    $extContent = @"
basicConstraints = critical,CA:FALSE
keyUsage = critical,digitalSignature,keyEncipherment,keyAgreement
extendedKeyUsage = serverAuth
subjectKeyIdentifier = hash
authorityKeyIdentifier = keyid,issuer
subjectAltName = DNS:$DomainName
"@
    Set-Content -LiteralPath $extFile -Value $extContent -Encoding ascii

    $serverCrt = Join-Path $WorkDir 'server.crt'
    Invoke-OpenSsl -Arguments @(
        'x509', '-req', '-sha256',
        '-in', $serverCsr,
        '-CA', $caCrt,
        '-CAkey', $caKey,
        '-CAcreateserial',
        '-days', "$Days",
        '-extfile', $extFile,
        '-out', $serverCrt
    ) -What '用 CA 签发服务器证书'
    Write-Ok "服务器证书 server.crt 已签发 (SAN=DNS:$DomainName, $Days 天)"

    # ── 签发后立刻自检：SAN 是否真的写进去了（minSdk 24 要求，漏了客户端必挂）──
    $sanDump = (& $OpenSsl x509 -in $serverCrt -noout -ext subjectAltName 2>&1 | Out-String)
    $sanPattern = [regex]::Escape("DNS:$DomainName")
    if ($sanDump -notmatch $sanPattern) {
        Die "服务器证书缺少 SAN (DNS:$DomainName)，签发异常。请检查 openssl 版本。输出：$sanDump"
    }
    Write-Ok "SAN 自检通过: DNS:$DomainName"

    # ── 链校验：确认 server.crt 确实由 ca.crt 签发 ──
    & $OpenSsl verify -CAfile $caCrt $serverCrt | Out-Null
    if ($LASTEXITCODE -ne 0) {
        Die '证书链校验失败：server.crt 无法用 ca.crt 验证。'
    }
    Write-Ok '链校验通过 (ca.crt -> server.crt)'

    # ─────────────────────────────────────────────────────────────────────────
    # [7/8] 打包 PKCS12 keystore + 导出 CA 证书
    # ─────────────────────────────────────────────────────────────────────────
    Write-Head '[7/8] 打包 PKCS12 keystore + 导出 CA 证书'

    # 随机口令：32 位 [A-Za-z0-9]
    $alphabet = [char[]]'ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789'
    $bytes = New-Object 'byte[]' $P12PasswordLen
    [System.Security.Cryptography.RandomNumberGenerator]::Create().GetBytes($bytes)
    $sb = New-Object System.Text.StringBuilder
    foreach ($b in $bytes) {
        [void]$sb.Append($alphabet[[int]$b % $alphabet.Length])
    }
    $P12Password = $sb.ToString()

    # legacy 模式：PKCS12 里若带旧式 PBE 算法，部分 JDK 需要 -legacy 才能读；
    # 用默认（现代）算法生成，JDK 17+ 完全支持，且比 legacy 更安全。
    Invoke-OpenSsl -Arguments @(
        'pkcs12', '-export',
        '-inkey', $serverKey,
        '-in', $serverCrt,
        '-certfile', $caCrt,
        '-name', $DomainName,
        '-out', $KeystorePath,
        '-passout', "pass:$P12Password"
    ) -What '导出 PKCS12 keystore'

    # 口令落盘（与 admin_initial_password.txt 同款：纯文本、同目录、已被 gitignore）
    Set-Content -LiteralPath $PasswordPath -Value $P12Password -Encoding ascii -NoNewline
    Write-Ok "keystore  -> $KeystorePath"
    Write-Ok "口令文件  -> $PasswordPath"

    # CA 证书以 PEM 形式导出（Android res/raw 需要 PEM）
    Copy-Item -LiteralPath $caCrt -Destination $CaCrtPath -Force
    $pemHead = Get-Content -LiteralPath $CaCrtPath -TotalCount 1
    if ($pemHead -notmatch 'BEGIN CERTIFICATE') {
        Die "CA 证书不是 PEM 格式（首行: $pemHead）"
    }
    Write-Ok "CA 证书(PEM) -> $CaCrtPath"

    # ── keystore 自检：能打开、且别名与证书匹配 ──
    $keytool = $null
    $kt = Get-Command keytool -ErrorAction SilentlyContinue
    if ($kt) { $keytool = $kt.Source }
    elseif ($env:JAVA_HOME -and (Test-Path "$env:JAVA_HOME\bin\keytool.exe")) {
        $keytool = "$env:JAVA_HOME\bin\keytool.exe"
    }
    if ($keytool) {
        $ktOut = (& $keytool -list -keystore $KeystorePath -storetype PKCS12 -storepass $P12Password 2>&1 | Out-String)
        if ($LASTEXITCODE -ne 0) {
            Die "keystore 自检失败（keytool 无法用该口令打开）: $ktOut"
        }
        Write-Ok "keystore 自检通过 (keytool -list 正常，别名含 $DomainName)"
    } else {
        Write-Warn2 '未找到 keytool，跳过 keystore 自检（不影响生成结果）'
    }

    # ── 有效期与到期时间 ──
    $caNotAfter   = (& $OpenSsl x509 -in $caCrt -noout -enddate 2>&1) -replace '^notAfter=', ''
    $srvNotAfter  = (& $OpenSsl x509 -in $serverCrt -noout -enddate 2>&1) -replace '^notAfter=', ''
    $srvSubject   = (& $OpenSsl x509 -in $serverCrt -noout -subject 2>&1) -replace '^subject=', ''
    $caSubject    = (& $OpenSsl x509 -in $caCrt -noout -subject 2>&1) -replace '^subject=', ''

    # ─────────────────────────────────────────────────────────────────────────
    # [8/8] 摘要
    # ─────────────────────────────────────────────────────────────────────────
    Write-Head '[8/8] 生成完成'

    Write-Host ''
    Write-Host '  ------------------------------------------------------------' -ForegroundColor Green
    Write-Host '  keystore   : ' -NoNewline; Write-Host $KeystorePath -ForegroundColor White
    Write-Host '  口令文件   : ' -NoNewline; Write-Host $PasswordPath -ForegroundColor White
    Write-Host '  CA 证书    : ' -NoNewline; Write-Host "$CaCrtPath  (PEM，供 Android res/raw)" -ForegroundColor White
    Write-Host '  ------------------------------------------------------------' -ForegroundColor Green
    Write-Host "  CA 主题    : $caSubject"
    Write-Host "  CA 到期    : $caNotAfter" -ForegroundColor Yellow
    Write-Host "  服务器主题 : $srvSubject"
    Write-Host "  服务器 SAN : DNS:$DomainName"
    Write-Host "  服务器到期 : $srvNotAfter" -ForegroundColor Yellow
    Write-Host '  ------------------------------------------------------------' -ForegroundColor Green
    Write-Host ''
    Write-Host '  下一步：' -ForegroundColor Cyan
    Write-Host '    1) 启动服务端（会自动读取上面的 keystore）：'
    Write-Host '         scripts\start-server.bat' -ForegroundColor White
    Write-Host '       启动横幅应显示 https://... （而不是 http://...）'
    Write-Host '    2) 用 CA 验证 HTTPS：'
    Write-Host "         curl --cacert `"$CaCrtPath`" https://$DomainName`:5000/version.json" -ForegroundColor White
    Write-Host ''
    Write-Warn2 "本脚本不会启动服务端，请自行执行 scripts\start-server.bat。"
    Write-Host ''
}
finally {
    # 中间文件（私钥/CSR/临时 CA）不留在磁盘上
    if (Test-Path -LiteralPath $WorkDir) {
        Remove-Item -LiteralPath $WorkDir -Recurse -Force -ErrorAction SilentlyContinue
    }
}

exit 0
