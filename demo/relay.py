#!/usr/bin/env python3
"""Public-demo relay for SilkRoute: one small HTTP server that serves the
playground page and forwards a WHITELIST of demo actions to the local sim.

Security model (explicit, enforced at runtime):
  - Upstream targets are PINNED CONSTANTS in this file. Before any request,
    _guard() re-validates the URL: http scheme, hostname exactly "127.0.0.1",
    and every resolved address inside 127.0.0.0/8. No user input ever reaches
    a URL - paths are a fixed whitelist and the only interpolated value is a
    clamped integer (latency ms). Redirects are disabled so a compromised
    upstream cannot bounce a request elsewhere.

  GET  /healthz                    -> ESB health (management actuator)
  POST /api/v1/orders              -> the ESB REST facade (the real saga)
  POST /demo/chaos/latency         {"ms": 0..5000}   -> ERP latency toxic
  POST /demo/chaos/disable         {}                -> ERP hard down
  POST /demo/chaos/restore         {}                -> remove toxics, enable

Everything else is 404. Bodies are capped, methods are pinned. Sim-only,
dummy credentials, fictional data.
"""
import http.server
import ipaddress
import json
import os
import socket
import urllib.error
import urllib.parse
import urllib.request

RELAY_PORT = int(os.environ.get("DEMO_RELAY_PORT", "18085"))
# bind loopback by default (local demo); the HF Space sets 0.0.0.0 so the
# platform's port mapping can reach it
RELAY_HOST = os.environ.get("DEMO_RELAY_HOST", "127.0.0.1")
ESB_API = "http://127.0.0.1:18081"
ESB_HEALTH = "http://127.0.0.1:18082/actuator/health"
TOXIPROXY = "http://127.0.0.1:18474"
MAX_BODY = 64 * 1024
UPSTREAM_TIMEOUT = 45

ROOT = os.path.dirname(os.path.abspath(__file__))


class _NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None


# no redirects: a response Location header can never move a request elsewhere
_OPENER = urllib.request.build_opener(_NoRedirect)


def _guard(url):
    """Allow only pinned http URLs whose host resolves inside 127.0.0.0/8."""
    parts = urllib.parse.urlsplit(url)
    if parts.scheme != "http" or parts.hostname != "127.0.0.1":
        raise ValueError("upstream not loopback-pinned: %r" % url)
    for fam, _, _, _, sockaddr in socket.getaddrinfo(parts.hostname, None):
        ip = ipaddress.ip_address(sockaddr[0])
        if not (ip.is_loopback and ip in ipaddress.ip_network("127.0.0.0/8")):
            raise ValueError("upstream resolved outside loopback: %s" % ip)
    return url


def _fetch(method, url, body=None, headers=None, timeout=UPSTREAM_TIMEOUT):
    _guard(url)
    req = urllib.request.Request(url, data=body, method=method)
    for k, v in (headers or {}).items():
        req.add_header(k, v)
    try:
        with _OPENER.open(req, timeout=timeout) as resp:
            return resp.status, resp.read()
    except urllib.error.HTTPError as e:
        return e.code, e.read()
    except Exception as e:  # upstream refused / timed out
        return 503, json.dumps({"code": "RELAY-UPSTREAM-UNREACHABLE",
                                "detail": str(e)}).encode()


class Handler(http.server.BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"
    server_version = "silkroute-demo-relay/1.0"

    def log_message(self, fmt, *args):
        print("[relay] %s" % (fmt % args), flush=True)

    def _cors(self):
        self.send_header("Access-Control-Allow-Origin", "*")
        self.send_header("Access-Control-Allow-Methods", "GET, POST, OPTIONS")
        self.send_header("Access-Control-Allow-Headers",
                         "Content-Type, Idempotency-Key")
        self.send_header("Access-Control-Max-Age", "600")

    def _reply(self, status, body, ctype="application/json"):
        self.send_response(status)
        self._cors()
        self.send_header("Content-Type", ctype)
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def do_OPTIONS(self):
        self.send_response(204)
        self._cors()
        self.end_headers()

    def do_GET(self):
        if self.path in ("/", "/playground.html"):
            with open(os.path.join(ROOT, "playground.html"), "rb") as f:
                self._reply(200, f.read(), "text/html; charset=utf-8")
        elif self.path == "/healthz":
            status, body = _fetch("GET", ESB_HEALTH, timeout=5)
            self._reply(status, body)
        else:
            self._reply(404, b'{"code":"NOT-FOUND"}')

    def do_POST(self):
        n = int(self.headers.get("Content-Length") or 0)
        if n > MAX_BODY:
            self._reply(413, b'{"code":"BODY-TOO-LARGE"}')
            return
        raw = self.rfile.read(n) if n else b"{}"
        try:
            payload = json.loads(raw or b"{}")
        except Exception:
            self._reply(400, b'{"code":"BAD-JSON"}')
            return

        if self.path == "/api/v1/orders":
            headers = {
                "Content-Type": "application/json",
                "Idempotency-Key": str(payload.get("idempotencyKey", ""))[:200],
            }
            status, body = _fetch("POST", ESB_API + "/api/v1/orders", raw, headers)
            self._reply(status, body)

        elif self.path == "/demo/chaos/latency":
            ms = max(0, min(5000, int(payload.get("ms", 0))))
            toxic = json.dumps({"name": "lat", "type": "latency",
                                "attributes": {"latency": ms}}).encode()
            _fetch("DELETE", TOXIPROXY + "/proxies/erp/toxics/lat", timeout=5)
            status, body = _fetch(
                "POST", TOXIPROXY + "/proxies/erp/toxics", toxic,
                {"Content-Type": "application/json"}, timeout=5)
            self._reply(200 if status in (200, 204) else status,
                        json.dumps({"chaos": "latency", "ms": ms,
                                    "toxiproxyStatus": status}).encode())

        elif self.path == "/demo/chaos/disable":
            status, body = _fetch(
                "POST", TOXIPROXY + "/proxies/erp",
                b'{"enabled":false}', {"Content-Type": "application/json"},
                timeout=5)
            self._reply(200 if status in (200, 204) else status,
                        json.dumps({"chaos": "disable",
                                    "toxiproxyStatus": status}).encode())

        elif self.path == "/demo/chaos/restore":
            for toxic in ("lat", "tmo", "rst"):
                _fetch("DELETE", TOXIPROXY + "/proxies/erp/toxics/" + toxic,
                       timeout=5)
            status, body = _fetch(
                "POST", TOXIPROXY + "/proxies/erp",
                b'{"enabled":true}', {"Content-Type": "application/json"},
                timeout=5)
            self._reply(200 if status in (200, 204) else status,
                        json.dumps({"chaos": "restore",
                                    "toxiproxyStatus": status}).encode())

        else:
            self._reply(404, b'{"code":"NOT-FOUND"}')


class Server(http.server.ThreadingHTTPServer):
    daemon_threads = True
    allow_reuse_address = True


if __name__ == "__main__":
    print("[relay] listening on %s:%d" % (RELAY_HOST, RELAY_PORT), flush=True)
    Server((RELAY_HOST, RELAY_PORT), Handler).serve_forever()
