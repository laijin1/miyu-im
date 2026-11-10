# 发布指南：APK 更新规则（必读）

> 本文所有命令均从**仓库根目录**执行。

> 本文档记录密语 App 发版的**正确步骤**与**更新机制原理**，照此发布不会出现
> "检查更新显示新版本、装上去却是旧包"、"下载到旧版本"等问题。

## 发版前先跑一次体检（最省事的一步）

```
scripts\publish-check.bat
```

它会在 10 秒内检查 5 件事，任何一项不通过就**不要发布**：

| 检查项 | 拦住的是什么事故 |
|---|---|
| 签名证书不是 Android 调试证书 | 被杀软判为"危险捆绑软件"（就是本次报毒的根因） |
| 不含 `android:debuggable` | 同上，另外可被任意调试注入 |
| 无"忽略电池优化"等敏感权限 | 被安全软件判为保活/常驻风险 |
| **Gson 关键 JSON 字段名没被混淆掉** | 接口返回空体 400（登录/注册直接挂）、升级后读不出旧存档 |
| APK 版本号 = version.properties，且不比它旧 | 服务端 `published=false`、网页显示新版本却下到旧包 |

`scripts\release-commit.bat` 已内置其中三项硬校验（签名/debuggable/字段名），
不通过会直接中止发布，不会把问题包复制进 `release\`。

> 🧠 **为什么需要这个体检**：本项目此前 30 多个版本全部用 `assembleDebug` 出货，
> `assembleRelease` 这条路径**从未真正执行过**。debug 不混淆、不跑资源校验、不需要签名，
> 于是三类缺陷被同时掩盖。一改成正式构建，它们会在同一时间集中暴露——
> 这就是"修一个冒出一个"的真正原因。体检脚本把"装到手机上才发现"提前到"本机 10 秒拦住"。

> ⚠️ **写 .bat 脚本的红线**：`cmd.exe` 用 ANSI(GBK) 解析 `.bat`，且 `chcp 65001` 生效前就已开始解析。
> 文件里的 UTF-8 中文若被读成 GBK，某些字节（如 `0x85`）是**续行符**，会静默吞掉后面的行、
> 导致脚本报出一堆莫名其妙的"不是内部或外部命令"。
> - 新建 `.bat` 必须：首行 `chcp 65001 >nul`，且**不要用中文写文件名参数**（用通配符代替）
> - 关键校验逻辑写成 `.ps1`（PowerShell 里中文没问题），`.bat` 只做纯 ASCII 的调用壳

## 一、更新机制是怎么工作的（理解原理）

```
手机 App 检查更新
  ├─ GET /version.json   → 服务器返回：最新 versionCode / versionName / 更新日志 / APK 下载地址 / APK 的 SHA-256
  ├─ 对比：服务器 versionCode > 本机 versionCode → 提示更新
  ├─ GET /apk            → 下载 APK 文件（支持断点续传 Range）
  ├─ 校验：SHA-256 必须等于 version.json 声明的值（防篡改/防损坏）
  ├─ 校验：读下载文件的真实版本号，必须等于服务器版本（防装到旧包）
  ├─ 校验：新包签名证书必须等于【本机证书】或【本 App 发布证书】（防被换成第三方改包）
  └─ 安装：FileProvider content:// → 系统安装页，由用户在系统界面上确认（**只有这一条路径**）
