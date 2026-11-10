# Repository Hygiene, Build Config & Release Process Audit — `<本地路径>`

**Audit type:** read-only. No files modified.
**Date of evidence:** working tree at commit `052c9a0` (master), 46 uncommitted modifications present.
**Method:** `git ls-files`, `git check-ignore -v`, `git log --all --diff-filter=A`, `git show`, `git count-objects -vH`, content greps, on-disk size measurement.

---

## 0. Headline: the alarming premise was FALSE — and that is the main finding

The audit was commissioned on the assumption that signing passwords, keystores, and ~1 GB of video/APK binaries were committed to git. **They are not.** Every one of those items is present on disk and correctly ignored. Verified independently, not assumed:

| Claim in the brief | Verdict | Evidence |
|---|---|---|
| Keystore passwords committed | **FALSE** | `git check-ignore -v miyu-app/release/keystore.properties` → `.gitignore:19:keystore.properties` |
| `release.jks` tracked | **FALSE** | `git ls-files` (390 files) contains no `.jks`; ignored via `.gitignore:17:*.jks` |
| APKs tracked | **FALSE** | ignored via `.gitignore:5:*.apk` |
| `视频/1.ts`+`2.ts` (797 MB) tracked | **FALSE** | `git ls-files 'miyu-app/视频'` → empty; ignored via `.gitignore:26` |
| `WechatRecall/` + 46 MB `gradle-8.13-bin.zip` tracked | **N/A — path does not exist** | No `WechatRecall` dir; no `gradle-8.13-bin.zip` anywhere on disk |
| Gradle wrappers inconsistent (8.13 vs other) | **FALSE** | Both projects pin **8.14.3** |
| Secrets in git *history* | **FALSE** | Full sweep below — no secret/binary path was ever added, even transiently |

**Full history sweep (the decisive check):**
```
git log --all --pretty=format: --name-only --diff-filter=A   → 428 unique paths ever added
   | Select-String 'keystore|\.jks|\.pem|\.p12|\.key$|secret|credential|token|\.apk|\.ts$|\.zip$|\.db$|\.bak'
   → (no output)
```
Nothing sensitive was ever committed *and then removed* — so this is not a "clean worktree hiding a dirty history" situation. **`git filter-repo` / BFG are not required.** History rewriting is unnecessary and would be actively harmful here.

**Repo size:** `git count-objects -vH` → `size-pack: 938.76 KiB`, `in-pack: 1373`. On-disk working tree is **2.65 GB across 15,689 files** — a ~2,900× ratio that is entirely explained by ignored build output and runtime data. **Disk hygiene ≠ repo hygiene; the repo itself is lean and healthy.**

---

## 1. Findings, prioritized

### 🟡 MEDIUM-1 — Cleartext HTTP is globally enabled in the release APK, and `RELEASE.md` documents the opposite

**This is the most security-relevant real finding.** The docs assert a hardened posture that the shipped configuration does not implement.

`RELEASE.md:91` states:
> 默认禁止明文 HTTP | `network_security_config.xml` 的 `base-config` 为 `cleartextTrafficPermitted="false"`，公网/隧道域名强制 HTTPS

Actual content — **both committed and in the working tree** (verification below):
```xml
<!-- miyu-app/apk/FamilyChatApp/app/src/main/res/xml/network_security_config.xml -->
<base-config cleartextTrafficPermitted="true">
```
```
git show HEAD:.../network_security_config.xml | Select-String 'cleartextTrafficPermitted'
  →     <base-config cleartextTrafficPermitted="true">
```
So the *committed* baseline already permits cleartext globally; this is not merely uncommitted drift.

**Risk:** `cleartextTrafficPermitted="true"` means the OS will not block plaintext HTTP to *any* host. The real control is pushed entirely into client logic (`ServerProtocol.isSecure()` — app code, and app code is not a security boundary on an untrusted network). Combined with the release build having **no obfuscation** (§MEDIUM-2), an attacker on-path can inject into any downgraded request. The in-file comment argues this was a deliberate availability tradeoff after an IP-whitelist change broke login (`<内网IP>` → DHCP moved to `<内网网段>`). That reasoning is sound for a LAN-only deployment; the **defect is that `RELEASE.md` documents it as `false`**, so a future maintainer will trust a control that isn't there.

**Remediation:** Correct the doc to match reality, or implement the documented posture the file's own comment endorses ("要重新收紧，前提是先有固定域名 + HTTPS 部署"):
1. Fix the doc now: change `RELEASE.md:92` to say cleartext is `true` while LAN-only.
2. Then implement the intended control once a stable domain exists — replace the global `base-config` with a scoped `domain-config` for the named tunnel hostnames only, and set `base-config cleartextTrafficPermitted="false"`.

---

### 🟡 MEDIUM-2 — `minifyEnabled false` on the release build: reasonable, but the cost is unbounded and undocumented-by-measurement

