# Run the 24/7 live demo on an Oracle Cloud Always-Free VM

Oracle's Always-Free tier gives an ARM VM (A1.Flex, up to 4 OCPU / 24 GB -
even the smallest shape dwarfs this demo) that is free forever: the card at
signup is for identity only, the Always-Free resources are never billed.

The demo runs as ONE docker container (see `deploy/hf-space/Dockerfile` -
multi-arch, works on ARM as-is) fronted by the playground relay on port 7860.

## One-time: create the VM (~20 min, all in the Oracle console)

1. Sign up at <https://cloud.oracle.com> (card required for verification).
2. Compute -> Instances -> Create instance:
   - Shape: **Ampere A1** (Always Free Eligible badge), 2 OCPU / 12 GB is plenty
   - Image: **Ubuntu 22.04+** (canonical)
   - SSH keys: add YOUR public key (or let Oracle generate and download it)
3. Networking details of the instance -> subnet -> Security List: add an
   **Ingress Rule**: source `0.0.0.0/0`, TCP, destination port `7860`.

## One command on the VM

SSH in (`ssh ubuntu@<VM_PUBLIC_IP>`) and run:

```bash
curl -fsSL https://raw.githubusercontent.com/xenoaitham/silkroute/main/deploy/oracle/bootstrap.sh | bash
```

The script installs docker, clones the repo, builds the container, and
installs a systemd service (`silkroute-demo`) that keeps it running across
reboots. When it finishes, the live demo answers on
`http://<VM_PUBLIC_IP>:7860/` - playground included.

## Point the playground at it

```bash
HF_SPACE_UNUSED=1 LIVE_URL="http://<VM_PUBLIC_IP>:7860" bash scripts/public-demo.sh --publish
```

(or edit `endpoint.txt` on the gh-pages branch with the URL). The page at
<https://xenoaitham.github.io/silkroute/playground.html> then talks to the
VM from anywhere, 24/7.

## Notes

- `sudo systemctl status silkroute-demo` / `journalctl -u silkroute-demo` to inspect.
- Updating the demo = `cd /opt/silkroute && git pull` then re-run the script
  (it rebuilds and restarts the service).
- HTTPS: put Cloudflare in front (orange cloud) or Caddy if you want TLS; the
  playground works fine over plain HTTP to the VM.
