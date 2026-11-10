# 💬 Miyu (密语)

> **Self-hosted, End-to-End Encrypted IM — Pure Java Full-Stack**
> **自托管的端到端加密即时通讯——纯 Java 全栈实现**

<p align="center">
  <img src="docs/assets/design-icons/apk图标.png" width="120" alt="Miyu Logo"/>
</p>

<p align="center">
  <a href="https://github.com/laijin1/miyu-im/stargazers"><img src="https://img.shields.io/github/stars/laijin1/miyu-im?style=flat-square" alt="Stars"/></a>
  <a href="https://github.com/laijin1/miyu-im/network"><img src="https://img.shields.io/github/forks/laijin1/miyu-im?style=flat-square" alt="Forks"/></a>
  <a href="LICENSE"><img src="https://img.shields.io/badge/license-MIT-blue?style=flat-square" alt="License"/></a>
  <img src="https://img.shields.io/badge/Java-17-orange?style=flat-square" alt="Java 17"/>
  <img src="https://img.shields.io/badge/Android-API%2034-green?style=flat-square" alt="Android"/>
  <img src="https://img.shields.io/badge/tests-365%20passed-brightgreen?style=flat-square" alt="Tests"/>
</p>

<p align="center">
  <a href="#-features">Features</a> ·
  <a href="#-quick-start">Quick Start</a> ·
  <a href="#-architecture">Architecture</a> ·
  <a href="#-security">Security</a> ·
  <a href="#-screenshots">Screenshots</a> ·
  <a href="README.zh-CN.md">🇨🇳 中文文档</a>
</p>

---

## ✨ Features

| | |
|---|---|
| 🔐 **True E2EE** | X25519 + AES-GCM for 1:1, per-member encrypted envelopes for groups (TOFU trust) |
| 💬 **Full Chat Suite** | 1:1 / group chats · Text / images / files / voice · Real-time WebSocket delivery |
| 📞 **Voice & Video** | WebRTC-based calls, STUN config included |
| 👥 **Group Ecosystem** | Pinned chats · Do-Not-Disturb · Announcements · Shared albums · Files · Polls |
| 💰 **Wallet Module** | Balance, payment PIN (bcrypt), ledger-style transactions |
| 🖥 **Admin Console** | Session-epoch password reset, forced first-time password change, audit log |
| 🌐 **Self-Hosted** | LAN fake-domain HTTPS + Cloudflare Tunnel for public exposure — no port forwarding needed |
| 🧪 **Engineering-Grade** | 365 unit tests · 7-gate publish check · release-key-only signing · obfuscation-safe JSON contract tests |

### 中文特性简介
- 🔐 **真·端到端加密**：X25519 + AES-GCM 双密钥体系，群聊逐成员加密，TOFU 信任模型
- 💬 **完整聊天**：单聊/群聊，支持文字、图片、文件、语音，WebSocket 实时推送
- 📞 **音视频**：基于 WebRTC
- 👥 **群生态**：置顶、免打扰、公告、相册、文件、投票
- 💰 **钱包**：余额、支付密码、流水
- 🖥 **管理后台**：重置口令强制踢旧会话、首登强制改密、审计日志
- 🌐 **自托管**：局域网假域名 HTTPS + Cloudflare Tunnel 公网穿透
- 🧪 **工程化**：365 个单测、7 项发版门禁、防调试签名、防混淆 JSON 契约

---

## 🚀 Quick Start

### Prerequisites

| Requirement | Version | Notes |
|---|---|---|
| JDK | **17 or 21** | JDK 24 compiles the APK but has known pitfalls |
| Git | any recent | |
| Android SDK | platform 34 + build-tools 34.0.0 | only needed to build the Android client |
| PowerShell 7 | optional, Windows | required by the helper/test scripts |

### 1. Clone

```bash
git clone https://github.com/laijin1/miyu-im.git
cd miyu-im
```

### 2. Run the server (about 5 minutes)

```bash
# Windows
scripts\start-server.bat

# Linux / macOS
bash scripts/run-server.sh
```

The server builds itself and listens on **port 5000**; it prints the LAN address on startup.
On first run it also prints a randomly generated admin password and writes it to
`server/data/admin_initial_password.txt` (git-ignored) — change it after the first login.

Verify:

```bash
curl http://127.0.0.1:5000/version.json
```

Useful environment variables (all optional; secure defaults):

