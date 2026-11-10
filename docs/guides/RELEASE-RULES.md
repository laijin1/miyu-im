# 发布更新规则（RELEASE-RULES）

> 本文件是发版的唯一标准流程。任何发版必须逐步执行，不允许跳步、不允许合并。

## 0. 触发条件

- 只有客户端代码改动 → 走 B 段
- 只有服务端代码改动 → 走 A 段
- 两者都有 → A 和 B 都走，**最后一起切换**（不分两次部署）

## A. 服务端（有服务端改动时）

A1. 重建 jar：
    cd server
    .\gradlew.bat shadowJar --no-daemon

A2. **验证 jar 里真的是新代码**（这一步不能省）：
    解 jar 查 class 字节，确认本次改动的关键字符串在 / 不在。
    例：本次改了文案 → 旧文案应找不到、新文案应找到。

A3. 不立刻启动服务。等服务端和客户端包都就绪后，
    在 B9 之后一起部署。

## B. 客户端发版

B1. **前置检查**：工作区干净、所有改动已提交。不干净不动手。

B2. **备份**（在改版本号之前）：
    release/密语.apk → 密语.apk.preN-bak
    release/version.properties → version.properties.preN-bak
    ⚠️ preN-bak 里装的是 **N-1** 版本（上一版），不是 N。
    ⚠️ 不动 pre(N-1)-bak 及更早。
    ℹ️ 只备份这一份：release/ 下只有 密语.apk 一个 APK（见 B6）。
    🧹 **保留策略**：只留最近 1 个 preN-bak 作为回滚点，更早的应删除。
       历史上未清理，累积到 85 个文件 / 3.03 GB（2026-09-23 已清理一次）。
       回滚只需要 N-1（见 L83），更早的版本没有回滚价值。

B3. 改 release/version.properties：
    - versionCode = 上一版 + 1（**必须提，不提等于白发**）
    - versionName = 1.x.y
    - changelog **整体替换**（删掉上一版那条，不叠加）

B4. 提交：chore(release): 1.x.y (NNN)

B5. 构建：:app:assembleRelease --no-daemon

B6. 复制新 APK 到 release/密语.apk（**只此一份**）
    （mtime 要晚于 version.properties）

B7. 跑 publish-check.bat（7/7）
    ⚠️ 必须在 B6 之后。复制前跑 → [5/7] 必红。

B8. 验证 /version.json：
    published=true / versionCode=NNN / versionName=1.x.y /
    apkSha256 与磁盘一致

B9. 服务端（如有改动）：
    确认无手机活跃连接后，停旧进程、起新 jar。
    启动后立刻验证 /version.json 返回 200。

## C. 真机验证（发版后必做）

- 冷启动 + 切后台 + 收消息（验证通知链）
- 本次改动对应的具体场景（每次不同）
- 滚动/复用类改动：滑出屏幕再滑回，样式不变
- 历史消息兼容：老数据在新版本下的表现
- 至少在一台"国产 ROM"手机上验一次 App 内自更新：
  若系统安装器拦截，确认文件管理器手动装能过。

## 依赖变更后的构建规则

- **背景**：2026-10-01 实测，依赖声明变更后的增量 `assembleDebug`
  可能产出异常偏大的 APK（本次 +4.98 MB / +9.2%），
  `:app:clean` 后重建即恢复。release 变体不受影响（clean 前后逐字节同值）。
- **规则**：任何 `build.gradle` 依赖声明变更后，在信任产物前
  至少跑一次 clean 构建：
      .\gradlew.bat :app:clean :app:assembleDebug
  或与已知 clean 参考体积比对：
      debug 参考体积 ≈ 54,290,684 B（截至 2026-10-01）
- **为什么**：增量打包在依赖图变更后可能与 clean 产物不一致；
  仅凭 `BUILD SUCCESSFUL` 不能保证产物与 clean 一致。

## UI 改动的验证

- **背景**：2026-10-05 实测，UI 改动只跑 library 模块级编译
  （`:feature-chat` / `:feature-group` / `:ui-common`）**通过**，但 `:app`
  的资源链接失败 —— `TextAppearance.FF.SectionHeader` 等 8 个根样式缺
  `parent=""`，aapt2 会为带点号的 style 名推断一个不存在的父样式
  （`TextAppearance.FF` / `Widget.FF`）。library 单独编译/打包 AAR 不报此错，
  只有 `:app` 的资源链接会拦下。
