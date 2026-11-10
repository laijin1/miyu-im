# 密语客户端单元测试运行器（纯 JVM，不需要模拟器/真机）
#
# 【工程已迁到 ASCII 路径，本脚本现在是"增强版"而非"必需绕行"】
#   推荐直接用 Gradle 原生任务：  cd client\android; .\gradlew.bat testDebugUnitTest
#   本脚本相对原生任务多做的事：
#     * 逐模块汇总用例数，一眼看清哪个模块跑了几条；
#     * 检查关键用例是否还在（防止有人误删保护性测试）；
#     * 强制先重建主代码产物，避免"旧 classes.jar 抢占新代码"导致改了代码却测不出来。
#
# 历史背景（当时不得不用本脚本）：
#   工程原先在含中文的路径（<本地路径>）。Gradle 命令行长时把 classpath 写进 @argfile，
#   写的是 UTF-8；而 JDK 按 sun.jnu.encoding(本机 GBK) 读它 → 中文路径变乱码 →
#   worker 报 java.lang.ClassNotFoundException（编译其实是成功的）。
#   已实测：同一 argfile 用 GBK 写就能跑通。这是"路径"问题，不是代码问题。
#   路径改 ASCII 后 Gradle 原生任务已正常（实测 52 用例通过）。
#
# 用法：
#   pwsh -File scripts\run-unit-tests.ps1                # 跑全部模块
#   pwsh -File scripts\run-unit-tests.ps1 -Module app    # 只跑某模块
param(
    [string[]]$Module = @('core', 'app', 'feature-chat', 'feature-wallet', 'feature-mall', 'feature-group'),
    [switch]$SkipBuild
)

# 注意：不要用 'Stop'。Windows PowerShell 5.1 会把"原生命令往 stderr 写东西"
# 当成终止性错误（例如 Gradle 的 WARNING），从而在完全正常的情况下中断脚本。
# 下面用临时文件接收 gradlew 的输出，既能拿到全部文本，又不受该行为影响。
$ErrorActionPreference = 'Continue'
$scriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$gradleRoot = Join-Path $scriptDir '..\client\android'
$gradleRoot = (Resolve-Path $gradleRoot).Path
$env:ANDROID_HOME = if ($env:ANDROID_HOME) { $env:ANDROID_HOME } else { 'C:\android-sdk' }

# 硬性要求 PowerShell 7+。
# Windows PowerShell 5.1（.NET Framework）在本工程会踩一连串坑：参数传递、
# 原生命令 stderr 被当异常，以及最危险的——静默输出"0 个测试全部通过"
# （看起来成功，其实一个都没跑）。宁可明确拒绝，也不给假成功。
if ($PSVersionTable.PSVersion.Major -lt 7) {
    Write-Host "[错误] 本脚本需要 PowerShell 7+（当前 $($PSVersionTable.PSVersion)）" -ForegroundColor Red
    Write-Host "       Windows PowerShell 5.1 下会静默报告『0 个测试全部通过』，可能掩盖失败。" -ForegroundColor Red
    Write-Host "       请用 pwsh 运行：  pwsh -File scripts\run-unit-tests.ps1" -ForegroundColor Yellow
    exit 1
}

