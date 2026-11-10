> 🌐 [English](README.md) | **中文**

# 密语（Miyu）— 自托管家庭聊天 App

一个跑在自己家里的完整聊天系统：Java 服务端 + Android 客户端，支持端到端加密私聊/群聊、
语音视频通话、朋友圈式短视频，以及自建的支付/商城闭环。

> **为什么会有这个项目**：把家人从第三方 IM 搬到自己的服务器上，消息不出自家局域网。

> **加密边界**：端到端加密覆盖单聊/群聊的消息；**群文件、群相册、群投票为明文存储**，不属于端到端加密范围，仅通过群成员鉴权保护。

---

## 一、30 秒了解项目

| | |
|---|---|
| 客户端 | Android，Java，`minSdk 24` / `targetSdk 34`，6 个 Gradle 模块 |
| 服务端 | Java 17+，Javalin + SQLite（WAL），单 JAR 可跑 |
| 代码量 | Java 约 3.5 万行（服务端约 1 万 / 客户端约 2.5 万） |
| 接口 | 128 条 REST 路由 + WebSocket；29 张表 |
| 加密 | ECDH(P-256) + AES-256-GCM，私钥密码加密备份 |
| 部署 | 局域网 HTTP，或 Cloudflare Tunnel 暴露到公网（HTTPS） |

---

## 二、目录结构

```
liaotian/                     仓库根
├── client/android/           Android 客户端（多模块）
│   ├── core/                 网络(ApiClient/Models/WebSocket) + 基础工具
│   ├── ui-common/            设计系统：颜色/主题/通用控件（资源下沉层）
│   ├── feature-wallet/       钱包：转账/收款/红包/账单
│   ├── feature-mall/         商城：商品/购物车/订单/退款/地址/评价
│   ├── feature-chat/         聊天：会话/E2EE/语音视频(WebRTC)/短视频(Media3)
│   ├── feature-group/        群聊：群管理/公告/文件/相册/投票
│   └── app/                  壳工程：登录/主界面/设置/更新器
├── server/                   服务端（Javalin + SQLite）
│   └── src/main/java/com/familychat/server/
│       ├── FamilyChatServer.java   入口：注册所有路由
│       ├── handler/                各业务接口（Auth/Message/Group/Wallet/Mall/File…）
│       ├── model/                  Database / DAO / DbUtil
│       ├── ws/                     WebSocket（在线状态、消息推送）
│       └── util/                   鉴权、限流、配置
├── release/                  发布产物 + 签名密钥（密钥不入库）
├── scripts/                  构建/发布/测试脚本 + 启动入口（start-server.bat / 启动隧道.bat）
├── deploy/                   部署配置（systemd / nginx / Cloudflare Tunnel）
└── docs/                     文档中心：guides/ 现行手册 + audits/deploy/testing 历史档案
```

**模块依赖是单向的**，不存在循环：

```
app ──► feature-* ──► ui-common ──► core
                    └──────────────► core
```

> 规则：功能模块之间**不互相依赖**；要共享的东西下沉到 `core`（逻辑）或 `ui-common`（资源/UI）。
> 这条规则曾经被破坏过（`bg_transfer_card` 画在 `app` 里却被 `feature-chat` 引用），
> 症状是 release 构建报 `resource not found`，详见 `docs/guides/RELEASE.md`。

---

## 三、本地跑起来

### 0. 日常启动（最快）

> 进入 `scripts/` 后双击 `start-server.bat`；公网隧道用 `启动隧道.bat`。两者路径已绝对定位，双击即可运行。

### 1. 环境要求

| 组件 | 版本 | 说明 |
|---|---|---|
| JDK | **17 或 21** | ⚠️ JDK 24 能编 APK，但有已知坑（见下文"踩坑"） |
| Android SDK | platform 34 + build-tools 34.0.0 | 设 `ANDROID_HOME`，或写 `local.properties` |
| PowerShell | **7+（pwsh）** | 跑单元测试脚本必需；Windows PowerShell 5.1 会给出假成功 |
| Gradle | 用仓库自带 `gradlew` | 已升到 8.14.3 |

### 2. 启动服务端

```powershell
cd server
.\gradlew.bat shadowJar            # 产出 build\libs\familychat-server.jar
cd ..
.\scripts\start-server.bat          # 默认监听 5000，控制台会打印局域网地址
```

验证：

```
http://<服务器IP>:5000/version.json      # 更新接口
http://<服务器IP>:5000/download          # APK 下载页
```

### 3. 编译并安装客户端

```powershell
# 首次需要配置发布签名（复制模板后填密码）
copy release\keystore.properties.example release\keystore.properties

cd client\android
.\gradlew.bat assembleRelease
# 产物：app\build\outputs\apk\release\app-release.apk
```

> ⚠️ **不要用 `assembleDebug` 出货**。调试签名是全世界公开的证书 + 带 `debuggable`，
> 会被安全软件判为"危险捆绑软件"。理由与完整流程见 `docs/guides/RELEASE.md`。

### 4. 客户端里填服务器地址

登录页"服务器地址"填 `192.168.x.x:5000`（手机与服务器同一局域网）。

> 明文 HTTP 只对 `network_security_config.xml` 里白名单的地址开放。
> 换网络后如果报 `CLEARTEXT communication to ... not permitted`，
> 把新 IP 加进该文件重新出包即可。

---

## 四、跑测试

**客户端单元测试**（纯 JVM，不需要模拟器/真机）：

