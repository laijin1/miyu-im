# FamilyChatApp — Optimization & Refactoring Audit

**Scope:** `<本地路径>` — READ-ONLY analysis.
All `build\` directories ignored. Evidence gathered from `src\main\`, `src\test\`, and Gradle files.

**Headline numbers**

| Metric | Value |
|---|---|
| Java files (non-build) | ~110 |
| `new Thread(` call sites | **169** across ~50 files |
| Total `findViewById()` calls | **>400** (ChatActivity 28, GroupChatActivity 31, MainActivity 71, MallActivity 62) |
| XML layouts | 91 |
| Hardcoded `android:text` in layouts | 397 |
| Hardcoded dp/sp literals in layouts | **2330** |
| Unit test files | 5 (+4 duplicated Android stubs) |

---

# 🔴 CRITICAL

## C1. Global static auth listener is clobbered by CallActivity → 401 auto-logout silently dies

**Evidence**
- `core\src\main\java\com\familychat\app\api\ApiClient.java:39` — `private static AuthFailureListener authListener;`
- `ApiClient.java:41-51` — `setAuthFailureListener()` / `clearAuthFailureListener()` mutate that single static.
- `feature-chat\...\ui\CallActivity.java:165` — installs its **own** listener, overwriting MainActivity's.
- `feature-chat\...\ui\CallActivity.java:930` — `ApiClient.clearAuthFailureListener();` inside `onDestroy()`.
- `app\...\ui\MainActivity.java:190-199` — the comment itself admits the coupling:
  > *"Called from onCreate and re-registered in onResume, because CallActivity clears it in onDestroy — without re-registration the 401 auto-logout would stop working."*

**Problem.** A single process-global slot is written by two Activities and cleared by one of them. Any Activity that is *not* MainActivity (e.g. `MallActivity`, `WalletActivity`, `GroupChatActivity` — none of which re-register) will keep firing requests with an expired token after a call ends, receive 401s, and **never force a logout**. Users see a permanently broken UI that only a manual reinstall fixes. This is a correctness *and* security-adjacent bug (sessions that should be terminated stay alive in a zombie state).

**Fix.** Delete both Activity registrations. Register **once** in `FamilyChatApp.onCreate()` against an application-scoped handler, and never clear it:
```java
// FamilyChatApp.java
ApiClient.setAuthFailureListener(() -> {
    PreferencesManager.getInstance(this).logout();
    Intent i = new Intent(this, LoginActivity.class)
        .addFlags(FLAG_ACTIVITY_CLEAR_TASK | FLAG_ACTIVITY_NEW_TASK);
    startActivity(i);
});
```
Remove `clearAuthFailureListener()` from `CallActivity.onDestroy()` and `MainActivity.onDestroy()` entirely.

---

## C2. Thread explosion — 169 ad-hoc `new Thread` sites, no pool, no cancellation, no lifecycle

**Evidence.** Representative samples (full grep = 169 hits):
- `MainActivity.java:418, 464, 479, 633, 653, 665, 816, 836, 881, 967, 1092, 1209, 1219, 1229, 1243, 1258` (16 sites)
- `ChatActivity.java:328, 386, 548, 591, 746, 868, 1004, 1099, 1157, 1348, 1392, 1734, 1786` (13 sites)
- `GroupDetailActivity.java:156, 201, 260, 287, 316, 343, 377, 400, 417, 436, 490, 509` (12 sites)
- `GroupChatActivity.java:385, 530, 545, 654, 720, 783, 832, 944, 1066, 1278, 1317, 1407` (12 sites)

Canonical shape, from `CallActivity.java:664-672`:
```java
private void reportCallStatus(String event) {
    if (api == null || remoteUser == null) return;
    final int friendId = remoteUser.id;
    final long start = callStartTime;
    new Thread(() -> {
        try {
            int dur = start > 0 ? (int) ((System.currentTimeMillis() - start) / 1000) : 0;
            if (dur < 0) dur = 0;
            api.reportCallStatus(friendId, event, dur);
        } catch (Exception e) {
            Log.e(TAG, "reportCallStatus failed: " + e.getMessage());
        }
    }).start();
}
```
And the polling loop, `ChatActivity.java:1099-1104` — **a brand-new thread every 5 seconds, forever**:
```java
new Thread(() -> {
    try {
        fetchNewMessages();
    } catch (Exception ignored) {}
    pollHandler.postDelayed(this, POLL_INTERVAL_MS);
}).start();
```

**Problem.** Each call allocates and destroys an OS thread (~1MB stack reserved). During scrolling, message send, and avatar loads this creates bursty thread churn and GC pressure. Threads hold strong refs to their capturing Activity (implicitly, via lambda `this`), so a slow 30s-timeout request outlives a finished Activity — a leak that no `onDestroy` can stop. There is no way to cancel in-flight work when the user leaves the screen.

**Fix.** Introduce one application-scoped executor in `:core`:
```java
// core/.../utils/AppExecutors.java
public final class AppExecutors {
    public static final ExecutorService IO = Executors.newFixedThreadPool(4);
    public static final Executor MAIN = cmd -> new Handler(Looper.getMainLooper()).post(cmd);
}
```
Replace all `new Thread(() -> {...}).start()` with `AppExecutors.IO.execute(() -> {...})`. For the poll loop specifically, replace the whole thing with a single `ScheduledExecutorService` task or `Handler.postDelayed` + executor (no new thread per tick).

---

## C3. `ChatActivity.onCreate` is a 558-line method

**Evidence.** `ChatActivity.java:172` `protected void onCreate(Bundle savedInstanceState) {` … next method declaration is `private String queryDisplayName(Uri uri)` at **`ChatActivity.java:730`**. Inside, `else if` appears 29 times.

**Problem.** A single method performs: view binding, intent parsing, E2EE key bootstrap, WebSocket listener wiring, adapter construction, ~150 lines of programmatic voice-bubble UI building, permission setup, polling start, and menu wiring. It is untestable, unreviewable, and any merge conflict is unresolvable. Cold-start cost is bundled: everything runs before first frame even for a text-only chat.

**Fix.** Decompose into `onCreate` + private phase methods, then extract aggressively (see C4): `bindViews()`, `parseIntent()`, `initE2ee()`, `initWebSocket()`, `initAdapter()`, `initPlusPanel()`, `initPermissions()`, `startPolling()`.

---

## C4. RecyclerView `onCreateViewHolder` builds an entire voice bubble in code (~150 lines)

**Evidence.** `ChatActivity.java:2006-2150+`. `onCreateViewHolder` inflates `item_msg_me`/`item_msg_other`, then manually constructs `FrameLayout`, `GradientDrawable`, `LinearLayout`, `TextView`, `WaveformView`, sets tags like `"voice_container"`, `"voice_row"`, `"voice_play_btn"`, `"voice_waveform"`, `"voice_duration"`:
```java
// ChatActivity.java:2018-2035
android.widget.FrameLayout voiceContainer = new android.widget.FrameLayout(ChatActivity.this);
voiceContainer.setVisibility(View.GONE);
voiceContainer.setTag("voice_container");
android.graphics.drawable.GradientDrawable bubbleBg = new android.graphics.drawable.GradientDrawable();
bubbleBg.setShape(android.graphics.drawable.GradientDrawable.RECTANGLE);
bubbleBg.setCornerRadius(dp(8));
if (viewType == TYPE_ME) { bubbleBg.setColor(getColor(R.color.bubble_self)); }
else { bubbleBg.setColor(getColor(R.color.bubble_other)); }
```
**Problem.** Allocating ~10 view objects + 2 drawables per voice-bubble ViewHolder is expensive during fling. `setTag("...")` + `findViewWithTag()` lookups replace what should be typed ViewHolder fields — losing compile-time safety and paying a view-tree traversal on every bind. The identical block is **duplicated** in `GroupChatActivity`.

**Fix.** Move to `res/layout/item_msg_voice_me.xml` / `item_msg_voice_other.xml` with a `<merge>`-friendly shape drawable. Declare typed fields on `VH`. Delete the tag-based lookup pattern.

---

# 🟠 HIGH

## H1. `WebRTCManager` is passed Activity-lifecycle Views and never nulled

**Evidence.** `WebRTCManager.java:74-75` — `private final SurfaceViewRenderer localRenderer; private final SurfaceViewRenderer remoteRenderer;` (both `final`, both Views). Constructed in `CallActivity.java:214-224` with `new SurfaceViewRenderer(this)`.
`WebRTCManager.java:775-805` also stores `relayLocalPreviewView` (TextureView), `relayLocalPreviewFrame` (ImageView), `relayRemoteView` (ImageView).

**Problem.** Views hold a strong reference to their `Context` = the Activity. `WebRTCManager` is a long-lived media object; if `CallActivity` is destroyed mid-call (rotation, low-memory kill) and `cleanup()` misses a path, the entire Activity hierarchy leaks. The `relayTextureListener` at `WebRTCManager.java:825-841` is a non-static field holding `this`, registered on a View.

**Fix.** Add explicit `detachViews()` called from `CallActivity.onDestroy()`; null all View fields. Better: have `CallActivity` own the renderers and pass frames via callback interfaces instead of handing Views to the manager.

## H2. Every Activity creates its own `OkHttpClient` — 8 separate connection pools

**Evidence.**
- `ChatActivity.java:84` — `private OkHttpClient httpClient = new OkHttpClient();`
- `GroupChatActivity.java:91` — `private OkHttpClient httpClient = new OkHttpClient();`
- `GroupFileActivity.java:53`, `GroupAlbumActivity.java:51` — `new OkHttpClient();`
- `MainActivity.java:895` — `uploadClient = new OkHttpClient.Builder()...`
- `AiChatActivity.java:166` — `OkHttpClient client = new OkHttpClient.Builder()...` (**created per request**, inside a method)
- `ServerInfoActivity.java:112` — `new OkHttpClient.Builder()...` per call
- `UpdateActivity.java:129` and `:232` — two more clients
- `ApiClient.java:27` — the *correct* static shared client.

**Problem.** Each `OkHttpClient` owns its own connection pool, dispatcher, and thread pool. Eight clients means eight connection pools that never share sockets, eight dispatcher thread pools, and repeated TLS handshakes to the same host. `AiChatActivity.java:166` and `ServerInfoActivity.java:112` construct a client **inside the request path** — leaking a dispatcher + pool per request, which is a genuine resource leak.

**Fix.** Delete all local clients. Expose `ApiClient.getHttpClient()` (returns the existing static `client` at `ApiClient.java:27`) and reuse it. For download-heavy flows use a single shared client with a short read timeout.

## H3. Duplicated multipart-upload boilerplate in `ApiClient` (~120 lines, near-identical)

**Evidence.**
- `uploadAvatar()` — `ApiClient.java:192-224`
- `uploadFile()` — `ApiClient.java:232-268`

Byte-for-byte the same error-handling block appears in both:
```java
// ApiClient.java:207-217  AND  247-257  (identical)
try {
    AuthResponse err = gson.fromJson(respBody, AuthResponse.class);
    String errorMsg = (err != null && err.error != null) ? err.error : "上传失败 (" + statusCode + ")";
    throw new ApiException(errorMsg, statusCode);
} catch (ApiException e) {
    throw e;
} catch (Exception e) {
    throw new ApiException("上传失败 (" + statusCode + ")", statusCode);
}
```
Note the second copy is **missing the 401 `authListener` notification** that `doRequestInternal` has at `ApiClient.java:133-135` — so an expired token during file upload does not trigger logout.

**Fix.** Extract `private <T> T execMultipart(Request, Class<T>)` and route both through it, ensuring the 401 branch is centralized. `ApiClient.java` has 114 `doRequest(` call sites and 129 public methods — the file is a 977-line procedural facade.

## H4. Second OkHttp/HTTP stack inside `ChatActivity` bypasses `ApiClient` error handling

**Evidence.** `ChatActivity.java:810, 839, 947, 983, 1013, 1035, 1350, 1394, 2425-2431` — nine hand-rolled `Request.Builder().url(fullUrl).build()` + `httpClient.newCall(req).execute()` sequences doing raw downloads/uploads with no `ApiException` mapping, no 401 handling, no retry.

**Problem.** Network failures here produce `IOException` logged to logcat, not surfaced as user-facing errors, and never trigger the logout path. This is the same duplication as `GroupChatActivity.java:889, 1047, 1075, 1280, 1319, 1932-1938`.

**Fix.** Add `ApiClient.downloadBytes(String url)` and `ApiClient.uploadBytes(...)` and call those. Removes ~18 duplicated call sites across the two chat Activities.

## H5. `MainActivity.onDestroy` removes the WS listener but the WS itself is owned per-Activity

**Evidence.** `MainActivity.java:1758-1765` (`onDestroy` → `clearAuthFailureListener()`), `WebSocketManager.java:537-557` (`disconnect()` nulls `listener`).
Meanwhile `ChatActivity.java:1856` — `if (ws != null) try { ws.disconnect(); } catch (Exception ignored) {}` and `GroupChatActivity.java:1467+`.

**Problem.** `WebSocketManager` is instantiated per Activity that needs it. `disconnect()` sets `shouldReconnect = false`, so a chat Activity finishing tears down the app's *global* push channel — and MainActivity must rebuild it. Connection state is mirrored in three places: instance `connected` (`WebSocketManager.java:32`), `globalConnected`/`globalConnecting` statics (`:38-41`). There is no single source of truth.

**Fix.** Make `WebSocketManager` an application-scoped singleton in `:core` (getInstance), with an observer/event-bus fan-out (`CopyOnWriteArrayList<Listener>`) so Activities subscribe/unsubscribe instead of owning the socket.

---

# 🟡 MEDIUM

## M1. `voicePreloader` pool duplicated as a `static` field in two Activities

**Evidence.**
- `ChatActivity.java:108` — `private static java.util.concurrent.Executor voicePreloader = ...newFixedThreadPool(3);`
- `GroupChatActivity.java:115` — identical line.

`static` on an inner Activity class means the pool survives all Activity instances — correct for reuse, but it is duplicated code, and `voiceCache` (`ChatActivity.java:107`, `GroupChatActivity.java:114`) is likewise duplicated as a static `ConcurrentHashMap` with an identical `VOICE_CACHE_MAX = 200` collision policy.

**Fix.** Move both to `core/.../utils/VoiceCache.java` (single shared pool + single LRU-bounded cache). The current "clear the whole map when full" policy (`ChatActivity.java:2442-2445`) is a crude eviction that will thrash.

## M2. Voice playback spawns an unbounded `Handler` + `Runnable` per play, never removed

**Evidence.** `ChatActivity.java:2498-2510`:
```java
final Handler progressHandler = new Handler();
progressHandler.post(new Runnable() {
    @Override public void run() {
        if (voicePlayer != null && voicePlayer.isPlaying() && playingPosition == position) {
            ...
            progressHandler.postDelayed(this, 200);
        }
    }
});
```
**Problem.** The `Runnable` is never stored in a field, so it **cannot be cancelled** — the only exit is the guard at line 2502. There is no `removeCallbacksAndMessages`. `resetVoicePlayback()` (`:2517`) does not clear it. Each voice play leaks a main-thread `Handler` + self-rescheduling `Runnable` until the player naturally stops. Identical code at `GroupChatActivity.java:1955+`.

**Fix.** Hoist to an instance `progressRunnable` field; `progressHandler.removeCallbacks(progressRunnable)` in `resetVoicePlayback()` and `onDestroy()`. Better: a single `ValueAnimator` or `Choreographer` callback.

## M3. `notifyDataSetChanged()` used for surgical changes on ~30 sites

**Evidence.** 41 hits. Worst: `MallActivity.java:200, 207, 308, 328, 383, 425, 495, 590` (8 calls), `MerchantBackendActivity.java:170, 190, 213`, `GroupAnnouncementActivity.java:97, 122`, `MainActivity.java:494-495, 521, 1528, 1533, 1686`.

Contrast: `ChatActivity.java:1964-1997` shows the team *knows* the right pattern (`notifyItemRangeInserted`, `notifyItemChanged`) — with `rebuildRows()` called before **every** notify, which is itself O(n) per append.

**Problem.** Full rebind + relayout of the whole list; on the mall/order lists this causes visible flicker and lost scroll position, and kills item animations.

**Fix.** Use `DiffUtil`/`ListAdapter` for the mall, order, announcement, and group-member lists. For `ChatActivity`, cache the row-index map in `rebuildRows()` instead of the O(n) linear scans in `rowIndexOfMessage()` (`:1956-1961`) called on every append.

## M4. Inflating layouts in code instead of XML — `CallActivity`

**Evidence.** `CallActivity.java:213-224`:
```java
remoteRenderer = new SurfaceViewRenderer(this);
remoteRenderer.setLayoutParams(new ViewGroup.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
remoteVideoContainer.addView(remoteRenderer);
```
plus `ChatActivity.java:2017-2079` (the voice bubble), `GroupChatActivity` equivalent.

**Problem.** Layout logic is invisible to the layout editor, not overridable per-config (landscape/tablet), and mixes dp math into Java.

**Fix.** Declare in XML; inflate.

## M5. No ViewBinding anywhere — 400+ `findViewById` calls

**Evidence.** `MainActivity.java` 71 calls, `MallActivity.java` 62, `GroupChatActivity.java` 31, `ChatActivity.java` 28. `app/build.gradle:90-92` enables **only** `buildConfig`:
```gradle
buildFeatures {
    buildConfig = true
}
```
**Problem.** Every `findViewById` is an untyped, unverified tree walk; a typo or a layout refactor yields a runtime NPE or `ClassCastException` at the call site rather than a compile error. Also forces full re-lookup in `showChats()`/`showContacts()` (`MainActivity.java:366-410`, 5 `findViewById` calls each, executed on every tab switch).

**Fix.** Set `viewBinding = true` in every module's `build.gradle`, replace `findViewById` incrementally, and cache the toolbar/view refs as fields.

## M6. 2330 hardcoded dp/sp literals and 397 hardcoded strings in layouts

**Evidence.** Measured across 91 layout files. Worst offenders: `activity_main.xml` (25 text, 127 dimen), `activity_mall.xml` (18/87), `activity_merchant.xml` (17/75 + 11 hardcoded colors), `activity_checkout.xml` (16/85), `activity_product_detail.xml` (16/84), `activity_add_product.xml` (13/78), `activity_settings.xml` (17/68).

**Problem.** No design-token consistency (spacing/type scale drifts file-to-file), no `values-sw600dp` adaptation, and hardcoded Chinese strings cannot be localized. Compounded by hardcoded Java strings, e.g. `MainActivity.java:367` `tvToolbarTitle.setText("消息")`, `:375` `"通讯录"`.

**Fix.** Extract to `dimens.xml` (`spacing_s/m/l`, `text_size_body`, …) and `strings.xml`. Enable the lint checks `HardcodedText` / `HardcodedDimens` as errors and run `lint` in CI.

## M7. `R8` is disabled in release — the app ships unshrunk and unobfuscated

**Evidence.** `app/build.gradle:68-75`:
```gradle
release {
    // ⚠️ 暂不开启 R8 混淆：... 历史上从未用 release 变体验证过，贸然开启会导致运行期崩溃（登录闪退）。
    minifyEnabled false
    proguardFiles getDefaultProguardFile('proguard-android-optimize.txt'), 'proguard-rules.pro'
}
```
Note `proguard-rules.pro` is **already correctly written** (Gson `@SerializedName` protection, okhttp/WebRTC/uCrop keeps, see `app/proguard-rules.pro:1-40`), and `GsonContractTest` exists specifically to guard this.

**Problem.** No dead-code elimination, no resource shrinking, no obfuscation → larger APK, slower dex load, and trivially reverse-engineerable E2EE client logic. The comment states the blocker is "never validated on a release build" — that's a process gap, not a technical impossibility, given the keep rules and contract test already exist.

**Fix.** Flip `minifyEnabled true` + add `shrinkResources true`; run `GsonContractTest` and a full manual regression on the release variant before shipping. Consider `-keep class com.familychat.app.api.Models$* { *; }` refinement.

## M8. `WebSocket` token passed in the query string

**Evidence.** `WebSocketManager.java:140-143` — the code itself flags it:
```java
// SECURITY NOTE: Token is passed as a query parameter for compatibility with
// the current server implementation. For production, consider sending the
// token as the first WebSocket message instead to avoid URL logging exposure.
String url = ServerProtocol.wsOf(server) + server + "/ws?token=" + token;
```
**Fix.** Send token as the first frame after `onOpen` (the `pendingSignals` queue at `:434` already provides the "send after connect" mechanism).

## M9. `ApiClient.clearAuthFailureListener()` called from `MainActivity.onDestroy` breaks backgrounded-app logout

**Evidence.** `MainActivity.java:1763`. Combined with C1: if the user backgrounds the app (MainActivity destroyed) while a token expires, no component holds the listener.

**Fix.** Covered by C1 (application-scoped registration).

---

# 🟢 LOW

## L1. `MallActivity` / `MerchantBackendActivity` — God Classes with 9 inner types each

**Evidence (measured):**

| File | Lines | Methods | Inner types | `findViewById` | `else if` |
|---|---|---|---|---|---|
| `ChatActivity.java` | 2592 | 52 | 3 | 28 | 29 |
| `GroupChatActivity.java` | 2042 | 54 | 3 | 31 | 29 |
| `MainActivity.java` | 1769 | 66 | **10** | 71 | 11 |
| `WebRTCManager.java` | 1351 | 46 | 4 | 0 | 2 |
| `ApiClient.java` | 1134 | **132** | 9 | 0 | 1 |
| `MallActivity.java` | 1072 | 36 | **9** | 62 | 10 |
| `CallActivity.java` | 954 | 28 | 0 | 18 | 2 |
| `WebSocketManager.java` | 558 | 18 | 1 | 0 | 5 |
| `PreferencesManager.java` | 468 | 54 | 1 | 0 | 0 |

`MainActivity` crams 10 nested types (adapters) plus chat-list, contacts, QQ-style pinyin grouping, photo picker + uCrop, avatar upload, friend requests, wallet/discover entry, call handling, and 16 raw threads into one file.

**Fix — concrete decomposition:**

*ChatActivity →*
- `ChatMessageListController` (rows, time-row insertion, diffing) — moves `MessageAdapter` (lines 1892-2410) out
- `ChatMessageSender` (`sendTextMessage`, `uploadAndSendFile/Video/Voice`)
- `VoicePlaybackController` (lines 2413-2533)
- `ChatE2eeHelper` (`maybeEncrypt`/`maybeDecrypt`/`getPeerPublicKey`, lines 1198-1346)
- `ChatPollingController` (lines 1091-1196)
- `ChatPlusPanelView` (`setupPlusPanel`/`addPanelItem`, lines 1483-1562)

*MainActivity →*
- `ChatListAdapter`, `ContactsAdapter`, `QqContactsController` (pinyin grouping, lines 1624-1700)
- `AvatarPickerController` (lines 1026-1174)
- `MainActivityWsListener` (lines 201-364 — a 163-line anonymous inner class)

*GroupChatActivity →* mirror of ChatActivity, sharing `ChatMessageListController`.

## L2. Exact copy-paste between the two chat Activities

**Evidence — identical method names, near-identical bodies:**

| Method | ChatActivity | GroupChatActivity |
|---|---|---|
| `uploadAndSendFile(Uri)` | :744 | :830 |
| `uploadAndSendVideo(Uri)` | :866 | :942 |
| `uploadFileBytes(...)` | :976 | :1040 |
| `uploadVoice(String,int)` | :1001 | :1063 |
| `queryDisplayName(Uri)` | :730 | :1123 |
| `hasAudioPermission()` | :1455 | :1137 |
| `onRequestPermissionsResult` | :1461 | :1143 |
| `pickFile/pickImage/pickVideo/captureFromCamera` | :1466-1643 | :1147-1191 |
| `setupPlusPanel/addPanelItem/togglePlusPanel/setPlusPanelVisible` | :1483-1562 | :1192-1254 |
| `applyNavPadding()` | :1563 | :1255 |
| `toggleVoiceMode(...)` | :1644 | :1265 |
| `saveImageToGallery(String)` | :1347 | :1277 |
| `downloadAndOpenFile(String,String)` | :1390 | :1316 |
| `showMessageMenu(...)` | :1692 | :1373 |
| `loadMsgAvatar(...)` | :1807 | :1439 |
| `preloadVoiceFile/playVoiceLocal/resetVoicePlayback` | :2413-2527 | :1924-2007 |
| `formatPlayTime(int,int)` | :2528 | :2008 |
| `dp(int)` | :2535 | :2015 |
| `class TimeText` | :1869 | :2020 |
| `MessageAdapter` | :1892 | :1562 |

Also: `okhttp3.Request` download logic — `ChatActivity.java:2425-2431` vs `GroupChatActivity.java:1932-1938` are line-for-line identical.

**Fix.** Extract a `BaseChatActivity` (shared `dp`, `applyNavPadding`, permission flow, plus-panel, pickers) and a `MediaMessageSender` component (upload + `uploadFileBytes`) and `VoicePlaybackController`. Realistically removes **~900 duplicated lines**.

## L3. `ApiClient` — 114 `doRequest` calls, 129 public methods, hand-written per endpoint

**Evidence.** `ApiClient.java` is 977 lines / 1134 with generated. Endpoints are 4-line stubs, e.g. `getProfile()` at `:182-184`. Loader/retry logic: `doRequest` (`:99`) → `doRequestInternal` (`:113`).

**Positive findings (credit where due):** there **is** a real shared OkHttp client (`:27-33`) with `connectTimeout(15s)`, `readTimeout(30s)`, `writeTimeout(30s)`, `retryOnConnectionFailure(true)`; there **is** a consistent `ApiException` model; there **is** centralized 401 handling (`:131-147`); JSON is a single static `Gson gson = new Gson()` (`:26`) — no per-call reflection setup.

**Remaining problems.** (a) `doRequestRaw` (`:88-97`) bypasses the error model entirely — it returns the raw body on 4xx/5xx and only wraps `IOException`. (b) No request cancellation/call-timeout tag support. (c) Full synchronization: every call blocks the calling thread (`execute()`); there is no async API, which is precisely why the codebase is saturated with `new Thread`. (d) `gson.fromJson(respBody, cls)` at `:148` will throw raw `JsonSyntaxException` on malformed bodies (caught only as a generic `RuntimeException` at `:106`).

**Fix.** Add `Call`-returning variants or `enqueue`-based `CompletableFuture` wrappers so callers stop spawning threads. Route `doRequestRaw` through the same error mapper.

## L4. Tests: 5 files for ~110 source files; duplicated Android stubs

**Evidence.**
```
app\src\test\java\com\familychat\app\api\GsonContractTest.java        (247 lines)
app\src\test\java\com\familychat\app\utils\KeyBackupTest.java         (197)
core\src\test\java\com\familychat\app\utils\ServerProtocolTest.java
feature-chat\src\test\java\com\familychat\app\utils\MessageEncryptorTest.java (203)
app\src\test\java\android\util\Base64.java    (81)  MD5 165ED2AE8EE957D14791184CFF32814A
app\src\test\java\android\util\Log.java       (54)  MD5 9E962BD00F5CD7DAEAF755AD1340AF5B
feature-chat\src\test\java\android\util\Base64.java  (81)  MD5 165ED2AE8EE957D14791184CFF32814A  ← IDENTICAL
feature-chat\src\test\java\android\util\Log.java     (54)  MD5 9E962BD00F5CD7DAEAF755AD1340AF5B  ← IDENTICAL
```
**Confirmed duplication:** the `Base64` and `Log` stubs are **byte-identical** across `app` and `feature-chat`. Comment at `core/build.gradle` explains the approach: `unitTests.returnDefaultValues = true`.

**The tests that exist are genuinely good** — `GsonContractTest.java:18-31` documents a real production incident (R8 renaming `username` → empty-body 400) and encodes a structural guard; `MessageEncryptorTest.java:14-28` covers ECDH symmetry, GCM tamper detection, IV randomness.

**Coverage gap.** Zero tests for: `WebSocketManager` message routing (the 30-case `switch` at `:174-376`), `ApiClient` error mapping, `PreferencesManager`, `WebRTCManager`, and **every Activity** (no Robolectric, no instrumentation tests at all — there is no `src\androidTest` directory).

**Fix.** Move the stubs into a shared `testFixtures` source set (or adopt Robolectric and delete them). Add unit tests for `WebSocketManager`'s JSON→callback dispatch and `ApiClient`'s 401/`ApiException` mapping — both are pure-logic and currently unguarded despite being the highest-traffic code paths.

## L5. Deprecated `getSystemWindowInsetBottom()` and `onBackPressed()`

**Evidence.** `ChatActivity.java:187` — `navInsetBottom = insets.getSystemWindowInsetBottom();` (deprecated in API 30; targetSdk is 34). `ChatActivity.java:1825` and `MainActivity.java:397` override `onBackPressed()` (deprecated in API 33 — should be `OnBackPressedDispatcher`).

**Fix.** Migrate to `WindowInsetsCompat` + `ViewCompat.setOnApplyWindowInsetsListener`, and `getOnBackPressedDispatcher().addCallback(...)`. Also `ChatActivity.java:2031` checks `Build.VERSION.SDK_INT >= JELLY_BEAN` — dead code at `minSdk 24`.

## L6. Dependency & config notes

**Evidence.** `app/build.gradle:50-52` — `minSdk 24`, `targetSdk 34`, `compileSdk 34`. `build.gradle:3` — AGP `8.7.3`. `core/build.gradle` has **no `targetSdk`**.

- `androidx.security:security-crypto:1.1.0-alpha06` (`app/build.gradle:163`) — an **alpha** guarding token/private-key storage. `1.1.0-alpha06` is the last published release and is effectively unmaintained; migrate to `1.1.0-alpha07`+ or a maintained alternative, and pin explicitly.
- `targetSdk 34` is one behind current requirements (35 for new submissions) — planned upgrade needed.
- `:core` library module sets only `minSdk` — fine, but means `targetSdk` behavior is inherited entirely from `:app`; worth an explicit comment.
- No version catalog (`gradle/libs.versions.toml`) — versions are inlined in 7 build files, so `okhttp`/`gson` appear in both `app` and `core` (`app/build.gradle:137,142`; `core/build.gradle` `api` deps) with duplicated literals.
- No `src\androidTest` and no CI lint config.

**Fix.** Introduce a version catalog; centralize dependency versions; schedule the `targetSdk 35` bump; evaluate the security-crypto alpha.

---

# Summary of recommended sequencing

| Order | Action | Impact |
|---|---|---|
| 1 | Fix C1 (app-scoped auth listener) | Correctness/security bug |
| 2 | Introduce `AppExecutors`, replace 169 `new Thread` | Memory + stability |
| 3 | Delete 8 duplicate `OkHttpClient`s → shared `ApiClient.getHttpClient()` | Sockets/latency |
| 4 | Extract `BaseChatActivity` + shared media/voice components (L2) | ~900 LOC deleted |
| 5 | Split `ChatActivity.onCreate` (558 lines) into phases (C3) | Reviewability |
| 6 | XML-ify voice bubble + `CallActivity` renderers (C4/M4) | Fling performance |
| 7 | Enable ViewBinding; migrate `findViewById` (M5) | Compile-time safety |
| 8 | Make `WebSocketManager` a singleton (H5) | Single source of truth |
| 9 | Enable R8 + `shrinkResources` (M7) | APK size, obfuscation |
| 10 | DiffUtil for mall/order/announcement lists (M3) | UI smoothness |
| 11 | Dedupe test stubs, add WS/ApiClient unit tests (L4) | Regression safety |
| 12 | Extract dimens/strings (M6), version catalog (L6) | Consistency |

**Notable strengths found:** `WebSocketManager` implements proper exponential backoff (`:405-413`), a bounded pending-signal queue with `MAX_PENDING_SIGNALS = 100` (`:434-435`), and complete teardown including `handler.removeCallbacksAndMessages(null)` (`:541`) and listener nulling (`:556`). `PreferencesManager.java:34-42` correctly fails closed rather than falling back to plaintext storage. `AudioConfig`/E2EE code is covered by deliberate, well-documented regression tests. These are the patterns the rest of the codebase should be brought up to.
