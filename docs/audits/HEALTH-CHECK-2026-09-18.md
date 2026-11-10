# 密语 (miyu-app) 项目体检报告

**对象**：`<本地路径>` — Android 客户端（6 模块）+ Java 服务端
**时间**：2026-09-18 18:32（HEAD `50d8a8d` + 未提交工作树）
**方式**：**实跑**（clean 构建、单测、发布门禁、APK 校验、活服务探测）+ 并行静态复核
**与既有报告的关系**：本报告是对 `<本地路径>`（2026-09-18，基于 `052c9a0`）的**实测复核**，逐条给出「已修 / 未修」，并补充本次新发现。

---

## 0. 总体结论

| 维度 | 结论 |
|---|---|
| 编译 | ✅ 服务端、客户端**从零 clean 构建全部成功** |
| 单元测试 | ✅ **102 个用例全绿**（客户端 83 + 服务端 19），0 失败 0 错误 |
| 发布产物 | ✅ `release/密语.apk`（v1.0.128）与当前源码**逐字节一致**（dex/manifest/资源哈希全同），正式证书签名 |
| 线上服务 | ✅ 正在运行并对外提供 v1.0.128，`published:true`，SHA-256 与所发 APK 相符 |
| **发布门禁** | ❌ **`publish-check.bat` 本次运行 5 通过 / 2 失败 → 按项目自身规则「禁止发布」** |
| 仓库卫生 | ⚠️ `git add -A` 会**误入库 98.3 MB 的 APK 备份**（.gitignore 有缺口） |
| 安全（P0） | ❌ 4 项 Critical 中**仍有 3 项未修**（造钱、JWT 密钥静默轮换、E2EE 无公钥认证） |

**一句话**：**工程质量依旧扎实（构建/测试/产物可复现、加密原语正确），但「能发布」和「安全」这两件事目前都不成立**——门禁自己就没过，且三个 Critical 安全项原封未动。另有本次新发现的 4 个高价值问题（见 §7）。

---

## 1. 构建与测试（实跑）

### 1.1 服务端

| 项 | 结果 |
|---|---|
| 命令 | `gradlew clean test shadowJar` |
| 结果 | **BUILD SUCCESSFUL**，1m 01s，6 tasks（5 executed） |
| 单测 | **19 个，0 失败 0 错误 0 跳过** |
| 产物 | `familychat-server.jar` 22,108,806 B |

用例分布：`WalletTest` 6、`MallOrderTest` 8、`AuthTest` 3、`GroupTest` 2。

> ⚠️ **本次 clean 构建是在隔离副本 `<本地路径>` 中执行的**。
> 原因：**线上服务正在运行（PID 12832，监听 :5000），锁住了
> `server-java\build\libs\familychat-server.jar`，在原目录 `clean` 必然失败**
> （实测报 `Unable to delete directory ... familychat-server.jar`）。
> 我没有停掉线上服务，而是复制源码另行构建——这样既验证了「从零可构建」，
> 又不影响正在使用的人。**未修改任何源文件。**

### 1.2 客户端

| 项 | 结果 |
|---|---|
| 命令 | `gradlew clean testDebugUnitTest assembleRelease` |
| 结果 | **BUILD SUCCESSFUL**，3m 18s，393 tasks（362 executed） |
| 单测 | **83 个，0 失败 0 错误 0 跳过** |

用例分布：

| 测试类 | 用例数 |
|---|---|
| `KeyTransferPayloadTest`（本次新增，未提交） | 31 |
| `GsonContractTest` | 15 |
| `MessageEncryptorTest` | 14 |
| `KeyBackupTest` | 13 |
| `ServerProtocolTest` | 10 |

> 📌 **`README.md:123` 写「当前覆盖 52 个用例」，实际已是 83 个**——文档落后于代码。
> （多出的主要是本次新增的 `KeyTransferPayloadTest` 31 条。）

### 1.3 工具链

只用 JDK 24（`JAVA_HOME=<本地路径>`），而 `README.md:65` 要求 **JDK 17 或 21**。
实测两者都构建成功，JDK 24 的坑（README 提到）本次未复现。无 CI 配置（`publish-check.bat` 是唯一门禁）。

---

## 2. 发布门禁实测：**不通过** ❌

```
APK: release\miyu.apk
[1/7] 签名证书 ................ [OK] 非调试证书
[2/7] android:debuggable ...... [OK]
[3/7] 敏感权限 ................ [OK]
[4/7] Gson 字段名 ............. [OK] 12 个关键字段名保留
[5/7] 版本一致性 .............. [FAIL] APK versionCode VC)      ← 见 §7.1
[6/7] 客户端单测 .............. [OK] 83 个测试全部通过
[7/7] 流水线编码守卫 .......... [FAIL] e2e-key-transfer.ps1 缺 UTF-8 BOM   ← 见 §7.4

Result: 5 passed, 2 failed
[X] DO NOT PUBLISH.
```

**门禁机制本身是好的**——它真的拦住了问题。但它拦住的第 [5/7] 项是**它自己选错了文件**（见 §7.1）。

---

## 3. 线上服务与产物一致性（实跑）

| 项 | 实测值 |
|---|---|
| 监听 | `0.0.0.0:5000`，java PID 12832，启动于 14:53:12 |
| `/version.json` | `versionCode=128`，`versionName=1.0.128`，`published=true` |
| 声明 SHA-256 | `b7cfba2a…95310` |
| `release/密语.apk` 实测 SHA-256 | `b7cfba2a…95310` ✅ **相符** |
| 运行中 jar | 构建于 2026-09-12 14:19，服务端源码最后改动 2026-08-17 → **运行版本即最新源码，无部署漂移** |

**产物可复现性验证**（本次新做）：把 fresh `assembleRelease` 的 APK 与已发布的 `密语.apk` 逐 entry 比对：

| Entry | 新构建 SHA-256 | 已发布 SHA-256 | 一致 |
|---|---|---|---|
| `classes.dex` | `579A9BA1…` | `579A9BA1…` | ✅ |
| `classes2.dex` | `2E1C41F0…` | `2E1C41F0…` | ✅ |
| `classes3.dex` | `DF63E9D4…` | `DF63E9D4…` | ✅ |
| `AndroidManifest.xml` | `0A374EBA…` | `0A374EBA…` | ✅ |
| `resources.arsc` | `1A4F05B2…` | `1A4F05B2…` | ✅ |

整包 SHA-256 不同（`0B164A97…` vs `B7CFBA2A…`）**仅因签名块**（v2/v3 签名含随机数），**代码内容完全相同**。
→ 结论：**已发布的 1.0.128 确实由当前工作树构建，未夹带其他改动。** 这是本次体检最重要的正面结论之一。

---

## 4. 仓库卫生

| 项 | 状态 |
|---|---|
| 版本控制 | `liaotian` 有 `.git`，HEAD `50d8a8d`（2026-09-18 16:15）；同级 `WechatRecall` **仍无 `.git`** |
| 仓库体积 | pack 938.76 KiB / 1373 objects —— 依然干净 |
| 跟踪的密钥/大文件 | **0 个** ✅（`*.jks`、`keystore.properties`、`*.apk`、`*.db`、`data/` 均正确忽略） |
| 未提交改动 | 6 个已修改 + 19 个未跟踪（审计时是 25 + 29，**已大幅改善**） |
| 测试树入库 | ✅ 已入库（审计 §4.2 的「一条 `git clean -fd` 就摧毁测试」风险**已解除**） |
| **`git add -A` 风险** | ❌ **会暂存 98.3 MB 的 APK 备份**（见 §7.3） |

---

## 5. 安全审计复核：`OPTIMIZATION-REPORT.md` 逐条状态

判定口径：**实跑**=本次执行验证；**静态**=读代码核对（含 `file:line` 证据）。

### 5.1 P0 — Critical（4 项）

