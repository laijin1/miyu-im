# E2EE Security Audit — 密语 / miyu-app (FamilyChatApp)

Scope: Android client (`apk/FamilyChatApp/**`) and Java server (`server-java/**`).
Method: static read-only review of the files listed in the task, plus the call sites that
publish/fetch keys, transmit messages, and store keys. No files were modified.
Every finding below quotes the actual source line that evidences it.

---

## Executive summary

The **cryptographic primitives are, unusually, correct**: AES-256-GCM with a fresh random
12-byte IV per message, ECDH on P-256 via the JCA, GCM authentication enforced on decrypt,
PBKDF2+AES-GCM for the key backup, `EncryptedSharedPreferences` for at-rest key storage,
fail-closed behaviour instead of plaintext fallback on the send path, and a key backup that
cannot be silently clobbered. There is **no ECB, no static IV, no RC4/DES, no raw RSA, and no
MD5/SHA-1 in the message path.**

The weaknesses are **not in the cipher — they are in the trust model around it**. The design has
no notion of *who* a public key belongs to:

1. **No authentication of public keys at all.** The server hands out any stored key to any
   authenticated user, and the client uses it without any fingerprint, TOFU pin, or
   signed-prekey check. A malicious or compromised server (or anyone who can call
   `POST /api/user/publickey` as a user, or tamper with the response) is a **full passive and
   active MITM** — this is the single most important finding.
2. **Key-change detection is actively inverted.** The client's "heal stale cache" logic
   unconditionally accepts whatever new key the server returns, so a server key swap is not
   just undetected, it is *automatically adopted*.
3. **The server never validates that a message is encrypted.** `E2eeValidator` validates
   *uploaded keys* only; message content is accepted as an opaque string. A client (or a
   modified client, or an attacker with a token) can send plaintext and the server stores and
   relays it. There is no server-side "encrypted only" guarantee, and the reported
   "encrypted" property is entirely a client-side honour system.
4. **Plaintext leaks into device logs**: the send path logs plaintext length *and* a
   ciphertext prefix under a plaintext-unaware tag; the WS receive path logs raw content
   prefixes before decryption. `Lookout`-style local log readers and `adb logcat` on
   debuggable builds see ciphertext (and metadata), not plaintext — but the raw-content dump
   on receive is still an unnecessary exposure surface for the *pre-decryption* value.
5. **Cleartext HTTP is globally permitted** in the network security config. The mitigation is
   a client-side heuristic (`ServerProtocol.isSecure`) rather than platform enforcement — for
   LAN deployments every byte, including the TLS-less `?token=` credential, is on the wire in
   the clear. This is a *stated, deliberate* trade-off, but it means the E2EE "confidentiality
   against the network" property is weaker than it looks for the common LAN case, and the
   tokens/key-backups ride the same cleartext channel.
6. **Group key management has no rotation and no forward secrecy**: there is no group key at
   all (per-member ECDH copies), which is actually good for removal semantics — see the
   balanced section — but message content is encrypted to each member's *long-term static*
   public key, so there is no FS anywhere in the system.

Details, evidence, and fixes follow.

---

## 1. Key exchange & trust

### 1.1 [Critical] Public keys are served unauthenticated by the server — full MITM

**Evidence**

`server-java/.../handler/PublicKeyHandler.java:62-68` — lookup by id, no ownership or
consistency check, returned verbatim:

```java
String publicKey = FamilyChatServer.getDb().getPublicKey(targetId);
if (publicKey == null) {
    ctx.status(404).json(Map.of("error", "未找到该用户的公钥"));
    return;
}

ctx.json(Map.of("user_id", targetId, "public_key", publicKey));
```

`server-java/.../handler/PublicKeyHandler.java:39` — the only write path, a blind upsert keyed
on the caller's own id (no key history, no notification to peers):

```java
FamilyChatServer.getDb().savePublicKey(userId, pk.toString());
```

`server-java/.../model/dao/UserDao.java:197-198` — `INSERT OR REPLACE` silently destroys the
previous key; there is no `previous_key` column, no history table, no audit:

```java
"INSERT OR REPLACE INTO public_keys (user_id, public_key, updated_at) VALUES (?,?,CURRENT_TIMESTAMP)");
```

`apk/.../utils/GroupEncryptHelper.java:151-162` — the client fetches and immediately trusts:

```java
String pub = api.getPublicKey(userId);
if (pub != null) {
    prefs.savePeerPublicKey(userId, pub);
    return pub;
}
```

`apk/.../ui/ChatActivity.java:1233-1247` — likewise on the 1:1 send path:

```java
String peerPub = fetchPeerPublicKey(peerUserId);
if (myPriv == null || peerPub == null) {
    ...
    return null;
}
String cipher = MessageEncryptor.encryptMessage(plainText, myPriv, peerPub);
```

**Attack.** An adversary who controls or compromises the server (or the LAN path, given
finding 6.1), or who obtains any valid token and calls `POST /api/user/publickey`, replaces
Bob's stored public key with the adversary's own. Alice's client fetches it, encrypts to it,
and the adversary decrypts with the matching private key, re-encrypts to Bob's real key, and
forwards. Both parties see a normal conversation. This is a **complete break of the E2EE
guarantee**, not a weakening of it. There is no code anywhere in either client or server that
computes a key fingerprint, compares it out-of-band, or pins a previously-seen key.

**Fix.**
- Client: implement **TOFU pinning** — on first sight of a peer key, store
  `SHA-256(SPKI)` in `PreferencesManager` next to `peer_pub_<uid>`; on any subsequent
  *different* value, block sending, surface a "safety number changed" warning, and require
  explicit user re-approval. Surface a per-contact **safety number** (Signal-style,
  human-comparable digits) so users can verify out of band.
- Server: keep an append-only `public_key_history(user_id, key, first_seen, replaced_at)` and
  expose the previous key + change timestamp so clients can detect substitution after the
  fact. Never `INSERT OR REPLACE` without recording the old value.
- Consider signed prekeys / a transparency log so that a server cannot equivocate.