```

> ⚠️ **合规红线（2026-09 整改，勿回退）**：安装动作必须由用户在系统安装界面上亲手确认。
> 以下三条路径已删除，**不要以"安装更稳"为理由加回来**：
> - 内置浏览器「下载任意 APK 后由本应用直接唤起安装」（`BrowserActivity`）——等于应用内任意
>   APK 下发器，是 OPPO 安装器判定「自动捆绑安装」最直接的依据；
> - `PackageInstaller` 会话直装（`installViaPackageInstaller`）——应用代用户装包；
> - 复制到公共下载目录再弹装（`installViaPublicDownloads`）——同属代装包，还会在用户下载目录留重复包。
>
> 背景与真机证据：`docs/audits/apk-av/报毒原因分析报告-补充-OPPO实测.md`。

**单一版本来源**：`release/version.properties` 是唯一版本来源——APK 的 versionCode/versionName
（`app/build.gradle` 构建时读取）和 `/version.json`（服务器运行时读取）都来自它。
**所以改版本号只改这一个文件。**

## 〇、发布必须用正式签名包（第一红线）

**绝对不要再用 `assembleDebug` 出货。**

调试版 APK 的签名证书是全世界公开的 Android 调试证书，并且带 `android:debuggable`，
加上 App 自身有"下载并安装 APK"的更新功能，会被安全软件判定为
**"危险捆绑软件 / 未知来源推广包"** 而拦截。历史故障与原因详见
`docs/audits/apk-av/报毒原因分析报告.md`。

现在正式包走标准签名流程：

| 项目 | 位置 |
|---|---|
| 密钥库 | `release/release.jks`（别名 `miyu`，RSA 2048，有效期至 2056） |
| 密码 | `release/keystore.properties`（**已被 .gitignore 忽略，不入库**） |
| 模板 | `release/keystore.properties.example` |
| 备份说明 | `release/签名密钥备份说明.md` ← **务必照做并异地备份** |

构建命令一律是 `gradlew assembleRelease`，产物 `app-release.apk`。
`scripts/release-commit.bat` 会在打包后**硬校验签名与 debuggable**，
发现仍是调试签名或含 debuggable 会直接中止发布。

> ⚠️ **换签名的一次性代价**：旧版是调试证书签名的，老用户**无法覆盖安装**新版，
> 必须先卸载再装（聊天记录在服务端，重装登录即可恢复）。发版前请提前告知。

## 一、网络与安全基线（已生效，不必每次动）

| 措施 | 说明 |
|---|---|
| 默认禁止明文 HTTP | `app/src/main/res/xml/network_security_config.xml` 的 `base-config` 为 `cleartextTrafficPermitted="false"`，公网/隧道域名强制 HTTPS |
| 局域网白名单 | 仅 `domain-config` 里列出的 IP 允许 http://，当前含实际部署地址 `<真实IP>`（另有历史地址 `<真实IP>`）。**换网络后 App 若报 `CLEARTEXT communication to x.x.x.x not permitted`，把新 IP 加进这个文件重新出包**（`ipconfig` 查看服务器实际地址） |
| 更新包签名校验 | `UpdateActivity.verifyApkSignature()` 比对证书 SHA-256，允许集合 = 本机证书 + `BuildConfig.RELEASE_CERT_SHA256`（构建时从 keystore 自动注入） |
| 旧调试证书兼容 | `app/src/main/assets/legacy_debug_cert.pem` 让老用户仍能下载更新；全部用户升级到位后可删除该文件与相关代码 |
| 已移除危险权限 | `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`（常被杀软判为保活风险项），长连接保活依赖前台服务 |

## 二、发版正确步骤（照做，别跳步）

用 `scripts/release-commit.bat`（一键）或手动按以下顺序：

1. **构建服务端**（如需）：`server` 下 `gradlew shadowJar`
2. **改版本号**：编辑 `release/version.properties`（versionCode 加 1、versionName、changelog）
3. **构建 APK**：`client/android` 下 `gradlew assembleRelease`（自动读取新版本号）
   —— **不要用 assembleDebug**，脚本会在打包后硬校验签名，不通过就中止
4. **复制 APK** 到 `release/密语.apk`（同时更新 `知网.apk`、`miyu.apk`）
5. **重启服务端**（`scripts\start-server.bat`）
6. 验证（见第四节）

> ⚠️ **签名凭证必须先就位**：`release/keystore.properties` 不存在时构建会被拦下。
> 换电脑发版时记得先把 `release.jks` + `keystore.properties` 放回来。

> ⚠️ **顺序不要乱**：版本号必须在构建 APK **之前**改（APK 版本号来自它）；
> APK 必须在改完版本号**之后**尽快复制（缩短"版本号已新、APK 还旧"的窗口）。
> 步骤 2→3→4 一气呵成，窗口只有构建的几分钟；客户端安装前会强制核对文件版本，
> 即使撞上窗口也会自动删除重下，最终装到正确版本。

## 三、服务器端已做的防错措施（不需要每次做，自动生效）

| 措施 | 作用 |
|---|---|
| `/version.json`、`/apk`、`/download` 返回 `Cache-Control: no-store` | 浏览器/手机永不缓存旧版本信息或旧 APK |
| **发布窗口保护**：`version.properties` 比 APK 文件新时（版本号已升、新 APK 未就位） | `/version.json` 返回 `published=false`（"新版本发布中"），`/apk` 直接 404——客户端/网页都拿不到与版本号不符的旧包 |
| 下载页 `download.html` 下载链接带时间戳 | 穿透浏览器缓存 |
| `version.json` 返回 APK 的 SHA-256 | 客户端下载后完整性校验 |
| 客户端安装前读 APK 真实版本号并核对（Android 13+ 已兼容） | 装到旧包自动删除重下 |
| 客户端安装前校验新包签名证书 | 更新包被替换成第三方改包时中止安装并删除 |

## 四、发版后验证清单

- [ ] `apksigner verify --print-certs release\密语.apk` → 证书是 `CN=密语...`（**不是 Android Debug**）
- [ ] `aapt dump xmltree release\密语.apk AndroidManifest.xml | findstr debuggable` → **无输出**
- [ ] `http://<服务器IP>:5000/version.json` → versionCode/versionName 是新的
- [ ] `curl -I http://<服务器IP>:5000/apk` → Content-Length 与 release/密语.apk 一致，Cache-Control=no-store
- [ ] 手机浏览器打开 `/download` → 页面显示新版本号
- [ ] 手机 App 内"检查更新" → 提示新版本 → 下载 → 安装页显示**新版本号**
- [ ] 安装后在手机「设置 → 应用 → 密语」确认版本号正确
- [ ] （可选）`git add -A && git commit`（提交信息带版本号，如 ", v1.0.115"）

