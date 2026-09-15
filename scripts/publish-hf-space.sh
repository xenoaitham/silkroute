#!/usr/bin/env bash
# Deploy the SilkRoute live demo to the Hugging Face Space (Wolfmeoze/silkroute).
# Stages the exact files the Dockerfile builds from (jars, relay, playground,
# start script), then creates/updates the Space via the hub API.
#
# Usage: HF_TOKEN=hf_xxx bash scripts/publish-hf-space.sh
set -euo pipefail
cd "$(dirname "$0")/.."

: "${HF_TOKEN:?HF_TOKEN must be set}"
SPACE="${HF_SPACE:-Wolfmeoze/silkroute}"
STAGE="${TMPDIR:-/tmp}/hf-space-stage"

echo "[hf-deploy] staging $SPACE"
rm -rf "$STAGE"
mkdir -p "$STAGE/apps/legacy-erp/target" "$STAGE/apps/esb/target" "$STAGE/demo"
cp deploy/hf-space/README.md   "$STAGE/README.md"
cp deploy/hf-space/Dockerfile  "$STAGE/Dockerfile"
cp deploy/hf-space/start.sh    "$STAGE/start.sh"
cp apps/legacy-erp/target/legacy-erp-1.0.0-SNAPSHOT.jar "$STAGE/apps/legacy-erp/target/"
cp apps/esb/target/esb-1.0.0-SNAPSHOT.jar               "$STAGE/apps/esb/target/"
cp demo/relay.py               "$STAGE/demo/relay.py"
cp demo/playground.html        "$STAGE/demo/playground.html"

echo "[hf-deploy] create + upload"
python3 - "$SPACE" "$STAGE" <<'EOF'
import os, sys
from huggingface_hub import HfApi
space, stage = sys.argv[1], sys.argv[2]
api = HfApi(token=os.environ["HF_TOKEN"])
api.create_repo(repo_id=space, repo_type="space", space_sdk="docker",
                private=False, exist_ok=True)
api.upload_folder(repo_id=space, repo_type="space", folder_path=stage,
                  commit_message="SilkRoute live demo: the whole order-mediation platform in one container")
print("UPLOAD DONE")
EOF

URL="https://${SPACE##*/}-$(echo "${SPACE%%/*}" | tr '[:upper:]' '[:lower:]' | tr '_' '-').hf.space"
echo "[hf-deploy] uploaded - Space builds at https://huggingface.co/spaces/$SPACE"
echo "[hf-deploy] public URL once RUNNING: $URL"
