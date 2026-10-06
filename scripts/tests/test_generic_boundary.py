import importlib.util
from pathlib import Path
import tempfile
import unittest
import zipfile

spec = importlib.util.spec_from_file_location("boundary", Path(__file__).parents[1] / "check_generic_boundary.py")
boundary = importlib.util.module_from_spec(spec)
spec.loader.exec_module(boundary)


class GenericBoundaryTest(unittest.TestCase):
    def apk(self, extra=None, dex_extra=b""):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        path = Path(temporary.name) / "fixture.apk"
        with zipfile.ZipFile(path, "w") as apk:
            apk.writestr("AndroidManifest.xml", "com.shihab.diplay.generic")
            apk.writestr("classes.dex", " ".join(boundary.CORE_CLASSES).encode() + dex_extra)
            for name in boundary.NATIVE_LIBRARIES:
                apk.writestr(name, b"synthetic native fixture")
            for name, value in (extra or {}).items():
                apk.writestr(name, value)
        return path

    def test_accepts_vendor_free_fixture(self):
        self.assertEqual([], boundary.apk_failures(self.apk(), True))

    def test_rejects_vendor_dex_descriptor(self):
        errors = boundary.apk_failures(self.apk(dex_extra=b"Lcom/shilapi/xcertplay/hud/BydHudBridge;"))
        self.assertTrue(any("classes.dex" in error for error in errors))

    def test_rejects_vendor_asset(self):
        self.assertTrue(boundary.apk_failures(self.apk({"assets/byd-hud-icons/turn.png": b"image"})))

    def test_rejects_utf16_vendor_query(self):
        self.assertEqual(["com.byd."], boundary.forbidden_tokens("com.byd.carsettings".encode("utf-16le")))

    def test_rejects_credential_in_source_only_build(self):
        path = self.apk({"assets/offline-mfi/identity.pk8": b"synthetic"})
        self.assertTrue(boundary.apk_failures(path, True))
        self.assertEqual([], boundary.apk_failures(path, False))

    def test_rejects_missing_core(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        path = Path(temporary.name) / "empty.apk"
        with zipfile.ZipFile(path, "w") as apk:
            apk.writestr("AndroidManifest.xml", "com.shihab.diplay.generic")
        self.assertTrue(any("core class" in error for error in boundary.apk_failures(path)))


if __name__ == "__main__":
    unittest.main()
