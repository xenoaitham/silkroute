#!/usr/bin/env bash
# SilkRoute live demo - VM bootstrap. Installs docker, clones the repo,
# builds the single-container demo, installs a systemd service that keeps it
# running across reboots. Intended for a fresh Ubuntu 22.04+ VM (written for
# the Oracle Always-Free ARM box, works on any amd64/arm64 Ubuntu).
# Run it ON the VM as a user with sudo:
#   bash bootstrap.sh
set -euo pipefail

if [ "$(id -u)" -ne 0 ]; then SUDO="sudo"; else SUDO=""; fi
REPO="https://github.com/xenoaitham/silkroute.git"
DIR=/opt/silkroute
PORT=7860

echo "[bootstrap] docker"
if ! command -v docker >/dev/null; then
  $SUDO apt-get update -y
  $SUDO apt-get install -y --no-install-recommends ca-certificates curl git
  $SUDO install -m 0755 -d /etc/apt/keyrings
  $SUDO curl -fsSL https://download.docker.com/linux/ubuntu/gpg -o /etc/apt/keyrings/docker.asc
  $SUDO chmod a+r /etc/apt/keyrings/docker.asc
  echo "deb [arch=$(dpkg --print-architecture) signed-by=/etc/apt/keyrings/docker.asc] https://download.docker.com/linux/ubuntu $(. /etc/os-release && echo "$VERSION_CODENAME") stable" \
    | $SUDO tee /etc/apt/sources.list.d/docker.list >/dev/null
  $SUDO apt-get update -y
  $SUDO apt-get install -y --no-install-recommends docker-ce docker-ce-cli containerd.io
fi
$SUDO systemctl enable --now docker

echo "[bootstrap] source"
if [ -d "$DIR/.git" ]; then
  git -C "$DIR" fetch --all
  git -C "$DIR" reset --hard origin/main
else
  $SUDO git clone "$REPO" "$DIR"
fi

echo "[bootstrap] image (this builds the whole demo; a few minutes)"
$SUDO docker build -f "$DIR/deploy/hf-space/Dockerfile" -t silkroute-demo:latest "$DIR"

echo "[bootstrap] systemd service"
$SUDO tee /etc/systemd/system/silkroute-demo.service >/dev/null <<EOF
[Unit]
Description=SilkRoute live demo (order mediation + chaos playground)
After=network-online.target
Wants=network-online.target

[Service]
Restart=always
RestartSec=10
ExecStart=/usr/bin/docker run --rm --name silkroute-demo -p ${PORT}:7860 silkroute-demo:latest
ExecStop=/usr/bin/docker rm -f silkroute-demo || true

[Install]
WantedBy=multi-user.target
EOF
$SUDO systemctl daemon-reload
$SUDO systemctl enable --now silkroute-demo

echo "[bootstrap] waiting for the stack inside the container (~60s)..."
for i in $(seq 1 40); do
  if curl -sf "http://127.0.0.1:${PORT}/healthz" | grep -q UP; then
    echo "[bootstrap] LIVE on port ${PORT} - open http://<THIS_VM_PUBLIC_IP>:${PORT}/ in a browser"
    exit 0
  fi
  sleep 5
done
echo "[bootstrap] container did not report healthy in time - check: journalctl -u silkroute-demo -n 50"
exit 1
