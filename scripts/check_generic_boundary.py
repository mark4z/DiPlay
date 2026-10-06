#!/usr/bin/env python3
"""Verify the generic source boundary and, optionally, the actual unminified APK.

This is deliberately independent of Gradle: CI verifies sources before compilation and the
packaged DEX/manifest/resources after assembly. It does not claim physical-device compatibility.
"""
import argparse
from pathlib import Path
import re
import zipfile

ROOT = Path(__file__).resolve().parents[1]
FORBIDDEN = (
    "com.shilapi.xcertplay.hud", "com/shilapi/xcertplay/hud",
    "com.byd.", "com/byd/", "com.ts.car.", "com/ts/car/",
    "byd-hud-icons", "bydauto", "bydoutputsettings", "bydnavigationoutputs",
    "keycode_byd", "adbclusteractivity", "dilink51clustermonitor",
)
CORE_CLASSES = ("CarPlayController;", "AndroidMediaSink;", "Iap2Session;",
                "Iap2RouteState;", "Iap2NowPlayingState;")
NATIVE_LIBRARIES = tuple(
    f"lib/{abi}/lib{name}.so"
    for abi in ("arm64-v8a", "armeabi-v7a", "x86_64")
    for name in ("xcertplay_i2c", "local_hotspot_radio")
)


def forbidden_tokens(data: bytes) -> list[str]:
    # DEX strings are UTF-8; Android binary XML/resources may use UTF-8 or UTF-16LE.
    haystacks = (data.decode("utf-8", errors="ignore").lower(),
                 data.decode("utf-16le", errors="ignore").lower(),
                 data[1:].decode("utf-16le", errors="ignore").lower())
    return [token for token in FORBIDDEN if any(token in text for text in haystacks)]


def source_failures(root: Path = ROOT) -> list[str]:
    errors = []
    paths = [root / "shared/src/main", root / "shared/src/generic", root / "generic/src"]
    for base in paths:
        for path in base.rglob("*"):
            if not path.is_file() or path.suffix not in (".kt", ".java", ".xml"):
                continue
            text = path.read_text()
            # Comments describe historical firmware behavior without introducing runtime coupling.
            text = re.sub(r"/\*.*?\*/|<!--.*?-->|(?m:^[ \t]*//[^\n]*$)", "", text, flags=re.S)
            found = forbidden_tokens(text.encode())
            if found:
                errors.append(f"{path.relative_to(root)}: {', '.join(found)}")
    build = root / "generic/build.gradle.kts"
    if not build.is_file():
        errors.append("generic/build.gradle.kts is missing")
    else:
        text = build.read_text()
        if re.search(r'project\s*\(\s*":(?:common|mobile|automotive)"', text):
            errors.append("generic must not depend on a vendor host module")
        if not re.search(r'missingDimensionStrategy\("vendor",\s*"generic"\)', text):
            errors.append("generic must explicitly select the generic shared variant")
        if 'applicationId = "com.shihab.diplay.generic"' not in text:
            errors.append("generic requires its separate application/data identity")
    for module in ("common", "mobile", "automotive"):
        if 'missingDimensionStrategy("vendor", "byd")' not in (root / module / "build.gradle.kts").read_text():
            errors.append(f"{module} must explicitly retain the BYD variant")
    if (root / "shared/src/main/java/com/shilapi/xcertplay/hud").exists():
        errors.append("vendor HUD sources remain in shared main")
    if (root / "shared/src/main/assets/byd-hud-icons").exists():
        errors.append("vendor HUD assets remain in shared main")
    return errors


def apk_failures(path: Path, source_only: bool = False) -> list[str]:
    errors = []
    dex = bytearray()
    with zipfile.ZipFile(path) as apk:
        names = apk.namelist()
        for name in names:
            found = forbidden_tokens(name.encode())
            if found:
                errors.append(f"packaged entry {name}: {', '.join(found)}")
            if source_only and (name.startswith("assets/offline-mfi/") or
                                Path(name).suffix.lower() in {".pk8", ".p7b", ".key", ".pem", ".p12", ".pfx", ".jks", ".keystore"}):
                errors.append(f"credential container in source-only APK: {name}")
            if name.endswith(".dex") or name in ("AndroidManifest.xml", "resources.arsc"):
                data = apk.read(name)
                found = forbidden_tokens(data)
                if found:
                    errors.append(f"packaged {name}: {', '.join(found)}")
                if name.endswith(".dex"):
                    dex.extend(data)
                if name == "AndroidManifest.xml" and not any(
                    "com.shihab.diplay.generic" in data.decode(encoding, errors="ignore")
                    for encoding in ("utf-8", "utf-16le")
                ):
                    errors.append("APK manifest lacks the separate generic package identity")
        if "AndroidManifest.xml" not in names:
            errors.append("APK has no manifest")
        for name in CORE_CLASSES:
            if name.encode() not in dex:
                errors.append(f"core class missing from unminified generic APK: {name}")
        for name in NATIVE_LIBRARIES:
            if name not in names:
                errors.append(f"core native library missing from generic APK: {name}")
    return errors


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--apk", type=Path)
    parser.add_argument("--source-only", action="store_true")
    args = parser.parse_args()
    errors = source_failures()
    if args.apk:
        errors += apk_failures(args.apk, args.source_only)
    if errors:
        raise SystemExit("Generic boundary failed:\n" + "\n".join(errors))
    print("Generic source boundary passed." + (" APK DEX, manifest and resources passed." if args.apk else " APK not inspected."))


if __name__ == "__main__":
    main()