| Variable | Default | Purpose |
|---|---|---|
| `JWT_SECRET` | auto-generated to `server/data/jwt_secret.key` | JWT signing key — back it up |
| `ADMIN_USER_IDS` | **empty** (everyone denied) | comma-separated user IDs allowed to self-recharge |
| `familychat.data.dir` | `server/data` | runtime data directory |
| `server.port` | `5000` | HTTP/HTTPS port |

### 3. Build the Android client

```bash
cd client/android

./gradlew assembleDebug     # trial build, debug-signed
# ./gradlew assembleRelease # production: needs your own keystore, see docs/guides/RELEASE.md
```

APK output: `client/android/app/build/outputs/apk/<variant>/app-<variant>.apk`.

> ⚠️ **Never ship a debug-signed APK.** Debug certificates are public and carry `debuggable`,
> which antivirus engines flag as "bundled adware". The repo ships a 7-gate release check
> (`scripts/publish-check.bat`) — see [`docs/guides/RELEASE.md`](docs/guides/RELEASE.md).

### 4. Point the client at your server

On the login screen enter `host:port` (for example `192.168.1.10:5000`). The client decides
HTTP vs HTTPS automatically: private IPv4 ranges use plain HTTP on LAN, everything else is
forced to `https/wss` with a pinned self-signed CA.

### Self-hosting on a public server

- Full deployment guide: [`docs/guides/DEPLOY.md`](docs/guides/DEPLOY.md)
- systemd unit + Nginx + Cloudflare Tunnel: [`deploy/`](deploy/)
- Release process: [`docs/guides/RELEASE.md`](docs/guides/RELEASE.md)

---

## 🏗 Architecture

A single repository, three deployable pieces:

```text
miyu-im/
├── client/android/           Android client (7 Gradle modules, Java)
│   ├── app/                  Shell: login, home, settings, updater
│   ├── core/                 ApiClient / models / WebSocket / crypto helpers
│   ├── ui-common/            Design system (colors, themes, shared widgets)
│   ├── feature-chat/         1:1 chat, E2EE, voice/video, short video
│   ├── feature-group/        Groups, announcements, files, albums, polls
│   ├── feature-wallet/       Wallet, transfers, bills
│   └── feature-mall/         Store, cart, orders, refunds, addresses
├── server/                   Java 17 · Javalin + Jetty · SQLite (WAL) · single JAR
│   └── src/main/java/com/familychat/server/
│       ├── FamilyChatServer.java   Entry point / route registration
│       ├── handler/                 REST + WebSocket handlers
│       ├── model/                   Database, DAOs, migrations
│       ├── admin/                   Admin console (auth, audit, pages)
│       ├── ws/                      Realtime hub
│       └── tools/                   Ops CLI (schema / reconcile / reset)
├── scripts/                  Build · test · release · run helpers
├── deploy/                   systemd · Nginx · Cloudflare Tunnel
├── docs/                     guides/ (living manuals) + audits/ (historical records)
└── release/                  Release artifacts + signing config (git-ignored)
```

One-way module dependency — features never depend on each other:

```text
app ──► feature-* ──► ui-common ──► core
```

| Layer | Technology |
|---|---|
| Client | Android (Java), `minSdk 24` / `targetSdk 34`, OkHttp, Glide, Media3, WebRTC |
| Server | Java 17, Javalin 6, Jetty, SQLite (WAL), Gson, java-jwt, jBCrypt |
| Crypto | X25519 ECDH + AES-256-GCM, TOFU key pinning, password-derived key backup |
| Ops | Gradle 8.14, systemd, Nginx, Cloudflare Tunnel |

---

## 🔐 Security

**Encryption model**

- **1:1 messages** — X25519 key agreement, AES-256-GCM per message, unique IV per message.
  The server stores and relays ciphertext only; it never holds the conversation keys.
- **Group messages** — every member receives the message encrypted with their own key
  (per-member envelopes). Removing a member re-keys the group.
- **Trust** — TOFU (trust on first use) with a safety-number fingerprint users can compare.
- **Key backup** — the private key is encrypted client-side with a key derived from the user's
  password before it is uploaded; the server cannot decrypt it.

**Explicit scope boundaries (honest disclosure)**

| Data | Encrypted end-to-end? |
|---|---|
| 1:1 messages, group messages | ✅ Yes |
| Message attachments (images / files / voice) | ⚠️ Stored server-side, protected by authorization only |
| Group albums, group files, group polls | ⚠️ Plaintext on the server (authorization-protected) |
| User profile, wallet ledger, orders | ⚠️ Plaintext on the server (authorization-protected) |

**Server & admin hardening**

