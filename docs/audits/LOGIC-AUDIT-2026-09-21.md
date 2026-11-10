# 逻辑缺陷检测报告 — 密语 server-java

**检测对象：** `<本地路径>`
**检测范围：** `src/main/java/com/familychat/server/` 全部 handler / DAO / Database / WsHandler / config
**检测方法：** 静态通读 + **写探针测试实机验证**（不是纯推测）
**环境：** JDK 24，Gradle 8.14.3，SQLite 3.46.0.0，`gradlew test` 基线全绿

> 说明：项目已有一份 `server-AUDIT-REPORT.md`（安全向）。本报告**刻意不重复**其中已列出的 20 条，
> 只记录**本轮新发现、且多数已用可运行测试复现**的逻辑问题。

---

## 结论摘要

| # | 严重度 | 位置 | 一句话 |
|---|---|---|---|
| 1 | **高危** | `MessageDao.clearConversation` | **任意登录用户可清空任意两人的聊天记录**（无参与者校验） |
| 2 | **高危** | `WalletDao.createTransfer / directPay / deductBalance` | 金额**未校验正数**：负数"转账"会把扣款变成**加钱**（造币） |
| 3 | 中 | `FileHandler.serveVideoFile / serveApk` | 标准 `Range: bytes=-500`（后缀区间）解析失败，静默降级为整文件 200 |
| 4 | 中 | `MallDao.approveRefund` | 卖家余额不足时退款**永久卡死**，且错误提示要求用户"线下处理" |
| 5 | 中 | `GroupDao.removeGroupMember` / `dissolveGroup` | 退群/踢人后**已读回执成孤儿行**；解散群只删索引行，**磁盘文件永不回收** |
| 5b | 中 | `FileHandler.uploadFile` | 上传配额的「检查—写入」**非原子**，并发上传可突破每人/全站配额 |
| 6 | 低 | `UserDao.getTokenVersion` | 查询失败/用户不存在时返回 `0`，把"查不到"当成"版本 0"，可能放行 `tv=0` 的旧 token |
| 7 | 低 | `DbUtil.rowToMap` | 任意 NULL 列被转成 `""`（字符串），数值列一旦为 NULL 会污染下游 |
| 8 | 低 | `WalletHandler.refundTransfer` 提示 | 接收方"立即退还"也走 403"无法撤回"，文案与语义不符 |

已确认**没有**问题的重点区域（避免误改）：并发防重复入账、并发扣款/转账不透支、群成员权限模型、群内容越权交叉校验、撤回/删除单条消息的鉴权、WS 连接表回收。

---

## 1.【高危】任意用户可清空他人聊天记录

**文件：** `model/dao/MessageDao.java:138-149`，路由 `handler/MessageHandler.java:367-375`

```java
// MessageDao.clearConversation —— 只按「这一对用户」删除，不校验调用者是谁
"DELETE FROM messages WHERE (sender_id=? AND receiver_id=?) OR (sender_id=? AND receiver_id=?)"
```

```java
// MessageHandler.clearConversation —— 仅要求已登录
int userId = AuthHandler.requireAuth(ctx);
...
int deleted = FamilyChatServer.getDb().clearConversation(userId, friendId);
```

`friendId` 完全来自路径参数，**从不校验 `userId` 是否是这段会话的参与者**。任何登录用户只要知道两个用户 id，就能删除他们之间的全部私聊记录——包括两个与自己毫无关系的陌生人。

**实测复现**（`LogicProbe5Test`）：alice 与 bob 有 2 条消息，无关第三方 eve 调用后：

```
[CLEAR] before = 2
[CLEAR] eve deleted=2 remaining=0      ← 记录被清空
[CLEAR][BUG] 无关第三方可以清空他人聊天记录！
```

**对比：** 同文件的 `recallMessage` / `deleteMessage` 都带了 `AND sender_id=?`，只有 `clearConversation` 漏了。属明显的漏改。

