"""Local test site for dKiosk.

Serves testsite/www over plain HTTP on :8780 and self-signed HTTPS on :8781. Reach it from a USB
device without touching the firewall:

    adb reverse tcp:8780 tcp:8780
    adb reverse tcp:8781 tcp:8781

then use http://localhost:8780/ as the kiosk's home URL.

    python testsite/serve.py              # reuse the existing self-signed cert
    python testsite/serve.py --new-cert   # new cert, to test the "certificate changed" prompt

/private/ needs HTTP basic auth (user "kiosk", password "letmein") and /503 always fails with a 503.
"""

import base64
import http.server
import pathlib
import ssl
import subprocess
import sys
import threading

HERE = pathlib.Path(__file__).resolve().parent
WWW = HERE / "www"
HTTP_PORT = 8780
HTTPS_PORT = 8781
# Test-only credentials for the basic auth area.
USERNAME = "kiosk"
PASSWORD = "letmein"
CERT = HERE / "selfsigned.pem"
KEY = HERE / "selfsigned.key"


def ensure_cert(regenerate: bool) -> None:
    if CERT.exists() and not regenerate:
        return
    subprocess.run(
        [
            "openssl", "req", "-x509", "-newkey", "rsa:2048", "-nodes", "-days", "365",
            "-subj", "/CN=localhost", "-addext", "subjectAltName=DNS:localhost",
            "-keyout", str(KEY), "-out", str(CERT),
        ],
        check=True,
        capture_output=True,
    )
    fingerprint = subprocess.run(
        ["openssl", "x509", "-noout", "-fingerprint", "-sha256", "-in", str(CERT)],
        check=True, capture_output=True, text=True,
    ).stdout.strip()
    print(f"new self-signed cert: {fingerprint}")


class Handler(http.server.SimpleHTTPRequestHandler):
    def __init__(self, *args, **kwargs):
        super().__init__(*args, directory=str(WWW), **kwargs)

    def do_GET(self):
        if self.path == "/503":
            self.send_error(503, "Service Unavailable")
        elif self.path.startswith("/private/") and not self.authorized():
            self.send_response(401)
            self.send_header("WWW-Authenticate", 'Basic realm="dKiosk test"')
            self.send_header("Content-Length", "0")
            self.end_headers()
        else:
            super().do_GET()

    def authorized(self):
        expected = "Basic " + base64.b64encode(f"{USERNAME}:{PASSWORD}".encode()).decode()
        return self.headers.get("Authorization") == expected


class ExclusiveServer(http.server.ThreadingHTTPServer):
    # On Windows SO_REUSEADDR lets a second server silently share a busy port; fail loudly instead.
    allow_reuse_address = False


def serve(port: int, tls: bool) -> http.server.ThreadingHTTPServer:
    server = ExclusiveServer(("127.0.0.1", port), Handler)
    if tls:
        context = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
        context.load_cert_chain(CERT, KEY)
        server.socket = context.wrap_socket(server.socket, server_side=True)
    threading.Thread(target=server.serve_forever, daemon=True).start()
    print(f"serving {WWW} on {'https' if tls else 'http'}://localhost:{port}/")
    return server


if __name__ == "__main__":
    ensure_cert(regenerate="--new-cert" in sys.argv)
    serve(HTTP_PORT, tls=False)
    serve(HTTPS_PORT, tls=True)
    threading.Event().wait()