```gradle
// miyu-app/apk/FamilyChatApp/app/build.gradle:74
minifyEnabled false
proguardFiles getDefaultProguardFile('proguard-android-optimize.txt'), 'proguard-rules.pro'
```

**Assessment: the decision is defensible, the process around it is not.** The in-repo justification is unusually good and I verified it is coherent: `proguard-rules.pro` already contains targeted R8 keep rules *including* an explicit fix for the exact failure mode (`-keepclassmembers,allowobfuscation class * { @com.google.gson.annotations.SerializedName <fields>; }`, lines 20-23). `RELEASE.md:175-195` documents the real incident: R8 renamed Gson field names → empty-body `400` on login, and a follow-up crash on login. Turning R8 back on without a full device regression would repeat that.

**The real cost:** no shrinking, no obfuscation, no optimization. Normally R8 cuts a chat app's DEX substantially; here everything is retained. Note the ProGuard rules are still *applied* but effectively inert for shrinking — yet `release-commit.bat:66` runs `publish-check-dex.ps1` to verify Gson field names survived, which is a genuine and clever guard.

**Risk:** (a) no obfuscation means the E2EE/wallet/mall logic is trivially reverse-engineerable — material for an app with a payment wallet; (b) larger APK; (c) the keep-rule set is unvalidated because R8 never runs, so the documented "待办" has no test coverage proving it would work.

**Remediation:** quantify the tradeoff before deciding — run one experiment and record the number in `RELEASE.md`, replacing the current qualitative "功能优先":
```bash
cd miyu-app/apk/FamilyChatApp
./gradlew assembleRelease -Pandroid.enableR8.fullMode=true   # baseline: record APK size
# then flip minifyEnabled true in a scratch branch, rebuild, record size + DEX method count
```
Then gate it properly in CI (§MEDIUM-4): `minifyEnabled true` on a branch, with the full device matrix from `RELEASE.md:193-195` (login, messaging, voice/video, scan, payment, mall order, multi-account, key-backup restore) plus a live `POST /api/register` asserting `409` not empty-body `400`.

---

### 🟡 MEDIUM-3 — No CI at all; the release gate is a manually-run local `.bat`

**Evidence:** no `.github/` anywhere in the tree; no `.gitlab-ci.yml`, `Jenkinsfile`, `azure-pipelines.yml`, `.travis.yml`, `circle.yml`, or `bitrise.yml`.

There is a *good* gate, it just isn't automated: `scripts/publish-check.bat` runs 7 checks (debug-cert, `debuggable`, sensitive permissions, Gson field names, versionCode consistency + publish-window freshness, unit tests, encoding guards) and `release-commit.bat` hard-wires 3 of them. Quality is clearly cared about here — the gap is that **nothing forces this to run**.

**Risk:** `publish-check.bat` must be invoked by hand; `release-commit.bat` is a `.bat` that a developer can bypass by running `gradlew assembleRelease` directly. The failure modes it guards against (per `RELEASE.md:25-28`: 30+ versions shipped from `assembleDebug`) are exactly the ones that recur without automation. Also `publish-check.bat` runs the unit tests via `run-unit-tests.ps1`, so **tests are build-wired but not CI-enforced**.

**Remediation — this is the highest-leverage single change.** Add `.github/workflows/ci.yml` running on push/PR:
```yaml
name: ci
on: [push, pull_request]
jobs:
  server:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-java@v4
        with: { java-version: '17', distribution: 'temurin' }
      - run: cd miyu-app/server-java && ./gradlew test shadowJar
  android:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-java@v4
        with: { java-version: '17', distribution: 'temurin' }
      - run: cd miyu-app/apk/FamilyChatApp && ./gradlew testDebugUnitTest
```
Critical constraint: the release APK job **cannot** run in CI without the keystore, and the keystore must never be a CI secret that a fork PR can exfiltrate. Keep `assembleRelease`+signing local; put `testDebugUnitTest` (no signing needed) and `check` in CI. Note the wrapper's `validateDistributionUrl=true` and the Tencent mirror in `server-java` may need adjusting for a GitHub runner.

---

### 🟡 MEDIUM-4 — Server tests bypass authentication by design: `token_version` revocation and JWT signing have no test coverage

`server-java/src/test` holds 5 classes (`AuthTest`, `DbTestSupport`, `GroupTest`, `MallOrderTest`, `WalletTest`). `AuthTest.java:14,18` exercises bcrypt path, and `:40` asserts `db.getTokenVersion(uid) > tv0` — good, revocation *is* tested at the DAO layer.

But `AuthHandler.requireAuth()` and the WS verifier — where the `tv` claim is actually *enforced* — are untested:
- `AuthHandler.java:234` — `// 吊销校验：tv 声明必须等于当前 token_version（改密后旧 token 失效）`
- `WsHandler.java:95` — same check on the WebSocket path

