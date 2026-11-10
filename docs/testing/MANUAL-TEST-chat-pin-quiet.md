# 单聊置顶 / 免打扰 —— 真机手测清单

**适用 commit**：`775f513`（批 1：单聊置顶 + `pinned_chats` 格式修复）、`e5345fc`（批 2：单聊免打扰）
**范围**：Android 客户端（`:app` / `:feature-chat` / `:core`）——**服务端未改**
**自动化已覆盖**：`PinnedChatsTest` 4/4（`:core`）、`NotificationPolicyTest` 17/17（`:app`）。本清单只覆盖自动化覆盖不到的部分。
**清单规模**：34 项 = §0 五项准备 + §1 十项 + §2 十一项 + §3 八项

> 本文是 `../../RELEASE.md`「脚本覆盖不到、必须真机手工验收的」段落的**展开清单**，可与之互链。
> （本次不改 `../../RELEASE.md`，仅在此声明关系。）

**约定**：文中 `文件:行号` 均相对模块根 `miyu-app/apk/FamilyChatApp/`，且为提交时点（`775f513` / `e5345fc`）的行号；后续若代码变更请重新核对。

---

## 0. 环境与观测准备（5 项）

- [ ] **0.1 装包与账号**：`.\gradlew.bat :app:installDebug`；登 A 账号并**停在主列表**；B 账号用于发消息（第二台设备或模拟器，或用 §0.3 的接口）。
  → 预期：主列表正常显示会话。
- [ ] **0.2 通知前置 + 每次观测前清空通知栏**：确认应用已授予通知权限、系统未开勿扰；然后
  ```powershell
  adb shell cmd notification clear_all
  ```
  （若所用机型/Android 版本的 `cmd notification` 不含 `clear_all` 子命令，退回手动操作：下拉通知栏 → 全部清除。）
  → 预期：通知栏为空。
- [ ] **0.3 无第二台设备时的发消息口**（服务端已有明文发送路由 `MessageHandler.java:14` `/api/messages/send`；鉴权头 `Authorization: Bearer <token>`，见 `ApiClient.java:129`）：
  ```powershell
  curl -s -X POST http://<server>/api/login -H "Content-Type: application/json" -d "{\"username\":\"B\",\"password\":\"***\"}"
  curl -s -X POST http://<server>/api/messages/send -H "Authorization: Bearer <B_token>" -H "Content-Type: application/json" -d "{\"receiver_id\":<A的好友id>,\"content\":\"t1\",\"msg_type\":\"text\"}"
  ```
  → 预期：返回 200，A 设备收到推送。
  → 注意：好友消息是 E2EE 的，这条明文消息的通知预览会是原文——**不影响本清单目标**（只看"弹不弹"）。
- [ ] **0.4 观测「弹没弹」**（按渠道过滤，比按包名精确）：
  ```powershell
  adb shell dumpsys notification --noredact | Select-String "familychat_msg" -Context 0,15
  ```
  → 预期：未开免打扰时能读到 `channel=...id=familychat_msg` 的通知块；**识别目标好友看该块内的 title**
    （标题由 `app/src/main/java/com/familychat/app/notify/NotificationPolicy.java:67-69` 的 `friendTitle` 给出，昵称缺失时回落 `好友`）。
  → ⚠️ `familychat_fg`（前台服务常驻通知）与 `familychat_call*`（通话）**不算消息通知**：按 `familychat_msg` 过滤天然排除它们；若按包名过滤会误判。
- [ ] **0.5 观测 UI（红点 / 静音图标）**：
  ```powershell
  adb shell uiautomator dump /sdcard/ui.xml; adb shell cat /sdcard/ui.xml | Select-String "tvUnreadBadge|ivMute"
  ```
  → 预期：能读到 `tvUnreadBadge` 的 `text`；`ivMute` **出现 = 图标显示，不出现 = 已隐藏**（`GONE` 的 View 会被 uiautomator 剪枝，不是 dump 失败）。
  → 观测前请先让该会话红点为 0（进一次会话再退），避免与历史未读混淆。

