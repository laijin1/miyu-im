# 发布前体检脚本自身的守卫（防止它们因编码/文案问题悄悄失效）
#
# 背景：这个项目在同一个坑上摔过三次，值得写成断言而不是靠记忆——
#   1) .bat 里出现 UTF-8 中文 → cmd 按 GBK 解析，静默吞行/报"不是内部或外部命令"；
#   2) .ps1 没有 BOM → Windows PowerShell 5.1 按 ANSI 解析，中文变语法错误；
#   3) .ps1 里用 PowerShell 5.1 容易被当成"原生命令 stderr = 终止错误"或静默空输出。
#
# 这里只做纯文本检查，不依赖 Android SDK / Gradle，可随时单独运行：
#   pwsh -File scripts\test-quality-guards.ps1
$ErrorActionPreference = 'Stop'

$scriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$repoRoot = (Resolve-Path (Join-Path $scriptDir '..')).Path

$fail = 0
$warn = 0

function Fail($msg) {
    Write-Host "  [FAIL] $msg" -ForegroundColor Red
    $script:fail++
}
function Warn($msg) {
    Write-Host "  [WARN] $msg" -ForegroundColor Yellow
    $script:warn++
}
function Ok($msg) {
    Write-Host "  [OK] $msg" -ForegroundColor Green
}

Write-Host "===== 流水线脚本编码守卫 =====" -ForegroundColor Cyan

# --- 1. .bat 含中文时必须带 UTF-8 BOM ---
# 关键区别（本工程踩过的坑）：
#   chcp 65001 管不了"批处理文件本身的解析"——cmd 在 chcp 生效前就按当前 ANSI(GBK) 读文件，
#   而某些 GBK 双字节的次字节是 0x85（续行符），会静默吞掉下一行。
#   实测：UTF-8 + BOM 的 .bat，cmd 能正确解析并正常显示中文；纯 ASCII 当然也安全。
#   最危险的是"UTF-8 无 BOM + 中文"：本地常常看起来没事，换机器/换 Windows 版本就炸。
$bats = Get-ChildItem -Recurse -Path $repoRoot -Filter *.bat -ErrorAction SilentlyContinue |
    Where-Object { $_.FullName -notmatch '\\build\\|\\\.gradle\\' }
foreach ($f in $bats) {
    $bytes = [System.IO.File]::ReadAllBytes($f.FullName)
    $nonAscii = ($bytes | Where-Object { $_ -gt 127 }).Count
    $hasBom = $bytes.Length -ge 3 -and $bytes[0] -eq 0xEF -and $bytes[1] -eq 0xBB -and $bytes[2] -eq 0xBF
    if ($nonAscii -eq 0) {
        Ok "$($f.Name) 纯 ASCII"
    } elseif ($hasBom) {
        Ok "$($f.Name) 带 UTF-8 BOM（编码齐全；但含中文仍有解析风险，见下方 WARN）"
    } else {
        Fail "$($f.Name) 含 $nonAscii 个非 ASCII 字节但缺 UTF-8 BOM：cmd 按 GBK 解析 .bat 可能静默吞行"
    }

    # --- 1b. .bat 的行尾符必须是 CRLF（2026-09-12 踩过）---
    # 症状：脚本能启动，但报一堆莫名其妙的错，像是半行被当成命令执行：
    #     '了服务，但跑的还是旧' is not recognized as an internal or external command
    #     'ATADIR!"' is not recognized ...
    # 原因：文件是纯 LF 换行。cmd 按 CRLF 切行，纯 LF 会把行切错位。
    # 最容易中招的场景：用非 Windows 工具（或 AI 的写文件工具）生成/改写了 .bat。
    # 注意 BOM 检查拦不住这个坑——BOM 正常、CRLF 缺失，一样炸。
    $lfOnly = 0
    for ($i = 0; $i -lt $bytes.Length; $i++) {
        if ($bytes[$i] -eq 0x0A -and ($i -eq 0 -or $bytes[$i - 1] -ne 0x0D)) { $lfOnly++ }
    }
    if ($lfOnly -gt 0) {
        Fail "$($f.Name) 有 $lfOnly 处纯 LF 换行：.bat 必须是 CRLF，否则 cmd 会把行切碎"
    }

    # --- 1c. 非 ASCII 的 .bat 只是"碰巧能用"，不是"安全"（2026-09-12 更正旧结论）---
    # 旧结论"UTF-8 + BOM 就能正确解析中文"是不完整的，实测被推翻：
    #   cmd 用【当前控制台代码页】解码 .bat。控制台若是 GBK/936（双击、新开窗口即是），
    #   UTF-8 多字节会让 cmd 的行偏移逐行漂移，注释行被从中间截断后当命令执行：
    #       D:\>@echo off
    #       '@echo' is not recognized as an internal or external command
    #       D:\>.bat   ...注释行的碎片被当成命令
    #   而 BOM 不但救不了，还会让第一行 @echo off 失效（echo 关不掉，错误更多）。
    #   实测：同一份文件从 PowerShell(UTF-8) 跑完全正常，从新控制台(GBK) 跑报一堆碎片错误。
    #   → 结论：.bat 里出现中文本身就是隐患。能改纯 ASCII 就改。
    if ($nonAscii -gt 0) {
        Warn "$($f.Name) 含 $nonAscii 个非 ASCII 字节：在新开的 cmd 窗口(GBK)下可能逐行错位，建议改为纯 ASCII"
    }
}

