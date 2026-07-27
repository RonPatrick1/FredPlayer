from __future__ import annotations

from dataclasses import dataclass
import hashlib
import json
import os
from pathlib import Path
import struct
import tempfile
from typing import Iterable, Optional
from urllib.parse import unquote
import uuid
import zlib

try:
    from mutagen import File as MutagenFile
except Exception:  # pragma: no cover - optional at runtime
    MutagenFile = None

from .leveling import LevelingSettings, TrackProfile, clamp
from .visualization import DEFAULT_WAVEFORM_POINTS, SpectrumProfile, VisualizationSettings, WaveformProfile
from . import remote


AUDIO_EXTENSIONS = {
    ".aac",
    ".aif",
    ".aiff",
    ".alac",
    ".flac",
    ".m4a",
    ".mp3",
    ".oga",
    ".ogg",
    ".opus",
    ".wav",
}


@dataclass(frozen=True)
class CacheStats:
    count: int
    bytes_used: int
    prune_after: int
    keep: int


def data_dir() -> Path:
    base = Path(os.environ.get("XDG_DATA_HOME", Path.home() / ".local" / "share"))
    directory = base / "fredplayer-ubuntu"
    directory.mkdir(parents=True, exist_ok=True)
    return directory


def config_dir() -> Path:
    base = Path(os.environ.get("XDG_CONFIG_HOME", Path.home() / ".config"))
    directory = base / "fredplayer-ubuntu"
    directory.mkdir(parents=True, exist_ok=True)
    return directory


def device_id() -> str:
    """Stable per-install identifier for the "Ask Liam" feature — lets
    the server-side agent keep this machine's conversation/session
    separate from every other FredPlayer device's, the same way each
    Matrix room gets its own isolated bucket. Generated once and cached
    in config_dir(); not tied to any real hardware identifier."""
    path = config_dir() / "device_id.txt"
    try:
        existing = path.read_text(encoding="utf-8").strip()
        if existing:
            return existing
    except OSError:
        pass
    new_id = uuid.uuid4().hex
    try:
        path.write_text(new_id, encoding="utf-8")
    except OSError:
        pass
    return new_id


def normalize_path(path: str | Path) -> str:
    if isinstance(path, str) and remote.is_remote(path):
        return path
    return str(Path(path).expanduser().resolve(strict=False))


def is_audio_file(path: str | Path) -> bool:
    if isinstance(path, str) and remote.is_remote(path):
        return True  # Already filtered to audio files by the server's own library scan.
    return Path(path).suffix.lower() in AUDIO_EXTENSIONS


def friendly_path(path: str | Path) -> str:
    if isinstance(path, str) and remote.is_remote(path):
        return path
    candidate = Path(path)
    try:
        home = Path.home().resolve()
        resolved = candidate.resolve(strict=False)
        return "~/" + str(resolved.relative_to(home))
    except Exception:
        return str(candidate)


@dataclass(frozen=True)
class TrackInfo:
    path: str
    title: str
    artist: str
    album: str
    album_artist: str
    track_number: str

    @property
    def display_title(self) -> str:
        return self.title or Path(self.path).stem or Path(self.path).name

    @property
    def display_artist(self) -> str:
        return self.artist or self.album_artist

    @property
    def subtitle(self) -> str:
        parts = [part for part in (self.display_artist, self.album) if part]
        return " - ".join(parts)

    @property
    def full_title(self) -> str:
        artist = self.display_artist
        if artist and self.display_title:
            return f"{artist} - {self.display_title}"
        return self.display_title


_TRACK_INFO_CACHE: dict[str, TrackInfo] = {}


