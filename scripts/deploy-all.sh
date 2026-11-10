#!/bin/bash
# 全量部署：编译服务端 → 编译 APK → 启动服务 → 启动隧道
set -e
SCRIPT_DIR="$(dirname "$0")"
PROJECT_DIR="$SCRIPT_DIR/.."

echo "========================================"
echo "  知网 全量部署脚本"
echo "========================================"
echo ""

# Step 1: Build Server
echo "[1/4] Building server JAR..."
cd "$PROJECT_DIR/server"
./gradlew shadowJar > /dev/null 2>&1
echo "      OK"
cd "$PROJECT_DIR"

# Step 2: Build APK（正式签名版；调试签名包会被杀软判为风险包）
echo "[2/4] Building Android APK..."
cd "$PROJECT_DIR/client/android"
export ANDROID_HOME="D:/Android"
if [ ! -f "$PROJECT_DIR/release/keystore.properties" ]; then
  echo "[ERROR] 缺少 release/keystore.properties，正式包必须签名。"
  exit 1
fi
./gradlew assembleRelease > /dev/null 2>&1
# 只发一份：release/密语.apk（服务端 FileHandler.APK_PATH 指的就是它）
cp "app/build/outputs/apk/release/app-release.apk" "$PROJECT_DIR/release/密语.apk"
echo "      OK: release/密语.apk"
cd "$PROJECT_DIR"

# Step 3: Start Server
echo "[3/4] Starting server on port 5000..."
kill $(lsof -ti:5000 2>/dev/null) 2>/dev/null || true
sleep 1
JAR="$PROJECT_DIR/server/build/libs/familychat-server.jar"
java -Dfile.encoding=UTF-8 -jar "$JAR" &
SERVER_PID=$!
sleep 2
echo "      PID: $SERVER_PID"

# Step 4: Start Tunnel
echo "[4/4] Starting Cloudflare Tunnel..."
nohup cloudflared tunnel --url http://localhost:5000 > /tmp/cloudflared.log 2>&1 &
TUNNEL_PID=$!
sleep 8

# Get tunnel URL
TUNNEL_URL=$(grep -oP 'https://[a-z-]+\.trycloudflare\.com' /tmp/cloudflared.log | head -1)

echo ""
echo "========================================"
echo "  部署完成！"
echo "========================================"
echo ""
echo "  局域网:    http://<局域网IP>:5000"
echo "  公网:      $TUNNEL_URL"
echo "  APK下载:   $TUNNEL_URL/download"
echo ""
echo "  服务 PID:  $SERVER_PID"
echo "  隧道 PID:  $TUNNEL_PID"
echo ""
echo "  停止:      kill $SERVER_PID $TUNNEL_PID"
echo "========================================"
