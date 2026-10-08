import importlib.util
from pathlib import Path
import tempfile
import unittest
import zipfile

ROOT = Path(__file__).resolve().parents[2]
SPEC = importlib.util.spec_from_file_location("embedded", ROOT / "scripts/check_embedded_viewer_apk.py")
guard = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(guard)


class EmbeddedViewerApkTest(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name)
        self.apk = self.root / "synthetic.apk"
        self.source = self.root / "source"
        self.source.mkdir()
        for name in guard.NAMES:
            (self.source / name).write_bytes(b"synthetic runtime content")

    def archive(self, omit=None, extra=None, alter=None):
        with zipfile.ZipFile(self.apk, "w") as archive:
            archive.writestr(guard.HTTPS_CONFIG, f"hostname={guard.DEFAULT_HOSTNAME}\n")
            for name in guard.NAMES - {omit}:
                archive.writestr(guard.PREFIX + name, b"changed" if name == alter else (self.source / name).read_bytes())
            if extra:
                archive.writestr(guard.PREFIX + extra, b"unexpected")

    def test_exact_canonical_runtime_passes(self):
        self.archive()
        guard.verify(self.apk, self.source)

    def test_source_only_apk_rejects_tls_bundle_files_and_directories(self):
        for name in ("assets/private-https/identity.zip", "assets/private-https/",
                     "assets/private-https", "assets/PRIVATE-HTTPS/identity.zip"):
            with self.subTest(name=name):
                self.archive()
                with zipfile.ZipFile(self.apk, "a") as archive:
                    archive.writestr(name, b"synthetic forbidden asset")
                with self.assertRaises(ValueError):
                    guard.verify(self.apk, self.source)

    def test_missing_unexpected_or_modified_assets_fail(self):
        for options in ({"omit": "audio.mjs"}, {"extra": "README.md"}, {"extra": "tests/test.mjs"}, {"alter": "viewer.mjs"}):
            with self.subTest(options=options):
                self.archive(**options)
                with self.assertRaises(ValueError):
                    guard.verify(self.apk, self.source)

    def test_oversize_asset_fails(self):
        (self.source / "viewer.mjs").write_bytes(b"x" * (512 * 1024 + 1))
        self.archive()
        with self.assertRaises(ValueError):
            guard.verify(self.apk, self.source)

    def test_generated_hostname_must_be_present_unique_and_match_expected_build(self):
        for values in ([], [b"hostname=other.example.com\n"], [b"hostname=tesla.mark4z.asia\n"] * 2):
            with self.subTest(values=values):
                with zipfile.ZipFile(self.apk, "w") as archive:
                    for name in guard.NAMES:
                        archive.writestr(guard.PREFIX + name, (self.source / name).read_bytes())
                    for value in values:
                        archive.writestr(guard.HTTPS_CONFIG, value)
                with self.assertRaises(ValueError):
                    guard.verify(self.apk, self.source)

    def test_custom_hostname_changes_only_generated_config(self):
        with zipfile.ZipFile(self.apk, "w") as archive:
            archive.writestr(guard.HTTPS_CONFIG, "hostname=viewer.example.com\n")
            for name in guard.NAMES:
                archive.writestr(guard.PREFIX + name, (self.source / name).read_bytes())
        guard.verify(self.apk, self.source, hostname="viewer.example.com")

    def test_workflow_runs_check_before_upload(self):
        workflow = (ROOT / ".github/workflows/android.yml").read_text()
        self.assertLess(workflow.index("python3 scripts/check_embedded_viewer_apk.py"), workflow.index("Upload credential-free mobile APK directly"))