def track_info(path: str | Path) -> TrackInfo:
    normalized = normalize_path(path)
    cache_key = _track_info_cache_key(normalized)
    cached = _TRACK_INFO_CACHE.get(cache_key)
    if cached is not None:
        return cached

    file_path = Path(path)
    title = ""
    artist = ""
    album = ""
    album_artist = ""
    track_number = ""
    if MutagenFile is not None and file_path.exists():
        try:
            audio = MutagenFile(str(file_path), easy=True)
            if audio is not None and audio.tags is not None:
                title = first_tag(audio.tags, "title") or ""
                artist = first_tag(audio.tags, "artist") or ""
                album = first_tag(audio.tags, "album") or ""
                album_artist = first_tag(audio.tags, "albumartist") or ""
                track_number = first_tag(audio.tags, "tracknumber") or ""
        except Exception:
            pass

    if not title:
        title = file_path.stem or file_path.name or str(file_path)
    info = TrackInfo(
        path=normalized,
        title=title,
        artist=artist,
        album=album,
        album_artist=album_artist,
        track_number=track_number,
    )
    _TRACK_INFO_CACHE[cache_key] = info
    return info


def display_name(path: str | Path) -> str:
    return track_info(path).full_title


def track_info_for_entry(entry: "PlaylistEntry") -> TrackInfo:
    """Like track_info(), but for a PlaylistEntry that may be remote —
    mutagen/file_path.exists() can't read tags over the network, so a
    remote entry's title/artist/album (already known from the server's
    /api/library response at add-time) are used directly instead."""
    if not entry.remote:
        return track_info(entry.path)
    title = entry.title
    if not title:
        tail = unquote(entry.path.rsplit("/", 1)[-1])
        title = tail.rsplit(".", 1)[0] if "." in tail else tail
    return TrackInfo(
        path=entry.path,
        title=title,
        artist=entry.artist,
        album=entry.album,
        album_artist="",
        track_number="",
    )


def _track_info_cache_key(path: str) -> str:
    try:
        stat = Path(path).stat()
        return f"{path}|size={stat.st_size}|mtime_ns={stat.st_mtime_ns}"
    except OSError:
        return path


def first_tag(tags: object, key: str) -> Optional[str]:
    try:
        values = tags.get(key)
        if values:
            value = str(values[0]).strip()
            return value or None
    except Exception:
        return None
    return None


@dataclass
class PlaylistEntry:
    path: str
    source_folder: str
    remote: bool = False
    title: str = ""
    artist: str = ""
    album: str = ""

    @classmethod
    def for_file(cls, path: str | Path, source_folder: str | Path | None = None) -> "PlaylistEntry":
        normalized = normalize_path(path)
        folder = normalize_path(source_folder) if source_folder else normalize_path(Path(normalized).parent)
        return cls(normalized, folder)

    @classmethod
    def for_remote(
        cls,
        url: str,
        title: str = "",
        artist: str = "",
        album: str = "",
        source_folder: str = "",
    ) -> "PlaylistEntry":
        """A track streamed from the FredPlayer media server — url is the
        full /stream/... URL, stored as-is (not run through normalize_path,
        which assumes a local filesystem path). source_folder is a display
        grouping label (e.g. "Artist/Album" from the server's relative
        path), not a real local directory."""
        return cls(path=url, source_folder=source_folder, remote=True, title=title, artist=artist, album=album)

    @classmethod
    def from_json(cls, value: object) -> Optional["PlaylistEntry"]:
        if isinstance(value, str):
            return cls.for_file(value)
        if not isinstance(value, dict):
            return None
        raw_path = value.get("path")
        if not raw_path:
            return None
        if value.get("remote"):
            return cls.for_remote(
                str(raw_path),
                title=str(value.get("title", "")),
                artist=str(value.get("artist", "")),
                album=str(value.get("album", "")),
                source_folder=str(value.get("source_folder", "")),
            )
        return cls.for_file(str(raw_path), value.get("source_folder"))

    def to_json(self) -> dict:
        data = {"path": self.path, "source_folder": self.source_folder}
        if self.remote:
            data["remote"] = True
            data["title"] = self.title
            data["artist"] = self.artist
            data["album"] = self.album
        return data


