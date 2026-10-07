#!/usr/bin/env python3
"""Check the final source-only APK's ZIP directory without extracting assets."""
import argparse
from pathlib import Path, PurePosixPath
import sys
import zipfile


CREDENTIAL_SUFFIXES = {
    ".pk8", ".p7b", ".key", ".pem", ".p12", ".pfx", ".jks", ".keystore",
}


def verify_source_apk(apk):
    """Fail closed for absent/malformed APKs or any packaged identity asset.

    This is a packaging check, not a signature or arbitrary-secret detector.
    It intentionally does not read, extract or print the contents of any entry.
    """
    apk = Path(apk)
    if apk.is_symlink() or not apk.is_file():
        raise ValueError("Source APK must be an existing regular file")
    with zipfile.ZipFile(apk) as archive:
        entries = archive.infolist()
        if not entries:
            raise ValueError("Source APK ZIP is empty")
        seen = set()
        for entry in entries:
            name = entry.orig_filename
            # Reject ambiguous names rather than letting ZIP readers disagree.
            if "\0" in name or "\\" in name or name.startswith("/"):
                raise ValueError("Source APK contains an invalid ZIP entry name")
            parts = name.rstrip("/").split("/")
            if any(part in {"", ".", ".."} for part in parts):
                raise ValueError("Source APK contains an invalid ZIP entry path")
            normalized = name.casefold()
            if normalized in seen:
                raise ValueError("Source APK contains duplicate ZIP entry names")
            seen.add(normalized)
            lowered_parts = [part.casefold() for part in parts]
            if "offline-mfi" in lowered_parts:
                raise ValueError("Source APK contains bundled offline identity assets")
            if (lowered_parts[0] == "assets"
                    and PurePosixPath(normalized).suffix in CREDENTIAL_SUFFIXES):
                raise ValueError("Source APK contains a credential container in assets")
        if "AndroidManifest.xml" not in archive.namelist():
            raise ValueError("Source APK has no Android manifest")


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("apk", type=Path, help="Exact source-only APK to upload")
    args = parser.parse_args(argv)
    try:
        verify_source_apk(args.apk)
    except (OSError, ValueError, zipfile.BadZipFile):
        # Never echo asset data, archive-supplied names or private source paths.
        print("Source APK check failed: invalid APK or bundled identity assets.", file=sys.stderr)
        return 1
    print("Source APK check passed: no bundled offline identity assets or credential containers.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
