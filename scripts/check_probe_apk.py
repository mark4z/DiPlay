#!/usr/bin/env python3
"""This standalone APK has no runtime assets, native code, or accessory credentials."""
import sys
import zipfile


def check(path):
    with zipfile.ZipFile(path) as archive:
        names = archive.namelist()
        forbidden = [name for name in names if name.lower().startswith(("assets/", "lib/"))
                     or name.lower().endswith((".pk8", ".p7b", ".key", ".pem", ".p12", ".pfx", ".jks", ".keystore"))]
        if forbidden:
            raise ValueError("Unexpected assets, native libraries or credentials in probe APK")
        if "AndroidManifest.xml" not in names or "classes.dex" not in names:
            raise ValueError("Missing APK manifest or code")


if __name__ == "__main__":
    check(sys.argv[1])
    print("Probe APK payload guard passed")
