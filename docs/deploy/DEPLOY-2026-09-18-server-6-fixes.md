# 部署记录：服务端 6 项修复（2026-09-18 21:45）

> 本文记录 2026-09-18 晚对生产 :5000 的一次部署：把体检报告「服务端 6 项」修复上线。
> 对应提交：`a22cc7c` `5ee5897` `70029ce` `e4b1c67` `c368707` `983e1fb`（+ 文档 `162f4f5` `02e3da9`）。

## 1. 部署产物

| 项 | 值 |
|---|---|
| 运行 jar | `server-java/build/libs/familychat-server.jar` |
| jar 大小 | 22,119,011 字节 |
| jar SHA-256 | `08A35146D4AB05302533B435B29FD318401067BE619B3CAB47FC5430F21C690E` |
| 构建命令 | `server-java$ .\gradlew.bat --offline shadowJar`（BUILD SUCCESSFUL） |
| 进程 | PID 22356（启动于 2026-09-18 21:45:12），监听 `0.0.0.0:5000` |
| 启动父进程 | cmd PID 15680（detach，与运维 shell 生命周期无关） |

**启动命令（照此复现）**：

```
set ADMIN_USER_IDS=<uid>
java -Xmx1g -Dfile.encoding=UTF-8 -Dserver.port=5000 ^
     -Dfamilychat.data.dir=<本地路径> ^
     -jar <本地路径>
```

日志重定向到 `server-java/build/deploy-logs/server.deploy.out.log` / `.err.log`
（由 cmd 持有重定向，进程独立存活）。

> **⚠️ 运维注意（本次踩到）**：`start-server.bat` **不含** `ADMIN_USER_IDS`。
> 原生产实例能充值，是因为启动它的那个 cmd 窗口**手工导出过** `ADMIN_USER_IDS=<uid>`
> （本次直接从旧进程 9656 的环境块里读出确认）。
> **若日后用 `start-server.bat` 重启而没有先 `set ADMIN_USER_IDS=<uid>`，管理员会失去充值权限（403）。**
> 建议把该变量固化进 `start-server.bat` 或改用显式命令行启动。

## 2. 部署前检查（只读）

| 检查项 | 结果 |
|---|---|
| 生产 `jwt_secret.key` | 存在，44 字节，指纹 SHA-256 前16位 `2c60cf8267d550b2…` |
| `ADMIN_USER_IDS` | `<uid>`（从进程环境块读取，非推测） |
| 旧 jar | 22,110,796 字节，SHA-256 `8AB4F62A…`，mtime 20:31:13（早于本次全部提交） |
| 旧 jar 内容 | `javap` 确认含此前修复（`ADMIN_USER_IDS`、`MAX_SINGLE_CREDIT=10000`），**不含**本次 6 项 |
| `data/chat_files` | 111 个文件 / 149,690,024 字节（142.76 MB） |
| `data/video_cache` | 2 个文件 / 912.3 MB（1.mp4 440.8 + 2.mp4 471.5） |

## 3. 备份

| 文件 | 大小 | 用途 |
|---|---|---|
| `data/backup-before-deploy-20260918-214319.db` | 364,544 | 部署前快照 |
| `data/backup-before-cleanup-20260918-214824.db` | 364,544 | 清理校验残留前的快照 |
| `build/libs/familychat-server.pre-deploy-20260918.jar` | 22,110,796 | 旧 jar（回滚用） |

备份方式是 `sqlite3 .backup`（SQLite 在线备份 API），**不是裸文件拷贝**：
生产库 `journal_mode = wal`，裸拷贝可能漏掉尚未 checkpoint 的事务。
每份备份均通过 `PRAGMA integrity_check = ok`（31 张表、85 用户）。

## 4. 部署后验证（全部通过）

| # | 验证项 | 结果 |
|---|---|---|
| 1 | `GET /version.json` | HTTP 200，`versionCode=128`，`published=true` ✔ |
| 2 | 管理员（uid=<uid>）登录 + `GET /api/wallet` | 登录 200；钱包 200，余额 <金额> 分，`pay_set=1` ✔ |
| 3 | 普通账号 `POST /api/wallet/recharge` | HTTP **403** `{"error":"充值功能未开放"}` ✔ |
| 4 | `POST /api/wallet/charge` ×12 | 前 10 次 200；**第 11、12 次 429**，`Retry-After: 60` ✔ |
| 5 | 日志出现 `[CACHE]` video_cache audit 报告 | 见下 ✔ |
| 附 | 未登录 `GET /api/wallet` | HTTP 401 ✔ |
| 附 | JWT 密钥指纹 | `2c60cf8267d550b2…` 与部署前一致 → **未强制登出任何用户** ✔ |

生产真实数据上的两条 audit 报告（每 60s 由既有调度线程输出一次）：

```
[CACHE][audit-only] video_cache: files=2 total=956624986B (912.3MB) max_bytes=2147483648 max_files=200 would_reclaim=0 freed_bytes=0
[CACHE][report-only] chat_files 报告: files=111 total=149690024B (142.8MB) 全服上限=8589934592B 超期(>180天)=0 个 [只报告不删除]
```

含义：912.3 MB 低于 2 GiB 阈值 → `would_reclaim=0`，**不删任何缓存**；
`chat_files` 142.8 MB 对 12 GiB 上限占 1.2%，超期文件 0 个。即「有观测、无动作」，符合预期。

## 5. 验证产生的临时数据（已清理）

为验证第 4 项而注册了两个校验账号（`deploycheck_a/b`，uid 103/104）并产生 10 条
`charge_requests`。清理后：

- `users` 87 → **85**（回到部署前数量）
- `charge_requests` 15 → **5**（回到部署前数量）
- `wallets` 仍 16、余额总和仍 **<金额> 分**、`transactions` 仍 66 条（**资金数据完全未变**）
- `PRAGMA integrity_check = ok`，表数仍 31

## 6. 回滚方案（一般用不上：本次未改 schema）

1. 停新进程（**显式 PID**，不要用会误杀其它 java 的脚本）：
   `Stop-Process -Id 22356 -Force`
2. 需要时恢复数据库：
   `Copy-Item data\backup-before-deploy-20260918-214319.db data\family_chat.db -Force`
   （仅在数据受损时；本次部署不涉及表结构变更，正常无需恢复）
3. 用旧 jar 重启：
   `java -Xmx1g -Dfile.encoding=UTF-8 -Dserver.port=5000 -Dfamilychat.data.dir=<生产data> -jar build\libs\familychat-server.pre-deploy-20260918.jar`
   （**记得先 `set ADMIN_USER_IDS=<uid>`**）

## 7. 遗留开关与待办

- 第 4 项 `-Dvideo.cache.audit.only` 生产仍为默认 **true（只报告不删）**；
  当前占用 912.3 MB 远低于 2 GiB，即使打开真删也不会删任何文件。
- 第 3 项 `chat_files` 的 TTL 同样是**只报告不删**（决策 (a)）；配额与总量上限已生效。
- 未纳入本次：头像上传配额、明文 HTTP、`?token=` 走 URL、充值按钮 UI、商家角色校验。
- 服务端单测在 Gradle test worker 下仍不可用（根因见 `../audits/HEALTH-CHECK-2026-09-18.md` §7.8.2），
  以 `scripts/run-server-tests.ps1` 为准。