| # | 问题 | 状态 | 证据 |
|---|---|---|---|
| 1.1 | 任何登录用户可无限造钱（`/api/wallet/recharge`） | ❌ **未修**（静态） | `WalletHandler.java:22` 仍注册；`:109` 仅 `requireAuth`；`:121` 仅校验正数；`:126` 自己给自己充值；`WalletDao.java:180` `UPDATE wallets SET balance = balance + ?`。全服务端无任何 admin/角色机制（`ADMIN_USER_IDS`、`requireAdmin` 零命中） |
| 1.2 | 丢失 `jwt_secret.key` 静默轮换签名密钥 | ❌ **未修**（静态） | `ServerConfig.java:52-57,81-98`：DB 存在时**只打印更大的警告**，随后照常生成+写入+返回新密钥；无 `JWT_SECRET_ROTATE` 闸门。新增子情形：`family_chat.db` 为 0 字节时 `dbExists` 被判为 false（`:55-56`），轮换完全无声 |
| 1.3 | E2EE 无公钥认证 → 完全中间人 | ❌ **未修**（静态） | 4 个证据点全部复现；`PublicKeyHandler.java:62-68` 任意登录者可取任意人公钥；`UserDao.java:197-198` `INSERT OR REPLACE` 无密钥历史；`ChatActivity.java:1235-1237` 每次发送强制拉取对端「当前」公钥；`:1272-1275` 解密失败无条件覆盖缓存。**对端密钥的 TOFU/pinning 全项目 0 处**（新功能只做**本机**指纹） |
| 1.4 | 全局 401 监听器被覆盖 | ✅ **已修**（实跑+静态） | `FamilyChatApp.java:121` 在 Application 级注册一次，配 `AuthFailureGuard.tryBegin()` 去重（`:124,136,142`）；**`clearAuthFailureListener` 已从全项目删除**（0 命中），`ApiClient.java:52` 是唯一 setter；`CallActivity` 的两处调用已移除。401 分支加了 `notifyAuthFailure` 开关（`ApiClient.java:152`），登录/注册不再误触发。提交 `50d8a8d` |

> 残留（非原缺陷）：`ApiClient.setAuthFailureListener` 仍是**无保护的 `public static`**，
> 「只许 Application 注册一次」目前**只靠注释约束、无测试**——将来某个 Activity 仍可静默**覆盖**（已不能清除）。

### 5.2 P1 — High（6 项）

| # | 问题 | 状态 | 证据 |
|---|---|---|---|
| 2.1 | 无连接池，每连接重放 7 条 PRAGMA | ❌ **未修**（静态） | `Database.java:58-70` 仍是裸 `DriverManager` + 7 条 PRAGMA（含 `cache_size=-4096`）。`build.gradle` 无 HikariCP。`getConn()` 出现 147 处（审计说 ~180，属高估） |
| 2.2 | 线程爆炸 + 多套 OkHttpClient | ⚠️ **大体未修，1 项部分修复**（实跑计数） | 实测 `new Thread(` **172 处/47 文件**（审计说 169/50，略有出入；**未提交的 4 个 Key\* Activity 又新增了 6 处** → 166→172）。`new OkHttpClient` **10 处/9 文件**，**全部从不 shutdown**；其中 `UpdateActivity.java:129,232`、`AiChatActivity.java:166` 是**每请求新建**。`ChatActivity`/`GroupChatActivity` 两份逐字重复的 `voicePreloader` 仍在（`:108`/`:115`），无 `AppExecutors`。<br>**部分修复**：轮询已可取消（`ChatActivity.java:1092,1111-1117`，`onDestroy():1855` 调用），但 `:1099` 仍**每 5 秒新建线程**；且 `:436` 用一次性 `new Handler().postDelayed(...startPolling, 3000)`，`onDestroy` 无法取消 → **3 秒内退出页面会复活一个持有已销毁 Activity 的轮询循环** |
| 2.3 | 登录限流可绕过 | ⚠️ **部分修复**（静态） | 驱逐策略改善：`AuthHandler.java:33-46` 先清过期项，仅兜底时才强删一半（审计「任意驱逐」表述已过时，但破坏性兜底仍在）。**键仍含未归一化的用户名**（`:182` 仅 `trim()`，`:186-188`），登录查询大小写敏感 → 大小写变体仍是独立配额桶。仍为内存态 |
| 2.4 | 支付密码 6 位 + 客户端哈希 + 内存锁定 | ❌ **未修**（静态） | `WalletDao.java:53-57,108,127` 仍以客户端 `SHA-256(PIN)` 作 PBKDF2 输入；锁定状态仍在 `ConcurrentHashMap`（`:131-132`），重启即清零（`wallets` 表无 `fail_count`/`lock_until` 列）。无 pepper |
| 2.5 | 上传无配额 | ❌ **未修**（静态） | 全局 100 MB（`FamilyChatServer.java:22-23,35`，且 `-Dmax.request.size` 无上限可覆盖）、单文件 60 MB（`FileHandler.java:512,564`）。无每用户配额/总量上限/TTL 回收。`data/` 实测 **1058 MB**（video_cache 912 MB、chat_files 143 MB） |
| 2.6 | 错误被伪装成「成功但没数据」 | ❌ **未修**（静态） | `MallDao.java:607-612` 吞 `SQLException` 返回 `total:0` → `MallHandler.java:78` 仍是 200。同形：`:646-648`、`:810-812`、`:985-987`。空 `catch` 仍在（`GroupContentHandler.java:46,74`、`GroupHandler.java:159,178,599,603,922`）；`GroupDao.java:510-515` 及 `:521-536` 共 6 处静默 catch，`dissolveGroup` 仍会 `commit()` → 散群残留子表数据 |

### 5.3 P2 — 本月（抽检）

| # | 问题 | 状态 |
|---|---|---|
| 3.1 | R8 关闭（`app/build.gradle:74` `minifyEnabled false`） | ❌ 未变 |
| 3.2 | 无 CI | ❌ 未变（门禁仍只有手工 `publish-check.bat`） |
| 3.3 | **`RELEASE.md:91-92` 声称明文默认禁止 + IP 白名单，实际 `network_security_config.xml` 是 `cleartextTrafficPermitted="true"` 全局放行** | ❌ **文档仍与实现矛盾** |
| 3.5 | `?token=` 走 URL query | ❌ 仍在（`AuthHandler.java:265`、`WsHandler.java:87`） |
| 3.7 | 非恒定时间比较 | ❌ 仍在（全服务端 `MessageDigest.isEqual` **零命中**；`UserDao.java:81,128`、`WalletDao.java:109,113` 仍用 `equals`） |
| 3.8 | `AiHandler` 限流 Map 无上限 | ❌ 仍在（`AiHandler.java:34`，只 `compute` 不回收） |
| 3.9 | `/api/mall/products` 无商家角色校验 | ❌ 仍在（`MallHandler.java:720-751`：仅 `requireAuth` + 参数校验，无任何角色判断） |
| 3.10 | 密钥文件位于 `data/` 内 | ⚠️ **作为 HTTP 暴露不成立**：逐条枚举全部路由后，**没有任何路由伺服 `data/` 或其父目录**（无 catch-all、无 `staticFiles`），部署配置也只是反代到 :5000。位置风险（备份/误 `git add -f`）仍在 |

### 5.4 P3 — 重构（实测行数）

| 文件 | 审计行数 | **实测行数** | 变化 |
|---|---|---|---|
| `ChatActivity.java` | 2592 | **2592** | 无变化 |
| `GroupChatActivity.java` | 2042 | **2042** | 无变化 |
| `MainActivity.java` | 1769 | **1747** | −22 |
| `MallActivity.java` | 1072 | **1072** | 无变化 |
| `ApiClient.java` | 1134 | **1155** | +21 |

**ViewBinding 使用：0 处**（`build.gradle` 里甚至没启用）。`MainActivity` 仍是 71→73 处 `findViewById`。
`ChatActivity.onCreate` 仍是**单个 556 行的方法**（`:172-727`）；语音气泡仍在 Java 里手工搭（`ChatActivity.java:2007-2113`、`GroupChatActivity.java:1571-1670`，`setTag("voice_container")` 等字符串逐字重复）。
**`ChatActivity` ∩ `GroupChatActivity` 同名方法已达 34 个**（审计说 ~20）——重复度**变差而非变好**。
→ **§4.1 的 God Class 重构建议完全未被采纳**，属预期（P3 优先级）。

---

## 6. 已提交（6a05610）的密钥管理功能复核（v1.0.128 新功能）

功能本身**已经发布上线**，**代码已于 `6a05610` 提交**。静态复核结论：整体设计认真（导入校验四重、重置双确认、私钥读写入口收敛、失败可撤销），但存在下列真实缺陷。

### 6.1 高

> **修复状态（2026-09-18 追加）**：本节 **H1–H4** 与 §6.2 的 **M1、M2** 按既定约束
> **作为一批修复**（不拆开）。逐项提交、未 push。每项修复后在本表对应行标注提交号与验证方式。
> 提交记录见本节末尾「§6.1 修复批次记录」。

