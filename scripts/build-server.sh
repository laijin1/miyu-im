#!/bin/bash
# 编译 Java 服务端
set -e
cd "$(dirname "$0")/../server"
echo "========================================"
echo "  Building Server JAR..."
echo "========================================"
./gradlew shadowJar
echo ""
echo "[OK] Build done: $(pwd)/build/libs/familychat-server.jar"