**修法（二选一）：**
- 最小改动：`clearConversation` 增加参与者校验——调用者必须等于 `sender_id` 或 `receiver_id` 之一；
- 语义正确改法：改为**单向清除**（新增 `messages_cleared` 表记 `(user_id, friend_id, cleared_before_id)`，查询时过滤），因为现在"我清空"会**同时抹掉对方那侧的记录**（`LogicProbe5Test.clearIsBidirectionalNotPerUser` 证实双方视图都变空）。微信/QQ 的"清空聊天记录"只影响本机。

---

## 2.【高危】负数金额可造币（金钱方向反转）

**文件：** `model/dao/WalletDao.java:461-546 (createTransfer)`、`554-617 (directPay)`、`229-243 (deductBalance)`

```java
// createTransfer：扣款条件只有「余额 >= amount」
"UPDATE wallets SET balance=balance-?, updated_at=? WHERE user_id=? AND balance>=?"
```
当 `amount = -100` 时该式变成 `balance = balance - (-100) = balance + 100`，且 `balance >= -100` 恒真 → **无条件加钱**。

`directPay` 与 `deductBalance` 是同一个模式，同样中招。

**实测复现**（`LogicProbe2Test`）：

```
[NEG] a before=500 after=600            ← 负数"转账"让发起方余额变多
[NEG-PAY] r={transfer_id=1, status=completed} a=600 b=-500   ← 负数扫码支付：自己+100，对方-500
[DEDUCT] deduct(-300)=true bal=400      ← 扣款(-300) 让余额从 100 变 400
```

**当前是否可被外部直接利用：否（但很脆弱）。**
`WalletHandler` 的 HTTP 层目前**都**做了 `amount <= 0` 拦截（`sendTransfer:266`、`recharge:210`、`createCharge:470`），所以走 REST 打不进来。这是**纵深防御缺失**，不是已失守的漏洞。

**为什么仍然高危：** 项目自己的设计原则是"授权边界不依赖上层 handler"——`addBalance` 就**刻意**在 SQL 里写了 `? > 0 AND ? <= ?` 守卫（注释明确写了这个意图）。`deductBalance` / `createTransfer` / `directPay` 三个却没有跟上，形成**不一致的半吊子防御**：一旦新增调用方（例如后续接入红包、批量转账、管理后台），或某天有人挪动了 handler 里的校验顺序，钱的方向就会反转。

**修法：** 与 `addBalance` 对齐，把正数约束下沉到 SQL：
```sql
WHERE user_id=? AND balance>=? AND ? > 0
```
或在 DAO 入口 `if (amount <= 0) return false/null;`。两者都做最稳。

---

## 3.【中】HTTP Range 后缀区间解析失败

**文件：** `handler/FileHandler.java:344-358`（`serveVideoFile`），`:1101-1116`（APK 下载，同一份复制逻辑）

```java
String[] parts = range.substring(6).split("-", 2);
start = Long.parseLong(parts[0].trim());     // "bytes=-500" → parts[0] 为空串 → 抛异常
```

`bytes=-500` 是 HTTP 标准里的**后缀区间**（取最后 500 字节），播放器拖到片尾、以及部分下载器会用到。此处 `parts[0]` 为空串，`Long.parseLong("")` 抛 `NumberFormatException`，被外层 `catch (Exception e) { /* 忽略，回退整段 */ }` 吞掉 → **静默返回整个文件（200）而不是 206 片段**。

**实测复现**（`LogicProbe6Test`，逐字复刻解析逻辑）：

```
[RANGE] bytes=-500       -> start=0 end=999 isRange=false status=200   ← 应为 206 + 最后 500 字节
[RANGE] bytes=0-499      -> start=0 end=499 isRange=true  status=200   ✓
[RANGE] bytes=500-       -> start=500 end=999 isRange=true status=200  ✓
[RANGE] bytes=1000-      -> status=416                                  ✓
```

后果：客户端请求尾段却拿到整文件，浪费带宽、拖尾可能异常；对"断点续传"语义是错的行为（虽不至于损坏数据）。

