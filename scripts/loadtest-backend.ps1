#requires -version 7
<#
.SYNOPSIS
  密语服务端并发容量压测（隔离实例）

.DESCRIPTION
  在隔离端口 + 临时数据目录上另起一个服务端实例，用 .NET 异步 HTTP 客户端做定并发压测，
  测出逐级并发下的吞吐量、延迟分位（p50/p95/p99）与状态码分布，以及 WebSocket 连接承载能力。

  压测器会回报每个并发档位的**状态码分布**——这是刻意的：压测最容易犯的错误是
  "测了一个并没有真正做事的接口"（例如请求打到不存在的路由上拿到一堆 404，
  看起来吞吐很高，其实毫无意义）。看状态码才能确认压测打的是真实路径。
  脚本还会先做冒烟校验，三条路径不是 200 就直接中止。

  隔离保证与 e2e-backend-tests.ps1 相同：
    * 独立端口（默认 5399），与正式实例的 5000 不冲突，端口被占用则直接报错退出；
    * 独立数据目录（默认 build\loadtest-data），正式 data\ 目录不会被读写；
    * 使用 build\e2e-libs\ 下的 jar，避开正式实例对 build\libs 的文件锁。

.EXAMPLE
  pwsh -File scripts\loadtest-backend.ps1
.EXAMPLE
  pwsh -File scripts\loadtest-backend.ps1 -ReadLevels 1,50,200,400 -DurationSec 10 -WsLevels 100,500
#>
[CmdletBinding()]
param(
    [int]$Port = 5399,
    [string]$Jar,
    [string]$DataDir,
    [int[]]$ReadLevels  = @(1, 10, 50, 100, 200),
    [int[]]$WriteLevels = @(10, 50, 100),
    [int[]]$WsLevels    = @(100, 300),
    [int]$DurationSec   = 6,
    [int]$UserPool      = 40,
    [switch]$SkipBuild
)

$ErrorActionPreference = 'Stop'

$scriptDir = $PSScriptRoot
$miyuRoot  = (Resolve-Path (Join-Path $scriptDir '..')).Path
$serverDir = Join-Path $miyuRoot 'server'
if (-not $Jar)     { $Jar     = Join-Path $serverDir 'build\e2e-libs\familychat-server-e2e.jar' }
if (-not $DataDir) { $DataDir = Join-Path $serverDir 'build\loadtest-data' }

$script:Base = "http://127.0.0.1:$Port"
$script:Proc = $null
$logOut = Join-Path $serverDir 'build\loadtest-server.out.log'
$logErr = Join-Path $serverDir 'build\loadtest-server.err.log'

# ===== 载入压测器（C#，避免 PowerShell 自身成为瓶颈）=====

Add-Type -TypeDefinition @'
using System;
using System.Collections.Concurrent;
using System.Diagnostics;
using System.Net.Http;
using System.Net.Http.Headers;
using System.Net.WebSockets;
using System.Text;
using System.Threading;
using System.Threading.Tasks;

public class LoadStats {
    public long Total; public long Errors; public double Seconds;
    public double Throughput; public double Avg, P50, P90, P95, P99, Max;
    public string Statuses = "";
    public override string ToString() {
        return string.Format("total={0} err={1} rps={2:F0} p50={3:F0} p95={4:F0} statuses=[{5}]",
            Total, Errors, Throughput, P50, P95, Statuses);
    }
}

