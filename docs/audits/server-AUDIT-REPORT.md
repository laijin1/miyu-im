# Security & Optimization Audit — `server-java` (密语 FamilyChat backend)

**Scope:** `<本地路径>`
**Method:** read-only static inspection of all handlers, DAOs, `Database`, `WsHandler`, `ServerConfig`, `build.gradle`.
**Note:** No files were modified. This report was written as a new artifact only.

Overall: the codebase is **much better than typical** — parameterized SQL is used almost everywhere, bcrypt is used with transparent SHA-256 migration, the JWT verifier is cached, path traversal is defended in depth, and `requireAuth` is present in essentially every handler method. The findings below are the genuine gaps, not nitpicks. Two are Critical.

---

## Priority Summary

| # | Sev | Area | One-line |
|---|---|---|---|
| 1 | **Critical** | AuthZ / Business logic | `/api/wallet/recharge` lets **any** logged-in user mint arbitrary balance out of nothing |
| 2 | **Critical** | AuthN | JWT secret auto-generates to a **new random value** if `data/jwt_secret.key` is lost → silent mass logout + rotation |
| 3 | High | Password hashing | Payment PIN is a **6-digit** secret hashed client-side; PBKDF2 lock state is in-memory only |
| 4 | High | Concurrency | `Database.getConn()` opens a **raw connection per call, no pool**, re-running 7 PRAGMAs every query |
| 5 | High | DoS | `AuthHandler.checkRateLimit` keyed on **client-supplied username** → unauthenticated map growth + lockout bypass |
| 6 | High | DoS | 100 MB max request size + 60 MB file cap + no per-user quota → trivial disk-exhaustion |
| 7 | High | Concurrency | `AuthHandler` login rate-limiter `rateLimitMap` **evicts arbitrary live entries** under load |
| 8 | Medium | AuthZ | `?token=` in query string leaks JWTs into **access logs of every proxy** (mitigated in-app, not at edge) |
| 9 | Medium | SQL | `UserDao.updateUser` builds `SET " + field` from a **whitelist Set** — safe today, one edit from injectable |
| 10 | Medium | SQL | `GroupDao.dissolveGroup` builds `DELETE FROM " + t` from a local array — safe today, fragile |
| 11 | Medium | AuthZ | `MallHandler.createProduct` has **no role/merchant check** — any user becomes a seller |
| 12 | Medium | Input validation | `AiHandler.aiRateLimit` is an **unbounded** `ConcurrentHashMap<Integer,…>` |
| 13 | Medium | Error handling | `MallDao.getProductsPage` swallows `SQLException` into a **count-0 success envelope** |
| 14 | Medium | Crypto | PIN comparison uses `String.equals` — **not constant-time** |
| 15 | Medium | Secrets | `data/deepseek.key` and `data/jwt_secret.key` sit **inside the served data dir**, `.gitignore` is the only guard |
| 16 | Low | Structure | `MallHandler` 867 lines / `MallDao` 1284 lines — God Classes; routing and envelopes inconsistent |
| 17 | Low | Concurrency | Non-daemon-thread hygiene / `scheduler` errors swallowed to `System.err` |
| 18 | Low | Config | `Access-Control-Allow-Origin: *` for **absent** Origin, and `trycloudflare.com` wildcard trusted |
| 19 | Low | Error handling | `GroupContentHandler` / `GroupHandler` empty `catch {}` hide real failures |
| 20 | Low | Build | `jbcrypt 0.4` is unmaintained (2014); no dependency scanning (architecture doc admits this) |

---

## Critical

### 1. Any authenticated user can mint unlimited money — `WalletHandler.recharge`

**File:** `handler/WalletHandler.java:103-138`

```java
108:    private void recharge(Context ctx) {
109:        int userId = AuthHandler.requireAuth(ctx);
...
121:        if (amount <= 0) {
...
126:        int toUser = userId;
127:        if (!FamilyChatServer.getDb().addBalance(toUser, amount)) {
```

