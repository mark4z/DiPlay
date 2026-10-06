#!/usr/bin/env python3
"""Ephemeral Actions build stage; source-only checks must have passed first."""
import argparse
import base64
import binascii
import hmac
import os
from pathlib import Path
import shutil
import signal
import subprocess
import sys
import tempfile
import zipfile

SECRET_FILES = {
    "DIPLAY_MFI_KEY_B64": "identity.pk8",
    "DIPLAY_MFI_CERT_B64": "certificate.p7b",
}
MAX_BYTES = 16 * 1024
BUILD_TARGETS = {
    "mobile": (":mobile:assembleStandaloneDebug", "mobile/build/outputs/apk/debug", "DiPlay-standalone-debug.apk"),
    "generic": (":generic:assembleStandaloneDebug", "generic/build/outputs/apk/debug", "DiPlay-generic-standalone-debug.apk"),
}
BUILD_DIRS = (".gradle", ".kotlin", "mobile/build", "generic/build", "common/build", "shared/build", "shared/.cxx")


class BuildError(Exception):
    """Fixed errors only: never wrap an exception containing credential data."""


def decode_secrets(environment):
    result = {}
    for name, filename in SECRET_FILES.items():
        value = environment.pop(name, "")
        if not value or not value.strip():
            raise BuildError(f"Required Actions Secret {name} is missing or empty.")
        try:
            compact = "".join(value.split()).encode("ascii")
            if len(compact) > 48 * 1024:
                raise BuildError(f"Actions Secret {name} exceeds the supported size limit.")
            payload = base64.b64decode(compact, validate=True)
        except (ValueError, UnicodeError, binascii.Error):
            raise BuildError(f"Actions Secret {name} is not valid Base64.") from None
        if not payload or len(payload) > MAX_BYTES:
            raise BuildError(f"Actions Secret {name} decodes to empty or oversized data.")
        result[filename] = payload
    return result


def verify_apk(apk, expected):
    with zipfile.ZipFile(apk) as archive:
        for filename, payload in expected.items():
            name = f"assets/offline-mfi/{filename}"
            entries = [entry for entry in archive.infolist() if entry.filename == name]
            if len(entries) != 1 or entries[0].file_size != len(payload):
                raise BuildError("Built APK is missing the selected identity or contains duplicates.")
            if not hmac.compare_digest(archive.read(entries[0]), payload):
                raise BuildError("Built APK authentication assets do not match the selected inputs.")


def cleanup_build(root):
    failed = False
    for name in BUILD_DIRS:
        path = root / name
        try:
            if path.is_symlink():
                path.unlink()
            elif path.exists():
                shutil.rmtree(path)
        except OSError:
            failed = True
    if failed:
        raise BuildError("Some build intermediates could not be removed; runner cleanup is required.")


def build(root, work_dir, environment, publish_dir=None, target="mobile"):
    # Select only fixed tasks/paths; never interpolate arbitrary input into a command or path.
    if target not in BUILD_TARGETS:
        raise BuildError("Unsupported build target; choose mobile or generic.")
    task, apk_directory, apk_name = BUILD_TARGETS[target]
    # Work/publish directories must be outside the checkout to avoid source uploads.
    root = root.resolve()
    work_dir = work_dir.resolve()
    if work_dir == root or root in work_dir.parents:
        raise BuildError("The temporary work directory must be outside the checkout.")
    if publish_dir is not None:
        publish_dir = publish_dir.resolve()
        if publish_dir == root or root in publish_dir.parents or publish_dir == work_dir or work_dir in publish_dir.parents:
            raise BuildError("The publication directory must be separate from source and temporary identity files.")
    work_created = False
    publish_created = False
    try:
        expected = decode_secrets(environment)
        # Gradle receives the explicit directory, never the Base64 secret values.
        for name in SECRET_FILES:
            environment.pop(name, None)
        work_dir.mkdir(mode=0o700, parents=True, exist_ok=False)
        work_created = True
        with tempfile.TemporaryDirectory(prefix="runtime-", dir=work_dir) as temporary:
            auth_dir = Path(temporary)
            asset_dir = auth_dir / "offline-mfi"
            asset_dir.mkdir(mode=0o700)
            for filename, payload in expected.items():
                path = asset_dir / filename
                with path.open("xb") as stream:
                    os.fchmod(stream.fileno(), 0o600)
                    stream.write(payload)
            environment["DIPLAY_AUTH_ASSETS_DIR"] = str(auth_dir)
            command = [str(root / "gradlew"), task, "--no-daemon",
                       "--no-build-cache", "--no-configuration-cache", "--no-scan", "--console=plain"]
            # Do not retain build output: a failed tool/plugin could print transformed secrets.
            completed = subprocess.run(command, cwd=root, env=environment, stdout=subprocess.DEVNULL,
                                       stderr=subprocess.DEVNULL, check=False)
            if completed.returncode:
                raise BuildError("Authenticated build failed. Sensitive-stage output was suppressed; inspect the source-only checks first.")
            apks = list((root / apk_directory).glob("*.apk"))
            if len(apks) != 1:
                raise BuildError("Expected exactly one debug APK.")
            verify_apk(apks[0], expected)
            if publish_dir is not None:
                publish_dir.mkdir(mode=0o700, parents=True, exist_ok=False)
                publish_created = True
                destination = publish_dir / apk_name
                with apks[0].open("rb") as source, destination.open("xb") as output:
                    os.fchmod(output.fileno(), 0o600)
                    shutil.copyfileobj(source, output)
    except BaseException:
        if publish_created and publish_dir.exists():
            shutil.rmtree(publish_dir)
        raise
    finally:
        # Includes Gradle copies of the assets, even after verification/build failure.
        cleanup_failed = False
        try:
            cleanup_build(root)
        except Exception:
            cleanup_failed = True
        try:
            if work_created and work_dir.exists():
                shutil.rmtree(work_dir)
        except Exception:
            cleanup_failed = True
        finally:
            for name in SECRET_FILES:
                environment.pop(name, None)
            environment.pop("DIPLAY_AUTH_ASSETS_DIR", None)
        if cleanup_failed:
            if publish_created:
                shutil.rmtree(publish_dir, ignore_errors=True)
            raise BuildError("Sensitive cleanup was incomplete; publication blocked and runner cleanup is required.")


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--target", choices=BUILD_TARGETS, default="mobile")
    parser.add_argument("--work-dir", type=Path, required=True)
    parser.add_argument("--publish-dir", type=Path)
    args = parser.parse_args(argv)
    # A normal cancellation executes finally; always() is the second cleanup layer.
    signal.signal(signal.SIGTERM, lambda *_: sys.exit(143))
    try:
        build(Path(__file__).resolve().parents[1], args.work_dir, os.environ, args.publish_dir, args.target)
    except BuildError as error:
        print(str(error), file=sys.stderr)
        return 1
    except Exception:
        print("Authenticated build or cleanup failed; no credential details are logged.", file=sys.stderr)
        return 1
    if args.publish_dir is None:
        print("Authenticated APK verified and deleted. Publication was not requested.")
    else:
        print("Authenticated APK verified. Explicitly selected artifact contains extractable identity.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
