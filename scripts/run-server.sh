#!/bin/bash
# 启动 Java 服务端 (端口 5000)
set -e
cd "$(dirname "$0")/.."

# 编译
echo "[1/2] Building server JAR..."
cd server && ./gradlew shadowJar > /dev/null 2>&1 && cd ..

# 启动
echo "[2/2] Starting server..."
JAR="server/build/libs/familychat-server.jar"
if [ ! -f "$JAR" ]; then
  echo "[ERROR] JAR not found: $JAR"
  exit 1
fi

# Kill old process on port 5000
kill $(lsof -ti:5000 2>/dev/null) 2>/dev/null || true
sleep 1

java -Dfile.encoding=UTF-8 -jar "$JAR"
