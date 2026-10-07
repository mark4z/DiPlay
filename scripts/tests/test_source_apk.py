"""Source APK guard and CI upload checks; archives contain only synthetic text."""
from contextlib import redirect_stderr, redirect_stdout
import importlib.util
import io
from pathlib import Path
import re
import tempfile
import unittest
import warnings
import zipfile


ROOT = Path(__file__).resolve().parents[2]
SPEC = importlib.util.spec_from_file_location("check_source_apk", ROOT / "scripts/check_source_apk.py")
guard = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(guard)
APK = "mobile/build/outputs/apk/debug/mobile-debug.apk"


class SourceApkTest(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name)
        self.apk = self.root / "mobile-debug.apk"

    def archive(self, names=(), manifest=True):
        with zipfile.ZipFile(self.apk, "w") as archive:
            if manifest:
                archive.writestr("AndroidManifest.xml", b"synthetic manifest")
            for name in names:
                archive.writestr(name, b"synthetic test data; not a credential")

    def test_accepts_source_apk_and_android_signature_metadata(self):
        self.archive(["classes.dex", "assets/notices.txt", "META-INF/CERT.RSA", "META-INF/CERT.SF"])
        self.assertIsNone(guard.verify_source_apk(self.apk))

    def test_rejects_offline_identity_folder_anywhere_even_empty(self):
        for name in ("assets/offline-mfi/identity.pk8", "assets/offline-mfi/certificate.p7b",
                     "assets/offline-mfi/unexpected.bin", "assets/offline-mfi/",
                     "assets/OFFLINE-MFI/identity.bin", "other/offline-mfi/identity.bin"):
            with self.subTest(name=name):
                self.archive([name])
                with self.assertRaisesRegex(ValueError, "bundled offline identity"):
                    guard.verify_source_apk(self.apk)

    def test_rejects_credential_containers_elsewhere_in_assets(self):
        for suffix in guard.CREDENTIAL_SUFFIXES:
            with self.subTest(suffix=suffix):
                self.archive([f"assets/nested/material{suffix.upper()}"])
                with self.assertRaisesRegex(ValueError, "credential container"):
                    guard.verify_source_apk(self.apk)

    def test_rejects_ambiguous_zip_paths(self):
        for name in ("assets\\offline-mfi\\identity.pk8", "/assets/readme.txt",
                     "assets/../readme.txt", "assets/./readme.txt", "assets//readme.txt"):
            with self.subTest(name=name):
                self.archive([name])
                with self.assertRaisesRegex(ValueError, "invalid ZIP entry"):
                    guard.verify_source_apk(self.apk)

    def test_rejects_duplicate_entries_including_case_variants(self):
        for names in (("classes.dex", "classes.dex"), ("assets/a.txt", "ASSETS/A.TXT")):
            with self.subTest(names=names), warnings.catch_warnings():
                warnings.simplefilter("ignore", UserWarning)
                self.archive(names)
                with self.assertRaisesRegex(ValueError, "duplicate ZIP"):
                    guard.verify_source_apk(self.apk)

    def test_rejects_nul_in_zip_name_before_a_reader_can_truncate_it(self):
        self.archive(["assets/aXb.txt"])
        self.apk.write_bytes(self.apk.read_bytes().replace(b"assets/aXb.txt", b"assets/a\0b.txt"))
        with self.assertRaisesRegex(ValueError, "invalid ZIP entry name"):
            guard.verify_source_apk(self.apk)

    def test_rejects_missing_empty_non_zip_and_manifestless_inputs(self):
        with self.assertRaises(ValueError):
            guard.verify_source_apk(self.apk)
        self.archive(manifest=False)
        with self.assertRaisesRegex(ValueError, "ZIP is empty"):
            guard.verify_source_apk(self.apk)
        self.archive(["classes.dex"], manifest=False)
        with self.assertRaisesRegex(ValueError, "no Android manifest"):
            guard.verify_source_apk(self.apk)
        for contents in (b"", b"not a zip"):
            self.apk.write_bytes(contents)
            with self.assertRaises(zipfile.BadZipFile):
                guard.verify_source_apk(self.apk)

    def test_rejects_symlink_instead_of_verifying_a_different_file(self):
        self.archive()
        link = self.root / "linked.apk"
        link.symlink_to(self.apk)
        with self.assertRaisesRegex(ValueError, "regular file"):
            guard.verify_source_apk(link)

    def test_cli_exit_codes_and_errors_do_not_expose_asset_names_or_bytes(self):
        self.archive(["assets/offline-mfi/private-test-name"])
        stdout, stderr = io.StringIO(), io.StringIO()
        with redirect_stdout(stdout), redirect_stderr(stderr):
            self.assertEqual(guard.main([str(self.apk)]), 1)
        self.assertEqual(stdout.getvalue(), "")
        self.assertNotIn("private-test-name", stderr.getvalue())
        self.assertNotIn("synthetic test data", stderr.getvalue())
        self.assertNotIn(str(self.apk), stderr.getvalue())
        self.archive()
        with redirect_stdout(io.StringIO()):
            self.assertEqual(guard.main([str(self.apk)]), 0)


class SourceApkWorkflowTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.workflow = (ROOT / ".github/workflows/android.yml").read_text()
        cls.steps = re.split(r"^      - ", cls.workflow, flags=re.MULTILINE)[1:]

    def test_apk_guard_and_upload_follow_successful_existing_checks(self):
        build = next(step for step in self.steps if "run: ./gradlew " in step)
        verify = next(step for step in self.steps if "scripts/check_source_apk.py" in step)
        upload = next(step for step in self.steps if "path: " + APK in step)
        self.assertLess(self.steps.index(build), self.steps.index(verify))
        self.assertLess(self.steps.index(verify), self.steps.index(upload))
        for step in (verify, upload):
            self.assertIn("if: ${{ success() }}", step)
            self.assertNotIn("continue-on-error:", step)
        self.assertIn("run: python3 scripts/check_source_apk.py " + APK + "\n", verify)
        self.assertIn("python3 -m unittest discover -s scripts/tests -v", self.workflow)
        for expected in (":shared:testDebugUnitTest", ":common:testDebugUnitTest", ":home:testDebugUnitTest",
                         ":mobile:lintDebug", ":home:lintDebug", ":maphost:lintDebug",
                         ":mobile:assembleDebug", ":home:assembleDebug", ":maphost:assembleDebug"):
            self.assertIn(expected, build)

    def test_direct_apk_artifact_is_exactly_one_file_not_outputs_or_caches(self):
        upload = next(step for step in self.steps if "path: " + APK in step)
        self.assertIn("uses: actions/upload-artifact@bbbca2ddaa5d8feaa63e36b76fdaad77386f024f # v7.0.0", upload)
        self.assertIn("archive: false\n", upload)
        self.assertIn("if-no-files-found: error\n", upload)
        self.assertIn("retention-days: 7\n", upload)
        self.assertEqual(re.findall(r"^          path: (.+)$", upload, re.MULTILINE), [APK])
        self.assertNotIn("*", upload)
        self.assertNotIn("include-hidden-files: true", upload)
        # The only other upload remains the existing explicit test/lint reports.
        uploads = [step for step in self.steps if "uses: actions/upload-artifact@" in step]
        self.assertEqual(len(uploads), 2)
        report = next(step for step in uploads if "name: test-reports" in step)
        self.assertIn("if: always()", report)
        self.assertNotIn("outputs/", report)
        self.assertNotIn("caches/", report)

    def test_automatic_workflow_does_not_access_identity_sources_or_secrets(self):
        for forbidden in ("secrets.", "DIPLAY_MFI_", "DIPLAY_AUTH_ASSETS_DIR",
                          "build_authenticated_debug.py", "download-artifact", "assembleStandaloneDebug"):
            self.assertNotIn(forbidden, self.workflow)
        self.assertIn("  push:\n", self.workflow)
        self.assertIn("  pull_request:\n", self.workflow)


if __name__ == "__main__":
    unittest.main()
