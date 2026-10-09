#!/usr/bin/env python3
"""Verify the built APK contains the exact credential-free canonical viewer runtime."""
from pathlib import Path
import sys
import zipfile

ROOT = Path(__file__).resolve().parents[1]
NAMES = {"index.html", "viewer.css", "viewer.mjs", "core.mjs", "session.mjs",
         "controls.mjs", "resolution.mjs", "diagnostics.mjs", "config.mjs"}
PREFIX = "assets/browser-carplay/"
HTTPS_CONFIG = "diplay-browser-https.properties"
DEFAULT_HOSTNAME = "tesla.mark4z.asia"


def verify(apk, source=ROOT / "site/browser-carplay", hostname=DEFAULT_HOSTNAME):
    with zipfile.ZipFile(apk) as archive:
        if any(name.rsplit("/", 1)[-1] == "libjingle_peerconnection_so.so" for name in archive.namelist()):
            raise ValueError("Obsolete browser WebRTC native library must not be bundled")
        if any(name.lower().rstrip("/") == "assets/private-https" or
               name.lower().startswith("assets/private-https/") for name in archive.namelist()):
            raise ValueError("Source-only APK must not contain bundled TLS identity assets")
        if archive.namelist().count(HTTPS_CONFIG) != 1 or archive.read(HTTPS_CONFIG) != f"hostname={hostname}\n".encode("ascii"):
            raise ValueError("Embedded HTTPS hostname configuration is missing or inconsistent")
        entries = [item for item in archive.infolist() if item.filename.startswith(PREFIX) and not item.is_dir()]
        if len(entries) != len(NAMES) or {item.filename[len(PREFIX):] for item in entries} != NAMES:
            raise ValueError("Embedded viewer has missing, duplicate or unexpected assets")
        total = 0
        for item in entries:
            if item.file_size <= 0 or item.file_size > 512 * 1024:
                raise ValueError("Embedded viewer asset exceeds size bounds")
            total += item.file_size
            if total > 2 * 1024 * 1024:
                raise ValueError("Embedded viewer exceeds total size bound")
            if archive.read(item) != (source / item.filename[len(PREFIX):]).read_bytes():
                raise ValueError("Embedded viewer differs from canonical source")


if __name__ == "__main__":
    if len(sys.argv) != 2:
        raise SystemExit("Usage: check_embedded_viewer_apk.py APK")
    verify(Path(sys.argv[1]))
    print("Embedded viewer APK assets match canonical source.")