## 五、踩过的坑（不要再犯）

1. **版本号与 APK 不一致窗口**：先改了 version.properties 却迟迟不复制新 APK →
   服务器说"114"实际给"113"包，SHA 又恰好匹配旧文件 → 装上旧包。
   ✅ 对策：改号→构建→复制一气呵成 + 客户端版本核对兜底。
2. **浏览器/更新器缓存旧 APK**：固定 URL `/apk` 被缓存 → 一直下载旧包。
   ✅ 对策：服务器 `no-store` + 下载页时间戳。
3. **Android 13+ 读不到 APK 版本号**：`getPackageArchiveInfo` 返回 null，
   版本核对被跳过 → 旧包溜过去。 ✅ 已修复（带标志位重试）。
4. **旧包残留**：手机下载目录的旧 APK（update.apk / 密语_v1.0.x.apk）被误装。
   ✅ 对策：更新页每次打开清理旧包；网页下载认准文件名里的版本号。
5. **用调试版出货导致被杀软判为"危险捆绑软件"**：调试证书是公开密钥、
   包内含 `android:debuggable`，叠加"App 内下载并安装 APK"的能力，
   被安全软件判为未知来源推广包。
   ✅ 对策：改用 `assembleRelease` + 正式 `release.jks` 签名，发布脚本硬校验签名与 debuggable。
   详见 `docs/audits/apk-av/报毒原因分析报告.md`。
5.1 **换了正式签名仍然被 OPPO 判「中高风险应用 / 自动捆绑安装」**（v1.0.125 实测）：
   签名已合格，但包内仍有"代用户装包"能力——内置浏览器可下载**任意 URL 的 APK 并自动弹装**、
   更新器有 `PackageInstaller` 会话静默直装、并把包复制进公共下载目录再弹装；
   另外 `assets/legacy_debug_cert.pem` 把公开调试证书带进了升级白名单。
   ✅ 对策（v1.0.126 已改）：上述三条代装路径全部删除，安装只留 FileProvider + 系统安装页；
   浏览器里 APK 只下载不代装；删除 `legacy_debug_cert.pem`，白名单只留"本机证书 + 正式发布证书"。
   ⚠️ **但"未经安全审核 / 未备案"那一条仍会提示**——那是厂商对侧载包的策略 + 缺 APP 备案，
   必须靠备案与上架解决，改代码改不掉。
   详见 `docs/audits/apk-av/报毒原因分析报告-补充-OPPO实测.md`。
6. **资源放错模块导致 release 编译失败**：`bg_transfer_card.xml` 原放在 `app` 模块，
   却被 `feature-chat` 的布局引用，而 `feature-*` 并不依赖 `app`（反向依赖）。
   debug 构建靠资源合并侥幸通过，`assembleRelease` 的 `verifyReleaseResources` 直接报
   "resource drawable/bg_transfer_card not found"。
   ✅ 对策：共享资源一律下沉到 `ui-common`（`paycode_orange` 颜色也在那）。
   **教训：只用 assembleDebug 会长期掩盖这类问题，发版前必须真实跑一次 assembleRelease。**