@dataclass
class StoredState:
    playlist: list[PlaylistEntry]
    named_playlists: dict[str, list[PlaylistEntry]]
    active_playlist: str
    output_level: float
    leveling_strength: float
    leveling_settings: LevelingSettings
    visualization_settings: VisualizationSettings
    window_state: "WindowState"
    server_base_url: str = ""
    server_token: str = ""
    shuffle_enabled: bool = True


@dataclass
class WindowState:
    x: int = 80
    y: int = 80
    width: int = 1120
    height: int = 720
    maximized: bool = False
    monitor_x: int = 0
    monitor_y: int = 0
    monitor_width: int = 0
    monitor_height: int = 0

    @classmethod
    def from_dict(cls, value: object) -> "WindowState":
        defaults = cls()
        if not isinstance(value, dict):
            return defaults
        return cls(
            x=_safe_int(value.get("x"), defaults.x),
            y=_safe_int(value.get("y"), defaults.y),
            width=max(900, _safe_int(value.get("width"), defaults.width)),
            height=max(620, _safe_int(value.get("height"), defaults.height)),
            maximized=bool(value.get("maximized", False)),
            monitor_x=_safe_int(value.get("monitor_x"), 0),
            monitor_y=_safe_int(value.get("monitor_y"), 0),
            monitor_width=max(0, _safe_int(value.get("monitor_width"), 0)),
            monitor_height=max(0, _safe_int(value.get("monitor_height"), 0)),
        )

    def to_dict(self) -> dict:
        return {
            "x": self.x,
            "y": self.y,
            "width": self.width,
            "height": self.height,
            "maximized": self.maximized,
            "monitor_x": self.monitor_x,
            "monitor_y": self.monitor_y,
            "monitor_width": self.monitor_width,
            "monitor_height": self.monitor_height,
        }


def _safe_int(value: object, default: int) -> int:
    try:
        return int(value)
    except (TypeError, ValueError):
        return default


class StateStore:
    DEFAULT_PLAYLIST_NAME = "Default"

    def __init__(self) -> None:
        self.path = config_dir() / "state.json"

    def load(self) -> StoredState:
        data = self._read_json(self.path)
        legacy_playlist = self._parse_playlist(data.get("playlist", []))
        named_playlists: dict[str, list[PlaylistEntry]] = {}
        raw_named = data.get("named_playlists", {})
        if isinstance(raw_named, dict):
            for raw_name, raw_entries in raw_named.items():
                name = str(raw_name).strip()
                if not name or name in named_playlists:
                    continue
                named_playlists[name] = self._parse_playlist(raw_entries)

        if not named_playlists:
            named_playlists[self.DEFAULT_PLAYLIST_NAME] = legacy_playlist

        requested_active = str(data.get("active_playlist", "")).strip()
        active_playlist = (
            requested_active
            if requested_active in named_playlists
            else next(iter(named_playlists))
        )
        # Version 2 mirrors the active named playlist here. If an older build
        # was used after a rollback, its edits only touch this legacy field, so
        # treat a present legacy value as the latest active-list copy.
        if isinstance(data.get("playlist"), list):
            named_playlists[active_playlist] = legacy_playlist
        playlist = list(named_playlists[active_playlist])

        return StoredState(
            playlist=playlist,
            named_playlists=named_playlists,
            active_playlist=active_playlist,
            output_level=clamp(float(data.get("output_level", 0.55)), 0.1, 1.0),
            leveling_strength=clamp(float(data.get("leveling_strength", 0.9)), 0.0, 1.0),
            leveling_settings=LevelingSettings.from_dict(data.get("leveling_settings", {})),
            visualization_settings=VisualizationSettings.from_dict(data.get("visualization_settings", {})),
            window_state=WindowState.from_dict(data.get("window_state", {})),
            server_base_url=str(data.get("server_base_url", "")),
            server_token=str(data.get("server_token", "")),
            shuffle_enabled=bool(data.get("shuffle_enabled", True)),
        )

    @staticmethod
    def _parse_playlist(value: object) -> list[PlaylistEntry]:
        if not isinstance(value, list):
            return []
        playlist: list[PlaylistEntry] = []
        seen = set()
        for item in value:
            entry = PlaylistEntry.from_json(item)
            if entry is None or entry.path in seen:
                continue
            seen.add(entry.path)
            playlist.append(entry)
        return playlist

    def save(self, state: StoredState) -> None:
        named_playlists = {
            str(name): [entry.to_json() for entry in entries]
            for name, entries in state.named_playlists.items()
            if str(name).strip()
        }
        active_playlist = (
            state.active_playlist
            if state.active_playlist in named_playlists
            else next(iter(named_playlists), self.DEFAULT_PLAYLIST_NAME)
        )
        data = {
            "version": 2,
            # Keep the active list in the original field so older versions can
            # still read it if the user rolls back.
            "playlist": [entry.to_json() for entry in state.playlist],
            "named_playlists": named_playlists,
            "active_playlist": active_playlist,
            "output_level": clamp(state.output_level, 0.1, 1.0),
            "leveling_strength": clamp(state.leveling_strength, 0.0, 1.0),
            "leveling_settings": state.leveling_settings.to_dict(),
            "visualization_settings": state.visualization_settings.to_dict(),
            "window_state": state.window_state.to_dict(),
            "server_base_url": state.server_base_url,
            "server_token": state.server_token,
            "shuffle_enabled": state.shuffle_enabled,
        }
        self._write_json(self.path, data)

    @staticmethod
    def _read_json(path: Path) -> dict:
        try:
            with path.open("r", encoding="utf-8") as handle:
                value = json.load(handle)
            return value if isinstance(value, dict) else {}
        except (OSError, json.JSONDecodeError):
            return {}

    @staticmethod
    def _write_json(path: Path, data: dict) -> None:
        path.parent.mkdir(parents=True, exist_ok=True)
        with tempfile.NamedTemporaryFile("w", encoding="utf-8", dir=path.parent, delete=False) as handle:
            json.dump(data, handle, indent=2, sort_keys=True)
            handle.write("\n")
            temp_name = handle.name
        os.replace(temp_name, path)


