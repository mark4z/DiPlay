import importlib.util
from pathlib import Path
import tempfile
import unittest
import zipfile

spec = importlib.util.spec_from_file_location("probe_guard", Path(__file__).parents[1] / "check_probe_apk.py")
guard = importlib.util.module_from_spec(spec)
spec.loader.exec_module(guard)

class ProbeApkTests(unittest.TestCase):
    def check_names(self, extra):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "synthetic.apk"
            with zipfile.ZipFile(path, "w") as archive:
                for name in ["AndroidManifest.xml", "classes.dex", *extra]:
                    archive.writestr(name, b"synthetic")
            guard.check(path)
    def test_plain_apk(self):
        self.check_names(["META-INF/ANDROID.RSA", "resources.arsc"])
    def test_credentials_assets_and_native_code_rejected(self):
        for name in ["assets/config.json", "assets/offline-mfi/identity.pk8", "hidden.PEM", "lib/arm64-v8a/helper.so"]:
            with self.subTest(name=name), self.assertRaises(ValueError):
                self.check_names([name])
