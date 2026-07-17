#!/usr/bin/env python3
# -*- coding: utf-8 -*-

"""
Мини веб-сервер-хелпер без зависимостей (только stdlib), работает на хосте VLC.
Слушает 0.0.0.0:3900. Нужен приложению, чтобы класть на хост VLC файлы, которые
VLC не умеет брать по URL сам:

- GET  /?url=...&name=...      — скачивает url во временный файл (субтитры),
                                 возвращает путь к нему (text/plain).
- POST /content?name=...       — сохраняет тело запроса во временный файл
                                 (напр. субтитры), возвращает путь (text/plain).
- GET  /mux/start?v=..&a=..     — запускает фоновую склейку video+audio (ffmpeg,
                                 copy) в MKV (играбелен по мере роста, любой кодек);
                                 возвращает id загрузки.
- GET  /mux/status?id=..        — "RUNNING <bytes> <total> <ms> <path>"
                                 | "DONE <bytes> <total> <ms> <path>"
                                 | "ERROR <code>" | "UNKNOWN".
- GET  /mux/cancel?id=..        — отменяет загрузку (убивает ffmpeg, удаляет файл).
  (до 8 склеек одновременно; требует ffmpeg на хосте)

Примеры:
  GET  http://127.0.0.1:3900/?url=https://example.com/subs.vtt&name=subs.vtt
  POST http://127.0.0.1:3900/content?name=video.mpd
  GET  http://127.0.0.1:3900/mux/start?v=<enc video url>&a=<enc audio url>
"""

import hashlib
import os
import re
import subprocess
import tempfile
import threading
import time
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


# ---- Background mux jobs (download YouTube video+audio into one seekable file) ----
# The app posts the video+audio URLs; ffmpeg remuxes them (copy, no re-encode) into a
# single MP4 written to a temp file on the host. The app gets a job id, polls /mux/status
# until "done", then tells VLC to play the finished local file (full quality + seek). If
# the app switches away it calls /mux/cancel and the download is stopped. Runs each job in
# its own ffmpeg process (the server is threaded), capped at MAX_JOBS concurrent downloads.
# Requires ffmpeg on the host.

MAX_JOBS = 8

_mux_lock = threading.Lock()
_mux_jobs = {}  # id -> {"path", "proc", "total"}


def _clen(u: str) -> int:
    m = re.search(r"[?&]clen=(\d+)", u or "")
    return int(m.group(1)) if m else 0


def _job_id(v: str, a: str) -> str:
    return hashlib.sha1((v + "|" + a).encode("utf-8")).hexdigest()[:16]


def _active_count() -> int:
    return sum(1 for j in _mux_jobs.values() if j["proc"].poll() is None)


def start_mux(v: str, a: str):
    """Returns (job_id, error). error is a string if the job could not be started."""
    jid = _job_id(v, a)
    with _mux_lock:
        job = _mux_jobs.get(jid)
        if job is not None:
            # Reuse an existing (running or finished) job for the same pair.
            if job["proc"].poll() is None or os.path.exists(job["path"]):
                return jid, None
        if _active_count() >= MAX_JOBS:
            return None, "busy: %d downloads already running" % MAX_JOBS
        path = os.path.join(tempfile.gettempdir(), "mux_%s.mkv" % jid)
        try:
            if os.path.exists(path):
                os.remove(path)
        except OSError:
            pass
        # Matroska (MKV) holds any codec (AVC/VP9/AV1 + AAC/Opus) and is playable while still
        # being written, so VLC can start during the download.
        proc = subprocess.Popen(
            ["ffmpeg", "-y", "-loglevel", "error",
             "-i", v, "-i", a, "-c", "copy",
             "-f", "matroska", path],
            stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL,
        )
        _mux_jobs[jid] = {"path": path, "proc": proc,
                          "total": _clen(v) + _clen(a), "start": time.time()}
        return jid, None


def _job_size(job) -> int:
    try:
        return os.path.getsize(job["path"])
    except OSError:
        return 0


def mux_status(jid: str) -> str:
    """One line (path is the last, space-free field):
       DONE <bytes> <total> <ms> <path> | RUNNING <bytes> <total> <ms> <path>
       | ERROR <code> | UNKNOWN"""
    job = _mux_jobs.get(jid)
    if job is None:
        return "UNKNOWN"
    ms = int((time.time() - job["start"]) * 1000)
    size = _job_size(job)
    code = job["proc"].poll()
    if code is None:
        return "RUNNING %d %d %d %s" % (size, job["total"], ms, job["path"])
    if code == 0 and os.path.exists(job["path"]):
        return "DONE %d %d %d %s" % (size, size, ms, job["path"])
    return "ERROR %s" % code


def cancel_mux(jid: str) -> str:
    job = _mux_jobs.pop(jid, None)
    if job is None:
        return "UNKNOWN"
    try:
        if job["proc"].poll() is None:
            job["proc"].kill()
            job["proc"].wait(timeout=5)
    except Exception:
        pass
    try:
        if os.path.exists(job["path"]):
            os.remove(job["path"])
    except OSError:
        pass
    return "OK"


def save_to_temp(name: str | None, data: bytes) -> str:
    filename = safe_name(name) if name else "content"
    fd, temp_path = tempfile.mkstemp(prefix="dl_", suffix="_" + filename)
    with os.fdopen(fd, "wb") as f:
        f.write(data)
    return temp_path


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
        qs = urllib.parse.parse_qs(parsed.query)

        # Background mux jobs.
        if parsed.path == "/mux/start":
            v = qs.get("v", [None])[0]
            a = qs.get("a", [None])[0]
            if not v or not a:
                self._send(400, "Missing 'v'/'a'\n")
                return
            jid, err = start_mux(v, a)
            if err:
                self._send(503, "ERROR: " + err + "\n")
            else:
                self._send(200, jid + "\n")
            return
        if parsed.path == "/mux/status":
            jid = qs.get("id", [None])[0]
            if not jid:
                self._send(400, "Missing 'id'\n")
                return
            self._send(200, mux_status(jid) + "\n")
            return
        if parsed.path == "/mux/cancel":
            jid = qs.get("id", [None])[0]
            if not jid:
                self._send(400, "Missing 'id'\n")
                return
            self._send(200, cancel_mux(jid) + "\n")
            return

        if parsed.path not in ("/", "/download"):
            self._send(404, "Not found\n")
            return

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

    def do_POST(self):
        # Save the request body to a temp file (used for generated .mpd manifests).
        parsed = urllib.parse.urlparse(self.path)
        if parsed.path != "/content":
            self._send(404, "Not found\n")
            return

        name = urllib.parse.parse_qs(parsed.query).get("name", [None])[0]
        try:
            length = int(self.headers.get("Content-Length") or 0)
            if length <= 0 or length > MAX_BYTES:
                self._send(400, "Bad Content-Length\n")
                return
            data = self.rfile.read(length)
            path = save_to_temp(name, data)
            self._send(200, path + "\n")
        except Exception as e:
            self._send(500, f"ERROR: {e}\n")

    def log_message(self, fmt, *args):
        print("%s - - [%s] %s" % (self.client_address[0], self.log_date_time_string(), fmt % args))


def main():
    httpd = ThreadingHTTPServer((HOST, PORT), Handler)
    print(f"Listening on http://{HOST}:{PORT}")
    print("GET  /?url=...&name=...   (download to temp)")
    print("POST /content?name=...    (save request body to temp)")
    httpd.serve_forever()


if __name__ == "__main__":
    main()