- **规则**：UI（layout / drawable / values）改动后必须跑一次
      .\gradlew.bat :app:assembleDebug
  只跑模块级编译不足以信任结果。
- **为什么**：资源合并与 aapt2 链接只发生在 `:app`。除隐式父样式外，
  同名资源跨模块冲突（例：`badge_bg` 这类通用名）也只在这一步暴露，
  而它影响的可能是**别的界面**。
- ⚠️ 判断"资源真的进了包"要看产物、不看源码（`--rerun-tasks` 强制重打，
  或直接查 APK）：
      .\gradlew.bat :app:assembleDebug --rerun-tasks
      aapt2 dump resources app\build\outputs\apk\debug\app-debug.apk

## 全模块 release 门禁

- **显式命令**（发版前 / 大改动后跑，一次覆盖 7 个模块）：
      .\gradlew.bat :app:assembleRelease :core:assembleRelease `
          :ui-common:assembleRelease :feature-chat:assembleRelease `
          :feature-group:assembleRelease :feature-mall:assembleRelease `
          :feature-wallet:assembleRelease
- **脚本里的裸 `assembleRelease`**（`scripts/deploy.bat`、
  `scripts/release-commit.bat`；Linux 侧同款 `scripts/*.sh`）会由 Gradle
  广播到所有含该任务的模块 —— 行为等价，但意图不显式。
  文档以本节的显式命令为准。
- ⚠️ **`assembleRelease` 成功 ≠ APK 重新打包**：若 `:app:packageRelease` 处于
  UP-TO-DATE，会复用旧 APK（见"依赖变更后的构建规则"）。要强制重打包：
      .\gradlew.bat :app:assembleRelease --rerun-tasks
  或与已知 clean 参考体积比对。

## 版本号规则

- versionCode：唯一、递增，**每次发布必须 +1**
- versionName：与 versionCode 对应，例如 142 → 1.1.9
- **同一 versionCode 不允许对应两个不同 APK**

## changelog 规则

- **整体替换**，不叠加
- 单行（Properties 格式）
- 用「」引号、全角括号
- 不写实现细节（用户看不懂）

## 回滚

- 回滚点：`release/密语.apk.preN-bak`（内容 = N-1 版本）
- 回滚时先还原 APK，再还原 version.properties
- **APK 的 mtime 必须晚于 version.properties**
  （否则触发发布窗口保护，published=false）
- 只还原 `密语.apk` 这一份即可 —— release/ 下现在只有它一个 APK。
  若历史上遗留过 `知网.apk` / `miyu.apk` 副本，回滚时一并删除，
  否则门禁 [5/7] 会拿旧副本比对而报红。

## 已知坑（踩过的）

| 坑 | 后果 | 规避 |
|---|---|---|
| release/ 下多出一个旧 APK | 门禁 [5/7] 红（版本号或 mtime 不符） | release/ 只留 密语.apk 一份；`.bat` 会逐个校验所有 `*.apk` |
| 备份在改号之后 | preN-bak 里装了 N | 备份必须在 B3 之前 |
| 硬校验在复制之前 | 门禁 [5/7] 红 | 硬校验必须在 B6 之后 |
| version.properties 比 APK 新 | published=false | 复制 APK 后确认 mtime |
| 服务端源码改了但 jar 没重建 | 重启后跑的还是旧代码 | A2 必须做 |
| 不提版本号直接重发 | 已装用户永远收不到更新 | B3 必须提号 |
| changelog 叠加 | 用户看到历次所有改动 | 整体替换 |
| 装 debug 包覆盖 release | 签名不符装不上；卸载会清数据 | 用 release 签名包 |
| 国产 ROM 拦 App 内自更新 | 系统安装器报"安装包已损坏"，装不上 | 用文件管理器手动打开 `下载/密语.apk` 安装；或 adb install -r |

## 不 push

本仓库无 remote。所有提交只在本地。发版 ≠ push。
