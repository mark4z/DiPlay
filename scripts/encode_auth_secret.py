#!/usr/bin/env python3
"""User-run, offline Base64 helper. Secret bytes never go to stdout or argv."""
import argparse
import base64
import os
from pathlib import Path
import shutil
import subprocess
import sys
import zipfile

ASSETS = {
    "key": "assets/offline-mfi/identity.pk8",
    "cert": "assets/offline-mfi/certificate.p7b",
}
# Match LocalMfiAuthenticationClient: each runtime input is limited to 16 KiB.
MAX_BYTES = 16 * 1024


class SecretError(Exception):
    """A fixed, safe-to-display failure message."""


def read_payload(file=None, apk=None, asset=None):
    if file is not None:
        with Path(file).open("rb") as stream:
            payload = stream.read(MAX_BYTES + 1)
    else:
        with zipfile.ZipFile(apk) as archive:
            name = ASSETS[asset]
            matches = [entry for entry in archive.infolist() if entry.filename == name]
            if len(matches) != 1 or matches[0].is_dir():
                raise SecretError("Selected APK asset is missing or duplicated.")
            with archive.open(matches[0]) as stream:
                payload = stream.read(MAX_BYTES + 1)
    if not payload:
        raise SecretError("Selected input is empty.")
    if len(payload) > MAX_BYTES:
        raise SecretError("Selected input exceeds the runtime 16 KiB limit.")
    return payload


def write_private_file(path, encoded):
    # Exclusive creation prevents overwriting a file or following a final symlink.
    # Use a private, non-synced destination; Windows must use clipboard instead.
    if os.name != "posix":
        raise SecretError("Private file output requires POSIX permissions; use --clipboard.")
    fd = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL | getattr(os, "O_NOFOLLOW", 0), 0o600)
    try:
        with os.fdopen(fd, "wb") as stream:
            os.fchmod(stream.fileno(), 0o600)
            stream.write(encoded)
    except BaseException:
        Path(path).unlink(missing_ok=True)
        raise


def copy_clipboard(encoded):
    if sys.platform == "darwin":
        command = ["pbcopy"]
    elif sys.platform == "win32":
        command = ["clip.exe"]
    elif os.environ.get("WAYLAND_DISPLAY") and shutil.which("wl-copy"):
        command = ["wl-copy"]
    elif os.environ.get("DISPLAY") and shutil.which("xclip"):
        command = ["xclip", "-selection", "clipboard"]
    else:
        raise SecretError("No supported clipboard tool found; use --output in a private directory.")
    subprocess.run(command, input=encoded, check=True, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    source = parser.add_mutually_exclusive_group(required=True)
    source.add_argument("--file", type=Path, help="An already-authorized local identity or certificate file")
    source.add_argument("--apk", type=Path, help="An already-authorized local official APK; no download occurs")
    parser.add_argument("--asset", choices=ASSETS, help="Required with --apk: key or cert")
    destination = parser.add_mutually_exclusive_group(required=True)
    destination.add_argument("--clipboard", action="store_true", help="Copy one value; clipboard sync/history may retain it")
    destination.add_argument("--output", type=Path, help="Create a NEW mode-0600 local file; never overwrite")
    args = parser.parse_args(argv)
    if (args.apk is not None) != (args.asset is not None):
        parser.error("Use --asset only with --apk, and always select it with --apk.")
    try:
        encoded = base64.b64encode(read_payload(args.file, args.apk, args.asset))
        if args.clipboard:
            copy_clipboard(encoded)
        else:
            write_private_file(args.output, encoded)
    except SecretError as error:
        print(str(error), file=sys.stderr)
        return 1
    except Exception:
        # Do not include input names, tool output, exception repr, or secret data.
        print("Could not read the input or write the chosen destination. No secret was printed.", file=sys.stderr)
        return 1
    print("Base64 saved to the selected destination. Treat it as the original secret.", file=sys.stderr)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