7. **改签名后老用户装不上**：Android 只允许同签名覆盖安装，调试签名 → 正式签名
   必须卸载重装一次。客户端把"本机当前证书"也算作合法升级来源，保证老用户"下得动包"，
   但最后一步安装仍需先卸载。发版前务必提前告知用户。
   📌 v1.0.126 起 `assets/legacy_debug_cert.pem` 已删除：不再把那张公开的调试证书当合法来源，
   老用户的可升级性由"本机证书"这一项保证，效果相同且没有后门。
8. **开启 R8 混淆后登录/注册全部返回 400（空响应体）**：Gson 默认用 **Java 字段名**当 JSON 键名，
   R8 把字段名混淆成 `a`/`b`，服务端按 `username`/`password` 读取就反序列化失败，
   Javalin 抛 `BadRequestResponse` → **空响应体 400**，客户端只能显示"服务器错误: 400"。
   最坑的是：`ApiClient` 里 `Models` 有 keep 规则所以一切正常，
   只有 `ApiClient$AuthBody` 这类请求体字段名被改掉，现象极难定位。
   ✅ 对策（三层）：
   - 请求体与本地持久化类的每个字段都加 `@SerializedName("原名")`，键名与字段名解耦
   - `proguard-rules.pro` 保留 `@SerializedName` 字段及注解本身
   - 跨版本持久化的类（如 `PreferencesManager.Account`）注解值**必须等于原字段名**，
     否则升级后读不出旧数据（token / E2EE 私钥丢失 = 要重新登录且历史消息解不开）
   🔍 自查：`aapt dump` 或解包 DEX 搜索字段名字符串；发布前跑一次注册接口，
      返回 409（已存在）说明契约正常，返回空体 400 就是字段名被改了。
   ⚠️ 教训同上：**只用 assembleDebug 永远发现不了这类问题**（debug 不混淆）。
9. **开启 R8 混淆后登录后闪退**：该应用大量依赖反射与 Gson（E2EE 加解密、多账号持久化、
   WebSocket 消息映射、各类 Adapter），历史上**从未用 release 变体跑过**，
   一开混淆就有未知的 keep 缺口，表现为登录跳主界面瞬间崩溃。
   ✅ 当前对策：`app/build.gradle` 里 `minifyEnabled false`——**功能优先**。
      报毒问题的主因是调试签名，与是否混淆无关，因此关闭混淆不影响修复效果。
   📌 待办：若日后想重新开启混淆，必须先在真机上完整回归
      （登录/注册、收发消息、语音视频通话、扫码、支付、商城下单、多账号切换、密钥备份恢复），
      并为所有反射入口补齐 keep 规则，不要直接改 `true` 就发版。

## 六、更新入口与日志

- App 内更新页：设置 → 检查更新（`UpdateActivity`）
- 网页下载：`http://<IP>:5000/download`
- 排查日志：设置 → 日志查看（AppLog 记录 UPDATE 全过程：检查/下载/校验/安装各步）

## 七、E2EE 密钥管理（换机迁移）

### 7.1 已发布：v1.0.128（第 1~5 步全部完成）

**状态：已发布（versionCode 128）。** 密钥管理（换机迁移）功能第 1~5 步已全部完成并发布。

> 历史：v1.0.127 是中间版本，只含第 1 步（只读指纹），其更新日志明确写了
> "二维码导出/扫码导入/重置密钥尚未开放"。v1.0.128 补齐了其余全部步骤。

各步骤完成情况：

1. ✅ 设置页「密钥管理」入口 + 本机私钥指纹只读显示（短码 8 位 + 全码 64 位）
   - 指纹算法已固化为协议：`SHA-256(X509 SPKI 原始字节)`，短码取前 4 字节。
     **改这个算法会让所有已发布的"指纹对照"失效，属破坏性变更。**
   - 该页面为纯只读：`KeyManagementActivity` 内不得出现私钥读取 / 密钥对生成 /
     密钥对保存 / 公钥上报四类调用（可用 grep 四类方法名验证零匹配）。