| # | 问题 | 状态 | 证据 |
|---|---|---|---|
| **H1** | **二维码里是明文 PKCS8 私钥**：无口令、无 KDF、无 MAC/AEAD；唯一的「完整性」字段 `fp8` 是**无密钥的公开校验和**（`SHA-256(pub)` 前 4 字节），攻击者可自行伪造一致载荷。导出页有常驻警告，故属设计取舍而非编码 bug，但「四重校验」只防**损坏**、不防**伪造** | ✅ **已修**（`1636d8b`） | 原状：`KeyTransferPayload.java:45,174-177`；类内无 `Cipher`/`Mac`/`SecretKey`/PBKDF。**现改为 v2 载荷** `miyu-keybackup:2:<salt>:<iv>:<ct>`：`PBKDF2-HMAC-SHA256(口令,16B 随机盐,210000,256)` + `AES/GCM/NoPadding(128 位标签)`；明文 `0x01\|pubLen\|pub\|priv`。**v1 明文码一律 `LEGACY_FORMAT` 拒绝**（不降级兼容）。「码旧」（v1→重新导出）与「码新」（v3+→升级 App）分开提示。实测：292 字符 / QR 版本 13（v1 为 239/版本 11）；测试 31→37 条、连跑 100 次全绿 |
| **H2** | **重置时的「密码预检」在任何拉取失败时被静默跳过** → 密码打错也可能覆盖掉唯一可恢复的备份，而 UI 报「已同步更新」。`KeyResetActivity.java:244-252` 的 `catch` 是**死代码**，因为 `ApiClient.fetchKeyBackup()`（`:328-340`）从不抛异常，只在网络/HTTP/解析失败时返回 `null`；`existing == null` 混淆了「服务端无备份」与「GET 失败」 | ✅ **已修**（`c626b4f`）。**网络失败分支：仅真机手测** | 新增 `KeyBackupResponse`（纯 JVM 三态解析）+ `ApiClient.fetchKeyBackupState() throws ApiException`：**失败只能是 checked 异常，不能是返回值** → 调用方不写 catch 就编译不过。`KeyResetActivity` 改为「确认现状失败 → **直接拒绝上传**」并给「重试同步」出口；`LoginActivity`/`SettingsActivity` 同步改造（后者顺带修掉「网络失败被报成『服务器上没有密钥备份』」）。旧 `fetchKeyBackup()` 标 `@Deprecated`。新增 `KeyBackupResponseTest` **8 条**（含 `failureNeverLooksLikeNoBackup`）；`scripts/e2e-key-transfer.ps1` 新增 `NB-1`（新账号返回 `backup_b64=""` 的契约锚点）与 `NB-2`（未认证查询被拒），实跑 **35/35 通过**。⚠️ 「GET 抛异常 → 拒绝上传」发生在 OkHttp 路径上，本机离线无 `mockwebserver`（见 §6.2 M2 说明）故**未单测覆盖，只能真机手测**：飞行模式下重置密钥必须弹「无法确认服务端备份状态」且不改动服务端备份 |
| **H3** | **导入路径不刷新服务端备份** → 换机导入后若「清数据重新登录」，`LoginActivity.ensureLoginKeys` 会用服务端旧备份把**旧密钥解回来并重新上报旧公钥**，静默撤销导入。作者已在 `RELEASE.md` 记为已知缺口，但成功提示（`:337`）无任何警告 | ✅ **已修**（`078a085`） | `KeyQrImportActivity` 在"导入 + 上报公钥成功"后新增 `maybeRefreshServerBackup()`：① 服务端确认无备份 → 明确告知"无需同步"；② 有备份 → 要求输入**登录密码**（与迁移口令分开持有，各自 `char[]` 清零）重新加密**刚导入的**密钥对并覆盖；③ 无法确认 → 如实警告"可能被旧备份覆盖"，绝不猜测。跳过同步时写明后果。另补 `prefs.saveCurrentAccount()`（`saveKeyPair` 只在账号已存在时更新，快照可能残留旧钥）。`RELEASE.md` 的"已知遗留缺口"该条已标注闭环 |
| **H4** | **全客户端无 `FLAG_SECURE`**（grep 0 命中）→ 私钥二维码可被截屏/最近任务缩略图捕获。仅靠 `ivQr.setImageDrawable(null)`（`:208-211`）不强制窗口重绘，而任务快照由系统在 stop 转换期抓取。**已发布文案承诺强于实现**（`version.properties` 「不留最近任务残影」、`RELEASE.md` §7.1「`onStop` 立即抹除」） | ✅ **已修**（`79dcd51`） | 导出/导入/重置三页 `onCreate` 中、`setContentView` **之前**设 `getWindow().setFlags(FLAG_SECURE, FLAG_SECURE)`（导入页/重置页含密码输入框，顺带禁掉该窗口的输入法截屏）。grep 校验：三处命中、无遗漏。`RELEASE.md` §7.1 的错误表述已校正为「真正起作用的是 `FLAG_SECURE`；`onStop` 只是纵深防御，不承担抹除快照的承诺」，并注明副作用（三页无法截图/录屏，属预期）。⚠️ 该结论为静态+编译验证，**未在真机复现截图**（见文末「方法与局限」） |

> **修复约束（2026-09-18 确认）**：以下 **6 项必须一起修、不拆开**：本节 H1–H4 加 §6.2 的 **M1、M2**。
> 其中 **M2 的优先级不低于 H1**——H1 的正确性完全依赖载荷层测试（`KeyTransferPayloadTest`）可信；
> 测试若 flaky，H1 修完也无法证明「篡改确实被拒」，断言即失去证据价值。修完在本节标注这 6 项为一批。

**§6.1 修复批次记录（逐项提交，未 push）**

| 顺序 | 项 | 提交 | 验证方式（实跑） |
|---|---|---|---|
| 1 | **M2** 篡改用例 1/16 假通过 | `a11f904` | 反证：临时还原旧代码，60 次运行第 9 次复现 `原末位=f 新末位=f`；修复版 **100 次 × 31 用例全绿**（`gradlew --offline` + `JUnitCore` 直跑，54 s） |
| 2 | **H1** v2 载荷（口令 + PBKDF2-SHA256/21 万 + AES-GCM） | `1636d8b` | `KeyTransferPayloadTest` 31 → **37 条**，`@Before/@After` 成对下调 KDF 迭代至 1000；**100 次连跑全绿**；`:core` 全套 47 条通过；`gradlew :app:compileDebugJavaWithJavac` 通过。实测载荷 292 字符 / QR 版本 13、encode 242 ms / decode 126 ms（21 万次 KDF） |
| 3 | **H2** 重置预检区分「无备份」与「拉取失败」 | `c626b4f` | `KeyBackupResponseTest` **8 条**（`failureNeverLooksLikeNoBackup` 逐状态码断言"失败绝不退化成无备份"）；`:core` 全套 47 → **55 条**通过；`scripts/e2e-key-transfer.ps1` 实跑 **35/35**（新增 NB-1/NB-2）。**网络失败分支仅真机手测**（离线无 mockwebserver） |
| 4 | **H3** 导入后刷新服务端备份 | `078a085` | `:app:compileDebugJavaWithJavac` 通过；导入成功 → 查备份状态 → 登录密码重新加密覆盖；三态分支与 H2 共用 `fetchKeyBackupState()`。**UI 流程与真机回归仅手测**（导入页无 JVM 可测层）：清数据重登后指纹必须保持新值，已写入 `RELEASE.md` §7.3 |
| 5 | **H4** 三页 `FLAG_SECURE` + 文案修正 | `79dcd51` | grep 校验三处命中（导出/导入/重置）；`:app:compileDebugJavaWithJavac` 通过；`RELEASE.md` §7.1 错误表述已校正。⚠️ 未真机复现截图（静态+编译验证） |
| 6 | **M1-C** 移除相册入口（改一次性系统分享） | `343b129` | `:app:compileDebugJavaWithJavac` 通过；**门禁**：`KeyQrExportActivity` 零命中 `EXTERNAL_CONTENT_URI/ContentValues/RELATIVE_PATH/DIRECTORY_PICTURES`，`Key*` 中 MediaStore 仅剩导入页 `ACTION_PICK`；清理逻辑（onDestroy + >1h 残留）。⚠️ 分享面板与第三方保存行为需真机点一遍 |