`DbTestSupport` also means tests build their own DB, so handler wiring (route registration, the Bearer/`?token=` fallback at `AuthHandler.java:247-265`) is not covered end-to-end. The untracked `scripts/e2e-backend-tests.ps1` (28 KB) does exercise HTTP-level cases including `old_password`/`new_password` flows and a `rlprobe` rate-limit probe — but it's a local script, not part of the build.

**Risk:** a regression that silently stops enforcing `tv` would leave every previously-issued JWT valid after a password change — a serious auth defect that no committed test would catch.

**Remediation:** promote `e2e-backend-tests.ps1` into CI (its HTTP-level assertions cover exactly this), or add JUnit tests that call `AuthHandler.requireAuth` with a stale-`tv` JWT and assert rejection.

---

### 🟢 LOW-1 — Private LAN IPs leaked into tracked docs and scripts

- `RELEASE.md:92` — `当前含实际部署地址 <内网IP>（另有历史地址 <内网IP>）`
- `scripts/deploy.bat:66` — `echo   LAN:  http://<内网IP>:5000`
- `scripts/deploy-all.sh:58` — `echo "  局域网:    http://<内网IP>:5000"`

**Risk:** Low — RFC1918 addresses are not routable from the internet and reveal only rough network topology + the practice of deploying on a home LAN. Not credentials. Worth cleaning because it also encodes *stale* addresses (the same drift documented in `network_security_config.xml`) that mislead operators.

**Remediation:** replace with placeholders in tracked files; keep real IPs in an untracked `local.properties`-style file. `RELEASE.md:92` should read `<内网IP>` with a note to run `ipconfig`.

---

### 🟢 LOW-2 — `.cloudflare-tunnel/familychat.yml` is tracked, but is a harmless placeholder template

**Tracked since the initial commit** — worth stating precisely, since this looks alarming at first glance:
```
git log --all --name-status -- '*familychat.yml*'
  aa63106 A  wechat-app/.cloudflare-tunnel/familychat.yml          # initial commit
  398f846 R100 wechat-app/... → miyu-app/.cloudflare-tunnel/familychat.yml   # rename
```
`git check-ignore` reports it as **NOT ignored** — a genuine `.gitignore` gap. **However the content is not sensitive**, and is byte-identical at `aa63106` and `HEAD`:
```yaml
tunnel: familychat
credentials-file: C:\Users\<you>\.cloudflared\familychat.json
ingress:
  - hostname: chat.your-domain.com
    service: http://localhost:5000
  - hostname: download.your-domain.com
```
The hostnames are literal `your-domain.com` **placeholders** — no real domain was ever committed. The actual tunnel credential JSON lives at `C:\Users\<you>\.cloudflared\familychat.json`, i.e. **outside the repo, never committed**. `start-tunnel.bat:56` actively `findstr`s for the `your-domain.com` marker to detect "unconfigured" and fall back to a quick tunnel — so the placeholder is intentional, functioning design, not an oversight.

**Only real exposure:** the local username in `C:\Users\<you>\...` (trivial) and a `no-store`-era habit of tracking environment config.

**Remediation:** commit `familychat.yml.example` with placeholders and gitignore the real `familychat.yml`, so that when someone *does* fill in a real domain + real credentials path it cannot be committed:
```gitignore
miyu-app/.cloudflare-tunnel/familychat.yml
miyu-app/.cloudflare-tunnel/*.json
miyu-app/.cloudflare-tunnel/cert.pem
```
(No `cert.pem` exists today — verified.) If already-filled-in configs were ever committed, `git filter-repo --path ... --invert-paths` would apply; **not needed now.**

---

### 🟢 LOW-3 — 46 uncommitted modifications, including a whole new untracked test tree

46 entries in `git status --short`, mixing three different things:
- **Legitimate new work, untracked:** `miyu-app/apk/FamilyChatApp/{app,core,feature-chat}/src/test/`, `scripts/publish-check.bat`, `scripts/e2e-backend-tests.ps1`, `scripts/loadtest-backend.ps1`, `run-unit-tests.ps1`, `test-quality-guards.ps1`, `publish-check-dex.ps1`, `DEPLOY.md`, `README.md`, `ARCHITECTURE.md`, `_apkcheck/`
- **Real source edits:** ~20 `.gradle`/`.java`/`.xml` files
- **Noise/artifacts:** `dump-classpath.gradle`, `deploy/`, `signing-key-backup-note.md`

**Risk:** the new test infrastructure — the single most valuable recent addition — is **entirely untracked**. A `git clean -fd` or a lost machine destroys it. `release-commit.bat:101` does `git add -A`, so the next release commit would sweep all of it (including `_apkcheck/*.apk`… which is `.apk`-ignored, so safe) into one commit.

**Remediation:** commit the test tree and scripts as their own reviewed commit *before* the next release, so the release commit stays a release commit:
```bash
git add miyu-app/apk/FamilyChatApp/*/src/test miyu-app/scripts/*.ps1 miyu-app/scripts/publish-check.bat
git commit -m "test: add Android unit tests + release gate scripts"
```