The route is registered as `app.post("/api/wallet/recharge", this::recharge)` (`WalletHandler.java:22`). The only checks are *logged in* and *amount > 0*. There is **no payment channel, no PIN, no admin role, no per-day cap**. The comment even admits it:

> `未来接入银行：此接口将对接真实支付渠道，当前为直接加余额。` ("In future this will connect a real payment channel; currently it directly adds balance.")

`WalletDao.addBalance` (`WalletDao.java:176-189`) unconditionally executes `UPDATE wallets SET balance = balance + ?`. Because the resulting balance is spendable through real mall orders, transfers and red packets, this is a **complete breakdown of the money system**: one request turns a zero-balance account into an unlimited spender.

**Fix (immediate):** return `403`/`501` from `recharge` in production, or gate it behind an admin role + a signed settlement record from a real payment provider. If a dev-only top-up is genuinely needed, require a server-side `ADMIN_USER_IDS` allowlist and a hard daily cap enforced in SQL (not in the handler).

---

### 2. Lost `jwt_secret.key` silently rotates the signing key

**File:** `config/ServerConfig.java:36-102`

```java
37:    private static String loadOrGenerateSecret() {
...
51:        // --- Key file missing or empty — CRITICAL SITUATION ---
...
81:        // Generate a new random 256-bit secret
82:        SecureRandom rng = new SecureRandom();
...
90:                Files.writeString(keyFile, generated);
...
93:            if (dbExists) {
94:                System.out.println("[!!] DATABASE EXISTS — all existing tokens are now INVALID.");
```

To its credit the code *warns loudly* and even detects that the DB exists. But it then proceeds anyway and overwrites the key path. The failure mode matters: the key file lives at `data/jwt_secret.key` (line 23) in the **same directory tree as the served media and the SQLite DB** — an accidental `data/` wipe, a backup restore that excludes it, or a Docker volume that only mounts the DB will silently rotate the secret. There is no `fail-fast`.

Consequences beyond logout: for a short window the operator may not notice, and any stale token signed with the *old* key is rejected, but any process that observed the new key (log file, `[OK] Generated new JWT secret key -> …`) can forge tokens for every user.

**Fix:** when `dbExists == true`, **throw** instead of generating (`RuntimeException("JWT secret missing but DB present — restore jwt_secret.key or set JWT_SECRET")`). Require an explicit `--rotate-jwt-secret` / `JWT_SECRET_ROTATE=1` opt-in. Never print the key path next to a freshly generated secret without an audit trail. Also resolve `JWT_SECRET` from env as mandatory in production.

---

## High

### 3. Payment PIN: 6 digits + in-memory-only lockout

**File:** `model/dao/WalletDao.java:53-123`

```java
54:     * 设置/修改支付密码。pinHash = 客户端 SHA-256(6位数字PIN)。
...
58:    public boolean setPayPassword(int userId, String pinHash) {
59:        String salt = Long.toHexString(new java.security.SecureRandom().nextLong())
60:                + Long.toHexString(System.nanoTime());
```

Two compounding problems.

**(a) The search space is 10^6.** The client sends `SHA-256(PIN)` (`WalletHandler.java:84-88` validates it as `[0-9a-f]{64}`). The server then runs PBKDF2-HMAC-SHA256 at 120 000 iterations (`WalletDao.java:127`) over an *already-hashed* value. This is a genuine improvement over the legacy `SHA-256(pinHash + salt)` path (line 112), but PBKDF2 does not help here: an attacker who steals the DB does **not** need to brute-force the PBKDF2 layer. They brute-force the **PIN** — 1 000 000 candidates × one SHA-256 each — then run PBKDF2 once on the winner. The 120 000 iterations only slow *verification*, not *recovery*. A 6-digit PIN is offline-crackable in well under a second.

**(b) `payLock` is process-local and unbounded-keyed.**