public static class LoadGen {
    static LoadStats Summarize(ConcurrentBag<double> lats, long total, long errors,
                               double secs, ConcurrentDictionary<int,int> statuses) {
        var arr = new double[lats.Count];
        int k = 0; double sum = 0;
        foreach (var v in lats) { arr[k++] = v; sum += v; }
        Array.Sort(arr);
        var s = new LoadStats();
        s.Total = total; s.Errors = errors; s.Seconds = secs;
        s.Throughput = secs > 0 ? total / secs : 0;
        s.Avg = arr.Length > 0 ? sum / arr.Length : 0;
        s.P50 = Pct(arr, 50); s.P90 = Pct(arr, 90); s.P95 = Pct(arr, 95); s.P99 = Pct(arr, 99);
        s.Max = arr.Length > 0 ? arr[arr.Length - 1] : 0;
        var sb = new StringBuilder();
        foreach (var kv in statuses) {
            if (sb.Length > 0) sb.Append(" ");
            sb.Append(kv.Key < 0 ? "EX" : kv.Key.ToString()).Append(":").Append(kv.Value);
        }
        s.Statuses = sb.ToString();
        return s;
    }
    static double Pct(double[] a, int p) {
        if (a.Length == 0) return 0;
        int idx = (int)Math.Ceiling(p / 100.0 * a.Length) - 1;
        if (idx < 0) idx = 0;
        if (idx >= a.Length) idx = a.Length - 1;
        return a[idx];
    }

    // 定并发闭环压测。isPost 必须显式传入：PowerShell 把 $null 绑定到 string 参数时
    // 会转成空字符串，所以"用 postBody==null 判断是不是 GET"是不可靠的。
    public static LoadStats Http(string url, string token, int concurrency, int seconds,
                                 string postBody, bool isPost) {
        var handler = new HttpClientHandler();
        handler.MaxConnectionsPerServer = 100000;
        var client = new HttpClient(handler);
        client.Timeout = TimeSpan.FromSeconds(60);
        if (token != null && token.Length > 0)
            client.DefaultRequestHeaders.Authorization = new AuthenticationHeaderValue("Bearer", token);

        var lats = new ConcurrentBag<double>();
        var statuses = new ConcurrentDictionary<int,int>();
        long total = 0, errors = 0;
        var sw = Stopwatch.StartNew();
        var tasks = new Task[concurrency];
        for (int i = 0; i < concurrency; i++) {
            tasks[i] = Task.Run(async () => {
                var lsw = new Stopwatch();
                while (sw.Elapsed.TotalSeconds < seconds) {
                    lsw.Restart();
                    try {
                        HttpResponseMessage resp;
                        if (isPost) {
                            var sc = new StringContent(postBody ?? "", Encoding.UTF8, "application/json");
                            resp = await client.PostAsync(url, sc).ConfigureAwait(false);
                        } else {
                            resp = await client.GetAsync(url).ConfigureAwait(false);
                        }
                        int code = (int)resp.StatusCode;
                        await resp.Content.ReadAsStringAsync().ConfigureAwait(false);
                        resp.Dispose();
                        statuses.AddOrUpdate(code, 1, (key, v) => v + 1);
                        if (code >= 400) Interlocked.Increment(ref errors);
                    } catch (Exception) {
                        statuses.AddOrUpdate(-1, 1, (key, v) => v + 1);
                        Interlocked.Increment(ref errors);
                    }
                    lsw.Stop();
                    lats.Add(lsw.Elapsed.TotalMilliseconds);
                    Interlocked.Increment(ref total);
                }
            });
        }
        Task.WaitAll(tasks);
        sw.Stop();
        return Summarize(lats, total, errors, sw.Elapsed.TotalSeconds, statuses);
    }

