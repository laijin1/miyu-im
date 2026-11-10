# 架构说明（密语）

本文解释**为什么这样设计**，以及各关键链路的完整数据流。配合 `README.md`（怎么跑）阅读。

---

## 一、整体形态

```
┌─────────────────────── Android 客户端 ───────────────────────┐
│  app          登录/主界面/设置/更新器                          │
│  feature-group 群聊：群管理/公告/文件/相册/投票                 │
│  feature-chat  聊天：会话/E2EE/WebRTC 通话/短视频               │
│  feature-mall  商城：商品/购物车/订单/退款/地址/评价            │
│  feature-wallet 钱包：转账/收款/红包/账单                       │
│  ui-common     设计系统（颜色/主题/通用控件）                    │
│  core          ApiClient / Models / WebSocket / 基础工具        │
└───────────────┬──────────────────────────────┬────────────────┘
        REST(HTTP/HTTPS)                 WebSocket(WS/WSS)
                │                              │
┌───────────────▼──────────────────────────────▼────────────────┐
│  server  Javalin + Jetty                                       │
│  handler/  Auth Message Group Mall Wallet File Ai …            │
│  ws/       在线状态、消息/钱包实时推送                          │
│  model/    Database(SQLite, WAL) + DAO                         │
└────────────────────────────────────────────────────────────────┘
```

**服务端是有状态的单实例**：SQLite 文件 + 内存中的 WebSocket 连接表。
没有 Redis/MQ，适合家庭规模（几个人到几十人）。

---

## 二、端到端加密（E2EE）

### 2.1 密钥体系

| 密钥 | 算法 | 存放位置 |
|---|---|---|
| 身份密钥对 | ECDH P-256（secp256r1） | 客户端本地（EncryptedSharedPreferences） |
| 公钥 | X509 编码 Base64 | 上传服务端 `public_keys` 表（供他人取用） |
| 私钥 | PKCS8 编码 Base64 | **只存本地**；另做密码加密备份上传 |

### 2.2 单聊消息加解密

```
发送方 Alice：
  共享密钥 = ECDH(Alice私钥, Bob公钥)            ← 纯本地计算
  AES密钥  = SHA-256(共享密钥)                    ← 无需 KDF 迭代，因为 ECDH 输出已是高熵
  密文     = AES-256-GCM(AES密钥, IV随机12字节, 明文)
  上线格式 = Base64(IV ‖ 密文‖GCM tag)

接收方 Bob：
  共享密钥 = ECDH(Bob私钥, Alice公钥)             ← 与发送方计算结果相同（ECDH 对称）
  明文     = AES-GCM 解密
```

**设计要点**：
- 用 **GCM** 而非 CBC：自带完整性校验，密文被改动会解密失败而不是解出乱码。
- 每次加密用**随机 IV**：同一明文两次密文不同（`MessageEncryptorTest` 有断言锁定该行为）。
- 服务端全程只见到 Base64 密文，没有私钥无法解密。

### 2.3 群聊：逐成员加密，不用共享群密钥

```
GroupEncryptHelper.encryptForGroup(明文, 我的id, 成员id列表)：
  out = { 我的id: 用我自己公钥加密的密文,          ← 为了自己重进群还能看历史
          成员A: 用A的公钥加密的密文,
          成员B: 用B的公钥加密的密文, … }
  存进消息 content（一个 JSON 对象）
```

- **不用共享群密钥**：新成员进群无法回看历史（拿不到旧密钥）；退群成员也无法再解新消息。
  代价是每条群消息的密文体积 = 成员数 × 单份长度，家庭群规模下可接受。
- **自己也要加密一份**：否则重进群后自己的历史消息会显示"无法解密"。
- 取不到某人公钥时**跳过该成员并记录**，绝不回退成明文发送。

> **边界说明（重要）**：本节的端到端加密只覆盖聊天消息；**群文件、群相册、群投票为明文存储**，不属于端到端加密范围，仅通过群成员鉴权保护（服务端与数据库可见其内容）。

### 2.4 私钥备份（换手机/重装后找回历史）

`KeyBackup` 把私钥用**登录密码派生的钥匙**加密后上传，服务端只存密文。

```
备份格式 v2：
  0x02 ‖ saltLen(1B) ‖ 随机盐(16B) ‖ IV(12B) ‖ AES-GCM密文
  密钥 = PBKDF2-HmacSHA1(密码, 固定前缀+userId ‖ 随机盐, 210000 次)
```