### 1.2 [High] Key-change "self-healing" silently adopts a substituted key

**Evidence** — `apk/.../ui/ChatActivity.java:1272-1281`:

```java
// Attempt 2: force re-fetch the peer's latest public key from server and retry once.
Log.d("E2EE_LOG", "decrypt attempt#1 failed — re-fetching peer pub key from server and retrying");
String freshPub = fetchPeerPublicKey(partnerUserId);
if (freshPub != null && !freshPub.equals(peerPub)) {
    plain = MessageEncryptor.decryptMessage(content, myPriv, freshPub);
    if (plain != null) {
```

`apk/.../utils/GroupEncryptHelper.java:134-139` — same pattern:

```java
String freshPub = fetchPeerPublicKey(senderId, api, prefs);
if (freshPub != null && !freshPub.equals(peerPub)) {
    plain = MessageEncryptor.decryptMessage(myCipher, myPriv, freshPub);
    if (plain != null) return plain;
}
```

**Attack / risk.** When decryption with the cached key fails, the client's response is to
accept *whatever the server now serves* and decrypt with it — and `fetchPeerPublicKey`
overwrites the cache (`ChatActivity.java:1207`, `GroupEncryptHelper.java:155`). The one
mechanism that could have detected a key substitution instead **erases the evidence and
adopts the attacker's key**. A server that swaps Bob's key learns that Alice's ciphertexts
(which she encrypts to the swapped key) decrypt under it. The comment "heals the case where
the peer regenerated keys" describes exactly the event that should be alarming.

**Fix.** On a mismatch, decrypt with the fresh key only for *display of that one legacy
message*, but **do not** overwrite the pinned key and **do not** use the fresh key for
subsequent outgoing messages until the user confirms the change (same UX as 1.1). Log and
surface the event. With TOFU pins from 1.1 in place this retry path should be gated behind an
explicit "key changed — verify" state, not automatic.

### 1.3 [Medium] Any authenticated user can link a key to an account id they do not own? — No; but there is no proof-of-possession, so account takeover of the key slot is silent

**Evidence** — `PublicKeyHandler.java:17` keys the write on the authenticated caller, so a
user can only write *their own* slot:

```java
int userId = AuthHandler.requireAuth(ctx);
```

This is the correct binding (**a genuine strength** — see §8). However, there is **no
proof-of-possession of the private key** at upload: any client that holds a valid token can
stage an arbitrary valid-P-256 key for that account, and the server cannot tell a legitimate
key rotation from a hijack. Combined with 1.1 this means an attacker who takes over an account
(leaked token, weak password) can silently substitute the key and read all future traffic
without leaving a server-side trace of the old key.

**Fix.** Have the client sign a server-issued nonce with the new private key at upload time and
have the server verify the signature before storing — this at least proves the uploader holds
the private half, and makes an accidental/buggy overwrite detectable. Record key history (1.1).

---

## 2. Algorithms & parameters

### 2.1 [Strength] The primitives are correct

**Evidence** — `apk/.../utils/MessageEncryptor.java:31-38`:

```java
private static final String EC_ALGO = "EC";
private static final String KEY_AGREEMENT = "ECDH";
private static final String AES_ALGO = "AES/GCM/NoPadding";
private static final int GCM_TAG_LENGTH = 128; // bits
private static final int IV_LENGTH = 12;       // bytes
private static final int AES_KEY_LENGTH = 32;  // bytes (256-bit)

private static final SecureRandom secureRandom = new SecureRandom();
```

- **AEAD, authenticated**: AES-256-GCM, 128-bit tag → tampering is detected. Confirmed by test
  `MessageEncryptorTest.tamperedCiphertext_isRejected` (`MessageEncryptorTest.java:122-134`).
- **Fresh random IV per message**: `MessageEncryptor.java:90-91`:

  ```java
  byte[] iv = new byte[IV_LENGTH];
  secureRandom.nextBytes(iv);
  ```

  No counter, no reuse, no static IV. Covered by
  `sameInput_producesDifferentCiphertext` (`MessageEncryptorTest.java:139-150`).
- **No ECB, no DES/RC4, no raw RSA, no MD5/SHA-1 in the message path.** Verified by grep across
  the client and server for those algorithm names — none appear in `MessageEncryptor`,
  `GroupEncryptHelper`, `KeyBackup`, or any message handler.
- Curve is P-256: `kpg.initialize(256, secureRandom)` (`MessageEncryptor.java:47`), and the
  server independently enforces the same (`E2eeValidator.java:56`).
- Wrong-key decryption **fails closed to `null`** rather than returning garbage
  (`MessageEncryptor.java:162-165`), covered by two negative tests.

### 2.2 [Medium] KDF for the message key is a bare SHA-256 of the ECDH secret (no HKDF, no salt, no context binding)

**Evidence** — `apk/.../utils/MessageEncryptor.java:85-87`:

```java
// Derive AES-256 key via SHA-256
MessageDigest sha256 = MessageDigest.getInstance("SHA-256");
byte[] aesKey = sha256.digest(sharedSecret);
```

and identically at `MessageEncryptor.java:142-144`.

**Risk.** This is the NIST SP 800-56A "concatenation KDF"-degenerate case and is
cryptographically *tolerable* here (the input is a uniformly random 256-bit X-coordinate, and
SHA-256 is a good PRF, so the output is indistinguishable from random), but it is not a
standards-compliant KDF and it omits **context binding**: no "info" string, no salt, no
protocol/version/party-identity input. That means the same shared secret always yields the same
AES key with no domain separation, so any future protocol that derives more than one key from
the same ECDH secret (e.g. a separate MAC key, or a file-transfer key) would reuse it.

**Fix.** Replace with HKDF-SHA256 (`crypto_kdf` / `javax.crypto` + `Mac`) or run the shared
secret through HKDF-Extract-with-zero-salt + HKDF-Expand with an info string such as
`"familychat/e2ee/v2/message"` and, ideally, both SPKI hashes. This is a cheap change that also
gives you a place to bind the protocol version.

