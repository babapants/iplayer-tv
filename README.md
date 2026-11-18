# iPlayer TV (get_iplayer-based)

Two parts: `server/` (get_iplayer wrapper, run on a UK-based always-on machine) and `android/` (Android TV client).

## Server
    cd server && docker compose up -d --build
Check: `http://<server-ip>:8000/api/search?q=news`. The first search can be slow while get_iplayer builds its index.
Env vars: `IPLAYER_QUALITY` (default `hd,sd`), `IPLAYER_CACHE_HOURS`, `IPLAYER_REFRESH_HOURS`, `IPLAYER_MAX_DOWNLOADS`.
Keep it on your LAN: there is no authentication. Needs a UK IP and personal use under a TV licence.

## Android TV app
Push to GitHub: the **Build APK** workflow produces `iplayer-tv-debug` (app-debug.apk) under Actions > Artifacts.
Sideload with `adb connect <tv-ip>` then `adb install app-debug.apk` (or the Downloader app). On first launch enter `http://<server-ip>:8000`.

## How playback works
Selecting a programme asks the server to download it with get_iplayer; the app polls until it is ready, then plays it
with ExoPlayer over HTTP (seeking supported, resume position stored per programme).
Programmes are fully downloaded before playback starts. Next step for faster start is remuxing to HLS while downloading.

## Notes
- Android part is untested here (no SDK in my sandbox): expect to fix a small compile error or two on first CI run.
- Banner and icon are placeholder vectors.
