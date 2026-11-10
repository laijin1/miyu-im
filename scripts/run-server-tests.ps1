#requires -version 7
<#
.SYNOPSIS
  服务端单测运行器（绕开本机失效的 Gradle test worker）

.DESCRIPTION
  本机（JDK 24 + Gradle 8.14.3）的 Gradle test worker 在加载 sqlite-jdbc 原生库时会抛
  UnsatisfiedLinkError，导致 24/24 全挂；已用 git worktree 在 HEAD 2947332 的干净代码上
  复现同样结果，而同一 jar 在普通 JVM 里 sqlite 完全正常 —— 属环境问题，非仓库代码问题。

  本脚本用「普通 JVM + 反射执行 @Test 方法」的方式跑同一批测试类，得到可信的回归结论：
    1) 用 Gradle 导出 test runtime 完整 classpath（配置期任务，不触发 test worker）；
    2) 用 javac 编译 main + test 源码；
    3) 反射执行全部 @Test（基类 DbTestSupport 的 @BeforeEach 会重置临时库）。

  数据目录固定在 build\manual-test-data，绝不触碰 server\data\。

.EXAMPLE
  pwsh -File scripts\run-server-tests.ps1
#>
[CmdletBinding()]
param(
    [string]$ServerDir
)

$ErrorActionPreference = 'Stop'
$scriptDir = $PSScriptRoot
if (-not $ServerDir) { $ServerDir = Join-Path (Resolve-Path (Join-Path $scriptDir '..')).Path 'server' }
$ServerDir = (Resolve-Path $ServerDir).Path
$work      = Join-Path $ServerDir 'build\manual-test'
$dataDir   = Join-Path $ServerDir 'build\manual-test-data'

# 测试类在执行前由编译产物自动发现（见下方第 3 步），无需手工维护清单：
# 任何 src/test 下编译出来的 *Test.class（不含内部类）都会被跑到，
# 避免「加了新测试却忘了加进清单」这种静默漏测。

New-Item -ItemType Directory -Force -Path $work | Out-Null

# ---- 1) 导出完整 test runtime classpath ----
$dumpInit = Join-Path $work 'dumpcp.gradle'
@'
gradle.projectsEvaluated {
    def p = gradle.rootProject
    p.tasks.register('dumpTestCp') {
        doLast {
            def f = new File(p.projectDir, 'build/manual-test/testcp.txt')
            f.parentFile.mkdirs()
            f.text = p.sourceSets.test.runtimeClasspath.files.join('\n')
            println "DUMPED " + p.sourceSets.test.runtimeClasspath.files.size() + " entries"
        }
    }
}
'@ | Set-Content $dumpInit -Encoding utf8

Push-Location $ServerDir
try {
    $dump = & .\gradlew.bat --offline -I $dumpInit dumpTestCp --console=plain 2>&1
} finally { Pop-Location }
$cpFile = Join-Path $work 'testcp.txt'
if (-not (Test-Path $cpFile)) {
    Write-Host "[失败] 未能导出 test classpath：" -ForegroundColor Red
    $dump | Select-Object -Last 10 | ForEach-Object { Write-Host "   $_" -ForegroundColor DarkRed }
    exit 1
}
$cp = ((Get-Content $cpFile -Raw) -split "`r?`n" | Where-Object { $_ }) -join ';'
Write-Host ("[1/3] classpath 已导出（{0} 项）" -f (($cp -split ';').Count)) -ForegroundColor DarkGray

# ---- 2) 编译 main + test ----
$classes = Join-Path $work 'classes'
Remove-Item $classes -Recurse -Force -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Force -Path $classes | Out-Null
$srcs = @(Get-ChildItem (Join-Path $ServerDir 'src\main\java'), (Join-Path $ServerDir 'src\test\java') `
            -Recurse -Filter *.java | Select-Object -ExpandProperty FullName)
$javacOut = & javac -encoding UTF-8 -nowarn -d $classes -cp $cp @srcs 2>&1
if ($LASTEXITCODE -ne 0) {
    Write-Host "[失败] 编译未通过：" -ForegroundColor Red
    $javacOut | Select-Object -First 25 | ForEach-Object { Write-Host "   $_" -ForegroundColor DarkRed }
    exit 1
}
Write-Host ("[2/3] 编译通过（{0} 个源文件）" -f $srcs.Count) -ForegroundColor DarkGray

# ---- 2.5) 从编译产物自动发现测试类 ----
$testClasses = @()
$testSrcRoot = (Resolve-Path (Join-Path $ServerDir 'src\test\java')).Path
Get-ChildItem $testSrcRoot -Recurse -Filter '*Test.java' | ForEach-Object {
    $rel = $_.FullName.Substring($testSrcRoot.Length).TrimStart('\', '/')
    $testClasses += ($rel -replace '\\', '.' -replace '/', '.' -replace '\.java$', '')
}
$testClasses = $testClasses | Sort-Object -Unique
if ($testClasses.Count -eq 0) { Write-Host "[失败] 没有发现任何测试类" -ForegroundColor Red; exit 1 }
Write-Host ("[2.5/3] 发现 {0} 个测试类: {1}" -f $testClasses.Count, ($testClasses -join ', ')) -ForegroundColor DarkGray

# ---- 3) 反射执行 ----
$runnerSrc = Join-Path $scriptDir 'test-runner-source\RunTests.java'
& javac -encoding UTF-8 -nowarn -d $work -cp "$classes;$cp" $runnerSrc 2>&1 | Out-Null
if ($LASTEXITCODE -ne 0) { Write-Host "[失败] 测试运行器编译失败" -ForegroundColor Red; exit 1 }

Remove-Item $dataDir -Recurse -Force -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Force -Path $dataDir | Out-Null
Write-Host "[3/3] 运行测试（普通 JVM，绕开 Gradle test worker）..." -ForegroundColor DarkGray

$out = & java "-Dfamilychat.data.dir=$dataDir" -cp "$work;$classes;$cp" RunTests @testClasses 2>&1
$out | Select-String -Pattern '^==|\[PASS\]|\[FAIL\]|RESULT|FAILED:' | ForEach-Object { Write-Host $_.Line }
$failed = ($out | Select-String -Pattern '^RESULT.*failed=(\d+)').Matches.Groups[1].Value
if (-not $failed) { $failed = '1' }
Write-Host ""
if ($failed -eq '0') {
    Write-Host "结果: 全部通过" -ForegroundColor Green
    exit 0
} else {
    Write-Host "结果: 有 $failed 项失败" -ForegroundColor Red
    exit 1
}