> ⚠️ **adb 读不到 `pinned_chats` / `friend_quiet_<id>` / `unread_<id>`**：三者都存放在
> `EncryptedSharedPreferences`（键 AES256_SIV、值 AES256_GCM，见
> `core/src/main/java/com/familychat/app/utils/PreferencesManager.java` 构造函数），
> XML 里既无明文键名也无明文值。因此"无 `5,5,` 残留""红点仍 +1"**只能从 UI 行为观测**，
> 不要用 `cat shared_prefs/*.xml` 下结论。

---

## 1. 批 1：单聊置顶（`775f513`，10 项）

- [ ] **1.1 首次置顶立即上顶**：主列表侧滑好友甲 → 点「置顶」。
  → 预期：甲**立刻**跳到列表最前，**不需要点第二次**（旧 bug 下首置顶永远排不上去）。
- [ ] **1.2 连置顶三人**：依次置顶甲、乙、丙。
  → 预期：三人占据列表最前；置顶段内部按**最后消息时间倒序**；若存在置顶群，置顶群与置顶好友同属"置顶段"混排（排序键见 `MainActivity.java:687-691`：置顶优先 → 最后消息时间）。
- [ ] **1.3 同一人连点 5 次「置顶」**。
  → 预期：状态按奇偶正确翻转，排序始终稳定；**不出现"点了没反应"**。
- [ ] **1.4 逐个取消**：侧滑 →「置顶」，逐个取消三人。
  → 预期：每取消一个，该好友**立刻**掉回按最后消息时间的位置；取消顺序任意都生效。
- [ ] **1.5 反复开关同一人 3 轮**（这是"无 `5,5,` 累积"的可观测代理）。
  → 预期：每轮都能立即上顶 / 立即回落（旧 bug 表现为首置顶排不上去、也取消不掉）。
- [ ] **1.6 详情面板开关初值**：进甲会话 → 右滑出详情面板。
  → 预期：卡片「聊天」内有 **「置顶会话」开关**，且为**开**（初值读 prefs，`ChatDetailPanel.java:170-176`）。
  → 已知：该行图标暂借 `R.drawable.ic_group`（`ChatDetailPanel.java:168-169` 有 `TODO(图标)`），属已知项，不是 bug。
- [ ] **1.7 面板关闭 → 列表**：在面板里关掉「置顶会话」→ 返回主列表。
  → 预期：甲已掉出置顶段；再侧滑确认状态一致。
- [ ] **1.8 列表开 → 面板读**：列表侧滑置顶甲 → 进甲会话 → 开详情面板。
  → 预期：面板开关显示**开**（两入口经 prefs 同步；面板在 `build()` 时读一次）。
- [ ] **1.9 返回列表排序立即刷新**：在详情面板切换置顶 → 立刻返回主列表。
  → 预期：排序**立即**刷新，不等 5 秒。
- [ ] **1.10（严格验 dirty，可跳过）**：冷启动 App 后 **5 秒内**完成"进会话 → 改置顶 → 返回"，且处于**离线 + 无群缓存**状态。
  → 预期：排序仍立即刷新（`dirty` 标记绕过 `MainActivity.onResume` 的 5s 防抖，`MainActivity.java:2022-2031`）。
  → 边界（务必知悉）：联网正常时该差异**看不出来**——`onResume` 每次都会 `loadGroups()`，其缓存分支（`:609`）或网络成功分支（`:627`）都会 `buildConversations()` 兜住刷新。

---

## 2. 批 2：单聊免打扰（`e5345fc`，11 项）

- [ ] **2.1 打开免打扰**：进 A 会话 → 右滑详情面板 → 打开 **「消息免打扰」** → 返回主列表。
  → 预期：开关保持开；重进面板仍为开。
- [ ] **2.2 不弹通知**：A 停在**主列表**，B 发 1 条消息；执行 §0.4 命令。
  → 预期：`familychat_msg` 过滤结果里**没有**该好友的通知块（通知栏只有 `familychat_fg` 常驻项）。
- [ ] **2.3 列表静音图标**：执行 §0.5 命令。
  → 预期：dump 里能搜到 `ivMute`。
