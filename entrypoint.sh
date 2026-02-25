#!/bin/bash
set -e

DISPLAY_NUM="${DISPLAY_NUM:-99}"
RESOLUTION="${RESOLUTION:-1280x1024x24}"
PORT="${PORT:-8075}"
VNC_PORT="${VNC_PORT:-6080}"

export DISPLAY=":${DISPLAY_NUM}"

# Start Xvfb
Xvfb "$DISPLAY" -screen 0 "$RESOLUTION" -ac -nolisten tcp &
XVFB_PID=$!
sleep 1

# Start window manager (needed for windowactivate/focus)
openbox &
sleep 0.5

# Start Chromium maximized in background
chromium --no-sandbox --disable-dev-shm-usage --disable-gpu \
    --start-maximized --no-first-run --disable-default-apps \
    "about:blank" &
sleep 2

# Start VNC server (no password, view-only optional)
x11vnc -display "$DISPLAY" -nopw -forever -shared -rfbport 5900 &
sleep 0.5

# Start noVNC web client (browser-based VNC on port 6080)
websockify --web /usr/share/novnc "$VNC_PORT" localhost:5900 &
sleep 0.5

echo "=== noVNC available at http://localhost:${VNC_PORT}/vnc.html ==="

# Run MCP server
exec python -m screen_mcp.server --port "$PORT"