> **默认破坏性变更（H1，已确认接受）**：v1 明文二维码不可再导入。升级后需**在两台设备上
> 都用当前版本重新导出/导入一次**。用户可见的四处文案已定稿：
> ① 导出页「旧版本 App 导出的二维码本版本无法导入；请让两台设备升级到同一版本」；
> ② 导入页常驻状态行与 ③ v1 命中时各出现一次「如对方设备版本较旧，请先让它升级到同一版本再扫码」；
> ④ v3+ 命中时「这是更新版本导出的二维码。请把你的 App 也升级到同一版本」。
> 另有两条口令保护文案：导出弹窗「⚠️ 请记牢这个口令，忘记则二维码永久无法恢复」、
> 二维码页「口令末 4 位：XXXX」（末 4 位**只在导出页本地计算**，二维码内不含任何口令派生信息）。

### 6.2 中 / 低

- **M1** 「保存到相册」把含私钥的 PNG 永久写入共享媒体（`Pictures/密语密钥`），60 秒自动隐藏对它无效、且无任何清理；有权限的 App 与云相册同步均可读。用户主动触发且有警告 → Medium。 —— ✅ **已修（方案 C）**（`343b129`）：**移除「保存到相册」入口**，改为「分享二维码图片」——PNG 只写到私有缓存 `cacheDir/qr_share/`，经 `FileProvider` 临时读授权走系统分享面板；`onDestroy` 删本次文件、每次打开本页清理 >1h 残留（防进程被杀）。分享前有明确知情确认（图片交给第三方后被如何保存/上云本应用无法控制）。**门禁**：`KeyQrExportActivity` 内 `EXTERNAL_CONTENT_URI|ContentValues|RELATIVE_PATH|DIRECTORY_PICTURES` 零命中；`Key*` 页面里 MediaStore 仅剩导入页 `ACTION_PICK`（读取）一处。⚠️ `WRITE_EXTERNAL_STORAGE` 声明**保留**：`PayCodeActivity` 在 Android 9- 仍依赖它
- **M2** `KeyTransferPayloadTest.java:330` 的篡改用例**约 1/16 概率假通过**（当随机指纹恰好以 `f` 结尾时「篡改」是空操作）。本次 31 条全绿，但这是**潜在的间歇性红**。修法：`+ (last=='f' ? "0" : "f")`。 —— ✅ **已修**（`a11f904`）：末位为 `f` 时改 `0`，假通过概率 1/16 → 0；**反证实测**（临时还原旧代码）60 次运行中第 9 次复现 `原末位=f 新末位=f expected:<FINGERPRINT_MISMATCH> but was:<null>`，修复版连跑 **100 次 31 用例全绿**。注：该用例在 H1 中已被 v2 的「密文逐字节翻转必须 `DECRYPT_FAILED`」取代（`decode_rejectsTamperedCiphertext`）。
- **L1** 扫码结果经 `ScanActivity.java:173-179` 以 Intent extra 传递，与导出页自述策略（`KeyQrExportActivity.java:43`「载荷不走 Intent extra」）矛盾（仅防御纵深）。
- **L2** `KeyTransferPayload.java:403-404` 注释称 URL 解码器接受 `+ /`，实际 `Base64.getUrlDecoder()` 不接受（文档瑕疵）。
- **L4** `parseEcPublicKey/parseEcPrivateKey`（`:366-390`）只校验 256 位，未校验曲线为 `secp256r1`。
- **L5** `QrUtils.java:50` 把静区设为 **1 模块**，而注释说默认是 4——低于规范要求，可能影响「尚未真机实测」的扫码成功率。

### 6.3 明确**没问题**（勿「修」）

- 导入校验严格且不会崩：段数/前缀/版本 → base64url → X509/PKCS8 解析 → 指纹 → **ECDSA 自验签配对**（用 ECDH 判定配对的推理是正确的：不配对的 ECDH 不报错）；每步 try/catch，`decode` 永不返回 null。
- 私钥静态存储：`EncryptedSharedPreferences` **fail-closed**，无明文回退；已排除云备份/设备迁移。
- 日志无密钥材料：只记录长度/指纹；崩溃处理器只写堆栈。
- 重置路径在同步成功时是闭合的（`INSERT OR REPLACE` 覆盖备份；登录优先本地/快照）。
- 新功能**没有削弱**也没有改善 §1.3 的对端密钥信任问题。

---

## 7. 本次新发现（既有报告未列）

### 7.1 【高】发布门禁校验的是**错误的 APK**，且失败信息本身是坏的

`publish-check.bat:19-20` 用通配取第一个 APK：

```bat
for %%F in (release\*.apk) do if not defined REL set "REL=%%F"
```

实测它选中了 **`release\miyu.apk`（v1.0.126，9/12 的旧包）**，而真正要发布的、也是服务端
`/apk` 实际发出的（`FileHandler.java:83` **硬编码** `release/密语.apk`）是 **v1.0.128**。
→ 门禁**永远不会校验将要发布的那一个文件**，只要 `release/` 里存在字典序更靠前的 APK。

同时 `:83` 的失败提示被 cmd 的延迟展开吃掉：

```bat
echo        [FAIL] APK versionCode != version.properties ^(expected !VC!^)
```

`!=` 里的 `!` 被当作变量起始符，吞掉了后续直到 `!VC!` 的 `!`，实测输出为
`[FAIL] APK versionCode VC)`——**期望版本号丢失、失败原因读不懂**。

> 修法：把 `REL` 写成确定的 `release\密语.apk`（与 `FileHandler.java:83` 同一路径），
> 并把 `!=` 改成 `not equal to`。

### 7.2 【高】`release/` 里留下两个旧版本 APK，破坏了「三份副本一致」的不变量

| 文件 | 版本 | 大小 | 时间 |
|---|---|---|---|
| `密语.apk` | **1.0.128** | 51,580,649 | 9/18 18:18 |
| `知网.apk` | 1.0.126 | 51,553,570 | 9/12 15:59 |
| `miyu.apk` | 1.0.126 | 51,553,570 | 9/12 15:59 |

审计 §4.3 当时记录「三份 APK SHA-256 一致（由 3 处重复 `copy` 脚本维护）」。
**现在它们已经漂移成两个版本**，且没有脚本再同步它们。三个包签名证书相同（`c60ace24…`，正确），
但「哪一个是发布物」已不再自明——正是 §7.1 的直接成因。

### 7.3 【高】`git add -A` 会误入库 98.3 MB 的 APK 备份

```
NOT-IGNORED: miyu-app/release/密语.apk.pre127-bak   51,553,570 B
NOT-IGNORED: miyu-app/release/密语.apk.pre128-bak   51,555,896 B
NOT-IGNORED: miyu-app/release/version.properties.pre127-bak
NOT-IGNORED: miyu-app/release/version.properties.pre128-bak
```

`.gitignore` 有 `*.apk` 与 `*.apk.bak*`，但**匹配不到 `密语.apk.pre128-bak` 这种后缀**。
`git add -A --dry-run` 实测会暂存这 4 个文件。一旦提交，**938 KiB 的干净历史会被永久撑到 100 MB 以上**
（这恰是既有审计最引以为豪的「仓库干净、无需 BFG」结论的反面）。

> 修法：`.gitignore` 加一行 `*.pre*-bak`（并把已有 4 个备份文件删除或移到仓库外）。
> 这些备份是回滚用的，`RELEASE.md` §7.2 确实引用了它们——**建议移到 `release/rollback/` 并忽略该目录**，
> 而不是留在 `release/` 根下等被误提交。

### 7.4 【高】门禁第 7 步因**新功能自己的脚本**而失败

```
[FAIL] e2e-key-transfer.ps1 含非 ASCII 但缺 UTF-8 BOM：
       Windows PowerShell 5.1 会把它当 ANSI 解析
```

`scripts/e2e-key-transfer.ps1`（本次新增、未提交）本身编码不合规——而它正是
`RELEASE.md` §7.3 列为「后续版本发布必跑」的脚本。另有 3 条 WARN：
`build-apk.bat`（819 个非 ASCII 字节）、`release-commit.bat`（1470）、`deploy.bat`（108）。

### 7.5 服务端新问题（静态复核）

- **充值金额无上限**：`WalletHandler.java:114-124` 只拒绝 `amount <= 0`，`longValue()` 可取到 2^63−1；
  `WalletDao.java:180` 的 `balance = balance + ?` 无溢出保护，`balance` 是 SQLite `INTEGER`
  → **两次超大充值即可把余额绕成负数**（与 §1.1 叠加）。