**修法：** 解析时区分 `parts[0].isEmpty()`（后缀区间 → `start = max(0, size - suffixLen), end = size-1`）与正常写法；`bytes=abc-def` 这类非法值应返回 `416` 而不是静默 200。

**附带（低）：** `FileHandler.java:360` 的 `in.skip(start)` **忽略了返回值**。`InputStream.skip` 的契约允许"少跳"，本地 `FileInputStream` 实测能跳满（`LogicProbe7Test` 验证 `skipped=500`），所以当前不构成实际故障，但替换数据源后即会静默返回错误片段。建议改成循环 skip 或 `in.skipNBytes(start)`。

> ⚠️ 本条引用的 `LogicProbe7Test.java` 已于 2026-10-01 删除
> （其唯一断言测的是本机 `FileInputStream.skip` 的返回值而非生产代码；探针前提已由 `skipFully`/`skipNBytes` 修复，空文件 416 语义已由 `FileHandlerRangeTest`（原 `LogicProbe6Test`）覆盖，见批 I 评估）。

---

## 4.【中】退款在卖家余额不足时永久卡死

**文件：** `model/dao/MallDao.java:1085-1091`

```java
if (d.executeUpdate() == 0) { c.rollback(); return "卖家余额不足，退款失败，请联系卖家线下处理"; }
```

卖家收到货款后若已把钱花掉（转账/购物/发红包），余额可能低于退款额。此时整笔退款事务回滚，`refund_status` **保持 `pending`** —— 买家的退款申请会**永远停在"处理中"**，既无法由卖家同意（一直报余额不足），也无法由买家撤回。订单被永久锁死（`applyRefund` 也只允许 `pending/approved` 之外的状态重新申请，见 `MallDao:1035`）。

这是**必然会发生**的状态机死锁，不是理论问题：商城下单即给卖家入账，卖家花钱是正常操作。

**修法：** 允许卖家余额**部分扣减至 0**（记为欠款）或引入平台垫付；至少要让买家能撤回申请 / 让订单可从死锁中退出。当前"请联系卖家线下处理"对一个家庭自建应用而言等于无解。

---

## 5.【中】退群留下孤儿已读回执；解散群不回收磁盘文件

**文件：** `model/dao/GroupDao.java:136 (removeGroupMember)`、`:500-548 (dissolveGroup)`、`model/dao/GroupFileDao.java:153 (deleteGroupFile)`

### 5a. 退群/踢人后已读回执残留（实测确认）

`removeGroupMember` 只删 `group_members` 一行，不碰 `group_message_reads`：

```java
"DELETE FROM group_members WHERE group_id=? AND user_id=?"     // GroupDao:136
```

**实测复现**（`LogicProbe9Test.removeMemberLeavesReadReceipts`）：

> ⚠️ 本条引用的 `LogicProbe9Test.java` 已于 2026-10-01 删除
> （0 断言、语义已被 GroupOrphanTest / AdminStatsTest 覆盖，见 commit e329fdf）。

```
[ORPHAN] markGroupRead=1 otherHasRead=true
[ORPHAN] 退群后 otherHasRead=true (已读回执仍在就是孤儿行)
```

成员已不在群里，其已读回执行**永久残留**。同一个人被重新拉进群时，历史消息会**直接显示为"已读"**（因为回执行还在），未读数计算（`getMyGroups` 的 `unread` 子查询）也会偏小——即"重新入群后看不到该看到的未读"。这些行没有任何清理路径。

### 5b. 磁盘文件字节永不回收

`group_files` 只存 `file_url`（`GroupFileDao:45`），删除索引行（`GroupFileDao:153`、`dissolveGroup`）**不会删除 `/chat_files` 下的实际字节**。而 `FileHandler.pruneChatFiles` 按其自身注释（`FileHandler.java:171`）是**"只报告不删除"**，`pruneVideoCacheNow` 只作用于 `video_cache` 目录。

因此：群解散、群文件被删、消息被清空之后，**磁盘占用一份都不会减少**，而上传配额却是按磁盘实算的（`reconcileUploadUsageIfStale`）——用户会看到"配额被已删除的文件占满"却无从释放。

