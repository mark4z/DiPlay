#!/usr/bin/env python3
"""Verify that the public APK contains the WebRTC JNI library for each shipped ABI."""
import argparse
from pathlib import Path
import sys
import zipfile

# ELF class and e_machine, matching common/build.gradle.kts abiFilters.
ABIS = {"arm64-v8a": (2, 183), "armeabi-v7a": (1, 40), "x86_64": (2, 62)}
LIBRARY = "libdiplay-rtc.so"


def verify_native_apk(apk):
    apk = Path(apk)
    if apk.is_symlink() or not apk.is_file():
        raise ValueError("APK must be a regular file")
    with zipfile.ZipFile(apk) as archive:
        for abi, (elf_class, machine) in ABIS.items():
            name = f"lib/{abi}/{LIBRARY}"
            entries = [entry for entry in archive.infolist() if entry.filename == name]
            if len(entries) != 1:
                raise ValueError(f"Missing or duplicate native library for {abi}")
            with archive.open(entries[0]) as library:
                header = library.read(20)
            if (len(header) != 20 or header[:4] != b"\x7fELF"
                    or header[4:7] != bytes((elf_class, 1, 1))
                    or int.from_bytes(header[16:18], "little") != 3
                    or int.from_bytes(header[18:20], "little") != machine):
                raise ValueError(f"Native library has an invalid ELF header for {abi}")


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("apk", type=Path)
    args = parser.parse_args(argv)
    try:
        verify_native_apk(args.apk)
    except (OSError, ValueError, RuntimeError, zipfile.BadZipFile):
        print("Native APK check failed: missing or invalid WebRTC JNI library.", file=sys.stderr)
        return 1
    print("Native APK check passed: WebRTC JNI library present for all supported ABIs.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