```java
131:    private static final java.util.concurrent.ConcurrentHashMap<Integer, long[]> payLock =
132:            new java.util.concurrent.ConcurrentHashMap<>();
```

`PAY_MAX_FAILS = 5` / 5-minute lock (`:128-129`) is real but evaporates on restart, and a restart is also the standard attacker move. `payRecordSuccess` does `payLock.remove(userId)` (line 172) — so a *successful* verification clears the failure counter, which is fine — but an attacker who guesses correctly once resets their budget.

**Fix:** enforce a **server-side minimum PIN policy of ≥6 digits but treat it as a low-entropy secret requiring server-side throttling that survives restart** — persist `pay_fail_count` / `pay_lock_until` columns in `wallets`. Better, stop trusting the client hash: send the PIN over TLS and hash server-side, so the server controls the KDF input and can add a per-user server secret (pepper) that never leaves the DB. Consider requiring the login password (already bcrypt) as a second factor for high-value transfers.

---

### 4. No connection pooling — a new SQLite connection plus 7 PRAGMAs per query

**File:** `model/Database.java:58-70`

```java
58:    public Connection getConn() throws SQLException {
59:        Connection c = DriverManager.getConnection("jdbc:sqlite:" + DB_PATH);
60:        try (Statement s = c.createStatement()) {
61:            s.execute("PRAGMA journal_mode=WAL");           // 读写并发基础
62:            s.execute("PRAGMA busy_timeout=5000");          // 写锁碰撞时等待 5s 而非报错
...
67:            s.execute("PRAGMA foreign_keys=ON");            // 外键约束
68:        }
69:        return c;
70:    }
```

Every one of the ~180 DAO call sites (`grep` shows `db.getConn()` in `MallDao` alone at 40+ locations) opens a **fresh TCP-less but still expensive** SQLite handle and issues **7 round-trips of PRAGMA** before the real statement. `PRAGMA journal_mode=WAL` is particularly costly — it inspects and can write the DB header.

Worse, **per-connection PRAGMAs are re-issued on every call**, so `cache_size=-4096` (4 MB read cache, line 64) is thrown away on every single query — the cache never survives long enough to be useful. Under the `loadtest-server.out.log` (605 KB) this is the dominant per-request cost.

