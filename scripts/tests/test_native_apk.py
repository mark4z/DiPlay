"""Packaging checks use synthetic ELF headers, never an Android build or credentials."""
import importlib.util
from pathlib import Path
import tempfile
import unittest
import warnings
import zipfile

ROOT = Path(__file__).resolve().parents[2]
SPEC = importlib.util.spec_from_file_location("native_apk", ROOT / "scripts/check_native_apk.py")
guard = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(guard)


def header(abi):
    elf_class, machine = guard.ABIS[abi]
    return b"\x7fELF" + bytes((elf_class, 1, 1)) + bytes(9) + b"\x03\x00" + machine.to_bytes(2, "little")


class NativeApkTest(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.apk = Path(temporary.name) / "synthetic.apk"

    def archive(self, omit=None, override=None):
        with zipfile.ZipFile(self.apk, "w") as archive:
            for abi in guard.ABIS:
                if abi != omit:
                    archive.writestr(f"lib/{abi}/{guard.LIBRARY}",
                                     override if override is not None else header(abi))

    def test_all_supported_abis_pass(self):
        self.archive()
        guard.verify_native_apk(self.apk)

    def test_each_missing_abi_fails(self):
        for abi in guard.ABIS:
            with self.subTest(abi=abi):
                self.archive(omit=abi)
                with self.assertRaises(ValueError):
                    guard.verify_native_apk(self.apk)

    def test_wrong_architecture_non_elf_and_truncated_library_fail(self):
        for contents in (header("armeabi-v7a"), b"not ELF" * 4, b""):
            with self.subTest(contents=contents):
                self.archive(override=contents)
                with self.assertRaises(ValueError):
                    guard.verify_native_apk(self.apk)

    def test_duplicate_library_fails(self):
        self.archive()
        with warnings.catch_warnings():
            warnings.simplefilter("ignore", UserWarning)
            with zipfile.ZipFile(self.apk, "a") as archive:
                archive.writestr(f"lib/arm64-v8a/{guard.LIBRARY}", header("arm64-v8a"))
        with self.assertRaises(ValueError):
            guard.verify_native_apk(self.apk)

    def test_missing_or_malformed_apk_fails(self):
        with self.assertRaises(ValueError):
            guard.verify_native_apk(self.apk)
        self.apk.write_bytes(b"not a ZIP")
        with self.assertRaises(zipfile.BadZipFile):
            guard.verify_native_apk(self.apk)

    def test_workflow_installs_cmake_and_guards_apk_before_upload(self):
        workflow = (ROOT / ".github/workflows/android.yml").read_text()
        self.assertIn("cmake;3.22.1", workflow)
        self.assertIn("GRADLE_BUILD_ACTION_CACHE_KEY_PREFIX: public-native-v1-", workflow)
        self.assertIn("'common/src/main/cpp/**'", workflow)
        self.assertIn("'common/consumer-rules.pro'", workflow)
        self.assertIn("'.github/workflows/android.yml'", workflow)
        self.assertLess(workflow.index("python3 scripts/check_native_apk.py"),
                        workflow.index("Upload credential-free mobile APK directly"))
        gradle = (ROOT / "common/build.gradle.kts").read_text()
        for abi in guard.ABIS:
            self.assertIn(f'"{abi}"', gradle)


if __name__ == "__main__":
    unittest.main()