class ProfileCache:
    PRUNE_AFTER = 5000
    KEEP = 4000

    def __init__(self) -> None:
        self.path = data_dir() / "profiles.json"
        self.profiles: dict[str, dict] = {}
        self._load()

    def _load(self) -> None:
        data = StateStore._read_json(self.path)
        profiles = data.get("profiles", {})
        self.profiles: dict[str, dict] = profiles if isinstance(profiles, dict) else {}

    def get(self, path: str | Path) -> Optional[TrackProfile]:
        key = self.cache_key(path)
        if key is None:
            return None
        value = self.profiles.get(key)
        if not isinstance(value, dict):
            return None
        return TrackProfile.from_dict(value)

    def put(self, path: str | Path, profile: TrackProfile) -> None:
        key = self.cache_key(path)
        if key is None:
            return
        self._load()
        self.profiles[key] = profile.to_dict()
        self._prune()
        self.save()

    def cache_key(self, path: str | Path) -> Optional[str]:
        return file_cache_key(path)

    def save(self) -> None:
        StateStore._write_json(self.path, {"version": 1, "profiles": self.profiles})

    def stats(self) -> CacheStats:
        self._load()
        try:
            bytes_used = self.path.stat().st_size
        except OSError:
            bytes_used = 0
        return CacheStats(
            count=len(self.profiles),
            bytes_used=bytes_used,
            prune_after=self.PRUNE_AFTER,
            keep=self.KEEP,
        )

    def _prune(self) -> None:
        if len(self.profiles) <= self.PRUNE_AFTER:
            return
        keep = list(self.profiles.items())[-self.KEEP :]
        self.profiles = dict(keep)