### 5c. `dissolveGroup` 的静默容错形状不好（未造成当前故障）

每个删除都包在 `catch (SQLException ignored) {}` 里（`:514/521/524/529/533/536`），把"表不存在"和"真实删除失败"一并吞掉，然后**无条件 `commit()` 并 `return true`**（`:541-542`）。

需要说明：**实测证明正常路径下清理是完整的**（`LogicProbe9Test.dissolveLeavesNoOrphans`：`members after=0`、`group_files 残留=0`、`myGroups=0`），所以我**没有**把"解散后残留数据"当作已发生的故障——这里只是指出：一旦某个 delete 真实失败，接口仍会回 `success` 且无法回滚，属于**把失败伪装成成功**的形状问题。修法：删除前用 `sqlite_master` 判断表是否存在，而不是靠 catch。

> ⚠️ 本条引用的 `LogicProbe9Test.java` 已于 2026-10-01 删除
> （0 断言、语义已被 GroupOrphanTest / AdminStatsTest 覆盖，见 commit e329fdf）。

---

## 5b.【中】上传配额可被并发突破

**文件：** `handler/FileHandler.java:887-934`（检查）、`:716-754`（记账）

```java
long declared = ctx.req().getContentLengthLong();
if (declared > 0) {
    if (usedByUser + declared > MAX_UPLOAD_BYTES_PER_USER) { ...413... }   // 读 + 判
}
... copyWithLimit(...)          // 落盘（慢）
long written = Files.size(target);
if (usedByUser + written > MAX_UPLOAD_BYTES_PER_USER) { ...413+删除... }    // 再用同一个旧快照
recordUploadBytes(userId, written);                                        // 写
```

`usedByUser` 是**请求开始时读取的一次快照**，此后一直被复用。`usagePerUser` 虽是 `ConcurrentHashMap`（单次 `merge` 原子），但**"读 → 判 → 落盘 → 写"整个序列不是原子的**：N 个并发请求会读到同一个旧值、各自认为"还有空间"、然后一起写入。

实测（`LogicProbe10Test`，完整复刻该形状，配额 1000 字节 / 每文件 100 字节 / 40 并发）：

```
[QUOTA-RACY] 接受=40 最终占用=4000 上限=1000  ← 已超额！
[QUOTA-SAFE] 接受=10 最终占用=1000 上限=1000  ✓ 未超额（检查+写入同锁）
```

**超额 4 倍**。单文件 60MB、每人配额 2GB 的配置下，一次并发爆发即可把配额顶穿；全站配额同理。

**为什么不能只靠"落盘后复核"兜住：** 第 921-932 行确实按**实际落盘字节**又查了一次——但它用的仍是**那张旧快照** `usedByUser`（而非重新读取），所以并发下这层"硬校验"与第一层同时失效。这个复核只在"客户端谎报 Content-Length"的单请求场景有效，不解决并发。

**修法：** 用每用户锁把"检查 + 记账"变成临界区（`synchronized` 或按 userId 分段的 `ReentrantLock`），或改成"先 `merge` 预留、失败再回退"的原子更新（`compute` 里判断并返回失败）。注意别用全局锁拖慢整体上传。

---

## 6.【低】`getTokenVersion` 用 `0` 表示"查不到"

**文件：** `model/dao/UserDao.java:149-159`，使用点 `handler/AuthHandler.java:235`

```java
if (rs.next()) return rs.getInt(1);
} catch (SQLException e) { ... }
return 0;     // ← 用户不存在 / DB 出错 都返回 0
```

而 `users.token_version` 的默认值**也是 0**（新用户 `LogicProbe4Test` 实测 `fresh user tokenVersion=0`）。于是：
- 正常情况：`tv=0` 的旧 token 与 `getTokenVersion=0` 相等 → **通过**（这是设计内的）；
- 用户被删除后：查不到 → 返回 `0` → 一个 `tv=0` 的 token 仍然**通过校验**，拿到一个已不存在用户的 `userId`，后续 DAO 操作全部退化成空结果而不是 401。