设计要点：
- **随机盐 + 21 万次迭代**（v1 是固定盐 + 5 万次）：同账号多次备份密文互不相同，
  离线爆破成本显著提高。v1 格式保留**只读兼容**，老用户的旧备份仍能解开。
- **PBKDF2WithHmacSHA1 而非 SHA256**：后者在 API 26 以下不可用，而备份必须跨设备/跨系统版本可解密。
- **失败必须返回 null**，绝不能返回垃圾数据——否则会用垃圾覆盖本地密钥，比丢失更糟。

### 2.5 登录时的密钥处理顺序（防"一次网络抖动废掉全部历史"）

`LoginActivity.ensureLoginKeys()` 的顺序是刻意的：

```
1. 本地已有私钥 → 直接用
2. 本地没有 → 从账号快照恢复（重登不换密钥，避免覆盖服务器上的公钥）
3. 快照也没有 → 从服务端取密码加密的备份，用密码解回
4. 服务器没有备份 → 新用户，生成新密钥
5. 备份存在但解密失败 → 弹窗让用户知情，绝不静默换新钥
```

> ⚠️ 关键约束：**网络失败时不允许生成新密钥**。
> 因为无法区分"服务器没有备份"和"网络请求失败"，一旦误判就会上传新公钥、
> 覆盖服务器上的旧公钥，导致历史消息永久无法解密。

---

## 三、鉴权与账号

- 密码：**bcrypt**（`jbcrypt`）；老库里的 SHA-256 密码在下次登录成功时**透明升级**为 bcrypt。
- 支付密码：单独一套，bcrypt + 随机盐 + 慢哈希，与登录密码分离。
- 会话：JWT（`HMAC`），密钥来自环境变量 `JWT_SECRET`，未设置则用 `SecureRandom` 生成并落盘
  `data/jwt_secret.key`，启动时打印指纹便于排查。
- JWT 带 `tv`（token version）声明：改密码/强制下线时递增，旧 token 立即失效。
- 多账号：客户端用 `PreferencesManager.Account` 列表持久化每个账号的
  token/服务器地址/E2EE 密钥，支持切换。

> 🔐 **实现红线**：`Account` 的字段用 `@SerializedName("原字段名")` 锁定 JSON 键名。
> 一旦被混淆改名，升级后读不出旧存档 → token 与 E2EE 私钥一起丢失。
> `GsonContractTest` 有结构性断言防止回归。

---

## 四、更新机制（自建应用分发）

```
App 启动/手动检查
  GET /version.json → { versionCode, versionName, changelog, downloadUrl, apkSha256, published }
  比对 versionCode > 本机 → 提示更新
  GET /apk          → 下载（支持 Range 断点续传）
  校验① SHA-256 等于 version.json 声明值      ← 防损坏/篡改
  校验② 读 APK 真实 versionCode 等于服务器版本  ← 防装到旧包
  校验③ 新包签名证书 ∈ { 本机证书, 内置发布证书 } ← 防被换成第三方改包
  安装：公共下载目录 → PackageInstaller 会话 → FileProvider（三级回退）
```

**发布窗口保护**（服务端）：`version.properties` 比 APK 文件新时（版本号已升、新包未就位），
`/version.json` 返回 `published=false`、`/apk` 返回 404——客户端与网页都拿不到与版本号不符的旧包。

**单一版本来源**：`release/version.properties`。APK 的 versionCode/versionName（构建时读取）
和服务端 `/version.json`（运行时读取）都来自它，**改版本号只改这一个文件**。

**签名校验的意义**：SHA-256 只能证明"没被改坏"，证明不了"是谁签的"。
更新下载是"应用内安装 APK"，正是恶意替换的高危入口，所以两者都要校验。

---

## 五、数据库（29 张表）

| 分类 | 表 |
|---|---|
| 用户与社交 | `users` `friendships` `public_keys` `key_backups` |
| 单聊 | `messages` |
| 群聊 | `groups` `group_members` `group_settings` `group_messages` `group_message_reads` |
| 群内容 | `group_announcements` `group_announcement_reads` `group_files` `group_albums` `group_album_photos` `group_polls` `group_poll_options` `group_poll_votes` |
| 资金 | `wallets` `transactions` `charge_requests` |
| 商城 | `products` `categories` `mall_cart` `mall_orders` `mall_addresses` `mall_favorites` `mall_reviews` `hot_searches` |