class SpectrumCache:
    VERSION = 2
    HEADER_STRUCT = struct.Struct(">I")
    PRUNE_AFTER = 5000
    KEEP = 4500

    def __init__(self) -> None:
        self.directory = data_dir() / "spectra"
        self.directory.mkdir(parents=True, exist_ok=True)

    def get(self, path: str | Path, settings: VisualizationSettings) -> Optional[SpectrumProfile]:
        key = self.cache_key(path, settings)
        if key is None:
            return None
        cache_path = self.directory / f"{key}.fsp"
        try:
            raw = cache_path.read_bytes()
        except OSError:
            return None
        if len(raw) < self.HEADER_STRUCT.size:
            return None
        header_size = self.HEADER_STRUCT.unpack(raw[: self.HEADER_STRUCT.size])[0]
        header_start = self.HEADER_STRUCT.size
        header_end = header_start + header_size
        if header_end > len(raw):
            return None
        try:
            header = json.loads(raw[header_start:header_end].decode("utf-8"))
            payload = zlib.decompress(raw[header_end:])
        except (OSError, ValueError, zlib.error, json.JSONDecodeError):
            return None
        if header.get("version") != self.VERSION or header.get("cache_key") != key:
            return None
        profile = SpectrumProfile.from_dict(header.get("profile", {}), payload)
        if profile is None or not profile.matches(settings, 48_000):
            return None
        return profile

    def put(self, path: str | Path, settings: VisualizationSettings, profile: SpectrumProfile) -> None:
        key = self.cache_key(path, settings)
        if key is None:
            return
        cache_path = self.directory / f"{key}.fsp"
        header = {
            "version": self.VERSION,
            "cache_key": key,
            "profile": profile.to_dict(),
        }
        encoded_header = json.dumps(header, sort_keys=True, separators=(",", ":")).encode("utf-8")
        compressed_payload = zlib.compress(profile.payload, level=6)
        payload = self.HEADER_STRUCT.pack(len(encoded_header)) + encoded_header + compressed_payload
        with tempfile.NamedTemporaryFile("wb", dir=self.directory, delete=False) as handle:
            handle.write(payload)
            temp_name = handle.name
        os.replace(temp_name, cache_path)
        self._prune()

    def cache_key(self, path: str | Path, settings: VisualizationSettings) -> Optional[str]:
        file_key = file_cache_key(path)
        if file_key is None:
            return None
        parts = [
            "spectrum-v2-centered",
            file_key,
            f"fps={settings.update_fps:.3f}",
            f"fft_size={settings.fft_size}",
            f"bands={settings.fft_columns}",
            f"scale={settings.fft_scale}",
        ]
        return hashlib.sha256("|".join(parts).encode("utf-8")).hexdigest()

    def _prune(self) -> None:
        files = self._cache_files()
        if len(files) <= self.PRUNE_AFTER:
            return
        for path in files[: len(files) - self.KEEP]:
            try:
                path.unlink()
            except OSError:
                pass

    def stats(self) -> CacheStats:
        files = self._cache_files()
        return CacheStats(
            count=len(files),
            bytes_used=sum(_safe_file_size(path) for path in files),
            prune_after=self.PRUNE_AFTER,
            keep=self.KEEP,
        )

    def _cache_files(self) -> list[Path]:
        return sorted(
            self.directory.glob("*.fsp"),
            key=lambda item: item.stat().st_mtime if item.exists() else 0.0,
        )


