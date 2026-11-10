# 密语 服务端部署指南

> 本文所有命令均从**仓库根目录**执行。

把服务端从「自己电脑上跑」搬到「一台常开的服务器上」。

---

## 0. 先决条件：必须有一个域名

**这不是建议，是硬性要求。** 你客户端里 `ServerProtocol.isSecure()` 的规则是：

> 除了 localhost 和私有 IPv4（10.x / 172.16-31.x / 192.168.x），**其余一切地址一律走 https/wss**。

所以如果服务器只有 IP，让家人填 `1.2.3.4:5000`，客户端会去连 **`https://1.2.3.4:5000`** —— 而**没有哪家 CA 会给裸 IP 签证书**，结果是直接连不上。

**结论：公网部署 = 域名 + 证书，绕不过去。**

---

## 1. 目录布局：照抄本地结构，不要自创

服务端有三个路径是**从数据目录往上两级**推算出来的，而且**没有开关可以改**（`FileHandler.java` 第 82-86 行）：

| 用途 | 计算方式 | 能否覆盖 |
|------|---------|---------|
| 聊天文件 | `DATA_DIR/chat_files` | 随 DATA_DIR |
| 短视频源目录 | `DATA_DIR/../../视频` | **不能** |
| 版本文件 | `DATA_DIR/../../release/version.properties` | **不能** |
| APK 路径 | `DATA_DIR/../../release/密语.apk` | 可以（`-Dapk.path`） |

所以服务器上必须是这个结构：

```
/opt/miyu/
├── server-java/
│   ├── build/libs/familychat-server.jar
│   └── data/                          ← 数据库 / JWT密钥 / 聊天文件
│       ├── family_chat.db
│       ├── jwt_secret.key             ← 丢了 = 所有人被登出
│       ├── chat_files/
│       ├── uploads/
│       ├── avatars/
│       └── video_cache/               ← 转码缓存，迁移时不用拷
├── release/
│   ├── version.properties
│   └── 密语.apk
└── 视频/                               ← 建个空目录即可（没有也能跑）
```

只要保持这个布局，除了端口和堆大小，**不需要任何 `-D` 参数**。

> 说明：仓库内该目录已统一改名为 `server/`；服务器上沿用 `/opt/miyu/server-java/`（`deploy/miyu-server.service` 按此路径编写）。部署时把仓库 `server/` 的内容放进 `/opt/miyu/server-java/` 即可，两侧路径独立。

```bash
sudo mkdir -p /opt/miyu/{server-java/build/libs,server-java/data,release,视频}
sudo useradd -r -s /usr/sbin/nologin miyu
sudo chown -R miyu:miyu /opt/miyu
```

---

## 2. 装依赖

```bash
# JDK 17（服务端目标 Java 17，17 以上也行）
sudo apt update && sudo apt install -y temurin-17-jdk
# 或: sudo apt install -y openjdk-17-jdk

# ffmpeg —— 视频转码要用，缺了短视频功能会报错
sudo apt install -y ffmpeg

java -version && ffmpeg -version | head -1
```

---

## 3. 迁移数据：哪些要拷，哪些绝对不要拷

从你本机 `server/data/` 拷过去。**当前实测占用 1058 MB，其中 912 MB 是可以不拷的。**

| 内容 | 大小 | 拷？ | 说明 |
|------|------|------|------|
| `family_chat.db`（含 `-wal` / `-shm`） | 0.34 MB | ✅ **必拷** | 全部账号、消息、钱包、订单 |
| **`jwt_secret.key`** | 44 B | ✅ **必拷** | **不拷的话所有人都被强制重新登录** |
| `chat_files/` | 143 MB | ✅ 必拷 | 聊天里发过的图片/文件 |
| `avatars/` | 2 MB | ✅ 必拷 | 头像 |
| `uploads/` | 0.35 MB | ✅ 必拷 | 上传的头像原图 |
| `video_cache/` | **912 MB** | ❌ **不要拷** | 转码缓存，需要时自动重新生成（代价是首次播放会现转，吃 CPU） |
| `backup-before-delete-*.db` | 0.34 MB | ❌ 不用 | 历史备份 |