工程实践：
- SQLite 开启 **WAL**，设 `busy_timeout` 应对并发写。
- 启动时做**幂等迁移**（`ALTER TABLE ADD COLUMN` 前先判断列是否存在），老库可平滑升级。
- `is_online` 存 INTEGER(0/1)，但序列化时统一转 boolean（`DbUtil`），与客户端模型对齐。

---

## 六、已知的结构性债务

诚实记录，避免下次又踩：

| 问题 | 影响 | 建议 |
|---|---|---|
| `ChatActivity` **2592 行**：本体约 1845 行 + 内嵌 `MessageAdapter` 约 570 行 + `VH` 约 170 行；`onCreate` 单方法 **557 行** | 一个文件装了两个类，`onCreate` 把 UI 初始化、适配器装配、权限、面板、轮询全部塞在一起 | **详见下方"ChatActivity 拆分方案"**——不要靠感觉拆 |
| `GroupChatActivity` 1897 行 / `MainActivity` 1622 行 | 同类问题 | 同方案，逐个来 |
| 客户端测试只覆盖纯逻辑（加密/契约/协议） | UI 与网络层无自动化回归 | 补齐测试后才好动这些巨型类 |
| `minifyEnabled false` | 无代码混淆、APK 略大 | 想开启必须先补齐 keep 规则并真机全量回归 |
| 工程路径含中文 | 一连串工具链问题（见 README） | 移到纯 ASCII 路径可根治 |
| 服务端单实例 + SQLite | 无法水平扩展 | 家庭规模够用，不必改 |

### ChatActivity 拆分方案（为什么没有直接拆）

审计结果：

```
L67    class ChatActivity            本体 ≈ 1845 行（onCreate 单方法 557 行）
L1869  static final class TimeText    时间格式化（已可独立）
L1892  class MessageAdapter           ≈ 570 行，消息列表适配器
L1898  static class Row               行模型/重建逻辑
L2367  class VH                       ≈ 170 行，ViewHolder（语音气泡是代码里手搭的）
```

**为什么不能直接搬 `MessageAdapter` 出去**：它是**非静态内部类**，并且有 **23 处
`ChatActivity.this`** 引用（读活动字段、调 `showMessageMenu`、`playVoiceLocal` 等）。
Java 不允许把内部类原样搬到另一个文件——必须改成 `static` 并显式传入宿主引用，
也就是要把这 23 处隐式引用全部重写。在**没有任何 UI 测试**的情况下做这种机械改写，
正是这个项目反复吃亏的模式（改一处、崩另一处）。

因此本轮的处置是：**先补安全网 + 写清方案，不盲拆**。建议的落地顺序：

1. 先为 UI 层补最基础的回归能力（Robolectric：能否 inflate、适配器能否绑定一屏消息）；
2. 把 `TimeText`（已独立）先移到 `util/`——零风险，可作为热身；
3. 把 `MessageAdapter` + `VH` + `Row` 移到 `MessageAdapter.java`，同时：
   - 改为 `static`，构造时传入 `ChatActivity host`（或抽一个 `Host` 接口只暴露它需要的成员）；
   - 23 处 `ChatActivity.this.xxx` → `host.xxx`，逐处对照，不做"顺手优化"；
   - 每移完一个方法就编译 + 跑测试，不要一次性改完再编。
4. 最后拆 `onCreate`：按"UI 初始化 / 适配器装配 / 权限 / 面板 / 轮询"分成若干个
   `initXxx()`，每个只做一件事。这一步纯移动代码、不改逻辑，风险最低但收益最大。

> 参考：`GroupChatActivity` 结构与 `ChatActivity` 高度相似，方案可复制。

---

## 七、安全清单（当前状态）

- [x] 密码 bcrypt + 老哈希透明升级
- [x] 支付密码独立慢哈希
- [x] 消息端到端加密（GCM 认证加密）
- [x] 私钥仅存本地，备份为密码派生密钥加密
- [x] 更新包 SHA-256 + 签名证书双重校验
- [x] 明文 HTTP 默认禁止，仅局域网白名单
- [x] 发布用正式签名（非调试证书）、不含 `debuggable`
- [x] JWT 可吊销（token version）
- [ ] 服务端接口的自动化安全测试（当前仅覆盖核心业务逻辑）
- [ ] 依赖漏洞扫描（可接入 OWASP dependency-check）
