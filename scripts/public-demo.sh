#!/usr/bin/env bash
# SilkRoute public live demo - boot or republish the testable instance.
#
# What visitors get: https://xenoaitham.github.io/silkroute/playground.html
# talks to a REAL running instance of the platform through a Cloudflare quick
# tunnel (no account needed). The tunnel URL is ephemeral by design - it
# changes whenever the tunnel process or this machine restarts - so re-running
# this script re-anchors the published page to the fresh URL.
#
# Usage:
#   bash scripts/public-demo.sh            # start everything, print the URL
#   bash scripts/public-demo.sh --publish  # also push the new URL to the site
#
# What it starts (all PID-file managed, never pkill):
#   sim stack (make up) -> ESB + ERP (make esb-run) -> demo relay (demo/relay.py
#   on 127.0.0.1:18085) -> cloudflared quick tunnel -> the public URL.
#
# Notes: the binary path defaults to /tmp/s9-shots/cloudflared (override with
# CLOUDFLARED_BIN). --publish writes endpoint.txt + the demo pages to a scratch
# clone and force-pushes ONLY the gh-pages branch (the site branch keeps no
# history that matters; main is never touched).
set -euo pipefail
cd "$(dirname "$0")/.."

CLOUDFLARED_BIN="${CLOUDFLARED_BIN:-/tmp/s9-shots/cloudflared}"
RELAY_PID=/tmp/silkroute-relay.pid
TUNNEL_PID=/tmp/silkroute-tunnel.pid
TUNNEL_LOG=/tmp/silkroute-tunnel.log
SCRATCH="${TMPDIR:-/tmp}/srpages-publish"

step() { printf '[public-demo] %s\n' "$*"; }

# 1. infrastructure + apps
step "sim stack: make up + smoke"
make -s up >/dev/null 2>&1 || true
make smoke || { echo "ERROR: sim stack not healthy"; exit 1; }
if [ -f /tmp/silkroute-esb-app.pid ] && kill -0 "$(cat /tmp/silkroute-esb-app.pid)" 2>/dev/null; then
  step "ESB already running"
else
  step "booting ESB + ERP (make esb-run)"
  make esb-run
fi

# 2. relay
if [ -f "$RELAY_PID" ] && kill -0 "$(cat "$RELAY_PID")" 2>/dev/null; then
  step "relay already running (pid $(cat "$RELAY_PID"))"
else
  step "starting demo relay on 127.0.0.1:18085"
  nohup python3 demo/relay.py > /tmp/silkroute-relay.log 2>&1 &
  echo $! > "$RELAY_PID"
  sleep 2
  curl -sf http://127.0.0.1:18085/healthz >/dev/null || { echo "ERROR: relay failed - see /tmp/silkroute-relay.log"; exit 1; }
fi

# 3. tunnel
if [ -f "$TUNNEL_PID" ] && kill -0 "$(cat "$TUNNEL_PID")" 2>/dev/null; then
  step "tunnel already running (pid $(cat "$TUNNEL_PID"))"
else
  step "starting cloudflared quick tunnel"
  nohup "$CLOUDFLARED_BIN" tunnel --url http://127.0.0.1:18085 --no-autoupdate > "$TUNNEL_LOG" 2>&1 &
  echo $! > "$TUNNEL_PID"
  sleep 12
fi
URL=$(grep -oE "https://[a-z0-9-]+\.trycloudflare\.com" "$TUNNEL_LOG" | head -1 || true)
[ -n "$URL" ] || { echo "ERROR: no tunnel URL in $TUNNEL_LOG"; exit 1; }
step "public URL: $URL"
curl -sf "$URL/healthz" >/dev/null || { echo "ERROR: public URL not answering"; exit 1; }

# 4. publish (optional)
if [ "${1:-}" = "--publish" ]; then
  step "publishing endpoint + playground to gh-pages"
  rm -rf "$SCRATCH" && mkdir -p "$SCRATCH"
  cp demo/index.html demo/playground.html "$SCRATCH"/
  printf '%s\n' "$URL" > "$SCRATCH/endpoint.txt"
  cp -r demo/screenshots "$SCRATCH"/
  git -C "$SCRATCH" init -q -b gh-pages
  git -C "$SCRATCH" add -A
  git -C "$SCRATCH" -c user.name="${GIT_AUTHOR_NAME:-xenoaitham}" \
      -c user.email="${GIT_AUTHOR_EMAIL:-xenoaitham@users.noreply.github.com}" \
      commit -q -m "refresh the live demo endpoint"
  git -C "$SCRATCH" remote add origin "https://github.com/${GITHUB_REPO:-xenoaitham/silkroute}.git"
  git -C "$SCRATCH" push -q --force origin gh-pages
  step "published - the site picks up the new endpoint within ~1 minute"
fi

step "OK - visitors test at https://xenoaitham.github.io/silkroute/playground.html (API: $URL)"