```bash
# 本机执行（Windows 上用 scp / WinSCP / rsync）
cd server\data
scp family_chat.db* jwt_secret.key user@服务器:/opt/miyu/server-java/data/
scp -r chat_files avatars uploads user@服务器:/opt/miyu/server-java/data/
```

> **数据库有 WAL 文件时不要只拷 `.db`**：先停掉本机服务端，或把 `.db` / `.db-wal` / `.db-shm` 一起拷。

---

## 4. ⚠️ 拷文件的顺序坑：`version.properties` 必须在 APK 之前

`FileHandler.getVersion()` 里有「发布窗口保护」（第 638 行）：

```java
boolean published = apk.lastModified() >= vf.lastModified();
```

**如果 APK 比 `version.properties` 旧，服务端会告诉客户端「新版本正在发布中」，应用内更新永远不会提示。**

拷贝顺序错了、或者用 `cp -p` 保留了原始时间戳（APK 构建早于本次改动），就会中招。

**正确做法**：先拷版本文件，再拷 APK，最后 touch 一下 APK。

```bash
scp release/version.properties user@服务器:/opt/miyu/release/
scp release/密语.apk          user@服务器:/opt/miyu/release/
ssh user@服务器 'touch /opt/miyu/release/密语.apk'
```

**验证**（必须看到 `"published":true`）：

```bash
curl -s localhost:5000/version.json
```

---

## 5. systemd 常驻服务

拷贝 `deploy/miyu-server.service` 到服务器：

```bash
sudo cp deploy/miyu-server.service /etc/systemd/system/
sudo mkdir -p /var/log/miyu && sudo chown miyu:miyu /var/log/miyu
sudo systemctl daemon-reload
sudo systemctl enable --now miyu-server
sudo systemctl status miyu-server
```

常用命令：

```bash
sudo systemctl restart miyu-server     # 重启
sudo journalctl -u miyu-server -f      # 看实时日志
tail -f /var/log/miyu/server.log       # 看访问日志
```

> **Linux 上没有 Windows 那个「JAR 被锁住无法重新构建」的问题**——Linux 允许替换正在使用的文件。所以服务器上更新版本只要：覆盖 jar → `systemctl restart`。

---

## 6. HTTPS：两条路

### 路线 A：Cloudflare 命名隧道（**推荐**）

优点：不用开任何入站端口（不怕扫端口）、证书自动、国内服务器**不需要备案**。

```bash
# 服务器上装 cloudflared
curl -L https://github.com/cloudflare/cloudflared/releases/latest/download/cloudflared-linux-amd64 \
  -o /usr/local/bin/cloudflared && chmod +x /usr/local/bin/cloudflared

cloudflared tunnel login                          # 浏览器授权，生成 cert.pem
cloudflared tunnel create miyu                    # 生成凭据 json
cloudflared tunnel route dns miyu chat.你的域名    # 绑定域名
```

`/etc/cloudflared/config.yml`：

```yaml
tunnel: miyu
credentials-file: /root/.cloudflared/<隧道ID>.json
ingress:
  - hostname: chat.你的域名
    service: http://localhost:5000
  - service: http_status:404
```

```bash
sudo cloudflared service install
sudo systemctl enable --now cloudflared
```

> 你本机 `deploy/cloudflare/familychat.yml` 就是这套模板，当时域名还是占位符——现在把它填实。

### 路线 B：Nginx + Let's Encrypt

适合「服务器有公网 IP 且愿意开 443」。配置见 `deploy/nginx-miyu.conf`。

```bash
sudo apt install -y nginx certbot python3-certbot-nginx
sudo cp deploy/nginx-miyu.conf /etc/nginx/sites-available/miyu
sudo ln -s /etc/nginx/sites-available/miyu /etc/nginx/sites-enabled/
sudo certbot --nginx -d chat.你的域名
sudo ufw allow 443/tcp
```

