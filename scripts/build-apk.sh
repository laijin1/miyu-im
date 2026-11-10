#!/bin/bash
# 编译 Android APK
set -e
cd "$(dirname "$0")/../client/android"

echo "========================================"
echo "  Building Android APK..."
echo "========================================"

export ANDROID_HOME="D:/Android"

# 正式包必须签名；缺少凭证直接拦下（调试签名包会被杀软判为风险包）
if [ ! -f "../../release/keystore.properties" ]; then
  echo "[ERROR] 缺少 release/keystore.properties，正式包必须签名。"
  echo "        请参照 release/keystore.properties.example 配置。"
  exit 1
fi

./gradlew assembleRelease

APK="app/build/outputs/apk/release/app-release.apk"
if [ -f "$APK" ]; then
  # 只发一份：release/密语.apk（服务端 FileHandler.APK_PATH 指的就是它）
  cp "$APK" "../../release/密语.apk"
  echo ""
  echo "[OK] APK: $(pwd)/../../release/密语.apk"
  ls -lh "../../release/密语.apk" | awk '{print "     Size:", $5}'
else
  echo "[ERROR] Build failed"
  exit 1
fi
