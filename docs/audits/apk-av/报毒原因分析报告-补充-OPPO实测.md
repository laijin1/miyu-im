# 补充报告：OPPO A93 实测「中高风险应用」告警归因

> 本文是 `报毒原因分析报告.md` 的**续篇**。前篇讲的是 v1.0.123 之前的**调试签名**问题，
> 该问题已在 v1.0.124/125 修复（正式 RSA2048 密钥 + v2/v3 签名 + debuggable 消失）。
> 但真机实测仍被拦，本文解释**剩下的告警从哪来**。

核查对象：`miyu-app\release\密语.apk`
- versionCode 125 / versionName 1.0.125
- SHA-256：`2A6DC800E47FE00CC7138891D8D2AFEB71FB6953DA62125A71EED2A31951832A`
- 实测机型：**OPPO A93（ColorOS）**，安装器弹「中高风险应用」

---

## 一、原始告警内容（真机截图，2026-09）

```
密语  版本号 1.0.125 | 49.2 MB
未查询到此应用的 ICP 核准（ICP 备案）…

[中高风险应用]
不建议安装此应用；若仍需安装，可查看详情

恶意行为
存在恶意占用系统资源、自动捆绑安装、自动修改系统配置、频繁发送骚扰信息等行为。

[安装增强防护]  深度扫描、安装管控、应用上传检测      [开启]
                                   [取消安装] [搜索相关应用]
```

**关键判定：这两行不是两件事，而是同一份检测报告的两个栏目。**
- 第 1 行是 **监管/备案维度的结论**（未备案/未经 OPPO 安全审核）；
- 「恶意行为」是 **行为维度的云端标签**（OPPO 安全检测云 + 合作引擎给出的启发式标签）。

---

## 二、逐条核对：标签 vs 包内事实

| 告警标签 | 包内事实（已核） | 判定 |
|---|---|---|
| **未查询到 ICP 核准 / 未备案** | 项目无域名、无 ICP 主体、无 APP 备案号 | ✅ **属实**，且是最硬的一条 |
| **自动捆绑安装** | ① 清单含 `REQUEST_INSTALL_PACKAGES`；② `BrowserActivity.downloadAndInstallApk()` 用 OkHttp 下载**任意网页触发的 .apk** 后用 FileProvider 弹装；③ `UpdateActivity` 含 `PackageInstaller` **会话静默直装** | ✅ 特征属实（业务是自更新+内置浏览器，不是推广），但启发式必然打此标签 |
| **恶意占用系统资源** | 前台服务 + WebSocket 长连接 + 4 个 ABI 的 WebRTC so | ⚠️ 通用兜底式描述，无法归因到具体证据 |
| **自动修改系统配置** | 清单内无 WRITE_SETTINGS；只有 `allowBackup=true`、`networkSecurityConfig`，未发现修改系统设置的代码 | ❌ **证据不支持**，疑为"打开系统设置页/请求权限"被泛化 |
| **频繁发送骚扰信息** | 清单**无任何 SMS 权限**（aapt dump permissions 已核），无短信发送代码 | ❌ **明确误报** |
| 体积 49.2 MB | 4 ABI WebRTC so + 3 个 dex，与下载包一致 | 正常 |

> 结论：**"恶意行为"这一栏是启发式模板文案，其中两条能被本包数据直接证伪。**
> 这正是申诉可以据理力争的地方——你要否认的不是"有没有装包能力"（确实有），
> 而是"这不是恶意推广行为，且部分指控与包内容不符"。

---

## 三、为什么"未经安全审核 + 未备案"是绕不过去的