⚠️ **如果服务器在国内**，域名指向国内服务器**必须先 ICP 备案**（1-2 周，要身份证）。不想备案就走路线 A，或买香港/日本服务器。

---

## 7. 验证清单

```bash
curl -s localhost:5000/version.json          # 看 versionCode 和 published:true
curl -sI localhost:5000/apk | grep -i length # 应与 release/密语.apk 字节数一致
curl -so /dev/null -w '%{http_code}\n' localhost:5000/download   # 200
```

然后**用手机流量（不要连 WiFi）**打开 `https://chat.你的域名/download`：

- [ ] 下载页能打开
- [ ] APK 能下载、能安装
- [ ] App 里服务器地址填 `chat.你的域名`，能注册/登录
- [ ] 发一条消息、发一张图
- [ ] **旧账号还能登录**（验证 jwt_secret.key 迁移成功）

---

## 8. 备份（别跳过）

必须备份的三样：

| 内容 | 丢了会怎样 |
|------|-----------|
| `server/data/family_chat.db` | 所有聊天记录、钱包、订单全没 |
| `server/data/jwt_secret.key` | 所有人被强制登出（数据还在） |
| `release/release.jks` + `keystore.properties` | **永远无法再发布新版本**（Android 拒绝签名不一致的覆盖安装） |

数据库备份要用 SQLite 在线备份（直接 cp 在 WAL 模式下可能拿到不一致快照）：

```bash
# /etc/cron.daily/miyu-backup
#!/bin/bash
D=/opt/miyu/server-java/data
B=/var/backups/miyu
mkdir -p $B
sqlite3 $D/family_chat.db ".backup '$B/family_chat-$(date +%F).db'"
cp $D/jwt_secret.key $B/
find $B -name 'family_chat-*.db' -mtime +30 -delete
```

---

## 9. 本项目的已知坑（部署前先知道）

| 坑 | 说明 |
|----|------|
| **磁盘会被聊天的图片/视频撑满** | `chat_files/` **没有任何清理逻辑**（只进不出）。零用户的你现在已经 143 MB。上线后要么写清理策略，要么上对象存储 |
| 视频转码缓存上限 | `MAX_CACHE_FILES = 200`，而单个缓存文件实测可达 **450 MB** → 最坏约 90 GB |
| 请求体上限 100 MB | 聊天文件另有 60 MB 上限（`MAX_CHAT_FILE_SIZE`） |
| 内存 | **没有连接池**，每次请求新建 SQLite 连接并执行 7 条 PRAGMA。每条连接带 4 MB 缓存，并发高时内存会涨。`-Xmx` 建议 1–2 GB |
| 容量天花板 | 实测约 **215–246 rps**（约 1000 人同时开着聊天页）。**加 CPU 不会提升**，瓶颈是数据库访问方式 |
| 限流是单机内存态 | 重启即清零，多实例不共享 |
| `视频/` 目录 | 建议建空目录。缺失时短视频源列表为空，但不影响聊天 |
| 备份里的中文文件名 | `release/密语.apk` 在 Linux 上一般没问题；若遇到编码怪问题，用 `-Dapk.path=/opt/miyu/release/miyu.apk` 指到纯 ASCII 文件名 |

---

## 10. 日常更新流程

```bash
# 本机：构建
cd server && ./gradlew shadowJar

# 上传 jar 并重启（Linux 可以覆盖正在运行的 jar）
scp server/build/libs/familychat-server.jar user@服务器:/opt/miyu/server-java/build/libs/
ssh user@服务器 'sudo systemctl restart miyu-server'

# 发新 APK 时（注意第 4 节的顺序坑）
scp release/version.properties release/密语.apk user@服务器:/opt/miyu/release/
ssh user@服务器 'touch /opt/miyu/release/密语.apk'
```

---

## 相关文件

- `deploy/miyu-server.service` —— systemd 服务单元
- `deploy/nginx-miyu.conf` —— Nginx + TLS 反向代理（路线 B 用）