- **`POST /api/wallet/charge` 无频率限制、无去重、无关系校验**（`WalletHandler.java:346-388`）：
  任何登录用户可对任意用户无限发起付款请求，每条都向受害者推送 `charge_requested` WS 通知
  → 未授权骚扰/钓鱼通道（受害者仍需点确认，不会直接扣款）。
- **`pin_hash` 未做格式校验**（`WalletHandler.java:164,183`），而 `setPassword` 是校验 `[0-9a-f]{64}` 的（`:85-88`）；
  任意长字符串仍会被喂进 12 万次 PBKDF2 才被拒绝，且这些端点无频率限制（CPU 放大）。
- **群文件授权是「查名字」而非「查权限」**：`FileHandler.java:597-601` 只在
  `findGroupIdByFileUrl()` 命中群文件时校验成员身份；**所有未被索引为群文件的 `/chat_files/*`（全部单聊媒体）
  对任何登录用户开放**（知道文件名即可下载）。
- **`video_cache` 只按文件**个数**限制**（`FileHandler.java:101-104`，`MAX_CACHE_FILES=200`），
  且仅在构造函数里跑一次；当前该目录 **2 个文件就占了 912 MB**，体积守卫等价于不存在。

### 7.6 新增代码在重复既有坏模式

本次未提交的 4 个 `Key*` Activity 共 **1265 行**，全部用 `findViewById`（无 ViewBinding），
并新增 **6 处 `new Thread(`**（`KeyQrExportActivity` 2、`KeyQrImportActivity` 2、`KeyResetActivity` 2）——
这正是 `new Thread` 从 166 涨到 172 的来源。它们比老代码好的地方是 UI 回调用
`isFinishing()/isDestroyed()` 做了保护（如 `KeyQrExportActivity.java:180`），
但仍是**无池、无生命周期绑定**的裸线程。

### 7.7 文档漂移

- `README.md:123`「覆盖 52 个用例」→ 实际 **83**。
- `RELEASE.md:91-92` 声称明文默认禁止 + IP 白名单 → 实际全局放行（§5.3 3.3）。
- `RELEASE.md` §7.2 新增段落中「回滚（如需）…」**重复了两遍**，且 §7.1 第 2 点有一处换行被吃掉
  （「便于审计。   - 相册保存：」挤在同一行）。

---

## 7.8 本次修复记录与遗留待办（2026-09-18 20:50 追加）

> 本节记录本报告 §1.1 / §5.1 1.1 / §7.5 相关项的**实际修复**与**遗留待办**。
> 本节只记录，不含客户端改动。

### 7.8.1 已修复并提交

| 原编号 | 问题 | 修复 | 提交 |
|---|---|---|---|
| §1.1 / §5.1 1.1 | 任何登录用户可无限造钱（`/api/wallet/recharge`） | 改为仅 `ADMIN_USER_IDS`（服务端环境变量，逗号分隔 userId）白名单可充值；未配置 = 空集合 = 任何人（含第一个用户）均不可充值，非白名单一律 403「充值功能未开放」 | `a3efab2` |
| §7.5 第 1 条 | 充值金额无上限、可为负（可溢出绕成负数） | 新增 `WalletDao.MAX_SINGLE_CREDIT=10000` 分；**SQL 层**（`UPDATE ... WHERE user_id=? AND ? > 0 AND ? <= ?`）与 handler 层（400）双重校验，负数/零/超上限在 SQL 层命中 0 行 | `a3efab2` |
| §7.5 第 4 条 | 单聊媒体对任何登录用户开放（IDOR） | `/chat_files/*` 非群内容要求 **请求者==上传者 或 上传者的已接受好友**，否则 404（非 403，不暴露资源是否存在）；上传者由服务端生成的文件名 `{ts}_{uuid8}_{uid}.{ext}`（兼容早期 `{ts}_{uid}.{ext}`）解析，解析失败 fail-closed 同样 404。群内容分支未改动 | `a3efab2` |
| §2 门禁 / §1.1 附注 | `-Dfile.encoding=GBK` 致 Gradle test worker 加载 sqlite-jdbc 原生库失败（单测 24/24 全挂，HEAD 上即存在） | `gradle.properties` 改为 UTF-8；改后 24/24 通过 | `a44cd00` |

> **× 上表最后一行已于 2026-09-18 21:40 修订（结论不成立）**：该故障在
> `gradle.properties` 已是 UTF-8 的情况下**再次复现**（HEAD `2947332` 干净代码，24/24 全挂），
> 故「改 UTF-8」不是根因、当时的「通过」很可能只是恰好绕过。真实根因是
> **Gradle test worker 的 `java.io.tmpdir` 不可写**（sqlite-jdbc 解压原生库/建 `.lck` 失败后
> 回退 `System.loadLibrary` 而失败），已用受控实验确认。详见 §7.8.2 该待办条目。
> **当前服务端单测以 `scripts/run-server-tests.ps1` 为准（39/39），不要再用 `gradlew test` 的结论。**

**验证**：隔离实例（临时数据目录 + 独立端口）HTTP 实测 —— 普通账号 403 / 管理员 200 / `-100`·`0`·`999999` → 400 / `10000` → 200；单聊媒体本人 200、非好友 404、未登录 401（好友场景按已批准口径为 200，见 7.8.2）。生产裸机闭环已确认：uid=<非白名单> → 403，uid=<白名单> → 200 且余额 +<金额>，`GET /api/wallet` → 200。

**部署要点**：生产必须以 `ADMIN_USER_IDS=<uid>`（username=`管理员`，`users.id=<uid>`）启动，否则白名单为空、**管理员同样无法充值**（已实测踩到该现象）。

### 7.8.2 遗留待办（本次**未改**）

- **【待办·构建/门禁】服务端单测在 Gradle test worker 下不可用**（2026-09-18 21:40 追加）
  - **现象**：`git worktree` 检出 HEAD `2947332` 的干净代码后跑 `gradlew test`，**24/24 全挂**，
    报 `java.lang.UnsatisfiedLinkError: 'void org.sqlite.core.NativeDB._open_utf8(byte[], int)'`；
    而同一个 fat jar 在普通 JVM 里 sqlite 完全正常（`java -jar` 起服务、初始化库均成功）。
    即：故障只出现在 Gradle test worker 进程里。
  - **与 `a44cd00` 的关系（重要）**：`a44cd00` 从 `-Dfile.encoding=GBK` 改为 UTF-8，
    当时结论是「改后 24/24 通过」（原记录见 §7.8.1 表格最后一行）。但本次复现时
    `gradle.properties` **已经是 UTF-8**、且 `JAVA_TOOL_OPTIONS` 为空，同样的 24/24 全挂再次出现。
    因此 `a44cd00` 的那处改动**不是本故障的根因**，更可能是当时**恰好绕过**（见下），
    或者是与编码无关的另一个独立触发条件。**§7.8.1 表格该行的结论应视为不成立。**
  - **根因（本次已用受控实验定位，但未修）**：sqlite-jdbc 加载原生库时先要往
    `java.io.tmpdir` 解压 `sqlitejdbc.dll` 并创建 `.lck` 锁文件；**只要该目录不可写**，
    它就回退 `System.loadLibrary("sqlitejdbc")`，而 `java.library.path` 里没有这个库，
    于是抛出**正是**上面那条 `UnsatisfiedLinkError`。受控实验（同一 jar、同一 DB、只改 tmpdir）：
    | tmpdir | 结果 |
    |---|---|
    | 沙箱托管的 `<本地路径>`（可写） | SQLITE-OK |
    | 工作区内目录（可写） | SQLITE-OK |
    | 存在但**只读**的目录 | **SQLITE-FAIL + 完全相同的 UnsatisfiedLinkError**（并伴随 `Failed to open directory`） |
    | 恢复可写后同目录再试 | SQLITE-OK |
    这解释了为什么「同一 jar 普通 JVM 能跑、Gradle worker 不能跑」，也说明改 `file.encoding`
    与它没有因果关系 —— 换编码很可能只是改变了 worker 的临时目录/文件创建行为而恰好绕开。
    **未做的部分**：没有实测确认 Gradle test worker 的 `java.io.tmpdir` 具体指向哪里、
    为何不可写（是否与 DSH 托管临时目录或 worker 的沙箱限制有关）。
  - **当前替代方案**：用 `pwsh -File scripts/run-server-tests.ps1` 跑服务端单测
    （普通 JVM + 反射执行同一批 `@Test`，自动发现 `src/test` 下全部 `*Test`，实测 **39/39 通过**）。
    **在根因修好之前，服务端单测一律以该脚本结果为准，不要再用 `gradlew test` 的结论。**
  - **对门禁的影响**：`publish-check.bat` 的 **[6/7] 仍只覆盖客户端 83 个测试**
    （见 §2），服务端测试从未进入门禁 —— 所以这个故障此前不会被门禁发现，
    也让「服务端改动没有回归保护」这件事被长期掩盖。
  - **可能的修法（下次做，本次未改）**：给 `test` 任务显式指定可写的
    `-Djava.io.tmpdir`（例如指向 `build/tmp/test-worker`），或在 worker 里
    设 `org.sqlite.tmpdir`；修好后应把服务端测试也纳入门禁。