OPPO 官方明确：**未备案应用（除单机资源）无法上架**，且非应用市场安装时
ColorOS 会弹「未经 OPPO 安全审核」/「未备案风险」提示（
[蒲公英 OPPO 安装注意事项](http://upload.pgyer.com/doc/app/oppo/APPKEY)、
[OPPO 应用上传流程](https://open.oppomobile.com/bbs/forum.php?mod=viewthread&action=printable&tid=6304)）。

也就是说：
1. **只要走侧载（官网/群文件分发），这台 OPPO 就一定会弹风险页**——与包好坏无关，属系统策略；
2. **要想让该提示消失，唯一正路是让包进入 OPPO 的安全审核库**（上架或在开放平台完成检测/报备）。

---

## 四、整改执行记录（v1.0.126，2026-09-12）

> 本节记录**实际已落地的代码改动与核验证据**，供申诉时作为"已整改"的凭据使用。

### 4.1 已完成的改动

| # | 动作 | 文件 | 结果 |
|---|---|---|---|
| 1 | 删除内置浏览器「下载任意 APK 后由本应用直接唤起安装」整条链路（`downloadAndInstallApk` / `openApkForInstall` / `canInstallApk` / `openInstallSourceSettings`）；网页触发的安装包改为**只下载到公共下载目录**，由用户自行点击安装 | `app/src/main/java/com/familychat/app/ui/BrowserActivity.java` | dex 中已无 `PackageInstaller` 引用 |
| 2 | 删除 `PackageInstaller` 会话静默直装（`installViaPackageInstaller` + `createSession/openSession/commit` + 结果回传 `PendingIntent`/`onNewIntent`/`handleInstallResult`） | `app/src/main/java/com/familychat/app/ui/UpdateActivity.java` | `PackageInstaller`/`getPackageInstaller`/`setAppPackageName`/`MODE_FULL_INSTALL`/`base.apk` 在 dex 中 **0 命中** |
| 3 | 删除「复制到公共下载目录再弹装」（`installViaPublicDownloads`）；安装只保留 `FileProvider` → 系统安装页一条路径 | 同上 | 同上 |
| 4 | 从包内移除历史调试证书 `assets/legacy_debug_cert.pem`，并从升级白名单删除对应读取逻辑（白名单只剩"本机证书 + 正式发布证书"） | `app/src/main/assets/`、`UpdateActivity.java` | `assets/` 仅剩 `dexopt/baseline.prof*`；`legacy_debug_cert` 0 命中 |

**保持不变（且更严）的部分**：更新包下载地址仍强制同源、真实版本号核对、SHA-256 完整性校验、
签名证书校验四重校验全部保留。

### 4.2 整改后产物的核验证据（可复现）

```
包名/版本 : com.familychat.app  versionCode=126  versionName=1.0.126
SHA-256   : 412EE31F036F0CD3B198DDD8E7789C526BA841CD8FDEDB737245F32AAF6E7750
签名证书  : CN=密语, OU=Miyu App, O=Miyu, L=Local, ST=Local, C=CN
证书 SHA-256: C60ACE246E5985F61D9A9CE0D90BEC7009392169843134C6B4BCFC456FEC4FD5（非 Android Debug）
签名方案  : v2=true, v3=true；android:debuggable 不存在
权限      : 无任何 SMS 权限；REQUEST_INSTALL_PACKAGES 仍在（仅用于自更新引导，见 4.3）
dex 扫描  : PackageInstaller / getPackageInstaller / setAppPackageName / MODE_FULL_INSTALL /
            base.apk / legacy_debug_cert / INSTALL_RESULT / installViaPublic  全部 0 命中
```

### 4.3 仍未解决的一项（改代码解决不了）

`REQUEST_INSTALL_PACKAGES` 权限**保留**：更新流程现在只做「下载 + 唤起系统安装页」，
但 Android 8.0+ 仍要求声明该权限才能把安装器拉起来（否则 `canRequestPackageInstalls()` 抛异常）。
即：应用**不再具备任何代用户装包的行为**，但清单里仍有这个权限名。
—— 这是风险评分里唯一剩下的"装包"字样，也是"未备案/未审核"之外最可能继续被标记的点。

若要彻底去掉，只能**放弃应用内自更新**，改为"检查到新版本 → 引导用户去官网/商店下载"。
这需要先有可上架的商店页或备案域名，属于产品决策，未在本轮改动中执行。

### 4.4 遗留的合规项（必须你去办）

1. **APP 备案 / ICP**（硬门槛，未备案者 OPPO 等明确不予上架）；
2. **软件著作权 / 电子版权证书**（OPPO 上架必需，主体需与开发者账号认证一致）；
3. **OPPO 开放平台提交审核**（1–3 个工作日），逐条填写敏感权限用途；
4. 上架后再走厂商误报申诉（附 4.2 的证据），主张"自动捆绑安装"标签已随整改消除。

> ⚠️ 预期管理：**整改后 OPPO 侧载仍会提示「未经 OPPO 安全审核 / 未备案风险」**——
> 那一条与包内容无关，属厂商策略。整改能争取的是"恶意行为"标签那一栏不再出现。

---

## 五、修复建议（按优先级）——原始分析，保留备查


## 五、原始整改清单（保留备查；P0-1 已于 v1.0.126 执行完毕，见第 4.1 节）

### P0-1 移除"自动捆绑安装"的实际特征（工程量小，收益最大）

| 动作 | 文件/位置 |
|---|---|
| 删掉内置浏览器"下载任意 APK 并安装"链路 | `app/.../ui/BrowserActivity.java`（`downloadAndInstallApk` / `openApkForInstall` / `canInstallApk` / `openInstallSourceSettings`），非 apk 下载保留 |
| 更新安装只保留 FileProvider → 系统安装页，删掉 `PackageInstaller` 会话静默直装 | `app/.../ui/UpdateActivity.java`（`installViaPackageInstaller` 及其回退分支） |
| 从包内移除历史调试证书 | `app/src/main/assets/legacy_debug_cert.pem`，签名白名单改为 BuildConfig 常量 |
| 评估 `REQUEST_INSTALL_PACKAGES` 是否随自更新一起下线 | `app/src/main/AndroidManifest.xml` |

> 注意：删掉自更新后，"检查更新"只能改为引导用户去商店/官网下载。
> 若自更新必须保留，**至少**去掉"任意 URL 的 APK 静默安装"这一层。

### P0-2 完成备案与安全审核（唯一能让提示消失的路径）

1. **APP 备案**（工信部，ICP/APP 备案）——没有它连上架资格都没有；
2. **软著 / 电子版权证书**——OPPO 上架硬性材料，需与账号认证主体一致；
3. **OPPO 开放平台**注册开发者 → 创建应用（包名 `com.familychat.app` 提交后不可改）
   → 上传包体 → 填**敏感权限说明**（CAMERA / RECORD_AUDIO / REQUEST_INSTALL_PACKAGES 必须逐条写用途）
   → 提交审核（1–3 个工作日）；
4. 上架后第三方渠道包应逐步收敛为"商店为主 + 官网兜底"。

> 现实提醒：OPPO 上架要求主体资质 + 软著 + 备案，个人/无资质主体基本走不通。
> 若短期无法满足，就要接受"OPPO 侧载必弹风险页"这个事实。

### P1 降低评分（配合申诉）

- `network_security_config.xml`：先固定域名 + HTTPS，再把全局 `cleartextTrafficPermitted="true"` 收紧（
  v1.0.124 那次收紧失败是因为写死了会变的局域网 IP，别再重蹈）；
- 统一分发名，不要同一 hash 用 `密语.apk`/`知网.apk`/`miyu.apk` 三名并存；
- 名称与图标（当前是「密语」+ 气泡图标，微信联想度高）建议去联想化。

### P2 申诉

- 拿整改后的正式包，通过 OPPO 开放平台/开发者社区提交**误报申诉**，附：
  包 SHA-256、正式证书 SHA-256（`C60ACE24…4FD5`）、本报告"标签 vs 事实"对照表；
- 同步在 VirusTotal 复检，其他厂商若也报，走各自申诉通道。

---

## 六、复现命令

```powershell
$bt='<本地路径>'; $apk='<本地路径>'
& "$bt\apksigner.bat" verify --print-certs --verbose $apk   # 正式签名，v2/v3=true
& "$bt\aapt.exe" dump permissions $apk                      # 核对无 SMS 权限
& "$bt\aapt.exe" dump xmltree $apk AndroidManifest.xml | Select-String debuggable
Get-FileHash $apk -Algorithm SHA256
# 整改核验：包内不应再有安装器相关引用
Add-Type -AssemblyName System.IO.Compression.FileSystem
$z=[System.IO.Compression.ZipFile]::OpenRead($apk)
($z.Entries | Where-Object { $_.FullName -match '^assets/' }).FullName   # 只应剩 dexopt/baseline.prof*
$z.Dispose()
```
