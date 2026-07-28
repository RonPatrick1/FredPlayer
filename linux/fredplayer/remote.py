"""HTTP client for the FredPlayer media server — the same server the
Android app and Liam's fredplayer_* tools already talk to. Stdlib-only
(urllib), matching this project's existing no-extra-HTTP-dependency style.

Every network call here degrades to a clear exception rather than hanging
or crashing the caller — playback and background precompute must keep
working locally when the server is unreachable, so callers are expected to
catch and fall back, not assume these succeed.
"""

from __future__ import annotations

import json
import urllib.error
import urllib.parse
import urllib.request

CONNECT_TIMEOUT_S = 6
# Goes through nginx (proxy_read_timeout), so this must stay above that
# ceiling — bumped alongside it to give handle_fredplayer_ask's up-to-3
# retry attempts room to actually finish.
ASK_LIAM_TIMEOUT_S = 620
STREAM_SEGMENT = "/stream/"


def is_remote(path: str) -> bool:
    return isinstance(path, str) and (path.startswith("http://") or path.startswith("https://"))


def normalize_base_url(base_url: str) -> str:
    return (base_url or "").strip().rstrip("/")


def _encode_path(relative_path: str) -> str:
    return "/".join(urllib.parse.quote(segment, safe="") for segment in relative_path.split("/"))


def build_stream_url(base_url: str, relative_path: str) -> str:
    return f"{normalize_base_url(base_url)}{STREAM_SEGMENT}{_encode_path(relative_path)}"


def _api_url(track_url: str, api_prefix: str) -> str | None:
    """Derives an /api/profile/... or /api/visual/... URL from a track's
    /stream/... URL by swapping the path segment — mirrors how the
    Android client (NormalizingAudioPlayer.remoteApiUrl) does the same
    substitution, so both platforms hit the identical server routes."""
    index = track_url.find(STREAM_SEGMENT)
    if index < 0:
        return None
    return track_url[:index] + api_prefix + track_url[index + len(STREAM_SEGMENT):]


def _request(url: str, token: str, method: str = "GET", data: bytes | None = None,
             content_type: str | None = None) -> bytes:
    headers = {"Authorization": f"Bearer {token}"}
    if content_type:
        headers["Content-Type"] = content_type
    request = urllib.request.Request(url, data=data, headers=headers, method=method)
    with urllib.request.urlopen(request, timeout=CONNECT_TIMEOUT_S) as response:
        return response.read()


def fetch_library(base_url: str, token: str) -> list[dict]:
    body = _request(f"{normalize_base_url(base_url)}/api/library", token)
    return json.loads(body)


def fetch_playlists(base_url: str, token: str) -> list[dict]:
    body = _request(f"{normalize_base_url(base_url)}/api/playlists", token)
    return json.loads(body)


def fetch_playlist_tracks(base_url: str, token: str, name: str) -> list[str]:
    url = f"{normalize_base_url(base_url)}/api/playlists/{urllib.parse.quote(name, safe='')}"
    body = _request(url, token)
    return json.loads(body).get("tracks", [])


def server_path(base_url: str, track_url: str) -> str | None:
    prefix = f"{normalize_base_url(base_url)}{STREAM_SEGMENT}"
    if not isinstance(track_url, str) or not track_url.startswith(prefix):
        return None
    encoded_path = track_url[len(prefix):]
    return urllib.parse.unquote(encoded_path) if encoded_path else None


def share_playlist(base_url: str, token: str, name: str, tracks: list[str]) -> None:
    url = f"{normalize_base_url(base_url)}/api/playlists"
    body = json.dumps({"name": name, "tracks": tracks}).encode("utf-8")
    _request(url, token, method="POST", data=body, content_type="application/json")


def ask_liam(base_url: str, token: str, device_id: str, message: str) -> dict:
    """POSTs to the FredPlayer server's /api/ask-liam relay, which forwards
    to Liam's own local-only agent endpoint. Long timeout — a multi-tool-call
    local-model turn can genuinely take a couple of minutes."""
    url = f"{normalize_base_url(base_url)}/api/ask-liam"
    body = json.dumps({"device_id": device_id, "message": message}).encode("utf-8")
    headers = {"Authorization": f"Bearer {token}", "Content-Type": "application/json"}
    request = urllib.request.Request(url, data=body, headers=headers, method="POST")
    try:
        with urllib.request.urlopen(request, timeout=ASK_LIAM_TIMEOUT_S) as response:
            return json.loads(response.read())
    except urllib.error.HTTPError as error:
        try:
            detail = json.loads(error.read()).get("error", "")
        except Exception:
            detail = ""
        raise IOError(f"Server returned HTTP {error.code}" + (f": {detail}" if detail else "")) from error


def fetch_profile(track_url: str, token: str) -> dict | None:
    url = _api_url(track_url, "/api/profile/")
    if url is None:
        return None
    try:
        body = _request(url, token)
    except (urllib.error.URLError, urllib.error.HTTPError, OSError, TimeoutError):
        return None
    try:
        return json.loads(body)
    except ValueError:
        return None


def upload_profile(track_url: str, token: str, rms: float, peak: float) -> None:
    url = _api_url(track_url, "/api/profile/")
    if url is None:
        return
    body = json.dumps({"rms": rms, "peak": peak}).encode("utf-8")
    try:
        _request(url, token, method="PUT", data=body, content_type="application/json")
    except (urllib.error.URLError, urllib.error.HTTPError, OSError, TimeoutError):
        pass  # Best-effort — the next device just recomputes locally instead.