- **【待办·客户端】充值按钮对所有人可见**：`WalletActivity.java:70-72` 注释写「充值对所有用户开放」、`btnRecharge.setVisibility(View.VISIBLE)`，而服务端已收敛为白名单 → 普通用户点充值会收到 403「充值功能未开放」。需客户端按 `ADMIN_USER_IDS` 语义隐藏按钮（或至少给出更准确的提示）。
- **【口径局限·服务端】IDOR 修复粒度**：单聊媒体 URL 在 `messages.content` 中为 E2EE 密文，库中不存在 file→会话 的映射表，服务端**无法**判定精确会话参与方；当前实现为「上传者或上传者的已接受好友」，故上传者的任一好友仍可下载其媒体。要做到精确会话级校验，须改客户端上传契约（上传时携带 receiver/conversation 标识）。
- **【数据】异常 `transfer` 流水仍在**：余额已归零，但 `transactions` 中超大额 transfer 记录保留（如 `id=76` 100,000,000,800 分、`id=58` 3,953,369,900 分），会继续出现在用户「账单」页；另有 3 笔 `pending`（id 7/8/11）在余额归零后前提失效。
- **【数据】归零可回滚**：备份 `server-java/data/backup-before-balance-reset-20260918-203706/`（SHA-256 `B259CA48…3553C`）。

---

## 8. 建议处置顺序

**立刻（今天）**
1. **决定 `release/` 里三个 APK 的去留**：删掉/移走 `知网.apk`、`miyu.apk`（v1.0.126），
   并把 `publish-check.bat` 的 `REL` 固定为 `release\密语.apk`（§7.1、§7.2）——**否则门禁永远在验错文件**。
2. **`.gitignore` 加 `*.pre*-bak`**，并把这 4 个备份移出 `release/` 根目录（§7.3）。
   在别人跑 `git add -A` 之前做掉。
3. **给 `e2e-key-transfer.ps1` 加 UTF-8 BOM**，让第 7 步通过（§7.4）。
4. **提交全部未提交工作**（6 改 + 19 新，含整个密钥管理功能与 31 条新测试）。
   ——这是唯一「可能已经造成不可逆损失」的一项。

**本周**
5. **关闭/加固 `/api/wallet/recharge`**（§1.1）——它是把整个资金系统作废的那一条。
6. `ServerConfig` 在 DB 存在且密钥缺失时**硬失败**（§1.2）。
7. **`FLAG_SECURE`** 加到密钥导出/导入/重置三个页面，并修掉已发布文案与实现的落差（§6.1 H4）。
8. 修 **H2**（重置预检：区分「无备份」与「拉取失败」，失败则拒绝上传）与 **H3**（导入后刷新服务端备份）——§6.1。
9. §2.x 的 P1 项按既有报告执行：连接池、`AppExecutors` + 单一 `OkHttpClient`、限流键归一化、PIN 锁定持久化、上传配额。

**本月**
10. 补一个最小 CI（哪怕只是 `publish-check.bat` + 两套 gradle test）——现在门禁只有「人记得跑」。
11. 修 §7.5 的 4 个服务端新问题（充值溢出上限、charge 频率限制、`pin_hash` 校验、群/单聊媒体鉴权口径）。
12. 修文档漂移（§7.7）。

> ⚠️ 2026-09-19 新增的待办（见 §9）中，
> B（`MessageDatabase.onUpgrade` 是 `DROP TABLE`）
> 优先于本节所有项——任何改 schema 的操作
> 之前必须先修它，否则会静默清空用户本地历史。

---

## 9. 会话预览修复与遗留待办（2026-09-19）

> 本节记录「消息列表页（会话列表）预览显示异常」的根因、第一批修复、第二批计划，
> 以及本次调查新发现的待办。**本节为纯文档追加，未改动任何代码。**
> §7.8.2 的原条目**保持原样未删**，9.4 只做引用与状态汇总。

### 9.1 已修复（第一批，已完成，未提交）

**根因（三个独立缺陷叠加）**

- **单聊：预览路径完全没有解密**。`MainActivity.previewFor` 好友分支直接把本地库
  `MessageDatabase.getLastMessage()` 的原始 `content` 交给 `previewText()` 截断展示，
  全程没有调用过 `MessageEncryptor.decryptMessage`。而本地库存的是密文 ——
  收到的消息在 `ChatActivity.java:453` **先入库**、`:461` 才解密（DB 拿到的永远是密文）；
  服务端历史同步在 `:656` 用 `CONFLICT_REPLACE` 按 id 覆盖，会把本机发出的明文行也改写成密文。
  → 列表显示 Base64。
- **群聊：解密逻辑写在主线程**。`previewFor` 在 `onBindViewHolder` 中被调用（主线程），
  公钥缓存未命中时 `GroupEncryptHelper.fetchPeerPublicKey` → `ApiClient.getPublicKey` →
  `doRequestRaw` 的**同步** `client.newCall(...).execute()`；主线程发起网络请求抛
  `NetworkOnMainThreadException`（RuntimeException，而 `doRequestRaw` 只 catch `IOException`，
  异常上抛后被 `getPublicKey` 自己的 `catch (Exception)` 吞掉返回 `null`）
  → 解密必然失败 → 退化为 `[消息解密失败]`。
- **群聊「自己发的消息」查错了缓存键**。发送时自己那一份是用**自己的公钥**加密的
  （`GroupEncryptHelper.encryptForGroup` 用 `prefs.getMyPublicKey()`），而解密侧统一走
  `prefs.getPeerPublicKey(senderId)`；`senderId == 自己` 时查的是 `peer_pub_<自己>`，
  这个 key **永远不会被写入** → 必然未命中。会话列表里「最后一条是自己发的」恰恰是最常见场景。

**修复方式**

- 新增 `PreviewDecryptor`（`feature-chat/.../utils/`）：单聊预览解密，采用与
  `ChatActivity.maybeDecrypt` 相同的两级策略（缓存公钥 → 服务端拉最新公钥重试一次）；
  非 text 类型（image/voice/video/file 存的是 URL）与明文一律透传；
  解不开时返回 `[无法解密]`，**绝不把 Base64 当预览展示**。
- `MainActivity.previewFor()` 改为**只读内存缓存**（两次 map 查找，零 I/O、零网络）；
  新增 `refreshPreviewCacheAsync()` 在后台批量预解密（单线程执行器 + `previewGeneration`
  代次丢弃过期结果），由 `buildConversations()` 末尾触发。
- `GroupEncryptHelper.decryptGroupMessage()` 增加 `isSelf` 分支：`senderId == myUserId`
  时改用 `prefs.getMyPublicKey()`，并跳过服务端公钥重试（本地公钥即权威）。
- 两个 `resolve*Preview` 的 `catch (Exception)` 里 `return ""` 改为返回占位符 ——
  原本任何异常都会**静默留白**，会话预览永远空白、用户看不出发生了什么。
- 顺带：群解密失败占位符在**列表**统一归一化为 `[无法解密]`（群聊页气泡仍保留
  `[此消息无法解密]` / `[消息解密失败]` 的细分），并抽出 `PLACEHOLDER_NO_SELF` /
  `PLACEHOLDER_FAILED` 常量，`GroupChatActivity` 同步引用（字符串值未变）。

**验证（已实跑）**

- 单元测试 **102 条全绿**（原 97 + 新增 5 条 `PreviewDecryptorTest`），
  `scripts/run-unit-tests.ps1` 输出「运行 102 个测试，全部通过」；原有 97 条无回归。
