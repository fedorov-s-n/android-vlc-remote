#!/usr/bin/env python3
# -*- coding: utf-8 -*-

"""
Мини веб-сервер без зависимостей (только stdlib):
- слушает 0.0.0.0:3900
- принимает только GET:
    /?url=...&name=...
- скачивает url во временный файл
- возвращает путь к нему (text/plain)

Пример:
http://127.0.0.1:3900/?url=https://example.com/file.bin&name=myfile.bin
"""

import os
import re
import tempfile
import urllib.parse
import urllib.request
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer


HOST = "0.0.0.0"
PORT = 3900

TIMEOUT_SEC = 30
MAX_BYTES = 200 * 1024 * 1024  # 200MB
ALLOWED_SCHEMES = {"http", "https"}


def safe_name(s: str) -> str:
    s = (s or "").strip()
    s = re.sub(r"[^A-Za-z0-9._-]+", "_", s)
    s = s.strip("._-")
    return (s[:120] or "download")


def download_to_temp(url: str, name: str | None = None) -> str:
    parsed = urllib.parse.urlparse(url)
    if parsed.scheme not in ALLOWED_SCHEMES:
        raise ValueError("Only http/https allowed")

    filename = safe_name(name) if name else safe_name(os.path.basename(parsed.path) or "download")
    suffix = "_" + filename

    fd, temp_path = tempfile.mkstemp(prefix="dl_", suffix=suffix)
    os.close(fd)

    req = urllib.request.Request(
        url,
        headers={
            "User-Agent": "no-deps-downloader/1.0",
            "Accept": "*/*",
        },
        method="GET",
    )

    bytes_written = 0
    try:
        with urllib.request.urlopen(req, timeout=TIMEOUT_SEC) as resp:
            cl = resp.headers.get("Content-Length")
            if cl:
                try:
                    if int(cl) > MAX_BYTES:
                        raise RuntimeError("File too large")
                except ValueError:
                    pass

            with open(temp_path, "wb") as f:
                while True:
                    chunk = resp.read(64 * 1024)
                    if not chunk:
                        break
                    bytes_written += len(chunk)
                    if bytes_written > MAX_BYTES:
                        raise RuntimeError("File too large")
                    f.write(chunk)

        return temp_path
    except Exception:
        try:
            os.remove(temp_path)
        except Exception:
            pass
        raise


class Handler(BaseHTTPRequestHandler):
    server_version = "NoDepsDownloadServer/1.1"

    def _send(self, code: int, text: str):
        body = text.encode("utf-8")
        self.send_response(code)
        self.send_header("Content-Type", "text/plain; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def do_GET(self):
        parsed = urllib.parse.urlparse(self.path)
        if parsed.path not in ("/", "/download"):
            self._send(404, "Not found\n")
            return

        qs = urllib.parse.parse_qs(parsed.query)

        url = qs.get("url", [None])[0]
        name = qs.get("name", [None])[0]

        if not url:
            self._send(400, "Missing 'url'\n")
            return

        try:
            path = download_to_temp(url, name=name)
            self._send(200, path + "\n")
        except Exception as e:
            self._send(500, f"ERROR: {e}\n")

    def log_message(self, fmt, *args):
        print("%s - - [%s] %s" % (self.client_address[0], self.log_date_time_string(), fmt % args))


def main():
    httpd = ThreadingHTTPServer((HOST, PORT), Handler)
    print(f"Listening on http://{HOST}:{PORT}")
    print("GET /?url=...&name=...")
    httpd.serve_forever()


if __name__ == "__main__":
    main()