2. ✅ 二维码导出（`KeyQrExportActivity`，唯一的密钥读取出口）
   - **载荷格式（v2，口令保护）**：`miyu-keybackup:2:<saltB64url>:<ivB64url>:<ctB64url>`
     （5 段，见 `KeyTransferPayload` 的协议注释）。编码侧只用 base64url 无填充，正文不含 `+ / =`。
     - 明文（加密前）：`0x01 | pubLen(1) | pub(X509 SPKI) | priv(PKCS8)`，实测 160 字节。
     - 派生：`PBKDF2-HMAC-SHA256(口令, 16 字节随机盐, 210000 次, 256 位)`（迭代数与 `KeyBackup` v2 对齐）。
     - 加密/认证：`AES/GCM/NoPadding`，128 位标签；salt/iv/ct 任一位被改都会在认证时失败。
     - 口令策略：**最短 8 位，不强制更长**；弹窗建议 12 位以上混合大小写数字。
       口令不落盘、不进 Intent、不打日志，只存在于导出页/导入页的输入框内存中（`char[]`，用完清零）。
   - **⚠️ 破坏性变更：v1 明文二维码（`miyu-keybackup:1:...`）已作废、不可导入。**
     v1 的唯一"完整性"字段 `fp8` 是无密钥校验和，攻击者可自造一致载荷，只防损坏不防伪造。
     旧码一律 `LEGACY_FORMAT`，提示「这是旧版本 App 导出的二维码。请用当前版本重新导出一张」；
     **不做降级兼容**——降级等于让明文私钥这条攻击面永久存活。
     升级后需**在两台设备上都用当前版本重新导出/导入一次**。
   - 安全设计：导出页顶部常驻不可关闭警告、**进入即要求设置迁移口令**（无"跳过口令"出口）、
     二维码下方显示**口令末 4 位**（仅供用户在新设备上核对输入）、60 秒倒计时自动隐藏、
     可手动立即隐藏、不做"复制私钥"按钮。
   - **`FLAG_SECURE`（H4 修正）**：密钥导出/导入/重置三页都设置了 `FLAG_SECURE`，
     用于**禁止系统截图与最近任务缩略图**。
     ⚠️ 校正一处旧表述：早先这里写「`onStop` 立即抹除（切后台/锁屏不留残影）」——
     不准确。最近任务的缩略图由**系统在 stop 转换期**抓取，`onStop` 里
     `ivQr.setImageDrawable(null)` 来不及阻止它；真正起作用的是 `FLAG_SECURE`。
     `onStop` 仍然保留（停倒计时 + 清空 ImageView），但它只是**纵深防御**，
     不承担"抹除快照"的承诺。
     ⚠️ 副作用（属预期）：这三页**无法截图/录屏**，需在验收时知悉，别当成 bug 上报。
   - **私钥出口唯一**：只有 `KeyQrExportActivity` 读私钥；`KeyManagementActivity`
     维持零私钥访问（grep 验证），便于审计。
   - 相册保存：**已移除（M1）**。原实现（API 29+ 走 MediaStore、API 24~28 申请
     `WRITE_EXTERNAL_STORAGE`）会把含私钥的 PNG 写进共享媒体 `Pictures/密语密钥`，
     任何有存储权限的应用与云相册同步都可读，且 60 秒自动隐藏对它无效、永不清理。
     现改为「分享二维码图片」：PNG 先写到本应用私有缓存 `cacheDir/qr_share/`，
     经 `FileProvider`（`file_provider_paths.xml` 的 `<cache-path>` 已覆盖）以临时读授权
     交给系统分享面板 → 用户选定的应用。本应用不留存、不进入共享媒体。
     清理：`onDestroy` 删除本次临时文件；每次打开本页清理超过 1 小时的残留
     （防"进程被杀导致 onDestroy 不执行"）。
     ⚠️ 诚实边界：图片一旦交给第三方应用（微信/文件管理器等），对方是否保存、是否上云
     由对方决定，本应用无法控制 —— 所以分享前有一次明确的知情确认。
     ⚠️ **不删 `WRITE_EXTERNAL_STORAGE` 声明**：`PayCodeActivity` 在 Android 9-
     写共享媒体仍依赖它（`maxSdkVersion=28`），该权限与密钥导出已无任何关系。
   - **容量实测结论（zxing 3.5.3 + 真实 P-256 密钥 91/67 字节）**：
     | 项 | v1（明文，已作废） | **v2（当前）** |
     | --- | --- | --- |
     | SPKI / PKCS8 原始字节 | 91 / 67 | 91 / 67 |
     | pub / priv 字段长度 | 122 / 90 | —（已合并进密文） |
     | salt / iv / ct 字段长度 | — | 22 / 16 / 235 |
     | 载荷总长 | 239 字符 | **292 字符** |
     | QR 版本（纠错级别 M） | 版本 11，61×61 模块 | **版本 13，69×69 模块** |
     | 960px 渲染下每模块像素 | 约 15.7 px | 约 13.9 px（充裕） |
     | encode / decode 耗时（JDK，21 万次 KDF） | — | 约 242 ms / 126 ms（中端真机约 0.5~1 s） |

     ⚠️ v2 **比 v1 更长**：salt(22) + iv(16) + 认证标签(16 字节→22 字符) 净增约 44 字符，
     只被"v1 两个 base64url 字段各自的填充浪费消失"抵消一小部分。
     设计阶段曾估 v2 约 180 字符，实测 292 —— 该估算已作废，以本表为准。
     该结论固化为 `KeyTransferPayloadTest.payloadLength_isWithinQrVersion40Capacity`
     （上界 400，刻意不贴着实测值 292 写，避免变成新的间歇性红灯）与
     `payload_isEncodableAsQrAtErrorCorrectionM`（断言版本 <= 20 且每模块 >= 3px）。
   - ⚠️ **仍需真机实测扫码成功率**：能生成 ≠ 两台真实设备间扫得动（v2 模块数比 v1 多，
     尤其需要实测）。建议在旧手机（暗屏/低亮度）与新手机之间各试 10 次，记录成功率。