    // WebSocket 承载：并发建立 count 条连接，每条等待一条服务端消息，再保持 holdSeconds 秒
    public static string Ws(string url, int count, int holdSeconds) {
        var clients = new ClientWebSocket[count];
        int ok = 0, fail = 0;
        var sw = Stopwatch.StartNew();
        var tasks = new Task[count];
        for (int i = 0; i < count; i++) {
            int idx = i;
            tasks[i] = Task.Run(async () => {
                try {
                    var c = new ClientWebSocket();
                    using (var cts = new CancellationTokenSource(TimeSpan.FromSeconds(30))) {
                        await c.ConnectAsync(new Uri(url), cts.Token).ConfigureAwait(false);
                    }
                    clients[idx] = c;
                    var buf = new byte[8192];
                    using (var cts2 = new CancellationTokenSource(TimeSpan.FromSeconds(30))) {
                        var r = await c.ReceiveAsync(new ArraySegment<byte>(buf), cts2.Token).ConfigureAwait(false);
                        if (r.Count > 0) Interlocked.Increment(ref ok);
                        else Interlocked.Increment(ref fail);
                    }
                } catch (Exception) { Interlocked.Increment(ref fail); }
            });
        }
        Task.WaitAll(tasks);
        double connectSec = sw.Elapsed.TotalSeconds;
        Thread.Sleep(holdSeconds * 1000);
        int open = 0;
        for (int i = 0; i < count; i++)
            if (clients[i] != null && clients[i].State == WebSocketState.Open) open++;
        for (int i = 0; i < count; i++) {
            try {
                if (clients[i] != null && clients[i].State == WebSocketState.Open)
                    clients[i].CloseAsync(WebSocketCloseStatus.NormalClosure, "done", CancellationToken.None).Wait(3000);
            } catch { }
        }
        return string.Format("connected={0} failed={1} connectSec={2:F1} stillOpenAfter{3}s={4}",
            ok, fail, connectSec, holdSeconds, open);
    }
}
'@

# ===== 隔离环境准备 =====

Write-Host "============================================" -ForegroundColor Cyan
Write-Host "  密语 服务端并发容量压测（隔离实例）" -ForegroundColor Cyan
Write-Host "============================================" -ForegroundColor Cyan
Write-Host ""

$busy = & netstat -ano 2>$null | Select-String -Pattern ":$Port\s" | Select-String -Pattern 'LISTENING'
if ($busy) { throw "端口 $Port 已被占用，脚本不会去动别人的进程。请用 -Port 换一个空闲端口。" }
Write-Host "[环境] 端口 $Port 空闲；隔离数据目录: $DataDir" -ForegroundColor DarkGray
Write-Host "[环境] 正式数据目录（不会读写）: $(Join-Path $serverDir 'data')" -ForegroundColor DarkGray

if (-not (Test-Path $Jar)) {
    if ($SkipBuild) { throw "找不到 jar: $Jar" }
    Write-Host "[准备] 构建 jar..." -ForegroundColor Yellow
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
    try { & .\gradlew.bat -I 'build\e2e-shadowjar.gradle' shadowJar --console=plain 2>&1 | Select-Object -Last 5 | ForEach-Object { Write-Host "      $_" -ForegroundColor DarkGray } }
    finally { Pop-Location }
}

if (Test-Path $DataDir) { Remove-Item -Recurse -Force $DataDir }
New-Item -ItemType Directory -Force -Path $DataDir | Out-Null

Write-Host "[准备] 启动隔离实例..." -ForegroundColor Yellow
$script:Proc = Start-Process -FilePath 'java' -ArgumentList @(
    "-Dserver.port=$Port", "-Dfamilychat.data.dir=$DataDir", '-jar', $Jar
) -RedirectStandardOutput $logOut -RedirectStandardError $logErr -PassThru -WindowStyle Hidden

function Invoke-Api {
    param([string]$Method, [string]$Path, $Body, [string]$Token)
    $headers = @{}
    if ($Token) { $headers['Authorization'] = "Bearer $Token" }
    $req = @{ Method = $Method; Uri = "$script:Base$Path"; Headers = $headers; SkipHttpErrorCheck = $true; TimeoutSec = 30 }
    if ($null -ne $Body) {
        $req['Body'] = ($Body | ConvertTo-Json -Compress -Depth 6)
        $req['ContentType'] = 'application/json; charset=utf-8'
    }
    $resp = Invoke-WebRequest @req
    $json = $null
    if ($resp.Content) { try { $json = $resp.Content | ConvertFrom-Json } catch { $json = $null } }
    [pscustomobject]@{ Status = [int]$resp.StatusCode; Json = $json; Text = $resp.Content }
}

$summary = [System.Collections.Generic.List[object]]::new()