### 2.3 [Low] Ciphertext length is not authenticated as a length prefix, and there is no message-type/recipient binding

**Evidence** — `MessageEncryptor.java:100-105`:

```java
byte[] combined = new byte[IV_LENGTH + ciphertext.length];
System.arraycopy(iv, 0, combined, 0, IV_LENGTH);
System.arraycopy(ciphertext, 0, combined, IV_LENGTH, ciphertext.length);
```

GCM is initialized with **no AAD** (`cipher.init(Cipher.ENCRYPT_MODE, keySpec, spec)` at
`MessageEncryptor.java:97` and `:156`). The IV is not covered by the tag either (it is a GCM
parameter), so an attacker can flip IV bits and the ciphertext will fail authentication
(harmless here) — but more importantly, nothing binds *who the message is from/to* or *its
type* to the ciphertext.

**Risk.** Since the key is pairwise and derived only from (myPriv, peerPub), a ciphertext
produced for Bob cannot be decrypted by Carol anyway, so cross-recipient confusion is limited.
However, a **reflection attack** is conceivable in the group case: I encrypt `plain` to myself
and my own entry is under my own key pair, so a message I sent can be replayed back to me as
"from me" — see also 3.2. Binding sender/receiver ids as AAD (or as a KDF info string) removes
this class entirely.

**Fix.** Add AAD: `new GCMParameterSpec(128, iv)` plus
`cipher.updateAAD((senderId + "|" + receiverId + "|" + msgType).getBytes(UTF_8))`, and use the
same AAD on decrypt. Bump the wire format to v2 with a version byte so old messages still
decrypt.

---

## 3. Private key storage on device

### 3.1 [Strength] Keys are stored in `EncryptedSharedPreferences` and it fails closed

**Evidence** — `apk/.../utils/PreferencesManager.java:26-33`:

```java
String masterKeyAlias = MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC);
prefsTemp = EncryptedSharedPreferences.create(
    NAME,
    masterKeyAlias,
    ctx.getApplicationContext(),
    EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
    EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
);
```

with a hard failure rather than a plaintext downgrade at `PreferencesManager.java:37-39`:

```java
Log.e(TAG, "EncryptedSharedPreferences init failed — refusing to fall back to plaintext", e);
throw new IllegalStateException(
        "安全存储初始化失败, 无法以安全模式运行(拒绝明文落盘 token/私钥)。请重装应用或检查系统锁屏/Keystore。", e);
```

The private key lives in that store at `PreferencesManager.java:170-183`:

```java
public void saveKeyPair(String publicKey, String privateKey) {
    prefs.edit().putString("e2ee_pub_key", publicKey)
                .putString("e2ee_priv_key", privateKey).apply();
```

This is the **correct** choice and materially better than plaintext `SharedPreferences`. The
Keystore master key is non-exportable and hardware-backed where available.

**Remaining gap (Medium):** the key is **not gated on user authentication** — the alias comes
from `MasterKeys.AES256_GCM_SPEC`, which does not set `setUserAuthenticationRequired(true)`.
Any process with app-private-file access (root, a forensic image of an unlocked or even locked
device in many cases, or an ADB backup on a debuggable build) that can also reach the Keystore
can decrypt. There is also no `setUnlockedDeviceRequired`.

**Fix (optional hardening).** Use an explicit `KeyGenParameterSpec` with
`setUserAuthenticationRequired(true)` (or at minimum `setUnlockedDeviceRequired(true)`) for the
master key, so the private key is only decryptable with a device unlock. Note this changes UX
(requires unlock) and must be paired with the backup-restore flow, which already exists.

### 3.2 [Low] `logout()` deliberately preserves the account list, which contains the private key

**Evidence** — `PreferencesManager.java:357-391`, `saveLoginAccount` writes the private key into
the account snapshot:

```java
acc.e2eePub = myPub;
acc.e2eePriv = myPriv;
```

and `PreferencesManager.java:216-241` — `logout()` clears the active slots but explicitly
restores the account list:

```java
String accounts = prefs.getString(KEY_ACCOUNTS, "[]");
...
.remove("e2ee_pub_key")
.remove("e2ee_priv_key")
...
// 恢复设备级配置（server / 账号列表 / 黑夜模式 / 搜索引擎）
prefs.edit().putString(KEY_ACCOUNTS, accounts).apply();
```

**Risk.** The private key persists in `KEY_ACCOUNTS` after logout, so "log out" does not remove
key material from the device. This is an intentional multi-account convenience (documented in
the code as preventing key loss) and the data is still inside EncryptedSharedPreferences, so
the practical impact is limited — but it does mean a *shared* device's "logout" does not
actually evict the other user's decryption capability. `removeAccount(userId)` exists
(`PreferencesManager.java:454-458`) and is the only path that drops it.

