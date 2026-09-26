"""Local test site for dKiosk.

Serves testsite/www over plain HTTP on :8780 and self-signed HTTPS on :8781. Reach it from a USB
device without touching the firewall:

    adb reverse tcp:8780 tcp:8780
    adb reverse tcp:8781 tcp:8781

then use http://localhost:8780/ as the kiosk's home URL.

    python testsite/serve.py              # reuse the existing self-signed cert
    python testsite/serve.py --new-cert   # new cert, to test the "certificate changed" prompt
"""

import functools
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


class ExclusiveServer(http.server.ThreadingHTTPServer):
    # On Windows SO_REUSEADDR lets a second server silently share a busy port; fail loudly instead.
    allow_reuse_address = False


def serve(port: int, tls: bool) -> http.server.ThreadingHTTPServer:
    handler = functools.partial(http.server.SimpleHTTPRequestHandler, directory=str(WWW))
    server = ExclusiveServer(("127.0.0.1", port), handler)
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