- `:app:compileReleaseJavaWithJavac` 通过；`:app:assembleRelease` 构建成功。
- 产物签名校验：`app-release.apk`（versionCode=130 / 1.0.130），
  证书 SHA-256 `c60ace246e5985f61d9a9ce0d90bec7009392169843134c6b4bcfc456fec4fd5`，
  与已发布 `release/密语.apk` **同一证书**（`apksigner --print-certs` 逐份比对），
  故可覆盖安装、本地库与密钥保留。
- 新增测试过程中确实抓到过一个边界错误（在测试用例自己身上）：40 个 Base64 字符
  正好落在 `looksLikeCiphertext` 的长度阈值上，已修正为 39/40 两侧各断言一次。

**待真机验证（4 项，⚠️ 本次未执行）**

| # | 场景 | 期望 |
|---|---|---|
| 1 | 单聊 · 自己发的消息 → 退回列表 | 预览显示明文 |
| 2 | 单聊 · 对方发的消息 → 回列表 | 预览显示明文 |
| 3 | 单聊 · 杀进程重启 App | 预览显示明文 |
| 4 | 单聊 · 进详情页再退出 | 预览显示明文 |

> **未执行原因（如实记录）**：本机 `adb devices` 无任何设备；
> `<本地路径>` 不存在、`system-images/` 为空
> （仅有 AVD 定义残留），无法起模拟器；且跑通场景还需后端服务与 ≥2 个账号。
> 这 4 项**保持「未验证」**，未做任何推断性结论。
> 排查用日志 tag：`E2EE_PREVIEW`、`E2EE_LOG`、`GroupE2EE`（release 包 `minifyEnabled false`，日志未被裁剪）。

### 9.2 第二批（未做，等第一批真机验证通过）

| # | 位置 | 改动 | 说明 |
|---|---|---|---|
| 1 | `ChatActivity.java:414` | `parseSentMessage(respBody, text, "text")` → 传 `cipher` | **1 行**。本地库 content 改存密文，让「只存密文」成为统一不变量；`saved` 对象只用于入库与回填 `id`/`created_at`，气泡用的是另一个乐观对象，**显示不受影响** |
| 2 | `MainActivity.java:190-205` | 好友通知预览改用 `PreviewDecryptor` | 当前直接用 WS 推送的 `msg.content`（`String preview = msg.content.substring(0,30)`），**完全未解密** → 通知栏直接弹 Base64。**比列表页严重**（用户不必进 App 就能看到） |

> **暂不做（记入 9.3 C）**：`SearchChatActivity.java:146-159` 的 `serverBatch` 结果未解密。
> 理由：服务端 `MessageDao.searchMessages` 用 `content LIKE '%kw%'`，而服务端**从始至终只存密文**
> （客户端 `ChatActivity:402` 提交的就是 `cipher`），文本本来就搜不到，触发不了。

**第二批验证清单（7 项）**

1. `run-unit-tests.ps1` 全绿（预期仍 102 条）
2. 发一条文本 → 查本地库 `content` 是 Base64
3. 重新进聊天页气泡仍显示明文
4. 发完立刻退回列表 → 预览显示明文
5. 同一会话重复「发 / 退 / 进 / 重启」循环 → 预览不出现 Base64
6. 本地搜索同一条消息仍能搜到
7. **【新增】** 收到好友消息时，通知栏预览显示明文，不是 Base64

### 9.3 新增待办（本次调查发现，非第一批引入）

| # | 问题 | 位置 | 风险 |
|---|---|---|---|
| A | 全新安装好友预览空白 | `MainActivity.resolveFriendPreview` 的 `m == null` 分支 | 全新安装/清数据后，未点开过的好友预览为空。根因：`GET /api/friends`（`FriendDao.java:85-106`）**不返回任何消息字段**，而好友的预览/时间/排序**全部依赖本地库**，无法从服务端补回。属**产品决策**（空白 vs.「暂无消息」vs. 新增接口补最后一条），未处理 |
| B | `MessageDatabase.onUpgrade()` 是 `DROP TABLE` | `MessageDatabase.java:65-69` | ⚠️ **将来任何 `DB_VERSION` 提升都会清空全部本地历史**；改 schema 前必须先改成真迁移。这也是 9.3-A 不能用「清库重拉」解决的原因 |
| C | `SearchChatActivity` 的 `serverBatch` 未解密 | `SearchChatActivity.java:147-160` | 服务端搜索命中的文本会显示 Base64；当前服务端只存密文、文本搜不到，**触发不了**，故本批不做 |
| D | WebSocket 正常关闭用 Error 级日志 | `WebSocketManager.java:389`（`onClosed` 无条件 `AppLog.e`） | `code=1000`（正常关闭）也打 Error；正常关闭路径本来就存在（`:132` 重连、`:544` bye）→ 日志里充满假错误，**干扰真问题排查**。**处置：不挂 §8**（成本低但非紧急），下次随手改日志时一并处理 |
| E | 转账状态变更时列表页不刷新预览 | `MainActivity` 未实现 `WebSocketManager.Listener.onTransferEvent`（接口默认空实现 `WebSocketManager.java:96`；`ChatActivity.java:476` 已实现） | 金额不变，不影响会话列表预览（预览按设计只显示金额 `[转账] ¥X.XX`、不显示 status）。**但将来若要在列表显示状态，必须先实现 `onTransferEvent`**，否则会出现「列表显示待收款、点进去已收款」的不一致 |
| F | 搜索结果预览显示 Base64 | `SearchChatActivity.java:288`（`h.preview.setText(highlight(previewText(m), …))` 直接用本地库原始 `content`；`decryptContent` 的结果只在 `searchLocal` 里用于关键词匹配，**未回写 `m.content`**） | 搜到**对方发来**的文本消息时，预览行显示的是密文 Base64（自己发的当前存明文，暂不触发）。与 §9.3-C（`serverBatch` 未解密）**同源**，都是「结果集未解密」，将来一起修 |
| G | 投票 `end_at` 用**服务端本地时区**，与消息 `created_at` 的 **UTC** 约定相反 | 写：`GroupPollDao.java:37`（默认时区 `SimpleDateFormat`）；读：`GroupPollHandler.java:216`（`ZoneId.systemDefault()`）；客户端显示：`GroupPollActivity.java:126`（直接 `substring(0,16)`） | 写读两端一致，当前显示**正确**（服务端与手机同机同区），故本次未动。但它是全仓**唯一**一套与消息 UTC 约定相反的时区实现：将来统一时区约定时必须一并处理，漏掉会让投票截止时间整体偏移。注意它与 `ChatTimeFormatter.parseUtc`（消息走 UTC，见 2026-09-19 时区修复）不是一套逻辑 |
| H | 单聊发**图片/文件/视频/语音**后不回填 `created_at` | `ChatActivity.java:849`（图片/文件：`httpClient.newCall(req2).execute()` 丢弃响应体）、`:957`（视频：同）、`:1054`（语音：只调 `parseMsgId`，不解析 `created_at`） | 乐观插入的对象 `created_at` 为 `null` 且此后**从不回填**，`localDb.insertMessage` 落库的也是 null → 这几类「我发的」消息**永远没有时间分隔符**，退出重进也没有（与 §9.3 之外那批「发送后时间行不出现」不同：那批是缺一次 notify，已在 `ChatActivity:421` / `GroupChatActivity:804` 修掉）。`fetchNewMessages` 又按 `id > lastId` 过滤，不会补。修法：改用已有的 `parseSentMessage(respBody, url, msgType)`（它已解析 `id`/`created_at`/`filename`，见 `:1676`）。**本批不做，单独一批处理**（2026-09-19 记录） |
| I | `unread_<friendId>` 未读 key **无 uid 作用域**，且登出不清 | 写：`NotificationDispatcher.Listener.onNewMessage`（`"unread_" + friendId`）；读：`MainActivity` 会话列表角标（`:1515`）；清理：`PreferencesManager.logout()` 只删 token/账号字段与 `peer_pub_`/`avatar_`/`ro_cache_` 前缀 | 切换账号后，**上一个账号的未读红点会留在新账号的会话列表上**。对照：好友/群列表缓存（`ReadOnlyCache`）是按 uid 作用域的，未读没有。与 §9.3-J、§9.2-2 同批处理（三者都是「通知/未读收尾」） |
| J | **打开会话不清除该会话的通知** | `NotificationHelper.cancelNotification()` 全仓库**无调用点**（只有退群时调 `cancelGroupNotification`，`MainActivity.java:1499`） | `setAutoCancel(true)` 只在**点击通知本身**时生效；从会话列表点进同一个好友不会清通知，于是「已读且未读已清零，通知栏还挂着那条消息」。修法：`ChatActivity.onStart` / `GroupChatActivity.onStart` 各加一行取消；因 `NotificationHelper` 在 `:app`（feature 模块看不见），需在 `:core` 加一个约 15 行的 id 门面（id 规则：好友 = friendId，群 = 100000 + groupId） |
| K | 通知预览 text 仍是 WS 密文（= §9.2-2 的落点） | 决策点已随「通知派发下沉」搬到 `NotificationDispatcher.dispatchFriend`（原 `MainActivity.java:265`）；群通知路径**已在后台线程解密**（`dispatchGroup`） | 好友文本消息的通知正文是 Base64。修法与 §9.2-2 相同（改用 `PreviewDecryptor`），但**落点变了**：现在在 `:app` 的 Dispatcher 里，可直接依赖 `:feature-chat` 的 `PreviewDecryptor`，**不再需要为它造跨模块接缝**——这是把派发放在 `:app` 而非 `:core` 的附带好处 |
| L | **【运维】生产 `data/` 必须成对备份 `family_chat.db` + `jwt_secret.key`** | `ServerConfig.java:66-72`（`loadOrGenerateSecret`：**数据库已存在 + 密钥文件缺失** → `throw IllegalStateException` 拒绝启动，除非显式设 `JWT_SECRET_ROTATE=1`） | 两个文件**只丢一个**（最常见的场景：备份脚本只抓了 `.db`）服务端**直接起不来**。且该检查发生在 `db.init()` **之前**（设计如此，见 `validateAtStartup` 注释），所以现象是"服务起不来"而不是"数据坏"，排查时容易误判成别的问题。恢复：从备份还原 `jwt_secret.key` —— 不丢数据、不踢任何在线用户；确实要轮换才用 `JWT_SECRET_ROTATE=1`，但那等于**全员强制重新登录**且不可撤销。2026-09-20 写服务端信令白名单契约测试时踩到同一条保护（`build/test-data` 里只有库、没有密钥，`WsHandler` 静态初始化构造 `JWTVerifier` 就炸）；测试侧已用 `@BeforeAll` 补测试专用密钥解决，生产侧仍需运维成对备份 |
| M | **主叫在响铃阶段取消（未接通）不留任何系统消息** | 客户端 `CallManager.cancelCall()`（`CallManager.java:312-325`）只发 `call_cancel` 就 `teardown`，对端 `onCancel()`（`CallManager.java:481-492`）同样只收尾不写；与主叫 30s 超时（`CallManager.java:264` → `:708-712`）**同源**，但那条已改为写「无人接听」 | 主叫在对方接听前主动挂断时，**双方聊天里没有任何记录**（微信会留一条）。按 2026-09-20 通话归属批次确立的不变量，这条应归 **主叫**（sender=主叫，同 `ended`/`missed`），但需要新增 event（如 `canceled` → 「已取消」）并同步扩服务端 event 白名单（`MessageHandler.java:185`）。**2026-09-20 决策：本批不做**，与 §9.3 其余项无关，独立成批 |
| N | **通话消息删除只能覆盖"自己发起的"** | `ChatActivity.showMessageMenu`（删除项 gate 是 `isMe`）＋服务端 `MessageDao.deleteMessage`（`DELETE FROM messages WHERE id=? AND sender_id=?`，`MessageDao.java:117`） | 长按通话记录时：**我发起的**有「删除」（撤回已被 `isCallType` 排除）；**对方发起的菜单为空**，`showMessageMenu` 直接 `return`，长按毫无反应。服务端只允许发送方删除，所以这不是客户端能单独修的 —— 微信里双方都能删自己的会话记录。修法需要服务端放开删除权限（或加"本地隐藏"语义），**2026-09-20 决策：本批不做** |