**Fix.** Document this in the UI ("logout keeps keys for account switching; use *remove account*
to delete keys"). Consider requiring device auth before switching into a saved account.

### 3.3 [Strength] Keys are excluded from cloud backup and device transfer

**Evidence** — `apk/.../res/xml/data_extraction_rules.xml:3-8`:

```xml
<cloud-backup>
    <exclude domain="sharedpref" path="family_chat_secure_prefs.xml"/>
</cloud-backup>
<device-transfer>
    <exclude domain="sharedpref" path="family_chat_secure_prefs.xml"/>
</device-transfer>
```

Correct and important: the Keystore-wrapped prefs file is useless on another device anyway, and
excluding it avoids shipping ciphertext (and the token) to Google's backup. `allowBackup="true"`
is still set (`AndroidManifest.xml:25`) but the sensitive file is excluded, which is the right
combination.

### 3.4 [Medium] Key backup uses PBKDF2-HMAC-**SHA1**, and the v1 format has no random salt

**Evidence** — `apk/.../utils/KeyBackup.java:35-44`:

```java
private static final String PBKDF2 = "PBKDF2WithHmacSHA1";
/** v1 旧格式迭代次数：仅用于解密历史备份。 */
private static final int V1_ITERATIONS = 50000;
/** v2 新格式迭代次数：P2-③ 由 5 万提升到 21 万（约 4 倍，兼顾旧手机耗时）。 */
private static final int V2_ITERATIONS = 210000;
...
private static final String SALT_PREFIX = "familychat/keybackup/v1/";
```

and `KeyBackup.java:122-123` for the legacy path:

```java
byte[] salt = (SALT_PREFIX + userId).getBytes("UTF-8");
SecretKey key = deriveKey(password, userId, salt, V1_ITERATIONS);
```

**Risk.** The v2 format is a real improvement (random 16-byte salt, 210k iterations, version
header) and the v1/v2 split is handled carefully — see §8. But: (a) HMAC-SHA1 is used for
*new* backups, not just legacy reads, justified by minSdk 24 compatibility; (b) 210,000 is
below the current OWASP recommendation for PBKDF2-HMAC-SHA1 (1,300,000) and far below what a
GPU can chew through, and the backup is **stored on the server**, so a server compromise hands
the attacker a high-value offline-cracking target for a *login password* (which is also reused
for authentication, so cracking it yields account takeover, not just key recovery); (c) v1
backups with the fixed, guessable salt (`"familychat/keybackup/v1/" + userId`) remain
decryptable, so any user who has not re-logged-in since the v2 upgrade still has a
rainbow-table-friendly backup on the server.

**Fix.**
- Move new backups to `PBKDF2WithHmacSHA256` (available via a fallback: try SHA256, catch
  `NoSuchAlgorithmException`, fall back to SHA1) or, better, **Argon2id / scrypt** via a
  vetted library. Bump to the OWASP guidance for whatever PRF you choose.
- Force a re-wrap: on next successful login, re-encrypt the v1 backup to v2 and overwrite the
  server copy, and record a "format upgraded" flag so it happens once. `LoginActivity` already
  re-uploads the backup on every login (`LoginActivity.java:471-473`), so the machinery exists —
  it just needs to always write v2 (it does) *and* the v1 record to be retired.
- Add an explicit "backup password" distinct from the login password, or derive the backup key
  from a dedicated secret, so that cracking the backup does not equal account takeover.

---

## 4. Group encryption

### 4.1 [Strength] There is no group key — this is a genuinely good design choice

**Evidence** — `apk/.../utils/GroupEncryptHelper.java:63-86`, a per-member copy:

```java
Map<String, String> out = new LinkedHashMap<>();
...
for (int uid : memberIds) {
    if (uid == myUserId) continue;
    String peerPub = getPeerPublicKey(uid, api, prefs);
    ...
    String cipher = MessageEncryptor.encryptMessage(plain, myPriv, peerPub);
```

**Why this is the right call.** Because there is no shared symmetric group key, a member who is
removed **cannot** decrypt messages sent after their removal — the sender simply does not
include an entry for them (`memberIds` is refreshed from the server via
`ensureMembersLoaded()`, `GroupChatActivity.java:699-716`). This eliminates the entire class of
"removed member still holds the old group key" bugs that plague sender-keys designs, and it
means **no group key rotation is required** and messages are **not re-encrypted under one
long-lived symmetric key**. Each ciphertext is bound to exactly one recipient's key pair.

### 4.2 [High] Group membership is attacker-controlled from the client's perspective; a member who joins later can read earlier messages only if they were included — but skips are silent to recipients

**Evidence** — `GroupEncryptHelper.java:71-78`, missing keys are skipped:

```java
String peerPub = getPeerPublicKey(uid, api, prefs);
if (peerPub == null) {
    Log.w(TAG, "skip member " + uid + ": no public key");
    result.skippedMembers.add(uid);
    continue;
}
```

**Risk.** Because encryption is per-recipient and driven by a client-side `memberIds` list,
the *sender's* client decides who can read. The sender is warned
(`GroupChatActivity.java:805-810`) and that is genuinely good UX, but the *recipients* have no
way to know that a message was sent to only a subset — there is no recipient-visible
member-coverage commitment. A modified or malicious client can send a group message encrypted
to only a chosen subset and every other member just sees the message as normal or as
`[此消息无法解密]`. In a group where decisions are made, this allows covert side-channel
conversations that are cryptographically indistinguishable from a delivery failure.

**Fix.** Include, inside the authenticated (AAD or inner-plaintext) payload, the sorted list of
recipient ids the message was encrypted for, and have receivers verify that the set equals the
group membership at send time. Since AAD is authenticated, a receiver can then detect
selective encryption. This requires the AAD change from 2.3 — do them together as wire format v2.

### 4.3 [Medium] Group membership is snapshotted client-side; a stale list silently excludes new members

**Evidence** — `GroupChatActivity.java:698-716` (`ensureMembersLoaded` only fetches when the
list is empty) and `GroupEncryptHelper.java:48-50` which takes `memberIds` as a *parameter*:

```java
public static EncryptResult encryptForGroup(String plain, int myUserId,
                                            List<Integer> memberIds,
                                            ApiClient api, PreferencesManager prefs) {
```

**Risk.** `ensureMembersLoaded()` returns immediately if `memberIdList` is non-empty
(`GroupChatActivity.java:700`: `if (!memberIdList.isEmpty()) return;`), so the member list used
for encryption is whatever was loaded when the screen opened, refreshed only on a
`group_members_changed` push. If that push is missed (WS disconnected, see
`WebSocketManager` reconnect logic at `WebSocketManager.java:405-413`, which can be delayed up
to 30 s), a newly added member is silently omitted from the ciphertext and gets
`[此消息无法解密]`. This is an availability/correctness bug rather than a confidentiality
break, but it is user-visible and erodes trust in the E2EE layer.

**Fix.** Re-fetch the member list immediately before each send (or use a short TTL cache, e.g.
30 s) rather than relying on push delivery; and treat "members changed" as invalidating the
cached list unconditionally.

### 4.4 [Low] Group messages are not bound to a group id

**Evidence** — `GroupEncryptHelper.encryptForGroup` never includes `group_id` in the AAD or
payload; the same helper serves group messages and group announcements
(`GroupChatActivity.java:684`, `GroupAnnouncementActivity.java:143`). The server keys storage by
path (`GroupHandler.java:628`) but the *ciphertext* itself does not commit to the group.

**Risk.** A ciphertext legitimately encrypted for a set of members can be replayed into a
different group whose membership overlaps — the recipients' key pairs are the same, so it
decrypts cleanly. Combined with the absence of AAD (2.3) this is a real (if narrow) confusion
vector.

**Fix.** Include `group_id` (and the sender id and message type) in the AAD.

### 4.5 [Strength] Non-JSON content passes through, but sending is fail-closed

**Evidence** — `GroupEncryptHelper.java:56-62`:

```java
String myPriv = prefs.getMyPrivateKey();
if (myPriv == null || myPriv.isEmpty()) {
    Log.e(TAG, "encrypt blocked: own private key missing");
    result.ownKeyMissing = true;
    result.json = null; // 绝不回退明文：调用方应取消发送
    return result;
}
```

and the callers honour it, cancelling the send rather than degrading to plaintext —
`GroupChatActivity.java:788-798`:

```java
if (er.json == null) {
    // P1-②: 本地私钥缺失时禁止明文回退，取消发送并提示
    Log.e(TAG, "send text blocked: cannot encrypt (own key missing)");
    runOnUiThread(() -> {
        messages.remove(msg);
```

Same pattern in `ChatActivity.java:388-399` for 1:1. This is exactly the right behaviour and is
one of the strongest parts of the implementation.

---

## 5. Server-side validation

### 5.1 [Critical] `E2eeValidator` validates *keys*, never *messages* — plaintext is accepted

**Evidence** — `E2eeValidator.java` has exactly two entry points and both are about uploads:

```java
public static boolean isValidPublicKey(String b64) {   // line 41
public static boolean isValidKeyBackup(String b64) {   // line 67
```

Its only call sites are `PublicKeyHandler.java:34` and `KeyBackupHandler.java:40`. **It is never
called on message content.** The message path performs no structural check whatsoever —
`MessageHandler.java:106-118`:

```java
Object contentObj = body.get("content");
String content = contentObj instanceof String ? (String) contentObj : "";
String msgType = body.getOrDefault("msg_type", "text").toString();
...
if (content.isEmpty() && "text".equals(msgType)) {
    ctx.status(400).json(Map.of("error", "消息不能为空"));
    return;
}
```

The only check is non-emptiness. Same on the group path — `GroupHandler.java:593`:

```java
String content = body.get("content") instanceof String s ? s : "";
```

and the WS path — `WsHandler.java:213, 236`:

```java
if (!msg.has("receiver_id") || !msg.has("content")) {
...
String content = msg.get("content").getAsString();
```

**Attack / risk.** The server will store and faithfully relay arbitrary plaintext. This matters
because:

- **There is no "encrypted" flag anywhere to trust or distrust — and that cuts both ways.** The
  task asked whether the server "trusts a client-supplied flag." It does not; there is
  no flag. But that is not a mitigation: the server equally has no way to *reject* plaintext, so
  the E2EE property of any given conversation is unverifiable from the server's side and depends
  entirely on which client build the sender ran.
- A malicious or modified client, or anyone with a valid token, can inject plaintext into the
  message store and it will be indistinguishable on the wire from a legitimate message. A
  downgrade attacker who controls the client (or performs an app-level patch) gets a
  **silent plaintext downgrade** — and, per §1, can also just substitute a key.
- The server's own `search` endpoints actively operate on the stored content
  (`MessageDao.java:86`: `AND msg_type!='recalled' AND (content LIKE ? OR filename LIKE ?)`),
  confirming that content is opaque, searchable text to the server, not a ciphertext blob it
  refuses to interpret.

**Fix.**
- Enforce a **server-side ciphertext shape check** on every message/announcement write for
  `msg_type` in {text, image, video, file, voice}: require a valid v2 envelope (version byte,
  base64, minimum length, IV+tag length arithmetic). This does not make the server able to read
  content, but it makes a plaintext downgrade **loud and rejected** instead of silent. Note the
  media cases legitimately carry plaintext URLs today (§5.2) — so this must be a
  per-message-type policy, and the design decision about media URLs must be made first.
- Better: move the integrity guarantee into the protocol — **the client must refuse to display
  any text message that is not a valid v2 envelope**, and must show an unmistakable
  "unencrypted message" warning if one appears. That is the only defence that survives a
  compromised server, and it complements TOFU from §1.
- Add a client-side "encryption unavailable — do not send" hard stop (already present for the
  key-missing case) for the "server returned a plaintext message" case.

### 5.2 [High] Media messages send **plaintext URLs** as `content` — the E2EE claim does not cover attachments

**Evidence** — `ChatActivity.java:826`, `:934`, `:1023` all encrypt a *URL* and then send it,
but the send path stores the encrypted URL in `content` while the **thumbnail is explicitly
plaintext** — `MessageHandler.java:111-113`:

```java
// 视频消息缩略图 URL（明文预览图，与 content 的加密互不干扰）
Object thumbObj = body.get("thumbnail");
String thumbnail = thumbObj instanceof String ? (String) thumbObj : "";
```

And the group file library stores the **plaintext URL** as the searchable filename/content —
`GroupHandler.java:638-640`:

```java
int fileId = FamilyChatServer.getDb().indexGroupFile(
        groupId, userId, filename.isEmpty() ? content : filename,
        content, thumbnail, fileSize, folder, msgType, ((Number) saved.get("id")).intValue());
```

with the server's own comment at `GroupHandler.java:631`:

```java
// ── 阶段4：文件/图片/视频/语音消息自动索引进群文件库（明文 URL，仅群成员可看）──
```

and search over it at `GroupFileDao.java:22-28`:

```java
/** 群内搜索：只搜明文字段（文件名/系统消息），E2EE 密文不可服务端搜索。 */
```

**Risk.** The message *text* is E2EE, but the **file/image/video/voice URLs and thumbnails are
plaintext on the server and in the message store**, and the server builds a searchable index
over them. Anyone with server access (or a SQL dump) obtains a full map of who shared which
files, with direct unauthenticated-by-URL retrieval paths, and thumbnails leak image content
itself. The comment "仅群成员可看" (visible only to group members) is a server-side
authorization claim, not a cryptographic one — it directly contradicts the E2EE threat model,
where the server is assumed untrusted.

**Fix.** Encrypt the file bytes and the thumbnail with a per-file random key, encrypt that key
to each recipient (reusing the existing per-member scheme), and store only ciphertext + an
opaque blob id. At minimum, stop persisting the plaintext URL in the searchable
`group_files` index — store the id and require the client to resolve it through the E2EE
channel.

### 5.3 [Strength] The validator itself is well-constructed and does what it claims

**Evidence** — `E2eeValidator.java:41-60` performs real structural validation of the uploaded
public key (base64 decode, X509 parse, EC type check, and a curve-size check at line 56):

```java
KeyFactory kf = KeyFactory.getInstance("EC");
PublicKey pub = kf.generatePublic(new X509EncodedKeySpec(der));
if (!(pub instanceof ECPublicKey)) return false;
ECPublicKey ec = (ECPublicKey) pub;
return ec.getParams().getCurve().getField().getFieldSize() == 256;
```

and `E2eeValidator.java:67-89` does proper bounds- and length-arithmetic on the backup format,
mirroring the client's v1/v2 layout. Combined with the length caps
(`PUBKEY_MAX_LEN = 1024`, `BACKUP_MAX_LEN = 200_000`) this closes the stated "malicious client
stuffs the DB with garbage" hole. It is scope-limited (uploads only) rather than broken.

**One residual:** `PUBKEY_MIN_LEN = 44` is far below the ~124 chars a real P-256 X509 SPKI
occupies, so the length gate is loose — but the X509 parse that follows is the real check, so
this is not exploitable. Tighten to ~120 for clarity.

---

## 6. Transport security

### 6.1 [High] Cleartext HTTP is globally permitted; enforcement is a client-side string heuristic

**Evidence** — `apk/.../res/xml/network_security_config.xml:35-40`:

```xml
<!-- 明文 HTTP 全局放行：公网安全性由 ServerProtocol 保证（见上） -->
<base-config cleartextTrafficPermitted="true">
    <trust-anchors>
        <certificates src="system" />
    </trust-anchors>
</base-config>
```

referenced from `AndroidManifest.xml:31`:

```xml
android:networkSecurityConfig="@xml/network_security_config">
```

The actual decision is made in `apk/.../utils/ServerProtocol.java:15-34`:

```java
public static boolean isSecure(String server) {
    if (server == null || server.isEmpty()) return false;
    String host = server.split(":")[0].trim().toLowerCase();
    ...
    if (host.equals("localhost") || host.equals("127.0.0.1") || host.equals("0.0.0.0")) return false;
    // Private IPv4 ranges
    if (isPrivateIPv4(host)) return false;  // 私有网段一律不强制 https
    if (host.startsWith("10.")) return false;
    if (host.matches("172\\.(1[6-9]|2[0-9]|3[01])\\..*")) return false;
    // Anything else (public domain, public IP, Cloudflare Tunnel) → WAN
    return true;
}
```

**Risk.** The config file's own comment (lines 14-31) documents that a previous attempt to
enforce this at the platform layer "打断了可用性" and was reverted. The consequence is that
**for every LAN deployment — which, per the server's own startup banner
(`FamilyChatServer.java:109`: `LAN: http://<ip>:<port>`), is the primary deployment mode — all
traffic is plaintext HTTP**: the JWT bearer token, the username/password at login, the
**password-encrypted private-key backup**, and every ciphertext. The E2EE layer still protects
message *contents* on a LAN wiretap, but:
- the **token is exposed**, allowing an attacker on the LAN to impersonate the user and, per
  §1, to **replace that user's public key** — converting a passive wiretap into a full MITM;
- the login password is exposed, which also unlocks the key backup (§3.4);
- `withToken()` appends the token as a **query parameter** to media URLs
  (`ServerProtocol.java:47-51`), so tokens land in server access logs, browser history, and any
  proxy in between. The WebSocket does the same (`WebSocketManager.java:143`:
  `String url = ServerProtocol.wsOf(server) + server + "/ws?token=" + token;`), though the
  server does redact it in its own access log (`FamilyChatServer.java:92-95`).

**Fix.**
- **Generate a self-signed cert at first install and pin it** (or ship a per-deployment cert),
  then use `https`/`wss` even on the LAN with a `network-security-config` `<domain>` pin set.
  This preserves the "no DNS required" property that motivated the current design while removing
  cleartext.
- **Move the token out of the query string** into the `Authorization` header for media and into
  the first WebSocket frame (the code at `WebSocketManager.java:140-142` already flags this as
  a known issue).
- If cleartext must stay for LAN, at least scope `cleartextTrafficPermitted="false"` in
  `<base-config>` and add a `<domain-config cleartextTrafficPermitted="true">` for the private
  ranges — accepting the DHCP-churn maintenance cost the comment describes.

### 6.2 [Medium] No certificate pinning on the HTTPS/WAN path

**Evidence** — `apk/.../api/ApiClient.java:27-33` builds a default client with no pinner:

```java
private static final OkHttpClient client = new OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .followRedirects(true)
        .retryOnConnectionFailure(true)
        .build();
```

Grep for `CertificatePinner` / `X509TrustManager` / `checkServerTrusted` across the client
returns **no matches** — there is no pinning anywhere. The trust anchors are the system store
plus user certs on debug builds (`network_security_config.xml:43-48`).

**Risk.** On the WAN path (Cloudflare Tunnel, per `ServerProtocol.java:28`), a
compromised/coerced CA, a corporate MITM proxy, or a hostile network can intercept TLS. Because
the client has **no key pinning for peers either** (§1), TLS interception translates directly
into the endpoint-substitution attack. Also note `followRedirects(true)` on a client that
carries a bearer token in a header — OkHttp strips `Authorization` on cross-host redirects, but
same-host redirect chains are still an unnecessary exposure.

**Fix.** Add `CertificatePinner` with the SPKI hashes of the deployment cert (plus a backup
pin), and set `followRedirects(false)` for API calls. Pinning is the only thing that makes the
WAN path meaningfully stronger than the LAN path.

---

## 7. Metadata leakage into logs

### 7.1 [Medium] Raw pre-decryption content is logged on the receive path

**Evidence** — `apk/.../ui/ChatActivity.java:457-460`:

```java
if (msg.content != null && !"recalled".equals(msg.msg_type)) {
    Log.d("E2EE_LOG", "onNewMessage raw content len=" + msg.content.length()
            + " head=\"" + msg.content.substring(0, Math.min(40, msg.content.length())) + "...\""
            + " sender=" + msg.sender_id);
```

**Risk.** This value is ciphertext for a well-behaved peer, so it is not a plaintext leak in
the normal case — but it is an unconditional dump of the raw wire value *before* the client has
established that it is a valid envelope. Under the plaintext-downgrade scenario of §5.1, this
line logs the **first 40 characters of a plaintext message** to logcat. It also leaks
`content.length()` (which, absent padding, is a close proxy for plaintext length — a real
metadata leak) and the sender id, in a log tag that any local app with `READ_LOGS` (or any
debugger on a debuggable build) can read.

### 7.2 [Medium] Ciphertext prefixes and plaintext lengths are logged on the send/decrypt paths

**Evidence** — `apk/.../utils/MessageEncryptor.java:106`:

```java
Log.d(TAG, "encrypt: len=" + plainText.length() + " → " + result.substring(0, Math.min(40, result.length())) + "...");
```

`MessageEncryptor.java:160`:

```java
Log.d(TAG, "decrypt: cipher=" + cipherTextB64.substring(0, Math.min(40, cipherTextB64.length())) + "... → len=" + plainText.length());
```

`ChatActivity.java:1244`:

```java
Log.d("E2EE_LOG", "send: len=" + plainText.length() + " cipher=" + cipher.substring(0, Math.min(40, cipher.length())) + "... (peerPub " + peerPub.length() + " chars)");
```

`GroupEncryptHelper.java:116` and `:140` log the member-id keyset and ciphertext length:

```java
Log.w(TAG, "no self entry, sender=" + senderId + " keys=" + obj.keySet());
...
Log.w(TAG, "decrypt failed, sender=" + senderId + " myCipherLen=" + myCipher.length());
```

**Risk.** No plaintext is logged here (this is better than many implementations), but the
**exact plaintext length** is logged on every encrypt and decrypt, and the first 40 base64 chars
of every ciphertext/IV. Length is meaningful metadata (a 6-character message vs. a 4000-character
one is distinguishable, enabling traffic analysis and confirmation-of-guess attacks). The 40-char
prefix includes the **entire 12-byte IV** plus the first ~18 bytes of ciphertext — not a
plaintext leak under GCM, but gratuitous.

The `TAG = "E2EE_LOG"` constant (`MessageEncryptor.java:30`) means all of this is tag-filterable
in a single `adb logcat -s E2EE_LOG`, which makes bulk collection trivial. Note these are
`android.util.Log` calls, **not** `AppLog`, so they do **not** land in the on-device
`logs/app.log` file (`AppLog.java:47-53`) — the exposure is logcat/`READ_LOGS`, not the
in-app log viewer. The one place `AppLog` is used for E2EE is benign
(`ChatActivity.java:1286`: `AppLog.e("E2EE", "消息解密失败 partnerUserId=" + partnerUserId)` —
no content).

**Fix.** Delete the content-bearing and length-bearing log statements, or gate them behind
`BuildConfig.DEBUG` and a single flag. Log only event outcomes ("decrypt failed",
"key changed"), never lengths, prefixes, or raw values. If diagnostics are needed in the field,
log a hash of the ciphertext, not the ciphertext.

---

## 8. What is sound (balanced assessment)

This is a codebase where the crypto is largely right and the trust model is largely missing.
Explicit credit:

1. **AEAD everywhere it matters.** AES-256-GCM with 128-bit tags and a fresh `SecureRandom` IV
   per message (`MessageEncryptor.java:31-38`, `:90-91`, `:95`). Tamper-detection is covered by
   a real negative test (`MessageEncryptorTest.java:122-134`), and wrong-key decryption returns
   `null` rather than garbage (`MessageEncryptorTest.java:103-117`).
2. **No legacy crypto.** No ECB, DES, RC4, raw RSA, or MD5 in the message path; PBKDF2 is used
   for the backup and SHA-256 for hashing. The only SHA-1 is the PBKDF2 PRF in `KeyBackup`
   (§3.4), not a signature or MAC.
3. **At-rest protection is real and fails closed.** `EncryptedSharedPreferences` backed by the
   Android Keystore, with a hard `IllegalStateException` instead of a plaintext downgrade
   (`PreferencesManager.java:26-40`) — a genuinely uncommon and correct choice. Keys are
   excluded from cloud backup and device transfer (`data_extraction_rules.xml:3-8`).
4. **Never falls back to plaintext on send.** Both 1:1 (`ChatActivity.java:388-399`) and group
   (`GroupChatHelper.java:56-62`, `GroupChatActivity.java:788-798`) paths **cancel the send**
   and tell the user when encryption is impossible. The group path additionally reports exactly
   which members could not be reached (`GroupEncryptHelper.java:38`, `:74-77`;
   `GroupChatActivity.java:805-810`).
5. **The per-recipient group design is better than a shared group key.** No group key means no
   rotation problem, no rekey race, and a removed member provably cannot decrypt subsequent
   messages (`GroupEncryptHelper.java:63-86`). Messages are never re-encrypted under one
   long-lived symmetric key.
6. **The key backup is carefully engineered.** Versioned format (`KeyBackup.java:22-28`), random
   16-byte salt for v2, per-account isolation via `userId` in the salt, GCM integrity, bounds-
   checked parsing on both sides (`KeyBackup.java:132-156`, `E2eeValidator.java:67-89`), and a
   solid test suite including v1 forward-compatibility
   (`KeyBackupTest.java:142-177`), wrong-password (`:83-88`), wrong-user (`:91-97`), tamper
   (`:100-110`), and malformed-input (`:113-133`) cases.
7. **Key-loss is treated as a first-class danger.** `LoginActivity.ensureLoginKeys`
   (`LoginActivity.java:377-432`) tries local → account snapshot → server backup, refuses to
   generate new keys on a *network error* specifically so a flutter cannot overwrite the
   server's public key and orphan history (`LoginActivity.java:403-409`), and when the backup
   exists but will not decrypt it asks the user before destroying access to old messages
   (`LoginActivity.java:446-459`). `PreferencesManager.saveLoginAccount` (`:357-375`) and
   `saveCurrentAccount` (`:394-420`) both contain explicit guards against overwriting a good
   key with an empty value. This is unusually careful.
8. **The validator does what it says.** `E2eeValidator` is not security theatre — it parses the
   X509 key, checks the EC curve size, and does correct length arithmetic on the backup
   (`E2eeValidator.java:41-89`), closing the DB-stuffing hole its comment describes.
9. **Server-side authorization is present on the key endpoints.** Both key endpoints require
   auth (`PublicKeyHandler.java:17`, `:45`; `KeyBackupHandler.java:23`, `:52`), a user can only
   write their own key slot, and `getKeyBackup` returns only the caller's own backup — an
   attacker cannot pull arbitrary users' backups. The server also redacts `?token=` in its
   access log (`FamilyChatServer.java:92-95`).

---

## Prioritized findings

| # | Sev | Finding | Key evidence |
|---|-----|---------|--------------|
| 1 | **Critical** | Public keys served and consumed with no fingerprint/TOFU/pin → malicious or compromised server is a full MITM | `PublicKeyHandler.java:62-68`; `GroupEncryptHelper.java:151-162`; `ChatActivity.java:1233-1247` |
| 2 | **Critical** | Server never validates that message content is encrypted; no structural check on any message write → silent plaintext downgrade accepted and stored | `E2eeValidator.java` (uploads only); `MessageHandler.java:106-118`; `GroupHandler.java:593` |
| 3 | **High** | Key-change auto-heal unconditionally adopts a substituted peer key, erasing the only detection signal | `ChatActivity.java:1272-1281`; `GroupEncryptHelper.java:134-139` |
| 4 | **High** | Plaintext file/image/video/voice **URLs and thumbnails** stored server-side and indexed for search; attachments are outside the E2EE boundary | `MessageHandler.java:111-113`; `GroupHandler.java:631-640`; `GroupFileDao.java:22-28` |
| 5 | **High** | Cleartext HTTP globally permitted; LAN deployments send token, login password, and key backup in the clear | `network_security_config.xml:36`; `ServerProtocol.java:15-34`; `ServerProtocol.java:47-51` |
| 6 | **High** | Selective encryption in groups is invisible to recipients (no authenticated recipient set) | `GroupEncryptHelper.java:71-86`; `GroupChatActivity.java:805-810` |
| 7 | **Medium** | Message-key KDF is bare SHA-256 of the ECDH secret — no HKDF, no salt, no info/context binding | `MessageEncryptor.java:85-87`, `:142-144` |
| 8 | **Medium** | No AAD: sender/receiver/type/group-id not bound to the ciphertext (enables replay/confusion) | `MessageEncryptor.java:97`, `:156`; `GroupEncryptHelper.java:79` |
| 9 | **Medium** | Raw pre-decryption content (40-char head + length) logged on receive; plaintext lengths logged on every encrypt/decrypt | `ChatActivity.java:457-460`; `MessageEncryptor.java:106`, `:160` |
| 10 | **Medium** | Backup uses PBKDF2-HMAC-**SHA1** for new backups at 210k iterations, below OWASP guidance; v1 fixed-salt backups remain decryptable server-side | `KeyBackup.java:35-44`, `:122-123` |
| 11 | **Medium** | Stale client-side member list silently excludes newly added members from group ciphertexts | `GroupChatActivity.java:698-716` |
| 12 | **Medium** | No certificate pinning on the HTTPS/WAN path; no proof-of-possession at key upload | `ApiClient.java:27-33`; `PublicKeyHandler.java:39` |
| 13 | **Medium** | `EncryptedSharedPreferences` master key is not gated on device unlock / user auth | `PreferencesManager.java:26` |
| 14 | **Low** | Private key survives logout via the account snapshot in `KEY_ACCOUNTS` | `PreferencesManager.java:216-241`, `:385-386` |
| 15 | **Low** | Group ciphertext not bound to `group_id` — cross-group replay between overlapping member sets | `GroupEncryptHelper.java:48-86` |
| 16 | **Low** | `PUBKEY_MIN_LEN = 44` is far below a real P-256 SPKI (~124 chars) | `E2eeValidator.java:21`, `:42` |

### Suggested remediation order

1. **TOFU pinning + key-change warnings** (#1, #3) — without this, nothing else in the E2EE
   design is trustworthy. Highest impact per unit of work.
2. **Wire format v2** bundling: HKDF with info (#7), AAD binding sender/receiver/type/group
   (#8, #15), and the authenticated recipient set for groups (#6). Do these as one breaking
   change with a version byte and dual-read support for existing history.
3. **Client must refuse non-envelope text messages** (#2) with a visible "unencrypted" warning,
   plus a server-side shape check as defence in depth.
4. **Encrypt attachments** (#4) and remove plaintext URLs/thumbnails from the searchable index.
5. **Transport**: pin the deployment cert and drop the `?token=` query parameter (#5, #12).
6. **Log hygiene** (#9) — cheap, mechanical, and removes a real metadata channel.
7. **Backup hardening**: SHA-256/Argon2 PRF, retire v1, add a distinct backup passphrase (#10).

---

*Read-only audit. No files were modified.*