当前影响有限（没有"删号"功能），但这属于"失败被当成合法值"的模式。**修法：** 查不到时返回 `-1`（任何 token 的 `tv` 都不可能等于 -1），让失败显式失败。

附带：`AuthHandler:235` 的 `jwt.getClaim("tv").asInt() != getTokenVersion(userId)` 在 **token 缺少 `tv` 声明**时会返回 `null`，`null != int` 触发自动拆箱 **NPE**，被外层 `catch (Exception e)` 吞成 401。结果正确（旧 token 确实该失效），但属于"靠异常兜底"，可读性差。

---

## 7.【低】`rowToMap` 把 NULL 列统一转成空字符串

**文件：** `model/DbUtil.java:24-39`

```java
if (colName.equals("is_online")) { val = val != null && ((Number) val).intValue() == 1; }
else if (val == null) { val = ""; }        // ← 所有 NULL 都变 ""
```

`is_online` 的 NULL 是安全的（短路返回 `false`，实测 `[ROW] is_online=NULL -> false`）。但**其他列**的 NULL 会变成**字符串 `""`**：

```
[ROW] balance=NULL -> '' (String)
```

于是下游 `((Number) m.get("balance")).longValue()` 会抛 `ClassCastException`，或客户端 Gson 反序列化数字字段时抛 `NumberFormatException`，且异常点离根因很远。当前 `wallets.balance` 是 `NOT NULL DEFAULT 0`，所以没有立即故障；但任何后续新增的可空数值列（如 `refund_time`、`mute_until`、`paid_at`）都会踩到。

**修法：** 只对 TEXT 列做 `""` 兜底，或让调用方显式处理 `null`。

---

## 8.【低】"立即退还"复用了"撤回"的错误文案

**文件：** `handler/WalletHandler.java:335-348`

DAO 层面 `resolveTransfer` **有意**允许接收方也调用 refund（`WalletDao:664`，注释写明"refund 可以是 from_user（撤回）或 to_user（立即退还）"），路由注释同样写了"发起方撤回/接收方立即退还"。

但失败分支只回 `"无法撤回"`（`:346`），与接口注释的语义不符；且 DAO 的 javadoc 仍写"**仅发起方可调**"（`WalletDao:627`），与实际行为矛盾。属**文档/文案与实际行为不一致**，会让排查问题的人误判权限模型。真正的权限校验本身是正确的。

---

## 已验证「不是问题」的区域（避免误改）

本轮专门验证过、结论是**正确**的部分：

| 区域 | 验证方式 | 结论 |
|---|---|---|
| 并发重复收款 | 16 线程抢同一笔转账 | `ok=1 fail=15`，只入账一次 ✓ |
| 并发扣款 | 20 线程各扣 100（余额 1000） | `ok=10 bal=0`，未透支 ✓ |
| 并发转账 | 20 线程各转 100 | `ok=10 a=0`，未透支 ✓ |
| 群成员角色 | 通读 `memberRole/isAdmin/isMuted` | owner 优先 `groups.owner_id`，`-1`=永久禁言一致 ✓ |
| 群内容越权 | 通读 `GroupContentHandler` | 每个 `aid/pid/fid` 都交叉校验 `group_id` ✓ |
| 群未读数 | 通读 `getMyGroups` | 排除自己发的 + 读回执表，不会为负 ✓ |
| `markGroupRead` | 通读 | `INSERT OR IGNORE` 幂等 ✓ |
| 上传配额（单请求） | 通读 `FileHandler` | Content-Length 预检 + 落盘后按实算复核 + 超限删除 ✓（但**并发**可突破，见第 5b 条） |
| WS 连接表 | 通读 `WsHandler` | `onClose`/`onError` 共享幂等 `removeConnection`，多连接正确 ✓ |
| 好友请求状态机 | 通读 `FriendDao` | accepted/pending/rejected 全分支处理 ✓ |
| 转账消息状态改写 | `PROBE7` | 备注里含 `"status":"pending"` 字样时替换仍正确 ✓ |
| 自转/自付/自收 | `PROBE3/5/6` | handler 层拦截，DAO 层净额守恒（不造币不销毁）✓ |