**Fix:** introduce a pool — `org.sqlite.SQLiteDataSource` with `SQLiteConfig` applied once, wrapped in HikariCP (`maximumPoolSize` ≈ 4-8 for SQLite's single-writer model), or a small custom `ThreadLocal<Connection>` / `BlockingQueue<Connection>` pool. Apply `PRAGMA foreign_keys` via `SQLiteConfig.enforceForeignKeys(true)` (a connect property) rather than a statement. Move one-time `journal_mode=WAL` to `init()` only. This is a pure win with no behavioural change.

---

### 5. Login rate limiter keyed on attacker-controlled username

**File:** `handler/AuthHandler.java:186-192`

```java
186:        String rateKey = username.isEmpty()
187:                ? ctx.req().getRemoteAddr()
188:                : username + "|" + ctx.req().getRemoteAddr();
189:        if (!checkRateLimit(rateKey)) {
```

The rate key is `username|ip`, and `username` comes straight from the request body. An attacker attacking **one** account simply varies the case or pads the username (`Alice`, `alice `, `alice\n`, …) — each distinct string gets its own 10-request budget — while the DB lookup is case-sensitive and the padded variants simply fail. More seriously, the *purpose* of this limiter is brute-force protection, and it is **per-username-per-IP**: 10 attempts per username per minute against a list of 100 usernames is 1000 attempts/min from one IP.

The map also grows one entry per distinct username tried (`:21`), which is the direct vector for finding #7.

**Fix:** maintain **two** counters: a per-IP counter (tight, e.g. 20/min) and a per-**account** counter keyed on the *resolved* `users.id` after the DB lookup (5 failures → exponential backoff persisted in the DB). Normalize the username (`trim().toLowerCase(Locale.ROOT)`) before keying. Return the same generic 401 either way to avoid enumeration.

---

### 6. Unbounded upload surface / no disk quota

**Files:** `FamilyChatServer.java:21-23`, `handler/FileHandler.java:512, 564`

```java
// FamilyChatServer.java
22:    private static final long MAX_REQUEST_SIZE = Long.parseLong(
23:            System.getProperty("max.request.size", String.valueOf(100 * 1024 * 1024)));
```
```java
// FileHandler.java
512:    private static final long MAX_CHAT_FILE_SIZE = 60L * 1024 * 1024;
...
564:            if (!copyWithLimit(file.content(), target, MAX_CHAT_FILE_SIZE)) {
```

The 60 MB per-file cap and the streaming `copyWithLimit` (which correctly deletes the partial file, `:565`) are good. But there is **no per-user quota, no aggregate storage cap, no upload rate limit, and no cleanup/retention**. The `data/chat_files/` directory already contains a **97 MB video** (`1786622618914_f88c2fb1_5.mp4`) and the `data/video_cache/` holds **462 MB + 494 MB** files — i.e. ~1 GB of media in a hobby deployment. Any authenticated user can loop `POST /api/chat/upload` and fill the disk, which for SQLite means **DB corruption risk on a full volume**, not just a failed write.

The 100 MB `max.requestSize` also means Javalin will buffer/accept very large bodies on endpoints that need nothing of the sort.

**Fix:** raise a per-route `maxRequestSize` for the upload endpoint only (Javalin supports per-endpoint config) and lower the global default to ~2 MB. Add a per-user quota column, checked before `copyWithLimit` and enforced again after. Add a TTL reaper for `chat_files`/`video_cache`. Put the data dir on a volume with a hard quota so a runaway cannot take down the DB.

---

### 7. Rate-limiter map evicts live entries, allowing limit bypass

**File:** `handler/AuthHandler.java:35-46`

```java
35:        if (rateLimitMap.size() >= RATE_LIMIT_MAP_MAX) {
36:            rateLimitMap.entrySet().removeIf(e -> now - e.getValue()[0] > RATE_LIMIT_WINDOW);
37:            // Fallback: if expired entries weren't enough, force-evict half to stay bounded
38:            if (rateLimitMap.size() >= RATE_LIMIT_MAP_MAX) {
39:                int half = rateLimitMap.size() / 2;
40:                java.util.Iterator<java.util.Map.Entry<String, long[]>> it = rateLimitMap.entrySet().iterator();
41:                for (int i = 0; it.hasNext() && i < half; i++) {
42:                    it.next();
43:                    it.remove();
```

The memory guard is correct in intent but becomes a **weapon**: an attacker floods 10 000 distinct usernames (each cheap, unauthenticated) to trip the cap, and the server then **force-evicts 5 000 arbitrary entries — including the counters protecting the accounts currently under attack**. Combined with finding #5 (attacker-controlled keys), an attacker can guarantee their own target's counter is cleared. The pruning is also O(n) under a lock-free map while other threads are mutating it.

**Fix:** bound the map by *design*, not by eviction: use a proper token-bucket library (Bucket4j) or a Caffeine cache with `maximumSize` + `expireAfterWrite` and `evictionListener` (Caffeine's Window-TinyLFU evicts the *least useful*, and refused admission never clobbers hot entries). Keep account-scoped counters in the DB so eviction of the cache is harmless.

---

## Medium

### 8. JWT accepted from the query string (`?token=`) — logged everywhere but the app

**File:** `handler/AuthHandler.java:251-283`

```java
248:     * 客户端 Glide/ExoPlayer 加载图片/视频不便带 header, 与 WS 一致走 query token。
...
265:        String qToken = ctx.queryParam("token");
266:        if (qToken != null && !qToken.isEmpty()) {
267:            try {
268:                var jwt = VERIFIER.verify(qToken);
```

The rationale (media loaders can't set headers) is legitimate, and `FamilyChatServer.java:92-95` does redact `token=` in its own access log:

```java
93:            if (qs != null && qs.contains("token=")) {
94:                qs = qs.replaceAll("(?i)(token=)[^&]*", "$1***");
```

But this is the *only* place redaction happens. Cloudflare (the tunnel explicitly supported at `FamilyChatServer.java:212`) logs full URIs; browser history, `Referer` headers on the download page, and any intermediate proxy also capture them. A 7-day-lived bearer token in a URL is a real credential-leak channel.

**Fix:** keep the mechanism but **shorten the blast radius**: issue a distinct, short-lived (60 s), scope-limited *media token* for `?token=`, validated with an audience claim, so a leaked URL cannot be replayed as a full API session. Ensure `Cache-Control: private, no-store` on token-bearing responses and add `Referrer-Policy: no-referrer`.

---

### 9. `UserDao.updateUser` — dynamic column name, whitelist-guarded

**File:** `model/dao/UserDao.java:247-264`

```java
250:    public void updateUser(int id, String field, String value) {
251:        if (!ALLOWED_UPDATE_FIELDS.contains(field)) {
252:            System.err.println("[ERROR] updateUser: invalid field '" + field + "'");
253:            return;
254:        }
...
256:            PreparedStatement ps = c.prepareStatement(
257:                    "UPDATE users SET " + field + " = ? WHERE id = ?");
```

**Not currently exploitable** — `field` is checked against `ALLOWED_UPDATE_FIELDS` before concatenation, and the *value* is a bound parameter. This is the correct pattern for a dynamic column. However the security of the whole method rests on one `contains` check in a different place from the concatenation. Any future refactor that moves the check, adds an early return, or widens the set (`"*"`, prefix matching) silently creates SQL injection.

**Fix:** make it impossible to misuse — replace the `String field` parameter with an `enum UserField { NICKNAME("nickname"), AVATAR("avatar"), … }` so the column name is a compile-time constant. Additionally escape with `"` + field.replace("\"","\"\"") + `"` as defence in depth.

---

### 10. `GroupDao.dissolveGroup` — dynamic table name from a local array

**File:** `model/dao/GroupDao.java:499-524`

```java
506:            String[] directTables = {
507:                    "group_settings", "group_album_photos", "group_albums", "group_files",
508:                    "group_message_reads", "group_messages", "group_members"
509:            };
510:            for (String t : directTables) {
511:                try (PreparedStatement ps = c.prepareStatement("DELETE FROM " + t + " WHERE group_id=?")) {
```

Again **not exploitable** — `t` iterates a hardcoded literal array, never user input. Worth flagging because it is the only other string-concatenated SQL in the DAO layer, and because the `catch (SQLException ignored)` on line 514 will silently **skip a whole table's deletion** if that table is missing, leaving orphaned rows after a "successful" dissolve with no log line. `GroupDao.java:521,524,529,533,536` repeat this pattern.

**Fix:** replace the loop with a single transaction and a static `Map<String,String> TABLE_TO_FK`; log at `WARN` (not silently ignore) when a delete fails; verify the result with a `SELECT COUNT(*)` assertion per table. Table names here should be `private static final String` constants.

---

### 11. Any user can become a merchant — no role check on product creation

**File:** `handler/MallHandler.java:704-751` (route registered at `:57`)

```java
57:        app.post("/api/mall/products", this::createProduct);              // 发布商品
```

`createProduct` (and `updateProduct` `:754`, `toggleProduct` `:789`, `deleteProduct` `:804`) call `AuthHandler.requireAuth(ctx)` and then rely on **ownership** checks inside the DAO (`updateProduct(userId, id, …)` returns `404 商品不存在或无权修改`). Ownership scoping is done correctly — a user cannot edit another seller's product. But there is **no check that the user is permitted to sell at all**. Combined with finding #1 (free money) this is how an attacker would monetise: mint balance → publish an item → buy from themselves → withdraw.

I verified `shipOrder` (`:818`) and the refund approve/reject paths follow the same ownership-in-DAO pattern; I did **not** find a cross-tenant write. So the specific gap is **role admission**, not horizontal privilege escalation.

**Fix:** add a `role` / `is_merchant` column to `users` and gate the four merchant routes behind a `requireMerchant(ctx)` helper analogous to `requireAuth`. If open selling is intended, say so explicitly and instead rate-limit product creation and add content moderation.

---

### 12. Unbounded per-user rate-limit map in `AiHandler`

**File:** `handler/AiHandler.java:33-48`

```java
33:    // AI 接口 per-user 限流: userId -> [windowStart, count]
34:    private static final ConcurrentHashMap<Integer, long[]> aiRateLimit = new ConcurrentHashMap<>();
```

Cleaner than `AuthHandler`'s (no force-eviction, `compute` is atomic) but it **never removes entries**. One entry per distinct user forever. Bounded in practice only by the number of registered accounts — which is unbounded, since `/api/register` is open (`AuthHandler.java:58`). Same class of bug as #7 but lower severity because keys aren't attacker-chosen per-request.

**Fix:** `Caffeine.newBuilder().maximumSize(10_000).expireAfterWrite(2, MINUTES).build()`.

---

### 13. `SQLException` swallowed into a "zero results" success envelope

**File:** `model/dao/MallDao.java:607-612`

```java
607:        } catch (SQLException e) {
608:            System.err.println("[ERROR] getProductsPage: " + e.getMessage());
609:            out.put("products", new ArrayList<>());
610:            out.put("total", 0);
611:            out.put("has_more", false);
612:        }
```

A genuine DB failure (lock timeout, corruption, schema drift) is indistinguishable to the client from "no products match". `MallHandler` then returns a `200` with an empty page. This is a *reliability* hazard during the very incident you most need signal for. The same shape appears in `getCart` (`:646`), `getFavorites`, `getReviews` — returning empty lists on error.

**Fix:** let `SQLException` propagate (declare it or wrap in a `DataAccessException`) and map to `500` in one place via `app.exception(...)`. At minimum, add a marker field (`"error": true`) to the degraded response so the client can distinguish empty from broken.

---

### 14. Non-constant-time secret comparison

**Files:** `model/dao/UserDao.java:81, 128`; `model/dao/WalletDao.java:109, 113`

```java
// UserDao.java:81
81:                    if (sha256hash != null && sha256hash.equals(storedHash)) {
```
```java
// WalletDao.java:109
109:            ok = calc != null && calc.equals(parts[3]);
```

`String.equals` returns early on the first differing byte, leaking a timing side-channel. In practice this is **very hard to exploit over a network** (the compared values are already hashes, and the legacy SHA-256 paths are the only ones using raw `equals` — bcrypt goes through `BCrypt.checkpw`, which is constant-time). Rated Medium because the legacy SHA-256 branch (`UserDao.java:78-91`) is a live authentication path for un-migrated accounts.

**Fix:** use `java.security.MessageDigest.isEqual(a.getBytes(UTF_8), b.getBytes(UTF_8))` for every hash comparison, and plan to drop the SHA-256 legacy branch (it also means un-migrated accounts have unsalted, fast-hashed passwords — a rainbow-table target).

---

### 15. Secrets stored inside the served data directory

**Files:** `config/ServerConfig.java:23`, `handler/AiHandler.java:61`

```java
// ServerConfig.java
23:    private static final String SECRET_FILE = DATA_DIR.resolve("jwt_secret.key").toString();
```
```java
// AiHandler.java
61:            Path keyFile = Paths.get(Database.DATA_DIR, "deepseek.key").toAbsolutePath().normalize();
```

`data/jwt_secret.key` (44 bytes) and `data/deepseek.key` (35 bytes) both exist on disk in the live tree, alongside `data/family_chat.db` and `data/chat_files/`. The class-level comment in `AiHandler.java:21` notes the key is "已被 .gitignore 忽略" (gitignored) — meaning **`.gitignore` is the only thing preventing the JWT signing key and the DeepSeek API key from being committed**. A single `git add -f`, a zip of the data dir for a bug report, or a backup uploaded somewhere shared leaks both.

**Fix:** move secrets out of `DATA_DIR` to a config path (`/etc/familychat/`, or env-only). Fail startup if `JWT_SECRET` is unset in production. Add a pre-commit secret scanner. Note `.gitignore` is not a security control.

---

## Low

### 16. God Classes and an inconsistent response envelope

- `handler/MallHandler.java` — **867 lines**, 40 routes, ~50 `requireAuth` calls in one class.
- `model/dao/MallDao.java` — **1284 lines**; `handler/GroupHandler.java` 47 KB.
- `model/Database.java` is 1263 lines but is now mostly `init()` DDL, which is acceptable.

Envelopes are **inconsistent**: `AuthHandler.registerUser` returns a bare `{"token","user"}` and errors as `{"error": …}` (`:111,156,167`), while `AiHandler` returns `{"code":200,"reply":…}` (`:164`), and `FileHandler.listVideos` returns `{"code":200,"data":{…}}` (`:157`). Three shapes for the same API. There is also copy-paste: the `bodyAsClass` + 400 pattern appears ~40 times verbatim, and the `JWT.create().withClaim("user_id",…).withClaim("tv",…).withExpiresAt(now + 86400*7*1000)` block is duplicated three times (`AuthHandler.java:98-102, 162-166, 206-210`).

**Fix:** extract `AuthHandler.issueToken(int userId)` (also removes the magic `86400 * 7 * 1000` from three sites), a `ctx.bodyAs(Map.class)` helper, and a `Response.ok(data)` / `Response.err(code, msg)` envelope. Split `MallHandler` along the existing comment sections (browse / orders / cart / address / merchant) into separate registrars. This pays for itself immediately — a single envelope makes the `app.exception` handler in #13 possible.

### 17. Scheduler error handling discards the cause

**File:** `FamilyChatServer.java:159-161`

```java
159:            } catch (Exception e) {
160:                System.err.println("[MALL] scheduler error: " + e.getMessage());
161:            }
```

`e.getMessage()` for an `SQLException` frequently returns `null`, producing `"[MALL] scheduler error: null"`. The thread is correctly a daemon (`:122`) and `scheduleWithFixedDelay` will keep running after a throw — good — but a persistent failure is invisible. **Fix:** log the full stack trace (`e.printStackTrace()` or, better, take the `slf4j-simple` dependency already on the classpath at `build.gradle:18` and use a real logger instead of `System.out`/`System.err` throughout). Add a failure counter that surfaces after N consecutive failures.

### 18. CORS: `*` when Origin is absent, and a public-suffix wildcard

**File:** `FamilyChatServer.java:48-62, 203-214`

```java
50:            if (origin == null || origin.isEmpty()) {
51:                // Same-origin request (browser doesn't send Origin for same-origin GET/POST)
52:                ctx.header("Access-Control-Allow-Origin", "*");
...
212:        Pattern.compile("^.*\\.trycloudflare\\.com$")
```

Two issues. The comment's premise is inverted — browsers **do** send `Origin` on cross-origin `POST`s; a *missing* `Origin` most often means a non-browser client. Emitting `*` there is harmless for the credential-less case but is conceptually the wrong default. More relevantly, `*.trycloudflare.com` is a **shared public suffix**: anyone who spins up a free quick tunnel gets a matching hostname, and it is trusted here. Combined with a cookie-based auth model this would be exploitable; since auth is `Authorization: Bearer` (not cookies) the practical risk is Low — but it should not be in the allowlist.

**Fix:** make the trusted list configurable via env (`CORS_ORIGINS`), drop the `trycloudflare` wildcard or make it opt-in for dev only. Since there is no `Access-Control-Allow-Credentials` header, the current design is not cookie-exploitable — keep it that way.

### 19. Empty `catch {}` blocks hide failures

**Files:** `handler/GroupContentHandler.java:46, 74`; `handler/GroupHandler.java:159, 178, 599, 603, 922`

```java
// GroupContentHandler.java:46
46:        catch (Exception ignored) {}
```

Representative of ~10 sites. `GroupDao.java:521,524,529,533,536` uses the same idiom inside `dissolveGroup` (see #10), where it means a failed cascade delete is silently treated as success. **Fix:** at minimum `System.err.println` with context; ideally narrow the catch to the expected exception type.

### 20. Unmaintained crypto dependency / no dependency scanning

**File:** `build.gradle:22`

```gradle
22:    implementation 'org.mindrot:jbcrypt:0.4'
```

`jbcrypt 0.4` has been unmaintained since 2014. It is functionally adequate (`BCrypt.hashpw`/`checkpw` default to cost 10, and `gensalt()` uses `SecureRandom`), so this is not an active vulnerability — but there is no upgrade path for future findings. `ARCHITECTURE.md:227` already lists `[ ] 依赖漏洞扫描（可接入 OWASP dependency-check）` as an open item. **Fix:** migrate to `at.favre.lib:bcrypt` (maintained, constant-time, supports cost upgrades) and add the `dependency-check-gradle` plugin to CI. Also consider `passay`/`zxcvbn` for password strength rather than the current `length >= 6` check (`AuthHandler.java:145`).

---

## What is already done well (so it is not "fixed" by mistake)

- **SQL injection:** parameterized statements are used consistently. The only three concatenation sites (`UserDao.java:257`, `GroupDao.java:511`, `MallDao.java:588/596`) are all driven by **non-user-controlled** values (a whitelist `Set`, a literal `String[]`, and a `StringBuilder` whose only appends are fixed literals). No exploitable injection path found.
- **`DbUtil.rowToMap`** (`DbUtil.java:22-28`) explicitly excludes the `password` column, which is the correct central defence against hash disclosure via `SELECT *`.
- **`WalletDao.getWallet`** (`:20-27`) selects only business columns and never returns `pay_hash`/`pay_salt`.
- **Path traversal:** `FileHandler` defends in depth — `contains("..")`, `contains("/")`, `contains("\\")`, plus `normalize()` + `startsWith(base)` re-check (`:204-212`, `:585-594`) — and additionally enforces **group membership** before serving group files (`:597-601`). This is better than most production code.
- **Connection lifecycle:** all `try (Connection c = db.getConn())` sites are correct; the hand-rolled transaction blocks in `WalletDao`/`GroupDao`/`GroupPollDao` all have `finally` with `rollback`/`close`. I found **no connection leak**.
- **JWT:** verifier is cached (`AuthHandler.java:18`), `tv` token-version revocation is enforced on **both** the HTTP path (`:235`) and the WebSocket path (`WsHandler.java:96-100`), and WebSocket identity comes from the connection attribute set during `onConnect` (`:164`), never from the message body — so WS messages cannot spoof a sender.
- **Group content authorization** is enforced on download (`FileHandler.java:598`) rather than only on listing.

---

## Suggested remediation order

1. **Now:** disable `/api/wallet/recharge` (#1); make missing JWT secret with an existing DB a hard failure (#2).
2. **This week:** add a connection pool (#4); fix the login limiter keying and eviction (#5, #7); persist payment-PIN lockout (#3).
3. **This month:** upload quotas + per-route request size (#6); media-scoped tokens (#8); merchant role (#11); envelope + `app.exception` refactor (#13, #16); real logging (#17).
4. **Backlog:** secret relocation (#15); `MessageDigest.isEqual` (#14); Caffeine for rate limits (#12); bcrypt library migration + dependency scanning (#20); secret-scanning pre-commit hook.