- [ ] **2.4 红点 +1**：同上。
  → 预期：`tvUnreadBadge` 由 0 → 1（读法见下方「红点观测说明」）。
- [ ] **2.5 第二条消息**：B 再发 1 条。
  → 预期：红点 2，仍**不弹**、图标仍在。
- [ ] **2.6 进会话清零**：进入 A 会话。
  → 预期：红点**清零**（`ChatActivity.java:211` 进会话即写 `unread_<id> = 0`）。
- [ ] **2.7 清零后图标仍在**：返回主列表看 A 那一行。
  → 预期：红点消失，**静音图标仍在**（quiet 与清零互不影响）。
- [ ] **2.8 关闭免打扰恢复**：进 A 会话 → 关掉「消息免打扰」→ 返回 → B 发 1 条。
  → 预期：**正常弹通知**（`familychat_msg` 有该好友的通知块）；静音图标**消失**；红点 +1。
- [ ] **2.9 反复开关 2 轮**：每轮切换后各发一条。
  → 预期：弹 / 不弹与开关状态严格一致（无"开关显示开着但仍弹"）。
- [ ] **2.10 进程重启后保持**：杀进程（或切后台被回收）后重进。
  → 预期：免打扰状态保持（本机 prefs 持久化）。
- [ ] **2.11（可选）跨设备口径确认**：换另一台设备登录同一账号。
  → 预期：好友免打扰**不同步**（本机 prefs，服务端无好友级会话设置表——**这是预期行为，非 bug**）；群免打扰**会**同步（走服务端 `group_settings.muted`）。

### 红点观测说明（避免下次手测再踩）

**「免打扰不弹通知」与「红点仍 +1」由两个不同的层保证，互不干扰：**

1. **数据写入**（红点为什么会 +1）：在 `NotificationDispatcher` 的**第一层**主线程回调里完成，且**位于 quiet 判定之前**——
   `app/src/main/java/com/familychat/app/notify/NotificationDispatcher.java:152-163`：
   先 `shouldCountUnread` 守卫 → 写 `unread_<id>`（`:155-157`）→ **之后**才做 quiet 通知守卫（`:160-163`）。
   即：免打扰好友的未读照常 +1，只是随后 `return`、不 `WORKER.execute`（不弹通知）。
   该顺序与群侧同构（群侧 `:186` 计未读 → `:192-195` quiet 守卫）。
2. **UI 呈现**（红点为什么会自己变）：`MainActivity` 注册在 `PushConnection` 上的 WS 回调
   `onNewMessage`（`MainActivity.java:293-301`）**只做** `runOnUiThread(() -> loadFriends())`——
   它**不计未读、不读 quiet、不弹通知**（代码注释 `:294-297` 明写"此处不得再实现通知/未读逻辑 —— 会与 Dispatcher 双计、双弹"）。
   刷新链路：`loadFriends()` → `buildConversations()`（`:682`）→ adapter rebind，绑定处读 `unread_<id>`（`:1604-1606`）。
3. 由此得到两条手测前提：
   - **主列表存活时**，新消息到达后红点会**自动 +1**，不需要手动下拉刷新；
   - **`MainActivity` 已销毁时**，红点仍写入 prefs，但 UI 不刷新，要等下次进列表才显示 → **要观测红点请停在主列表**。
4. 既有副作用（与本任务无关，仅备注）：主列表存活时**每条消息触发一次 `loadFriends()` 网络请求**。

---

## 3. 回归（8 项）

- [ ] **3.1 群免打扰行为不变**：群会话列表侧滑 →「免打扰」→ 群里发消息。
  → 预期：不弹群通知；群行**静音图标**在；群红点 +1（判定顺序：`NotificationDispatcher.java:186-195`）。
- [ ] **3.2 群免打扰关闭恢复**：关掉后再发。
  → 预期：恢复弹群通知。
- [ ] **3.3 群置顶两入口一致**：侧滑「置顶」与群详情「置顶会话」。
  → 预期：均生效、互相一致；重进群详情开关状态正确（`GroupDetailPanel.refresh → setSectionChecked` 未被本次改动影响）。
- [ ] **3.4 单聊置顶回归**：重跑 §1 全部。
  → 预期：全部通过。