> **M 为 2026-09-20「通话系统消息归属」批次（`b4c3ab4` 渲染 + 同批 `missed`）调查所得，\*\*未修\*\*。**
> 该批次已修两件事：① `ChatActivity` 删掉强制居中/隐藏头像，通话系统消息按 `sender_id` 归到主叫或被叫一侧（数据层本就正确，纯渲染修复）；② 新增 `missed`（主叫 30s 超时 → 「无人接听」，sender=主叫），同时把被叫 45s 兜底改为**只发信令不写消息**（那条只在主叫客户端已死时触发，写出来是 sender=被叫的「已拒绝」，归属与文案双错）。
> 归属不变量已写入两侧源码注释：客户端 `CallManager.reportCallStatus`（`CallManager.java:607-621`）、服务端 `MessageHandler.reportCallStatus`（`MessageHandler.java:143-152`）。

> **I / J / K 为 2026-09-20「通知派发下沉」批次（`b8fc131` → `9193792` → `226d605`）调查所得，三条均\*\*未修\*\*，与 §9.2-2 同批处理。**
> **L 为 2026-09-20「通话架构改造 Step 1」写服务端契约测试时发现，属运维事项（非代码缺陷），不需要改代码。**
> 该批次已修的三件事（连接宿主下沉的配套）：通知派发从 MainActivity 搬到进程级 `NotificationDispatcher`；`NotificationHelper` 去掉对 feature UI 的反向 import 并修掉 PendingIntent requestCode 撞车；POST_NOTIFICATIONS 结果处理 + 提示音三路叠加收敛为单路。
> 另：`§9.2-2` 里引用的 `MainActivity.java:190-205` 行号与实现位置**已随该批次失效**，以 K 的行为准。

### 9.4 已经确认但仍然保留的待办（前面的批次遗留）

**已完成（标注提交号，详见 §6.1 / §6.2，本次不改动其记录）**

| 项 | 状态 | 提交 |
|---|---|---|
| H1 二维码明文私钥 → v2 载荷（口令 + PBKDF2-SHA256 + AES-GCM） | ✅ 已修 | `1636d8b` |
| H2 重置预检区分「无备份」与「拉取失败」 | ✅ 已修 | `c626b4f` |
| H3 导入后刷新服务端备份 | ✅ 已修 | `078a085` |
| H4 导出/导入/重置三页 `FLAG_SECURE` | ✅ 已修 | `79dcd51` |
| M1 移除「保存到相册」，改一次性系统分享（方案 C） | ✅ 已修 | `343b129` |
| M2 篡改用例 1/16 假通过 | ✅ 已修 | `a11f904` |

**仍未做（原条目见 §7.8.2，未删、未改）**

| 项 | 状态 | 引用 |
|---|---|---|
| 服务端单测在 Gradle test worker 下不可用（根因：`java.io.tmpdir` 不可写，已定位未修） | ❌ 未修 | §7.8.2 第 1 条；当前以 `scripts/run-server-tests.ps1`（39/39）为准 |
| 客户端充值按钮对所有人可见（服务端已收敛白名单 → 普通用户点了收 403） | ❌ 未修 | §7.8.2 第 2 条 |
| IDOR 修复粒度只能到「上传者或其已接受好友」 | ⚠️ 口径局限 | §7.8.2 第 3 条 |
| 异常 `transfer` 流水仍在（`id=76`、`id=58` 等仍出现在账单页） | ❌ 未清 | §7.8.2 第 4 条 |
| 余额归零可回滚（备份目录与 SHA-256 已记录） | ℹ️ 备份保留 | §7.8.2 第 5 条 |

---

## 附：本次体检的方法与局限

- **实跑**：服务端与客户端的 `clean` 构建与单测、`publish-check.bat` 全流程、`aapt`/`apksigner` 校验三个 APK、APK 内部 entry 哈希比对、`/version.json` 活探测、`git check-ignore`/`add --dry-run`。
- **静态**：§5 与 §6 的结论由 4 个并行只读复核给出，每条附 `file:line`。**未做动态渗透测试**，未执行任何写操作于源码。
- **服务端 clean 构建在隔离副本中进行**（线上服务占用 jar，见 §1.1），因此该结论验证的是「源码可从零构建」，不等价于「原目录可 clean」。
- `new Thread` / `new OkHttpClient` 是**调用点计数**，不等于并发线程数。
- H1/H4 中「任务快照能否抓到二维码」为**静态推理**（缺少 `FLAG_SECURE` 是确凿事实），未在真机复现截图。
- 本次体检**未修改任何项目源文件**；临时产物 `<本地路径>` 已在体检结束后清理。