> 注：`PROBE3/5/6` 显示 DAO 层允许"自己转给自己"，但**净额为 0**（`a=500` 进出持平），且 handler 层已用 `toUser == userId → 400` 拦住，不构成问题。

---

## 建议修复顺序

1. **第 1 条**（任意用户清空他人记录）——唯一一个当前**可被外部直接利用**的越权，改动小、收益最大。
2. **第 2 条**（负数造币）——把 `? > 0` 守卫补到 `deductBalance / createTransfer / directPay`，与 `addBalance` 对齐。
3. **第 5b 条**（配额竞态）——把"检查+记账"收进临界区。修法局部，但影响磁盘安全。
4. **第 4 条**（退款死锁）——真实业务场景必然触发，需要重新设计余额不足时的退款语义。
5. **第 3 条**（Range 后缀）——影响视频拖尾/续传体验，修法独立。
6. **第 5 条**（退群孤儿回执 + 磁盘文件不回收）——需要一次数据清理 + 定期回收任务。
7. **第 6/7/8 条**——健壮型与文案型，可一起做。

每条修完都建议补一条回归测试锁住行为（现有 `src/test` 已有 `DbTestSupport` 基类可直接复用）。

---

## 附录：本轮探针测试

以下临时测试为本报告的**证据来源**，位于 `src/test/java/com/familychat/server/`：

| 文件 | 验证内容 |
|---|---|
| `LogicProbeTest` | 转账/收款/自转/状态改写/`getTokenVersion` 初筛 |
| `WalletNegativeAmountTest`（原 `LogicProbe2Test`） | **负数造币**（转账/扫码支付/扣款） |
| `WalletConcurrencyTest`（原 `LogicProbe3Test`） | 并发收款、并发扣款、并发转账（**均正确**） |
| `TokenVersionTest`（原 `LogicProbe4Test`） | `token_version` 语义与改密递增 |
| `MessageAuthorizationTest`（原 `LogicProbe5Test`） | **第三方清空他人记录**、撤回/删除鉴权（正确） |
| `FileHandlerRangeTest`（原 `LogicProbe6Test`） | **Range 后缀区间解析失败** |
| `LogicProbe7Test` | `skip()` 返回值被忽略（当前无实际故障） |
| `DbUtilRowToMapTest`（原 `LogicProbe8Test`） | `rowToMap` 列语义（`is_online` NULL 安全；其他列 NULL→`""`） |
| `LogicProbe9Test` | 解散群清理（**正常**）、退群已读回执**孤儿** |
| `UploadQuotaConcurrencyTest`（原 `LogicProbe10Test`） | **上传配额并发突破** |

> ⚠️ 本条引用的 `LogicProbe9Test.java` 已于 2026-10-01 删除
> （0 断言、语义已被 GroupOrphanTest / AdminStatsTest 覆盖，见 commit e329fdf）。

> ⚠️ 2026-10-01 批 I 处置（仅涉及本附录表）：
> - **删除 2 个**：`LogicProbeTest.java`（8 个 @Test 里 6 个零断言、仅 2 条准备态断言）、`LogicProbe7Test.java`。
> - **改名 7 个**（仅类名与文件名，**未改任何断言**）：`LogicProbe2Test`→`WalletNegativeAmountTest`、`LogicProbe3Test`→`WalletConcurrencyTest`、`LogicProbe4Test`→`TokenVersionTest`、`LogicProbe5Test`→`MessageAuthorizationTest`、`LogicProbe6Test`→`FileHandlerRangeTest`、`LogicProbe8Test`→`DbUtilRowToMapTest`、`LogicProbe10Test`→`UploadQuotaConcurrencyTest`。
> - 本报告正文里按旧名出现的「实测复现」引用（第 1/2/3/5b/6 条）保持原文不改，对应关系见上表。

这些探针把问题从"代码看着不对"变成了"可复现的失败"，修复时也可以直接把它们改写成正式回归断言。
