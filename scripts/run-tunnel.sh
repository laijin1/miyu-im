#!/bin/bash
# 启动 Cloudflare Quick Tunnel（临时公网域名）
set -e

echo "========================================"
echo "  Starting Cloudflare Quick Tunnel..."
echo "========================================"
echo ""

cloudflared tunnel --url http://localhost:5000