# --- 2. 我方可维护的 .ps1 必须带 UTF-8 BOM（含中文文案）---
$ps1s = Get-ChildItem -Recurse -Path $repoRoot -Filter *.ps1 -ErrorAction SilentlyContinue |
    Where-Object { $_.FullName -notmatch '\\build\\|\\\.gradle\\' }
foreach ($f in $ps1s) {
    $bytes = [System.IO.File]::ReadAllBytes($f.FullName)
    $hasBom = $bytes.Length -ge 3 -and $bytes[0] -eq 0xEF -and $bytes[1] -eq 0xBB -and $bytes[2] -eq 0xBF
    $hasNonAscii = ($bytes | Where-Object { $_ -gt 127 }).Count -gt 0
    if ($hasNonAscii -and -not $hasBom) {
        Fail "$($f.Name) 含非 ASCII 但缺 UTF-8 BOM：Windows PowerShell 5.1 会把它当 ANSI 解析"
    } else {
        Ok "$($f.Name) 编码合规$(if (-not $hasNonAscii) { '（纯 ASCII）' } else { '（UTF-8 + BOM）' })"
    }
}

# --- 3. 单元测试运行器必须拒绝 PowerShell 5.1 ---
$runner = Join-Path $scriptDir 'run-unit-tests.ps1'
if (Test-Path $runner) {
    $text = [System.IO.File]::ReadAllText($runner)
    if ($text -match 'PSVersionTable\.PSVersion\.Major\s*-lt\s*7') {
        Ok "run-unit-tests.ps1 含 PowerShell 7 版本守卫"
    } else {
        Fail "run-unit-tests.ps1 缺少 PowerShell 版本守卫：5.1 下会静默报『0 个测试全部通过』"
    }
} else {
    Fail "找不到 run-unit-tests.ps1"
}

# --- 4. 客户端测试必须存在且数量不为零（防止测试被误删/改名后无人察觉）---
$testRoots = @(
    'client\android\core\src\test',
    'client\android\app\src\test',
    'client\android\feature-chat\src\test'
)
$testFiles = @()
foreach ($r in $testRoots) {
    $p = Join-Path $repoRoot $r
    if (Test-Path $p) {
        $testFiles += Get-ChildItem -Recurse -Path $p -Filter '*Test.java' -ErrorAction SilentlyContinue
    }
}
if ($testFiles.Count -ge 3) {
    Ok "客户端测试类 $($testFiles.Count) 个（$((($testFiles | ForEach-Object { $_.BaseName }) | Sort-Object) -join ', ')）"
} else {
    Fail "客户端测试类只有 $($testFiles.Count) 个，预期至少 3 个（加解密/序列化契约/协议逻辑）"
}

# --- 5. 关键安全断言必须在测试里（防止有人删掉保护性用例）---
$assertions = @{
    'app\src\test\java\com\familychat\app\api\GsonContractTest.java' = 'allGsonFieldsMustDeclareSerializedName'
    'app\src\test\java\com\familychat\app\utils\KeyBackupTest.java'   = 'v1LegacyBackup_stillDecryptable'
    'feature-chat\src\test\java\com\familychat\app\utils\MessageEncryptorTest.java' = 'tamperedCiphertext_isRejected'
}
foreach ($rel in $assertions.Keys) {
    $path = Join-Path (Join-Path $repoRoot 'client\android') $rel
    if (-not (Test-Path $path)) {
        Fail "缺少关键测试文件 $rel"
        continue
    }
    $content = [System.IO.File]::ReadAllText($path)
    if ($content -match [regex]::Escape($assertions[$rel])) {
        Ok "$([System.IO.Path]::GetFileName($rel)) 含关键用例 $($assertions[$rel])"
    } else {
        Fail "$([System.IO.Path]::GetFileName($rel)) 缺少关键用例 $($assertions[$rel])"
    }
}

Write-Host ""
if ($fail -gt 0) {
    Write-Host "===== 编码守卫: $fail 项未通过$(if ($warn -gt 0) { ", $warn 项警告" }) =====" -ForegroundColor Red
    exit 1
}
if ($warn -gt 0) {
    Write-Host "===== 编码守卫: 通过（$warn 项警告，见上）=====" -ForegroundColor Yellow
    exit 0
}
Write-Host "===== 编码守卫: 全部通过 =====" -ForegroundColor Green
exit 0