3. ✅ 扫码导入（`KeyQrImportActivity`，唯一的密钥**写入**入口）
   - **四重校验**：段数/前缀/版本 → salt/iv/ct 可解（长度合法）→ **GCM 认证** → 密钥格式
     （X509/PKCS8 EC P-256）+ 公私钥配对。
   - **口令错误与"二维码被改动"在密码学上不可区分**（都是认证标签失败），
     因此 `DECRYPT_FAILED` 的提示同时覆盖两种可能：核对口令末 4 位 / 回旧设备重新导出。
     口令错误时**停留在页面上允许重试**，不要求重扫。
   - **版本提示按"码旧"与"码新"分开**：
     - 扫到旧版（v1）码 → 「这是旧版本 App 导出的二维码。请用当前版本重新导出一张。」
     - 扫到更新版（v3+）码 → 「这是更新版本导出的二维码。请把你的 App 也升级到同一版本。」
     两者处置动作相反，混成一句会让用户去升级已经是最新的设备却依然扫不动。
   - 页面**常驻**版本提示：「如对方设备版本较旧，请先让它升级到同一版本再扫码。」
   - **配对判定用 ECDSA 自验签**：ECDH 无法判定配对（任意私钥 + 任意公钥都能协商出
     结果，不配对只是结果不同而**不报错**）；JDK 的 `java.security.spec.ECPoint`
     也没有点乘（那是 BouncyCastle 的 API）。故用私钥签一段固定常量、再用公钥验签。
   - 「本地已有密钥」策略：**指纹一致 → 提示"无需导入"且不写入任何内容**（换机时
     最常见的操作是重复扫同一张码，若每次都弹确认，用户会养成一路点确认的习惯）；
     不一致 → 双指纹并排 + 强确认后才覆盖，覆盖前旧密钥留在内存、上报失败可一键撤销。
   - **导入成功后同步服务端密码备份（H3 闭环）**：导入并上报公钥成功后，先查服务端备份状态，
     - 服务端**确认没有**备份 → 明确告知"无需同步"；
     - 有备份 → 要求输入**登录密码**（与迁移口令是两把不同的口令），用它
       `KeyBackup.encryptBackup` 重新加密**刚导入的密钥对**并覆盖，彻底避免
       "清数据重新登录 → 旧备份把旧密钥解回来 → 导入被静默撤销"；
     - **无法确认**（网络/HTTP 失败）→ 不猜测，如实警告"本次导入可能被旧备份覆盖"。
     与重置路径（第 5 步）现在使用同一套三态判定（`fetchKeyBackupState()`）。
     跳过同步时会明确写出后果（"清除数据/换机重登会恢复旧密钥，本次导入白做"）。
   - 权限：相机运行时申请（拒绝后给明确提示）+ 相册选图兜底（无相机权限也能用）。
