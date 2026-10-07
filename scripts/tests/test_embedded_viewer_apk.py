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
            for name in guard.NAMES - {omit}:
                archive.writestr(guard.PREFIX + name, b"changed" if name == alter else (self.source / name).read_bytes())
            if extra:
                archive.writestr(guard.PREFIX + extra, b"unexpected")

    def test_exact_canonical_runtime_passes(self):
        self.archive()
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

    def test_workflow_runs_check_before_upload(self):
        workflow = (ROOT / ".github/workflows/android.yml").read_text()
        self.assertLess(workflow.index("python3 scripts/check_embedded_viewer_apk.py"), workflow.index("Upload credential-free mobile APK directly"))