class WaveformCache:
    VERSION = 2
    HEADER_STRUCT = struct.Struct(">I")
    PRUNE_AFTER = 5000
    KEEP = 4500

    def __init__(self) -> None:
        self.directory = data_dir() / "waveforms"
        self.directory.mkdir(parents=True, exist_ok=True)

    def get(self, path: str | Path, settings: VisualizationSettings) -> Optional[WaveformProfile]:
        key = self.cache_key(path, settings)
        if key is None:
            return None
        cache_path = self.directory / f"{key}.fwp"
        try:
            raw = cache_path.read_bytes()
        except OSError:
            return None
        if len(raw) < self.HEADER_STRUCT.size:
            return None
        header_size = self.HEADER_STRUCT.unpack(raw[: self.HEADER_STRUCT.size])[0]
        header_start = self.HEADER_STRUCT.size
        header_end = header_start + header_size
        if header_end > len(raw):
            return None
        try:
            header = json.loads(raw[header_start:header_end].decode("utf-8"))
            payload = zlib.decompress(raw[header_end:])
        except (OSError, ValueError, zlib.error, json.JSONDecodeError):
            return None
        if header.get("version") != self.VERSION or header.get("cache_key") != key:
            return None
        profile = WaveformProfile.from_dict(header.get("profile", {}), payload)
        if profile is None or not profile.matches(settings, 48_000, DEFAULT_WAVEFORM_POINTS):
            return None
        return profile

    def put(self, path: str | Path, settings: VisualizationSettings, profile: WaveformProfile) -> None:
        key = self.cache_key(path, settings)
        if key is None:
            return
        cache_path = self.directory / f"{key}.fwp"
        header = {
            "version": self.VERSION,
            "cache_key": key,
            "profile": profile.to_dict(),
        }
        encoded_header = json.dumps(header, sort_keys=True, separators=(",", ":")).encode("utf-8")
        compressed_payload = zlib.compress(profile.payload, level=6)
        payload = self.HEADER_STRUCT.pack(len(encoded_header)) + encoded_header + compressed_payload
        with tempfile.NamedTemporaryFile("wb", dir=self.directory, delete=False) as handle:
            handle.write(payload)
            temp_name = handle.name
        os.replace(temp_name, cache_path)
        self._prune()

    def cache_key(self, path: str | Path, settings: VisualizationSettings) -> Optional[str]:
        file_key = file_cache_key(path)
        if file_key is None:
            return None
        parts = [
            "waveform-v2-current-sample",
            file_key,
            f"fps={settings.update_fps:.3f}",
            f"window_ms={settings.waveform_window_ms:.3f}",
            f"points={DEFAULT_WAVEFORM_POINTS}",
        ]
        return hashlib.sha256("|".join(parts).encode("utf-8")).hexdigest()

    def _prune(self) -> None:
        files = self._cache_files()
        if len(files) <= self.PRUNE_AFTER:
            return
        for path in files[: len(files) - self.KEEP]:
            try:
                path.unlink()
            except OSError:
                pass

    def stats(self) -> CacheStats:
        files = self._cache_files()
        return CacheStats(
            count=len(files),
            bytes_used=sum(_safe_file_size(path) for path in files),
            prune_after=self.PRUNE_AFTER,
            keep=self.KEEP,
        )

    def _cache_files(self) -> list[Path]:
        return sorted(
            self.directory.glob("*.fwp"),
            key=lambda item: item.stat().st_mtime if item.exists() else 0.0,
        )


def file_cache_key(path: str | Path) -> Optional[str]:
    normalized = normalize_path(path)
    if isinstance(normalized, str) and remote.is_remote(normalized):
        return normalized
    try:
        stat = Path(normalized).stat()
    except OSError:
        return None
    return f"{normalized}|size={stat.st_size}|mtime_ns={stat.st_mtime_ns}"


def _safe_file_size(path: Path) -> int:
    try:
        return path.stat().st_size
    except OSError:
        return 0


def collect_audio_files(folder: str | Path) -> list[PlaylistEntry]:
    root = Path(folder).expanduser().resolve(strict=False)
    entries: list[PlaylistEntry] = []
    for current_root, directories, files in os.walk(root):
        directories.sort(key=str.casefold)
        for name in sorted(files, key=str.casefold):
            path = Path(current_root) / name
            if is_audio_file(path):
                entries.append(PlaylistEntry.for_file(path, root))
    return entries


def merge_entries(existing: Iterable[PlaylistEntry], incoming: Iterable[PlaylistEntry]) -> list[PlaylistEntry]:
    merged: list[PlaylistEntry] = []
    seen = set()
    for entry in list(existing) + list(incoming):
        if entry.path in seen:
            continue
        seen.add(entry.path)
        merged.append(entry)
    return merged