```powershell
# 推荐：Gradle 原生任务（工程已在 ASCII 路径，可正常使用）
cd client\android
.\gradlew.bat testDebugUnitTest

# 可选：更详细的逐模块汇总 + 关键用例存在性检查
pwsh -File scripts\run-unit-tests.ps1
```

当前覆盖 52 个用例，集中在最不能出错的三块：

| 测试 | 覆盖内容 |
|---|---|
| `MessageEncryptorTest` | E2EE 往返、跨用户、错误密钥必须失败、密文篡改被 GCM 检出、IV 随机性、中文/emoji/100KB 边界 |
| `KeyBackupTest` | 私钥备份往返、密码错必须返回 null、v1 旧格式兼容、随机盐、篡改拒绝 |
| `GsonContractTest` | 请求体/响应模型的 JSON 键名契约 + **结构性护栏**：Gson 类字段必须有 `@SerializedName` |
| `ServerProtocolTest` | 局域网/公网协议判定、URL token 追加幂等 |

**服务端测试**：

```powershell
cd server
.\gradlew.bat test
```

---

## 五、发布前必做：跑一次体检

```powershell
scripts\publish-check.bat
```

7 项硬校验，任何一项不过就**不要发布**：

1. 签名必须是正式证书（不是 Android 调试证书）
2. 不含 `android:debuggable`
3. 无"忽略电池优化"等敏感权限
4. Gson 关键字段名没被 R8 混淆掉（否则接口返回空体 400）
5. APK 版本号 = `version.properties`，且不比它旧
6. 客户端单元测试全绿
7. 流水线脚本编码/版本守卫

完整发版流程见 [`docs/guides/RELEASE.md`](docs/guides/RELEASE.md)。

---

## 六、踩过的坑（写给未来的自己）

这些都是真实事故，不是假设：

| 症状 | 根因 | 对策 |
|---|---|---|
| 被杀软报"危险捆绑软件" | 30 多个版本全部用调试签名出货 | 正式 keystore + `assembleRelease`；`publish-check` 硬校验 |
| 登录返回 **400 且响应体为空** | R8 把 `username` 混淆成 `a`，服务端反序列化失败 | 字段加 `@SerializedName` + keep 规则 + 契约测试 |
| 升级后账号/E2EE 私钥丢失 | `PreferencesManager.Account` 被混淆，读不出旧存档 | 同上，且注解值必须等于原字段名 |
| 登录后闪退 | 该工程从未在 R8 混淆下跑过，一开就崩 | 暂关 `minifyEnabled`；要重开需先补齐 keep 并真机回归 |
| release 编译报 `resource not found` | `app` 的资源被 `feature-chat` 引用（反向依赖） | 共享资源下沉到 `ui-common` |
| **单元测试在本机永远 `ClassNotFoundException`** | 工程路径含中文，JDK 读 Gradle 的 `@argfile` 时用 `sun.jnu.encoding=GBK`，而 Gradle 按 UTF-8 写 | **已根治：工程迁到纯 ASCII 路径** |
| .bat 报"不是内部或外部命令" | cmd 按 GBK 解析 `.bat`，UTF-8 中文被读坏（`0x85` 还是续行符） | `.bat` 带 UTF-8 BOM 或纯 ASCII；写成守卫脚本自动检查 |
| PS 脚本"0 个测试全部通过" | PowerShell 5.1 下脚本静默失效 | 脚本内加 PS 7 版本守卫，宁可报错也不给假成功 |

### 关于工程路径（已解决）

> **本工程原先位于 `<本地路径>`，中文路径引发过一连串工具链问题，
> 现已迁移到纯 ASCII 路径 `<本地路径>`。**

迁移带来的实际改善：

| 问题 | 中文路径下 | ASCII 路径下 |
|---|---|---|
| `gradlew testDebugUnitTest` | **必然失败**（`ClassNotFoundException`，编译却是成功的） | ✅ 直接可用，实测 52 用例通过 |
| `aapt` / `apktool` 分析 APK | 直接报 `Illegal byte sequence` | ✅ 正常 |
| Gradle `@argfile` | classpath 被读成乱码 | ✅ 无此问题 |
| `.bat` 里写中文 | 需 UTF-8 BOM 才能被 cmd 正确解析 | 同样建议带 BOM（与路径无关） |

**根因说明**（值得记住）：Gradle 在命令行过长时会把 classpath 写进 `@argfile`，
写的是 UTF-8；而 JDK 按 `sun.jnu.encoding`（中文 Windows 下是 GBK）去读它。
两边编码不一致，含中文的路径就变成乱码，表现为测试类"找不到"。
已实测：同一个 argfile 用 GBK 写就能跑通——所以这是**路径**问题，不是代码问题。

因此：

- **推荐**：直接用 Gradle 原生任务 `gradlew testDebugUnitTest`（或 `gradlew test`）。
- `scripts\run-unit-tests.ps1` 仍然保留，它额外提供逐模块汇总、关键用例存在性检查，
  并且**能提前发现"新旧 jar 抢占"这类坑**。两者都可以用。

---

## 七、相关文档

- [`docs/guides/RELEASE.md`](docs/guides/RELEASE.md) — 发版步骤、更新机制原理、红线与踩坑清单
- `docs/guides/` — 其余现行手册：`ARCHITECTURE.md`、`DEPLOY.md`、`RELEASE-RULES.md`
- [`release/签名密钥备份说明.md`](release/签名密钥备份说明.md) — 签名密钥备份（**务必照做**）
- `docs/audits/apk-av/报毒原因分析报告.md` — 报毒事件的完整取证分析
- [`docs/`](docs/README.md) — 文档中心索引（guides 现行手册 + audits/deploy/testing 历史档案）
