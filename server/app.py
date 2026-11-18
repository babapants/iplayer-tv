"""Thin HTTP wrapper around get_iplayer for the Android TV client."""
import os
import re
import shutil
import subprocess
import threading
import time
from pathlib import Path

from fastapi import FastAPI, HTTPException, Query
from fastapi.responses import FileResponse

GET_IPLAYER = os.environ.get("GET_IPLAYER_BIN", "get_iplayer")
CACHE = Path(os.environ.get("IPLAYER_CACHE", "/data/cache"))
QUALITY = os.environ.get("IPLAYER_QUALITY", "hd,sd")
CACHE_HOURS = float(os.environ.get("IPLAYER_CACHE_HOURS", "12"))
REFRESH_HOURS = float(os.environ.get("IPLAYER_REFRESH_HOURS", "6"))
MAX_DOWNLOADS = int(os.environ.get("IPLAYER_MAX_DOWNLOADS", "2"))

PID_RE = re.compile(r"^[a-z][0-9a-z]{7}$")
SEP = "@@@"
FIELDS = ["pid", "name", "episode", "channel", "thumbnail", "desc"]
LISTFORMAT = SEP.join(f"<{f}>" for f in FIELDS)

app = FastAPI(title="iplayer-tv-server")
CACHE.mkdir(parents=True, exist_ok=True)

jobs: dict = {}
lock = threading.Lock()
slots = threading.Semaphore(MAX_DOWNLOADS)


def _set(pid, **kw):
    with lock:
        jobs.setdefault(pid, {}).update(kw)


def _check_pid(pid: str):
    if not PID_RE.match(pid):
        raise HTTPException(400, "Invalid pid")


def find_file(pid: str):
    d = CACHE / pid
    if not d.is_dir():
        return None
    files = sorted(d.glob("*.mp4"))
    return files[0] if files else None


def _refresh_loop():
    while True:
        try:
            subprocess.run([GET_IPLAYER, "--type=tv", "--refresh", "--nocopyright"],
                           capture_output=True, timeout=1800)
        except Exception:
            pass
        time.sleep(REFRESH_HOURS * 3600)


@app.on_event("startup")
def _startup():
    threading.Thread(target=_refresh_loop, daemon=True).start()


def _cleanup():
    cutoff = time.time() - CACHE_HOURS * 3600
    with lock:
        busy = {p for p, j in jobs.items() if j.get("state") in ("queued", "downloading")}
    for d in CACHE.iterdir():
        if d.is_dir() and d.name not in busy and d.stat().st_mtime < cutoff:
            shutil.rmtree(d, ignore_errors=True)


def _download(pid: str):
    with slots:
        _set(pid, state="downloading", error=None)
        d = CACHE / pid
        d.mkdir(parents=True, exist_ok=True)
        cmd = [GET_IPLAYER, "--type=tv", "--nocopyright", "--no-tag", "--force", "--overwrite",
               f"--pid={pid}", f"--output={d}", f"--file-prefix={pid}", f"--tv-quality={QUALITY}"]
        try:
            r = subprocess.run(cmd, capture_output=True, text=True, timeout=3600)
        except Exception as e:
            _set(pid, state="error", error=str(e))
            return
        if find_file(pid):
            _set(pid, state="ready", error=None)
        else:
            tail = (r.stdout + r.stderr).strip().splitlines()[-3:]
            _set(pid, state="error", error=" | ".join(tail) or "get_iplayer produced no file")


@app.get("/api/health")
def health():
    return {"ok": True}


@app.get("/api/search")
def search(q: str = "", channel: str = "", limit: int = Query(60, ge=1, le=200)):
    for v in (q, channel):
        if v.startswith("-"):
            raise HTTPException(400, "Invalid search term")
    cmd = [GET_IPLAYER, "--type=tv", "--nocopyright", "--listformat", LISTFORMAT,
           "--sort=available", "--sortreverse", f"--limit-matches={limit}"]
    if channel:
        cmd.append(f"--channel={channel}")
    if q:
        cmd += ["--fields=name,episode,desc", q]
    try:
        out = subprocess.run(cmd, capture_output=True, text=True, timeout=180).stdout
    except subprocess.TimeoutExpired:
        raise HTTPException(504, "get_iplayer timed out (first index refresh can be slow)")
    results = []
    for line in out.splitlines():
        parts = line.split(SEP)
        if len(parts) == len(FIELDS) and PID_RE.match(parts[0]):
            results.append(dict(zip(["pid", "title", "episode", "channel", "thumb", "desc"], parts)))
    return {"results": results[:limit]}


@app.post("/api/prepare/{pid}")
def prepare(pid: str):
    _check_pid(pid)
    if find_file(pid):
        _set(pid, state="ready", error=None)
    else:
        with lock:
            state = jobs.get(pid, {}).get("state")
        if state not in ("queued", "downloading"):
            _cleanup()
            _set(pid, state="queued", error=None)
            threading.Thread(target=_download, args=(pid,), daemon=True).start()
    return status(pid)


@app.get("/api/status/{pid}")
def status(pid: str):
    _check_pid(pid)
    with lock:
        j = dict(jobs.get(pid, {}))
    if not j and find_file(pid):
        j = {"state": "ready"}
    return {"state": j.get("state", "idle"), "error": j.get("error")}


@app.get("/media/{pid}")
def media(pid: str):
    _check_pid(pid)
    f = find_file(pid)
    if not f:
        raise HTTPException(404, "Not downloaded yet")
    return FileResponse(f, media_type="video/mp4")  # supports HTTP Range for seeking
