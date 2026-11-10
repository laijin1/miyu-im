#!/bin/bash
# 编译全部：服务端 JAR + Android APK（不启动服务）
set -e
SCRIPT_DIR="$(dirname "$0")"
PROJECT_DIR="$SCRIPT_DIR/.."

echo "========================================"
echo "  知网 编译全部"
echo "========================================"
echo ""

echo "[1/2] Building server JAR..."
cd "$PROJECT_DIR/server"
./gradlew shadowJar
echo ""

echo "[2/2] Building Android APK..."
cd "$PROJECT_DIR/client/android"
export ANDROID_HOME="D:/Android"

# 正式包必须签名；调试签名包会被杀软判为风险包
if [ ! -f "$PROJECT_DIR/release/keystore.properties" ]; then
  echo "[ERROR] 缺少 release/keystore.properties，正式包必须签名。"
  exit 1
fi

./gradlew assembleRelease
# 只发一份：release/密语.apk（服务端 FileHandler.APK_PATH 指的就是它）
cp "app/build/outputs/apk/release/app-release.apk" "$PROJECT_DIR/release/密语.apk"

echo ""
echo "========================================"
echo "  Done!"
echo "  Server: $PROJECT_DIR/server/build/libs/familychat-server.jar"
echo "  APK:    $PROJECT_DIR/release/密语.apk"
echo "========================================"