---

### 🟢 LOW-4 — Duplicated Android framework stubs in the new test tree

`android/util/Base64.java` (3,028 bytes) and `android/util/Log.java` (2,379 bytes) are **byte-identical** copies in two places:
- `apk/FamilyChatApp/app/src/test/java/android/util/{Base64,Log}.java`
- `apk/FamilyChatApp/feature-chat/src/test/java/android/util/{Base64,Log}.java`

**Risk:** Low but real drift hazard — the two copies must stay in sync; when one is fixed (e.g. a `Base64` edge case) the other silently keeps the old behavior, and a test could pass in one module and fail in another for the wrong reason. Note `core` and the other 4 modules have no copy, so coverage is also uneven. Note `build.gradle` uses `unitTests.returnDefaultValues = true`, which is the *other* technique for this problem — the repo is using both approaches.

**Remediation:** move the stubs into a single shared test-fixture module:
```gradle
// new :test-fixtures (com.android.library) with src/main/java/android/util/{Base64,Log}.java
testImplementation project(':test-fixtures')
```
Or drop the hand-written stubs and rely solely on `returnDefaultValues = true` plus real unit tests for the Base64 logic.

---

### 🟢 LOW-5 — `release-commit.bat` uses `git add -A`

`scripts/release-commit.bat:101` — `git add -A` stages everything in the tree. It is guarded by a printed `git status --short` and a Y/N confirm (lines 103-107), which is better than nothing. But `.gitignore` is the only thing standing between this command and committing `release.jks`, `keystore.properties`, or a filled-in tunnel config — and `.cloudflare-tunnel/familychat.yml` is **currently not ignored** (§LOW-2).

**Remediation:** prefer explicit staging, or add a pre-commit guard that fails if any staged path matches the secret patterns:
```bash
# .git/hooks/pre-commit  (or use pre-commit / gitleaks in CI)
git diff --cached --name-only | grep -Ei '\.(jks|keystore|pem|p12)$|keystore\.properties|\.cloudflared' && {
  echo "BLOCKED: signing/credential file staged"; exit 1; }
```
Plus a `gitleaks detect --no-git` job in the CI workflow from §MEDIUM-3.

---

### 🟢 LOW-6 — Missing LICENSE; product is a near-clone of a well-known app

No `LICENSE`, `COPYING`, or license header anywhere; the only match for `README*` is `miyu-app/README.md`. Dependencies are a mix of Apache-2.0 (AndroidX, OkHttp, Gson, Media3), MIT (Javalin, java-jwt, jbcrypt, pinyin4j), BSD (ZXing), and **GPL-with-classpath-exception concerns are not present, but `com.github.yalantis:ucrop` and `io.getstream:stream-webrtc-android` warrant a check**. The project is described in-repo as "全仿QQ" (`git log`: 群聊全仿QQ, 消息页全仿QQ混排) and the brief notes a `知网.apk`/`密语.apk` brand mix.

**Risk:** Low technically, real commercially — absent a LICENSE the code is all-rights-reserved by default *and* a clone-style UI carries trade-dress risk. Also the release artifacts mix two brands (知网 / 密语 / miyu) which is a product-identity problem, not a build one.