# 运行原生命令并把 stdout+stderr 一起取回。
# 刻意用 .NET Process 而不是 PowerShell 的 `*>` / `2>&1`：
#   - PS 5.1 会把原生命令的 stderr 当终止性错误；
#   - 重定向到文件在 PS 5.1 与 pwsh 下行为不一致（曾静默返回空，导致"0 个测试全部通过"）。
# 用 Process 能同时拿到完整输出与退出码，且两个版本行为一致。
function Invoke-NativeCapture {
    param(
        [string]$Exe,
        [string[]]$Arguments,
        [string]$WorkingDirectory
    )
    # PS 5.1 跑在 .NET Framework 上，ProcessStartInfo 没有 ArgumentList（只有 Arguments 字符串），
    # 因此手工拼接并按需加引号 —— classpath 含分号，引号必须正确。
    $quoted = foreach ($a in $Arguments) {
        if ($a -match '[\s;"]') { '"' + ($a -replace '"', '\"') + '"' } else { $a }
    }
    $argLine = ($quoted -join ' ')

    $psi = New-Object System.Diagnostics.ProcessStartInfo
    # UseShellExecute=false 时 FileName 必须是可直接执行的文件；
    # .bat 要经 cmd.exe 解析，且相对路径在这里不可靠，统一转成绝对路径。
    if ($Exe -match '\.(bat|cmd)$') {
        $exePath = Join-Path $WorkingDirectory $Exe
        if (-not (Test-Path $exePath)) { $exePath = $Exe }
        $psi.FileName = $env:ComSpec
        $psi.Arguments = '/c "' + $exePath + '" ' + $argLine
    } else {
        $psi.FileName = $Exe
        $psi.Arguments = $argLine
    }
    $psi.WorkingDirectory = $WorkingDirectory
    $psi.UseShellExecute = $false
    $psi.RedirectStandardOutput = $true
    $psi.RedirectStandardError = $true
    $psi.CreateNoWindow = $true
    $psi.StandardOutputEncoding = [System.Text.Encoding]::UTF8
    $psi.StandardErrorEncoding = [System.Text.Encoding]::UTF8

    $proc = [System.Diagnostics.Process]::Start($psi)
    $stdout = $proc.StandardOutput.ReadToEnd()
    $stderr = $proc.StandardError.ReadToEnd()
    $proc.WaitForExit()
    return @{
        Output   = ($stdout + "`n" + $stderr)
        ExitCode = $proc.ExitCode
    }
}

Write-Host "============================================" -ForegroundColor Cyan
Write-Host "  密语 单元测试（JVM，无需模拟器）" -ForegroundColor Cyan
Write-Host "============================================" -ForegroundColor Cyan
Write-Host ""

