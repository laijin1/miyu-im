#!/bin/bash
# 停止所有知网进程
echo "========================================"
echo "  Stopping all processes..."
echo "========================================"

# Kill java server
JAVA_PID=$(lsof -ti:5000 2>/dev/null)
if [ -n "$JAVA_PID" ]; then
  kill $JAVA_PID 2>/dev/null
  echo "  [OK] Server (PID $JAVA_PID)"
fi

# Kill cloudflared
TUNNEL_PID=$(pgrep -f "cloudflared.*tunnel" 2>/dev/null)
if [ -n "$TUNNEL_PID" ]; then
  kill $TUNNEL_PID 2>/dev/null
  echo "  [OK] Tunnel (PID $TUNNEL_PID)"
fi

# Force kill if any remaining
sleep 1
lsof -ti:5000 2>/dev/null | xargs kill -9 2>/dev/null || true
pgrep -f "cloudflared.*tunnel" 2>/dev/null | xargs kill -9 2>/dev/null || true

echo ""
echo "  All stopped."
echo "========================================"