- [ ] **3.5 WS 订阅回归**：断网→恢复、切前后台、杀进程重进。
  → 预期：仍能收到消息（订阅/注册逻辑未动）；同一好友只弹一条、红点只 +1（不双计不双弹）。
- [ ] **3.6 会话内提示回归**：停在 A 会话时 B 发消息。
  → 预期：不弹通知（"正在聊天不打扰"）；消息正常进入列表；滚动/输入/语音按钮正常。
- [ ] **3.7 未读清零回归**：在 A 会话内收消息后退出会话。
  → 预期：停在该会话期间不计未读；退出后红点为 0。
- [ ] **3.8 群 system 消息口径不变**：触发入群/退群/公告类 system 消息。
  → 预期：**仍计群未读**但**不弹通知**（群侧既有不对称口径，`NotificationPolicy.java:53-57` 未被改动）。

---

## 4. 观测陷阱速查

| 陷阱 | 说明 |
|---|---|
| `familychat_fg` | 前台服务常驻通知（`NotificationHelper.FG_CHANNEL_ID`），**永远在**。按 `familychat_msg` 过滤即可排除 |
| `familychat_call*` | 通话渠道，与本次无关 |
| prefs 是密文 | `cat .../shared_prefs/family_chat_secure_prefs.xml` 只能看到密文，**别据此判断**"无残留 / 红点值" |
| uiautomator 剪枝 | `GONE` 的 `ivMute` 不出现在 dump 里 → "搜不到" = "已隐藏"，而非 dump 失败 |
| 红点基线 | 观测 §2.4 前先让该会话红点为 0，否则分不清是 1 还是历史值 |
| 通知栏未清 | 每一步前执行 §0.2 清空，否则旧通知会被误读成"弹了" |
| 通知权限 | Android 13+ 未授权则什么都看不到（首次启动有引导弹窗） |
| dirty 不可见 | 联网正常时"返回立即刷新"主要靠 `loadGroups → buildConversations`；只有 §1.10 那个构造场景才真正验证 dirty |
| `cmd notification clear_all` | 并非所有版本都有该子命令；不可用时手动清通知栏（见 §0.2） |

---

## 5. 已知不可手测（静态已钉死）

**第二层 quiet 守卫（"排队期间刚开免打扰"）当前 UI 不可达。**

- 证据链：好友 `friend_quiet_<id>` 的**唯一写入点**是详情面板回调 `ChatActivity.java:317-324`；
  而详情面板挂在 `ChatActivity` 内容层（`:290-318`）→ **进面板必先进会话** →
  进会话即 `currentFriendId = friendId`（`:210`）→ 消息到达时第一层 `shouldCountUnread` 已为 false
  （`NotificationDispatcher.java:153`）→ **根本不入队**，第二层的 quiet 分支无从触发。
- 对照：**群侧可达**，因为群免打扰能从会话列表侧滑直接切换
  （`MainActivity.java:1629` `h.btnClear.setText(isFriend ? "清空" : "免打扰")` —— 好友侧那一格是"清空"），
  那是群侧第二层（`NotificationDispatcher.java:258-264`）的真实通路。
- **何时变可达**：新增任一"不进入 `ChatActivity` 也能写 `friend_quiet_<id>`"的入口即可 ——
  典型是给好友侧会话列表侧滑加一格「免打扰」，或设置页加会话开关。
- 验证手段：instrumented 测试（当前无 dispatcher 级测试；`NotificationPolicyTest` 只覆盖纯策略层）。
- 结论：该分支为**防御性代码**（与群侧同构、防未来写入方），**不需要也不应该**用手测判定为失败。

---

## 6. 验收结论

| 项 | 填写 |
|---|---|
| 结论 | ☐ 全绿 ☐ 部分绿（有失败/跳过） ☐ 失败 |
| 失败或跳过项编号 | |
| 验收人 | |
| 日期 | |
| 设备型号 + Android 版本 | |
| APK versionCode / versionName | |
| 被测 commit | `775f513`（批 1）+ `e5345fc`（批 2） |
| 备注（如 §2.11 未测、§1.10 未构造） | |