- No default credentials anywhere. The admin account is bootstrapped once with a random
  14-character password (bcrypt-hashed in the DB); first login forces a password change.
- Password reset bumps a session epoch, instantly invalidating all old admin sessions.
- The recharge allowlist (`ADMIN_USER_IDS`) is **empty by default** — nobody can mint balance.
- JWT secret is generated on first run (or supplied via env) and is **never** committed;
  the server refuses to start if the database exists but the key file is missing.
- Keystores, signing passwords, databases and runtime data are all git-ignored.

**Operational caveat**

On a LAN, cleartext HTTP is deliberately permitted (Android cannot whitelist CIDR ranges);
public addresses are always forced to HTTPS/WSS. Treat LAN traffic as trusted, or deploy a
proper domain + TLS before exposing the service.

**Reporting a vulnerability**

This is a personal project maintained in spare time. If you find a security issue, please open
a private report via GitHub Security Advisories rather than a public issue. It has been
internally audited but **not** by an external party — do not use it for high-risk threat models.

---

## 🧪 Engineering

| Signal | Detail |
|---|---|
| Unit tests | **365 total** — 208 client JVM tests + 157 server tests, all green |
| Release gate | `scripts/publish-check.bat` runs **7 checks**: signing key, `debuggable`, sensitive permissions, obfuscation-safe Gson names, version consistency, unit tests, script encoding guards |
| Release discipline | Only release-key-signed builds; version comes from one file (`release/version.properties`) |
| Repo hygiene | Small history, no binaries, no secrets, gradle-wrapper pinned and committed |

Known trade-offs (being upfront): R8/minification is currently **disabled** on the release
build to avoid reflection/Gson regressions, and there is **no CI pipeline** yet — both are on
the roadmap. Build from source and run the gate locally if you plan to ship.

---

## 📸 Screenshots

> Screenshots are being prepared. Once `chat.png`, `admin.png` and `download.png` are added to
> `docs/assets/screenshots/`, the gallery below will render automatically.

<!--
<p align="center">
  <img src="docs/assets/screenshots/chat.png"     width="240" alt="Chat screen"/>
  <img src="docs/assets/screenshots/admin.png"    width="240" alt="Admin console"/>
  <img src="docs/assets/screenshots/download.png" width="240" alt="Download page"/>
</p>
-->

| Screen | File | Status |
|---|---|---|
| Chat | `docs/assets/screenshots/chat.png` | 🕓 pending |
| Admin console | `docs/assets/screenshots/admin.png` | 🕓 pending |
| Download page | `docs/assets/screenshots/download.png` | 🕓 pending |

---

## 📚 Documentation

| Document | What it covers |
|---|---|
| [`docs/README.md`](docs/README.md) | Documentation index (living manuals + historical audits) |
| [`docs/guides/ARCHITECTURE.md`](docs/guides/ARCHITECTURE.md) | Deep dive: data flows, module boundaries |
| [`docs/guides/DEPLOY.md`](docs/guides/DEPLOY.md) | Deploying to a real server (systemd, Nginx, TLS) |
| [`docs/guides/RELEASE.md`](docs/guides/RELEASE.md) | Release process, updater mechanics, red lines |
| [`docs/guides/RELEASE-RULES.md`](docs/guides/RELEASE-RULES.md) | Server/client release rulebook |

---

## 🗺 Roadmap

- [ ] Re-enable R8 with a complete keep-rule set + on-device regression matrix
- [ ] CI pipeline (unit tests + publish gate on every push)
- [ ] Screenshots and a short demo video
- [ ] iOS client (the protocol is plain REST + WebSocket)
- [ ] Cleaner ops CLI and one-command Docker deployment

---

## 🤝 Contributing

Issues and pull requests are welcome. Before opening a PR:

1. Run `cd server && ./gradlew test` and `cd client/android && ./gradlew testDebugUnitTest`.
2. Run `scripts/publish-check.bat` if you touched anything release-related.
3. Keep the E2EE boundary intact: never log plaintext, never move encryption server-side.

---

## 📄 License

[MIT](LICENSE) © 2026 laijin1

## ⚠️ Disclaimer

Miyu is a self-hosted project provided **as-is**, without warranty of any kind. You are
responsible for the server you run, the data you store, and compliance with the laws of your
jurisdiction (including self-hosted instant-messaging regulations). The maintainers are not
liable for data loss, service interruption, or misuse.

<p align="center"><sub>Built with ☕ and Java · 端到端加密，自己家的服务器自己说了算</sub></p>