**Remediation:** add an explicit `LICENSE` (or a proprietary "all rights reserved" notice if that's the intent); run a license audit over the Gradle dependency tree; finish the 知网→密语 rename so `release/知网.apk` stops shipping.

---

### 🟢 LOW-7 — `server-java/gradle.properties` forces GBK encoding, contradicting the ASCII-path migration

```properties
# miyu-app/server-java/gradle.properties
org.gradle.jvmargs=-Xmx512m -Dfile.encoding=GBK
```
The Android project's comment (`gradle.properties`) records that the tree was migrated to a pure-ASCII path (`<本地路径>`) specifically so these workarounds could be dropped and "可以删掉下面两行". The server project still forces `GBK` while its own `build.gradle:32` sets `options.encoding = 'UTF-8'`, and `release-commit.bat:89` launches the server with `-Dfile.encoding=UTF-8`. Three different encodings in one pipeline is a reliable source of mojibake in logs and of the argfile `ClassNotFoundException` the comment describes.

**Remediation:** since the path is now ASCII, drop `-Dfile.encoding=GBK` and use UTF-8 consistently; verify with `./gradlew test`.

---

## 2. Verified-clean items (recorded so they are not re-litigated)

**Secret handling is genuinely good.** I looked for live credentials with targeted patterns across `.java/.yml/.json/.properties/.bat/.sh/.md/.ps1/.xml/.gradle`:
```
(sk-[A-Za-z0-9_\-]{16,}|AKIA[0-9A-Z]{16}|ghp_[A-Za-z0-9]{20,}|Bearer\s+[A-Za-z0-9_\-\.]{20,}|AIza[0-9A-Za-z_\-]{30,}|xox[baprs]-)
→ No matches found
```
The only credential-shaped strings are obvious test fixtures: `AuthTest.java:14,18` (`"secret123"`), `e2e-backend-tests.ps1` (`'pass1234'`, `'wrongpass'`), `loadtest-backend.ps1:289`, `GsonContractTest.java:98`.

- **`ServerConfig.java` — clean by design.** JWT secret from `System.getenv("JWT_SECRET")` (line 27) else a 256-bit `SecureRandom` value (lines 83-86) persisted to the gitignored `data/jwt_secret.key`. No hardcoded default secret, no fallback constant. The missing-key path (lines 59-79) prints a genuinely excellent warning explaining that all users will be logged out and that restoring from backup is the correct recovery. **This is better than most production code** — it refuses to silently rotate a key that protects an existing database.
- **`AiHandler.java` — clean by design.** DeepSeek key from `env DEEPSEEK_KEY` or gitignored `data/deepseek.key` (lines 53-60); the class comment (line 20) explicitly states the reason: *"避免把 API Key 放进 APK（可被反编译提取）"*. Correct architecture. Also has per-user rate limiting (lines 34-48) and truncates/validates message roles — good hygiene.
- **Keystore password strength.** `release/keystore.properties:4,6` holds a single random 32-char alphanumeric value (≈190 bits) used for both `storePassword` and `keyPassword`. Strong; identical reuse is fine for a single-key JKS. `keystore.properties.example` is a **safe template** — its values are literal Chinese placeholders (`storePassword=在此填入 storePassword`), no real secret. (Per instruction, no password value is reproduced in this report.) The real file's header comment even instructs immediate off-site backup in two locations.
- **Log redaction.** `FamilyChatServer.java:91-94` redacts `?token=` from query strings before logging, with the reason stated (`否则身份凭证明文落盘`). `AiHandler.java:166` avoids logging response bodies because they may contain chat content. Both are above-average.
- **Password storage.** `UserDao.java:29` bcrypt on registration; `:80-90` transparently migrates legacy SHA-256 hashes to bcrypt on next successful login (with a `[MIGRATED]` log). Correct, and unusually careful.
- **Secret-dependent files correctly ignored:** `.idea/`, `.vscode/`, `.claude/settings.local.json` (`.gitignore:35`), `android-verify.log` (`*.log`), `server-java/data/`, `build/`, `.gradle/`, `.playwright-mcp/`. All confirmed **not tracked** and confirmed ignored via `git status --ignored` (`!!` markers).

> ⚠️ 本条提及的 `android-verify.log` 已于 2026-10-01 删除
> （未入库、被 .gitignore 忽略，见 commit e329fdf）。

**One nuance worth flagging about `.claude/`:** `.claude/settings.local.json` is ignored, but **`.claude/settings.json` IS tracked** (along with 9 other `.claude/` files). I read it — it contains only permission allowlist entries and one `additionalDirectories` path. **No secrets**, but it does leak a stale absolute path from a previous machine/location: `<本地路径>`. Harmless, but it's dead config for a project that now lives at `<本地路径>`, and it's the kind of personal-path leakage that should be gitignored alongside `.claude/settings.local.json`.

---

## 3. On-disk footprint (not repo footprint)

Measured recursively. This is **disk** hygiene, entirely untracked:

| Path | Size | Tracked? | Notes |
|---|---|---|---|
| `miyu-app/server-java/data/` | **1,058.03 MB** (134 files) | No — `.gitignore:23` | Runtime DB + uploads + transcode cache. **Live production data.** |
| `miyu-app/视频/` (`1.ts` 476,954,120 B; `2.ts` 320,320,228 B) | **760.34 MB** | No — `.gitignore:26` | Short-video source material. |
| `miyu-app/release/` | **297.11 MB** (11 files) | No — `*.apk`, `*.apk.bak*`, `*.jks` | 3 identical 51,553,570 B APKs + 3 `.apk.bak` (~50 MB each). |
| `miyu-app/server-java/build/` | **169.73 MB** (104 files) | No — `build/` | |
| `_apkcheck/` | **51.29 MB** (3 files) | No — `*.apk` | `miyu.apk` (53,761,536 B) + 2 AV-analysis `.md` reports. |
| `miyu-app/apk/FamilyChatApp/.gradle/` | 16.18 MB | No — `.gradle/` | |
| `.playwright-mcp/` | 0.72 MB (40 files) | No — `.gitignore:29` | Logs + PNGs. |
| `.idea/` | 0.01 MB (8 files) | No — `.gitignore:39` | |
| `.vscode/` | ~0 MB (1 file) | No — `.gitignore:40` | |
| `android-verify.log` | 0.01 MB | No — `*.log` | |
| **Total working tree** | **2.65 GB** / 15,689 files | — | vs **938.76 KiB** packed in git. |

> ⚠️ 本条提及的 `android-verify.log` 已于 2026-10-01 删除
> （未入库、被 .gitignore 忽略，见 commit e329fdf）。

**Assessment:** correct behavior. The `视频/` and APK duplication is wasteful on disk (~1.05 GB reclaimable) but the repo is unaffected. The `release/` directory holding 3 byte-identical APKs **plus 3 stale `.apk.bak` files** is a deployment convenience that is deliberately untracked (`release-commit.bat:75` comments *"三个名字都要，服务器伺服的是 密语.apk"*).

**Fragility note on the 3 APKs (verified, not assumed):** `密语.apk`, `知网.apk`, `miyu.apk` are **byte-identical** — same SHA-256 `412EE31F036F0CD3B198DDD8E7789C526BA841CD8FDEDB737245F32AAF6E7750`, same size. In `_apkcheck/`, `miyu.apk` is a *different* build (SHA-256 `58AE9626...`, 53,761,536 B — i.e. the older v1.0.125-era 51.3 MB artifact). So the triplication is a **manual brand-fallback step**, replicated in `release-commit.bat:78-80`, `deploy.bat:39-41`, and `build-all.sh:28-30` — the same three `copy /Y` lines in three scripts. This is a maintenance smell (change one, forget two) but **not a correctness bug**: the three names are the same artifact, so drift would require someone to hand-edit one copy. The `知网` name is the legacy brand; the fix is to serve one canonical name and 302 the aliases.

Suggested disk reclaim (safe, all untracked/generated):
```powershell
Remove-Item -Recurse -Force miyu-app\server-java\build, miyu-app\apk\FamilyChatApp\*\build
Remove-Item miyu-app\release\*.apk.bak
# keep server-java\data\ (live data) and 视频\ (content) but move to a non-repo volume
```

---

## 4. Build configuration quality

**Version catalog: absent.** No `gradle/libs.versions.toml` in either project (verified via glob). Versions are inline string literals in each `build.gradle`, and AGP is pinned directly in the root file:
```gradle
// miyu-app/apk/FamilyChatApp/build.gradle
id 'com.android.application' version '8.7.3' apply false
```

**Cross-module duplication (real, and a live inconsistency risk).** Three of the 7 Android modules declare overlapping dependencies. `glide` appears in `app`; `pinyin4j`, `ucrop`, `zxing`, `stream-webrtc-android`, `security-crypto`, `media3` in `app` — while `core/build.gradle` uses `api` for the shared ones:
```gradle
// core/build.gradle
api 'com.squareup.okhttp3:okhttp:4.12.0'
api 'com.google.code.gson:gson:2.11.0'
implementation 'androidx.security:security-crypto:1.1.0-alpha06'
implementation 'com.google.zxing:core:3.5.3'
```
and `app/build.gradle` **re-declares the same coordinates at the same versions** (lines 137, 142, 156, 163). `security-crypto` and `zxing:core` are thus declared twice (once `api`/`implementation` in `core`, once in `app`). Today the versions agree by hand-coincidence; there is nothing enforcing it. `compileOptions` (`JavaVersion.VERSION_17`) is likewise copy-pasted verbatim into `app` and `core`.

**SDK levels — consistent:** `compileSdk 34`, `targetSdk 34`, `minSdk 24` in `app`; `compileSdk 34` / `minSdk 24` in `core`. **This is now outdated:** Android 15 is API 35, and Play has required `targetSdk 35` for new submissions since Aug 2025. `targetSdk 34` will block store listing, which matters because `RELEASE.md:161-162` explicitly says the remaining OPPO warning "必须靠备案与上架解决" — i.e. store listing is a stated goal that `targetSdk 34` obstructs.

**Dependency freshness:** mostly recent and sane — `okhttp 4.12.0`, `gson 2.11.0`, `glide 4.16.0`, `media3 1.3.1`, `zxing 3.5.3`, `pinyin4j 2.5.1`, `javalin 6.3.0`, `java-jwt 4.4.0`, `sqlite-jdbc 3.46.0.0`, `jbcrypt 0.4`, JUnit 4.13.2/Jupiter 5.11.0. Two stand out: `androidx.security:security-crypto:1.1.0-alpha06` is **still an alpha** used for the wallet's encrypted storage, and `appcompat 1.6.1` / `material 1.11.0` trail current releases. `pinyin4j 2.5.1` is unmaintained upstream.

**`server-java` toolchain:** Java 17 `sourceCompatibility`/`targetCompatibility` (not a `toolchain {}` block, so it silently depends on whatever JDK runs Gradle), `shadow` plugin `8.1.1` with a correct fat-jar setup (`archiveClassifier ''`, `archiveVersion ''` → stable `familychat-server.jar`), and *explicit* `dependsOn(shadowJar)` wiring for `startScripts`/`distTar`/`distZip`/`startShadowScripts` — a real Gradle 8 task-dependency pitfall handled correctly and explained in-comment. Tests are properly isolated to `build/test-data` (line 52) so they cannot touch production `data/`. **This file is high quality.**

**Gradle wrappers — consistent, and the brief's premise is wrong.** Both pin **8.14.3**:
```
miyu-app/apk/FamilyChatApp/gradle/wrapper/gradle-wrapper.properties
  distributionUrl=https\://services.gradle.org/distributions/gradle-8.14.3-bin.zip
miyu-app/server-java/gradle/wrapper/gradle-wrapper.properties
  distributionUrl=https\://mirrors.cloud.tencent.com/gradle/gradle-8.14.3-bin.zip
```
Only the **mirror differs** — the server uses a Tencent mirror, Android uses `services.gradle.org`. On a CI runner or outside China the mirror is a third-party supply-chain dependency for a build tool; note `validateDistributionUrl=true` is set (good), but the mirror is not checksum-stable in the same way as the canonical URL. **The `gradle-8.13-bin.zip` "smell" does not exist** — no such file on disk, and no `WechatRecall` directory anywhere in the tree.

**No `toolchain {}` declaration** means the build depends on `JAVA_HOME`; `build-apk.bat:41-46` hardcodes fallbacks to `<本地路径> Files\Eclipse Adoptium\jdk-17.0.12.7-hotspot` and `jdk-21.0.4.7-hotspot`, and `.claude/settings.local.json` shows JDK 18/21/24 attempts. That's environment fragility, not a repo defect, but it will bite in CI.

---

## 5. Release/packaging process

`scripts/` (16 files) plus `RELEASE.md` (201 lines), `DEPLOY.md`, `ARCHITECTURE.md`. The scripts are unusually well-engineered for a solo project — `start-tunnel.bat` opens with an ASCII-only mandate explaining that `cmd.exe` parses `.bat` in the ANSI codepage *before* `chcp 65001` takes effect and that byte `0x85` acts as a line-continuation character, silently eating lines. `RELEASE.md:30-34` repeats the rule, and `publish-check.bat:13-17` explains why it locates the APK by `release\*.apk` wildcard rather than its Chinese filename. **These are hard-won, correctly-diagnosed lessons.**

**Release checklist: partly automated, manual at the critical step.** `publish-check.bat` automates 7 checks with a pass/fail tally and an explicit `[X] DO NOT PUBLISH`. `release-commit.bat` automates 6 steps and hard-fails on 3 checks (debug cert at line 59-60, `debuggable` at 61-62, Gson field names at 66-71). But:
- The version bump is an interactive `set /p BUMP=` prompt (line 35) — cannot run unattended.
- `RELEASE.md:105` step 4 ("复制 APK") is manual prose, duplicated as scripted `copy` in three files.
- `RELEASE.md:128-137` is a **manual checklist** where only the first two items are machine-checked.
- `git add -A` + interactive confirm (lines 101-107).

**`adb`-based hacks: none found.** Grep for `adb ` across scripts and docs returned nothing — install verification is done via `apksigner`/`aapt` static inspection plus the in-app updater, which is *better* than adb-driven smoke tests. No `adb install`/`adb push` anywhere. (Reporting this as verified-absent rather than leaving it implied.)

**Hardcoded values in scripts (all environment-specific, none secret):**
- `ANDROID_HOME=<本地路径>` in `release-commit.bat:50`, `deploy.bat:26`, `build-all.sh:19`; `<本地路径>` in `publish-check.bat:21`; `build-tools\34.0.0` in `release-commit.bat:57`. Build-tools pinned to `34.0.0` while AGP is 8.7.3 — a stale pin that will break when build-tools are updated.
- **`C:\Users\<you>\bin\cloudflared.exe`** hardcoded in `start-tunnel.bat:23,30` and `随机域名.bat` (the entire file: `C:\Users\<you>\bin\cloudflared.exe tunnel --url http://localhost:5000`). Unusable on any other machine.
- Server restart is `netstat -ano | findstr ":5000"` → `taskkill /F /PID` (`release-commit.bat:86`, `deploy.bat:46`) — a blunt but functional "find by port, kill" with no PID tracking. It will kill an unrelated process that happens to hold :5000.
- `release-commit.bat:89` launches the server with `start /MIN ... cmd /c "java -jar ..."` — **no restart-on-crash, no service wrapper, no log rotation**, and the JVM is tied to an interactive console session.

**Tunneling setup (per the brief's item 4):** two modes, correctly distinguished in `start-tunnel.bat:52-78`. If `familychat.yml` still contains the `your-domain.com` marker → **quick tunnel** (`cloudflared tunnel --url http://localhost:5000`), which issues a **new random `*.trycloudflare.com` domain on every restart** — the script prints a loud warning that saved server addresses on family phones will stop working. Otherwise → **named tunnel** (`cloudflared tunnel --config ... run familychat`) for a stable domain. `随机域名.bat` is just the quick-tunnel one-liner (its name means "random domain"). This is a coherent design; the fragility is inherent to quick tunnels (no auth, public URL, anyone can reach the server) and is disclosed in-script.

**Server-side publish-window protection is genuinely good design** (`RELEASE.md:122`): when `version.properties` is newer than the APK, `/version.json` returns `published=false` and `/apk` returns 404 — closing the "server advertises v126 but serves the v125 APK" race that `RELEASE.md:141-143` records as a real past incident. Clients additionally verify SHA-256, the real APK version, and the signing certificate before install (lines 43-46).

**One residual server-side concern** (already self-documented in `docs/audits/server-AUDIT-REPORT.md:400-405`): `FamilyChatServer.java:212` trusts `Pattern.compile("^.*\\.trycloudflare\\.com$")`. `trycloudflare.com` is a **shared public suffix** — *anyone* can spin up a free quick tunnel and obtain a matching hostname. The repo's own audit correctly notes the practical risk is Low because auth is `Authorization: Bearer` (not cookies) and no `Access-Control-Allow-Credentials` is sent. I agree with that assessment; it should still be made configurable via `CORS_ORIGINS` and the wildcard dropped or dev-gated.

---

## 6. Could not verify / caveats

- **Not run:** any Gradle build, APK signing/verification, or server start — all are prohibited (mutating) for this read-only audit. Statements about R8 output size, `assembleRelease` success, and test pass counts are **not** independently confirmed; I relied on in-repo documentation (`RELEASE.md:191` claims 52 unit tests pass in the *Android* project) and on static inspection.
- **`apksigner`/`aapt` were not executed**, so the claim that `release/密语.apk` is correctly signed with the release key (not `Android Debug`) is **unverified**. Given that `release-commit.bat:59-62` hard-gates it, it is likely fine — but the brief's assumption that these 3 APKs are release-signed should be confirmed by running `publish-check.bat`.
- **The keystore's key strength** (RSA 2048, valid to 2056 per `RELEASE.md:75`) was not independently inspected — that would require opening the JKS with the password, which I deliberately did not do. The password's entropy was characterized from its length/charset without reproducing the value.
- **Git history depth:** the repo has ~20+ commits on `master` only (no other branches, no remotes inspected). The `--all` sweep covers all refs present locally; if this clone is shallow or missing remote refs, an object not reachable locally would be missed. Nothing in `git count-objects` suggests a shallow clone.
- `README.md`, `ARCHITECTURE.md`, `DEPLOY.md`, and `E2EE_SECURITY_AUDIT.md` were searched but not read in full; findings are drawn from `RELEASE.md`, which is the authoritative release document.
- `_apkcheck/*.md` (the OPPO/AV reports) were **not** read in depth; they are referenced by `RELEASE.md` as the evidence base for the compliance changes, and the version.properties changelog corroborates the removal of the three auto-install paths.

---

## 7. Recommended action order

| # | Action | Effort | Why first |
|---|---|---|---|
| 1 | Commit the untracked test tree + gate scripts (§LOW-3) | 5 min | Highest value at highest risk — one `git clean -fd` destroys it |
| 2 | Correct `RELEASE.md:92` to match the real cleartext setting (§MEDIUM-1) | 5 min | Doc currently asserts a control that does not exist |
| 3 | Add `.github/workflows/ci.yml` running both test suites (§MEDIUM-3) | 1 h | Converts a manual `.bat` into an enforced gate |
| 4 | Gitignore the real tunnel config, commit a `.example` (§LOW-2) | 15 min | Closes the only live path by which a real secret *could* be committed |
| 5 | Add a `pre-commit` secret guard + `gitleaks` in CI (§LOW-5) | 30 min | Makes the excellent current hygiene structurally enforced |
| 6 | Promote `e2e-backend-tests.ps1` into CI for `tv`-revocation coverage (§MEDIUM-4) | 1 h | Only gap in auth-critical test coverage |
| 7 | De-duplicate `core`/`app` dependency declarations; add `libs.versions.toml` (§4) | 2 h | Prevents version drift; enables SDK-35 bump |
| 8 | Measure + decide on `minifyEnabled` (§MEDIUM-2) | 1 day | Real security/APK-size gain, needs a device regression |
| 9 | Bump `targetSdk` to 35; add `LICENSE`; finish 知网→密语 rename (§4, §LOW-6) | 1 day | Store-listing blockers, per `RELEASE.md:161` |

**Bottom line:** this repository is in **far better shape than the brief anticipated**. Secret management is deliberate and correct (env vars + gitignored key files, with `ServerConfig` refusing to silently rotate a JWT secret and `AiHandler` explicitly keeping the API key out of the APK). History is clean — no rewriting needed. The real, defensible findings are: an unautomated release gate, a documentation/implementation mismatch on cleartext traffic, obfuscation disabled for documented-and-valid reasons, and untracked test infrastructure that should be committed before it is lost.