4. ✅ 重置密钥（`KeyResetActivity`）
   - 两次确认，第二次必须逐字输入「我确认」；确认按钮初始不可点（`TextWatcher` 控制）。
   - 生成新钥 → 校验新旧指纹确实不同（防"重置成同一把"白烧数据）→ 写本地 → 上报公钥。
   - 上报公钥失败会明确告知，并说明"在聊天页发一条消息会自动重试"。
5. ✅ 重置时同步服务端密码加密备份
   - 用登录密码 `KeyBackup.encryptBackup` 加密**新**密钥对 → `uploadKeyBackup` 覆盖。
   - **正确性预检**：先取服务端现有备份用该密码试解一次；解不开（密码错）则不上传，
     避免用错误密码加密新备份、把唯一可恢复的旧备份也毁掉。
   - 用户可选择跳过，但会明确警告"重置可能被旧备份静默撤销"。

**密钥读写入口分布（审计用）**：

| 入口 | 文件 | 说明 |
| --- | --- | --- |
| 读私钥 | `KeyQrExportActivity` | 唯一读取点 |
| 写密钥 | `KeyQrImportActivity` / `KeyResetActivity` | 导入与重置 |
| 只读、零私钥访问 | `KeyManagementActivity` | 指纹展示与入口聚合 |

**已知遗留缺口（本次未处理，需评估）**：
- ✅ **已闭环（H3）**：导入路径现在也会刷新服务端密码备份（见第 3 点）。
  原先的缺口——"换机导入后清数据重登会用旧备份覆盖新密钥"——已修复。
- ⚠️ **扫码成功率仍需真机实测**：能生成 ≠ 两台真实设备间扫得动（v2 载荷模块数多于 v1）。
  建议在旧手机（屏幕较暗/低亮度）与新手机之间各试 10 次并记录成功率。
- 各 Activity 的分支逻辑（成功/失败/撤销等）已通过编译与纯 JVM 单测覆盖到载荷层，
  但**权限拒绝、覆盖确认、重置二次确认等 UI 流程需真机点一遍**。

### 7.2 发布记录

| 项 | 值 |
| --- | --- |
| 版本 | 1.0.128（versionCode 128） |
| 发布时间 | 2026-09-18 |
| APK | `release/密语.apk`，51,580,649 bytes |
| SHA-256 | `b7cfba2acbf4b18fb45846210aae0e1856bd27267566a12cfea33205e8e95310` |
| 签名证书 SHA-256 | `c60ace246e5985f61d9a9ce0d90bec7009392169843134c6b4bcfc456fec4fd5`（与 1.0.126/1.0.127 同一密钥，可覆盖安装） |

上一版记录（v1.0.127，仅指纹）：APK 51,555,896 bytes，SHA-256
`85d33a34296017d75113eff8c716d8c513d735f6259269e52f7505e27b6a7564`。

回滚（如需）：`release/` 下有 `密语.apk.pre127-bak`、`version.properties.pre127-bak`
以及 `密语.apk.pre128-bak`、`version.properties.pre128-bak`。
回滚时**先还原 apk、再还原 version.properties**，保证 apk 修改时间不早于版本文件。

回滚（如需）：`release/` 下有 `密语.apk.pre127-bak` 与 `version.properties.pre127-bak`。
回滚时**先还原 apk、再还原 version.properties**，保证 apk 修改时间不早于版本文件。

### 7.3 后续版本发布检查清单（第 1~5 步已完成，供下次改动本功能时使用）

**本功能的测试覆盖（改动前后都应跑）**：

```powershell
# 1) 客户端纯 JVM 单测（载荷编解码/指纹/配对判定，31 条）
pwsh -File scripts\run-unit-tests.ps1

# 2) 密钥链路后端集成测试（公钥上报 + 密码加密备份，33 项；退出码 0=全通过）
pwsh -File scripts\e2e-key-transfer.ps1
pwsh -File scripts\e2e-key-transfer.ps1 -BaseUrl http://<示例IP>:5000

# 3) 硬门禁：KeyManagementActivity 必须零匹配
grep -n "getMyPrivateKey\|generateKeyPair\|saveKeyPair\|uploadPublicKey" `
  client/android/app/src/main/java/com/familychat/app/ui/KeyManagementActivity.java