Push-Location $gradleRoot
try {
    if (-not $SkipBuild) {
        Write-Host "[1/2] 编译主代码与测试代码..." -ForegroundColor Yellow
        # 用 bundleLibRuntimeToJarDebug 而不是 assembleDebug：
        #   1) 测试只需要 classpath 上的类，不需要产出 APK，更快；
        #   2) :app:assembleDebug 会触发 validateSigningDebug，
        #      在未配置调试签名时直接失败，与"跑测试"这件事无关。
        $tasks = @()
        foreach ($m in $Module) {
            # application 模块（:app）没有 bundleLibRuntimeToJarDebug，用它的主类打包任务；
            # 库模块用它自己的打包任务（更快，且不产出 APK）。
            # 注意不要用 assembleDebug：它会依赖 validateSigningDebug（需写调试密钥库），
            # 而跑单测不需要签名，依赖它会平白引入失败点。
            if ($m -eq 'app') {
                $tasks += ":$m`:bundleDebugClassesToRuntimeJar"
            } else {
                $tasks += ":$m`:bundleLibRuntimeToJarDebug"
            }
            $tasks += ":$m`:compileDebugUnitTestJavaWithJavac"
        }
        # 注意：不要用 -q。静默模式下 javac 的编译错误会被吞掉，
        # 结果就是"编译失败但仍在跑上一次的旧测试类"，看起来像测试通过。
        $buildRes = Invoke-NativeCapture -Exe '.\gradlew.bat' -Arguments (@($tasks) + @('--console=plain')) -WorkingDirectory $gradleRoot
        $buildText = $buildRes.Output
        if ($buildRes.ExitCode -ne 0 -or $buildText -match '(?m)^\s*error:|What went wrong|BUILD FAILED') {
            Write-Host "[失败] 编译未通过，测试中止：" -ForegroundColor Red
            ($buildText -split "`r?`n") | Select-String -Pattern 'error:|What went wrong|Execution failed|\.java:' |
                Select-Object -First 25 | ForEach-Object { Write-Host "      $($_.Line.Trim())" -ForegroundColor Red }
            exit 1
        }
        Write-Host "      编译通过" -ForegroundColor Green
    } else {
        Write-Host "[1/2] 跳过编译 (-SkipBuild)" -ForegroundColor Yellow
    }

    Write-Host "[2/2] 运行测试..." -ForegroundColor Yellow
    Write-Host ""

    # 用 Gradle 的 init 脚本导出真实 classpath（避免手写遗漏依赖）
    $dumpTasks = @()
    foreach ($m in $Module) { $dumpTasks += ":$m`:dumpTestClasspath" }
    $dumpRes = Invoke-NativeCapture -Exe '.\gradlew.bat' -Arguments (@('-I', 'dump-classpath.gradle') + $dumpTasks + @('--console=plain', '-q')) -WorkingDirectory $gradleRoot
    $dumpText = $dumpRes.Output
    if ($dumpRes.ExitCode -ne 0 -or $dumpText -match 'What went wrong|BUILD FAILED') {
        Write-Host "[失败] 导出 classpath 失败（后续会用到旧文件，故直接中止）" -ForegroundColor Red
        ($dumpText -split "`r?`n") | Select-String -Pattern 'What went wrong|Execution failed|error:' |
            Select-Object -First 10 | ForEach-Object { Write-Host "      $($_.Line.Trim())" -ForegroundColor Red }
        exit 1
    }

    $totalTests = 0
    $totalFailures = 0
    $failedModules = @()

    foreach ($m in $Module) {
        $cpFile = Join-Path $gradleRoot "build\testcp-$m.txt"
        if (-not (Test-Path $cpFile)) {
            Write-Host "  [$m] 跳过（没有测试 classpath，可能该模块没有单元测试）" -ForegroundColor DarkGray
            continue
        }

        $lines = Get-Content $cpFile | Where-Object { $_ -ne '' }
        $sep = [Array]::IndexOf($lines, '---CP---')
        if ($sep -lt 0) { Write-Host "  [$m] classpath 文件格式异常" -ForegroundColor Red; continue }

        $testDirs = $lines[0..($sep - 1)]
        $cp = $lines[($sep + 1)..($lines.Count - 1)] -join ';'

        # 找出测试类（从 testClassesDirs 里的 .class 文件反推全限定名）
        $testClasses = @()
        foreach ($dir in $testDirs) {
            if (-not (Test-Path $dir)) { continue }
            Get-ChildItem -Recurse -Path $dir -Filter '*Test.class' -ErrorAction SilentlyContinue |
                Where-Object { $_.Name -notmatch '\$' } | ForEach-Object {
                    $rel = $_.FullName.Substring($dir.Length).TrimStart('\')
                    $testClasses += ($rel -replace '\\', '.' -replace '\.class$', '')
                }
        }

        if ($testClasses.Count -eq 0) {
            Write-Host "  [$m] 没有测试类" -ForegroundColor DarkGray
            continue
        }

        Write-Host "  [$m] $($testClasses.Count) 个测试类: $($testClasses -join ', ')" -ForegroundColor White
        # 同样走临时文件捕获：JUnit 的失败堆栈走 stderr，
        # 在 PS 5.1 下直接用 2>&1 会被当成终止性错误。
        $junitRes = Invoke-NativeCapture -Exe 'java' -Arguments (@('-cp', $cp, 'org.junit.runner.JUnitCore') + $testClasses) -WorkingDirectory $gradleRoot
        $text = $junitRes.Output

        # 解析 JUnit 输出
        if ($text -match 'OK \((\d+) tests?\)') {
            $n = [int]$Matches[1]
            $totalTests += $n
            Write-Host "       OK - $n 个测试全部通过" -ForegroundColor Green
        } elseif ($text -match 'Tests run: (\d+),\s+Failures: (\d+)') {
            $n = [int]$Matches[1]; $f = [int]$Matches[2]
            $totalTests += $n; $totalFailures += $f
            $failedModules += $m
            Write-Host "       FAILED - 运行 $n, 失败 $f" -ForegroundColor Red
            Write-Host $text
        } else {
            Write-Host "       无法解析测试输出：" -ForegroundColor Red
            Write-Host $text
            $failedModules += $m
        }
    }

    Write-Host ""
    Write-Host "============================================" -ForegroundColor Cyan
    if ($totalFailures -gt 0 -or $failedModules.Count -gt 0) {
        Write-Host "  结果: 运行 $totalTests 个测试，失败/异常模块: $($failedModules -join ', ')" -ForegroundColor Red
        Write-Host "============================================" -ForegroundColor Cyan
        exit 1
    } else {
        Write-Host "  结果: 运行 $totalTests 个测试，全部通过" -ForegroundColor Green
        Write-Host "============================================" -ForegroundColor Cyan
        exit 0
    }
} finally {
    Pop-Location
}