function Show-Levels {
    param([string]$Title, [string]$Url, [string]$Token, [int[]]$Levels, [string]$Body, [bool]$IsPost, [string]$Kind, [string]$Endpoint)
    Write-Host ""
    Write-Host "===== $Title =====" -ForegroundColor Cyan
    Write-Host ("  {0,-5} {1,-8} {2,-7} {3,-8} {4,-7} {5,-7} {6,-7} {7,-8} {8}" -f '并发','总请求','错误','吞吐/s','p50','p95','p99','max','状态码')
    foreach ($c in $Levels) {
        $s = [LoadGen]::Http($Url, $Token, $c, $DurationSec, $Body, $IsPost)
        Write-Host ("  {0,-5} {1,-8} {2,-7} {3,-8:F0} {4,-7:F0} {5,-7:F0} {6,-7:F0} {7,-8:F0} {8}" -f `
            $c, $s.Total, $s.Errors, $s.Throughput, $s.P50, $s.P95, $s.P99, $s.Max, $s.Statuses)
        $summary.Add([pscustomobject]@{
            Kind = $Kind; Endpoint = $Endpoint; Concurrency = $c
            Rps = [math]::Round($s.Throughput, 1); Errors = $s.Errors
            P50 = [math]::Round($s.P50); P95 = [math]::Round($s.P95); P99 = [math]::Round($s.P99)
            Statuses = $s.Statuses
        })
        Start-Sleep -Milliseconds 400
    }
}

try {
    # ---- 就绪等待 ----
    $deadline = (Get-Date).AddSeconds(90)
    $ready = $false
    while ((Get-Date) -lt $deadline -and -not $ready) {
        if ($script:Proc.HasExited) { throw "服务端意外退出，见 $logErr" }
        try {
            $r = Invoke-WebRequest -Uri "$script:Base/api/wallet" -TimeoutSec 3 -SkipHttpErrorCheck
            if ($r.StatusCode -eq 401) { $ready = $true }
        } catch { }
        if (-not $ready) { Start-Sleep -Milliseconds 500 }
    }
    if (-not $ready) { throw "服务端未就绪，见 $logOut" }
    Write-Host "[准备] 实例已就绪 (PID $($script:Proc.Id))" -ForegroundColor Green

    # ---- 用户池 ----
    Write-Host "[准备] 注册 $UserPool 个测试用户..." -ForegroundColor DarkGray
    $tokens = @(); $ids = @()
    for ($i = 1; $i -le $UserPool; $i++) {
        $r = Invoke-Api POST '/api/register' @{ username = "ltuser$i"; password = 'pass1234'; nickname = "lt$i" }
        if ($r.Status -ne 200) { throw "注册 ltuser$i 失败: $($r.Status) $($r.Text)" }
        $tokens += [string]$r.Json.token
        $ids    += [int]$r.Json.user.id
    }
    Write-Host "[准备] 用户池 $($tokens.Count) 个（token 长度 $($tokens[0].Length)）" -ForegroundColor DarkGray

    # ---- 建立好友关系：其余人都与 user1 互为好友，否则发消息会被 403 拦掉 ----
    Write-Host "[准备] 建立好友关系..." -ForegroundColor DarkGray
    for ($i = 1; $i -lt $tokens.Count; $i++) {
        $null = Invoke-Api POST '/api/friends/request' @{ friend_id = $ids[0] } -Token $tokens[$i]
    }
    $pending = @((Invoke-Api GET '/api/friends/requests' -Token $tokens[0]).Json.requests)
    foreach ($req in $pending) {
        $null = Invoke-Api POST "/api/friends/request/$([int]$req.friendship_id)/accept" -Token $tokens[0]
    }
    $friendCount = @((Invoke-Api GET '/api/friends' -Token $tokens[0]).Json.friends).Count
    Write-Host "[准备] user1 好友数 = $friendCount" -ForegroundColor DarkGray

    $hubId     = $ids[0]
    $sendTok   = $tokens[1]
    $pollUrl   = "$script:Base/api/messages/$hubId`?limit=50"
    $walletUrl = "$script:Base/api/wallet"
    $sendUrl   = "$script:Base/api/messages/send"
    $sendBody  = @{ receiver_id = $hubId; content = 'loadtest'; msg_type = 'text' } | ConvertTo-Json -Compress

    # ---- 冒烟校验：先确认真实路径返回 200，再开始压 ----
    Write-Host "[准备] 冒烟校验（确认压测打的是真实路径）..." -ForegroundColor DarkGray
    $smoke1 = [LoadGen]::Http($pollUrl,   $sendTok, 1, 2, $null,    $false)
    $smoke2 = [LoadGen]::Http($walletUrl, $sendTok, 1, 2, $null,    $false)
    $smoke3 = [LoadGen]::Http($sendUrl,   $sendTok, 1, 2, $sendBody, $true)
    Write-Host "      GET  /api/messages/{id} -> $smoke1" -ForegroundColor DarkGray
    Write-Host "      GET  /api/wallet        -> $smoke2" -ForegroundColor DarkGray
    Write-Host "      POST /api/messages/send -> $smoke3" -ForegroundColor DarkGray
    if ($smoke1.Statuses -notmatch '200:') { throw "冒烟失败：GET /api/messages/{id} 未返回 200（$($smoke1.Statuses)）——压测目标不对，已中止" }
    if ($smoke2.Statuses -notmatch '200:') { throw "冒烟失败：GET /api/wallet 未返回 200（$($smoke2.Statuses)）" }
    if ($smoke3.Statuses -notmatch '200:') { throw "冒烟失败：POST /api/messages/send 未返回 200（$($smoke3.Statuses)）" }
    Write-Host "      冒烟通过：三条路径均返回 200" -ForegroundColor Green

    Show-Levels -Title '1. 读负载：消息轮询（GET /api/messages/{id}?limit=50，客户端每几秒一次的主路径）' `
        -Url $pollUrl -Token $sendTok -Levels $ReadLevels -Body $null -IsPost $false -Kind '读' -Endpoint 'GET /api/messages/{id}'
    Show-Levels -Title '2. 读负载：钱包（GET /api/wallet）' `
        -Url $walletUrl -Token $sendTok -Levels @($ReadLevels | Where-Object { $_ -ge 50 }) -Body $null -IsPost $false -Kind '读' -Endpoint 'GET /api/wallet'
    Show-Levels -Title '3. 写负载：发消息（POST /api/messages/send，SQLite 单写者）' `
        -Url $sendUrl -Token $sendTok -Levels $WriteLevels -Body $sendBody -IsPost $true -Kind '写' -Endpoint 'POST /api/messages/send'

    Write-Host ""
    Write-Host "===== 4. WebSocket 连接承载（/ws?token=...）=====" -ForegroundColor Cyan
    foreach ($n in $WsLevels) {
        $tok = $tokens[($n + 3) % $tokens.Count]
        $line = [LoadGen]::Ws("ws://127.0.0.1:$Port/ws?token=$tok", $n, 5)
        Write-Host "  [$n 条] $line"
        $summary.Add([pscustomobject]@{ Kind='WS'; Endpoint='/ws'; Concurrency=$n; Rps=$null; Errors=$null; P50=$null; P95=$null; P99=$null; Statuses=$line })
        Start-Sleep -Seconds 1
    }

    Write-Host ""
    Write-Host "===== 汇总 =====" -ForegroundColor Cyan
    $summary | Format-Table -AutoSize | Out-String -Width 200 | Write-Host
}
finally {
    if ($script:Proc -and -not $script:Proc.HasExited) {
        Write-Host ""
        Write-Host "[清理] 停止隔离实例 (PID $($script:Proc.Id))..." -ForegroundColor Yellow
        Stop-Process -Id $script:Proc.Id -Force -ErrorAction SilentlyContinue
        Start-Sleep -Milliseconds 1500
    }
    if (Test-Path $DataDir) {
        Remove-Item -Recurse -Force $DataDir -ErrorAction SilentlyContinue
        Write-Host "[清理] 已删除隔离数据目录" -ForegroundColor DarkGray
    }
}

Write-Host ""
Write-Host "============================================" -ForegroundColor Cyan
Write-Host "  压测结束（服务端日志: $logOut）" -ForegroundColor Cyan
Write-Host "============================================" -ForegroundColor Cyan