# 4) 硬门禁（M1）：密钥相关页面不得再往共享媒体写东西
#    （Key* 页面里唯一允许的 MediaStore 用法是导入页"从相册选图"的 ACTION_PICK 读取）
grep -n "MediaStore" client/android/app/src/main/java/com/familychat/app/ui/Key*.java
#    期望：只有 KeyQrImportActivity 的 ACTION_PICK 一处；KeyQrExportActivity 零命中
grep -n "EXTERNAL_CONTENT_URI\|ContentValues\|RELATIVE_PATH\|DIRECTORY_PICTURES" `
  client/android/app/src/main/java/com/familychat/app/ui/KeyQrExportActivity.java
#    期望：零命中
```

`e2e-key-transfer.ps1` 的密码学计算**不引用 App 代码**（密钥对由 .NET ECDsa 生成，
备份加解密由 `scripts/tools/KeyBackupTool.java` 用纯 JDK 实现），
所以它能真正验证"服务端存的密文解得开/解不开"，而不是拿同一个实现自证。
其中 `PW-1/PW-2` 两项专门证明第 5 步"密码预检失败就不上传"这一决策的必要性。

**脚本覆盖不到、必须真机手工验收的**：
- 两台设备之间的实际**扫码成功率**（能生成 ≠ 扫得动；v2 模块数多于 v1，建议各试 10 次）
- 相机权限拒绝流程、相册兜底
- 覆盖确认框、重置两次确认（含手动输入「我确认」）
- **口令路径**：导出时设置迁移口令 → 导入端**输错口令**必须提示"口令不正确或二维码被改动"
  且允许重试（不要求重扫）；输对口令必须成功导入
- **v1 旧码拒绝**：用旧版本导出的 `miyu-keybackup:1:` 码扫描 → 必须提示
  「这是旧版本 App 导出的二维码。请用当前版本重新导出一张。」
- **H2 网络失败分支**：飞行模式下重置密钥 → 必须弹「无法确认服务端备份状态」，
  且服务端备份**不被改动**；恢复网络后点「重试同步」必须成功
  （此分支无自动化覆盖：本机离线无 mockwebserver，见 §7.3 说明）
- 核心黄金路径：A 导出（设口令）→ B 清数据 → B 扫码导入（输口令）→ B 能解开历史消息
- **H3 回归**：B 导入成功并按提示用**登录密码**同步备份后 → B 再"清数据 + 重新登录" →
  指纹必须仍是导入进来的那把（若跳过同步，则会被旧备份恢复成旧密钥 —— 预期行为）
- 重置回归：重置 → 退出登录 → 用同一密码重新登录 → 指纹应保持新值

- [ ] 只改 `release/version.properties` 一处（versionCode、versionName、changelog）
      ——服务端 `/version.json` 与 APK 构建共用此文件（`app/build.gradle:5`）
- [ ] ⚠️ 顺序要求：先备好新 APK，再改版本号。`version.properties` 比 `密语.apk` 新时，
      `/version.json` 会返回 `published=false`（发布窗口保护，`FileHandler.java:638`）
- [ ] `./gradlew :app:assembleRelease --no-daemon`
      （`--no-daemon` 非必需但更稳：长期存活的守护进程会撞 `debug.keystore.lock` 的
      `AccessDeniedException`，与代码无关）
- [ ] 替换 `release/密语.apk`（服务端 `/apk` 取包路径，`FileHandler.java:82`；
      服务端**无需重启**，该文件与 version.properties 都是按请求实时读取）
- [ ] `curl -s localhost:5000/version.json` → versionCode/versionName 是新的且 `published:true`
- [ ] 下载一次 `/apk` 校验 SHA-256 与声明一致（客户端会做这重校验）
- [ ] 真机回归：旧版本 → 检查更新 → 下载 → SHA-256 与签名校验通过 → 安装成功
- [ ] ⚠️ **UI 改动必须用 release 变体回归**：debug 与 release 签名不同，
      debug 包无法覆盖已装正式版（自测时需先卸载或换设备）
- [ ] 密钥管理专项回归：清数据后不进任何聊天，反复进出密钥管理页，
      确认服务端公钥未被写入（证明只读页面没有副作用）
