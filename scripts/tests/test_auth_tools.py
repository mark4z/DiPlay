"""No real identities: all file contents are generated synthetic bytes."""
import base64
from contextlib import redirect_stderr, redirect_stdout
import importlib.util
import io
import os
from pathlib import Path
import stat
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch
import warnings
import zipfile

ROOT = Path(__file__).resolve().parents[2]


def load(name):
    spec = importlib.util.spec_from_file_location(name, ROOT / "scripts" / f"{name}.py")
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


encoder = load("encode_auth_secret")
builder = load("build_authenticated_debug")


class AuthenticationToolsTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.base = Path(self.temporary.name)
        self.root = self.base / "repo"
        self.root.mkdir()
        self.work = self.base / "auth"
        self.publish = self.base / "publish"
        self.payloads = {"identity.pk8": b"synthetic-test-key\x00\xff", "certificate.p7b": b"synthetic-test-certificate\x00"}
        self.environment = {name: base64.b64encode(self.payloads[filename]).decode()
                            for name, filename in builder.SECRET_FILES.items()}

    def archive(self, path, payloads=None):
        path.parent.mkdir(parents=True, exist_ok=True)
        with zipfile.ZipFile(path, "w") as archive:
            for filename, value in (payloads or self.payloads).items():
                archive.writestr(f"assets/offline-mfi/{filename}", value)

    def fake_gradle(self, command, **kwargs):
        self.assertIn(":mobile:assembleStandaloneDebug", command)
        for flag in ("--no-daemon", "--build-cache", "--no-configuration-cache", "--no-scan"):
            self.assertIn(flag, command)
        self.assertIn("--init-script", command)
        self.assertEqual(command[command.index("--init-script") + 1],
                         str(self.root / "scripts/gradle-readonly-cache.init.gradle"))
        self.assertNotIn("--no-build-cache", command)
        self.assertEqual(kwargs["stdout"], subprocess.DEVNULL)
        self.assertEqual(kwargs["stderr"], subprocess.DEVNULL)
        environment = kwargs["env"]
        self.assertTrue(set(builder.SECRET_FILES).isdisjoint(environment))
        directory = Path(environment["DIPLAY_AUTH_ASSETS_DIR"])
        self.assertNotIn(self.root, directory.parents)
        for filename, expected in self.payloads.items():
            path = directory / "offline-mfi" / filename
            self.assertEqual(path.read_bytes(), expected)
            self.assertEqual(stat.S_IMODE(path.stat().st_mode), 0o600)
        self.archive(self.root / "mobile/build/outputs/apk/debug/mobile-debug.apk")
        return subprocess.CompletedProcess(command, 0)

    def test_file_encoding_is_exact_and_never_stdout(self):
        source = self.base / "input"
        output = self.base / "encoded"
        source.write_bytes(self.payloads["identity.pk8"])
        stdout, stderr = io.StringIO(), io.StringIO()
        with redirect_stdout(stdout), redirect_stderr(stderr):
            self.assertEqual(encoder.main(["--file", str(source), "--output", str(output)]), 0)
        self.assertEqual(stdout.getvalue(), "")
        self.assertEqual(base64.b64decode(output.read_bytes()), source.read_bytes())
        self.assertEqual(stat.S_IMODE(output.stat().st_mode), 0o600)
        self.assertNotIn(output.read_text(), stderr.getvalue())

    def test_private_file_never_overwrites(self):
        output = self.base / "encoded"
        output.write_bytes(b"existing")
        with self.assertRaises(FileExistsError):
            encoder.write_private_file(output, b"new")
        self.assertEqual(output.read_bytes(), b"existing")

    def test_private_file_rejects_symlink(self):
        target = self.base / "target"
        target.write_bytes(b"existing")
        link = self.base / "link"
        link.symlink_to(target)
        with self.assertRaises(FileExistsError):
            encoder.write_private_file(link, b"new")
        self.assertEqual(target.read_bytes(), b"existing")

    def test_apk_reads_only_explicit_assets_without_extracting(self):
        apk = self.base / "selected.apk"
        self.archive(apk)
        for kind, filename in (("key", "identity.pk8"), ("cert", "certificate.p7b")):
            self.assertEqual(encoder.read_payload(apk=apk, asset=kind), self.payloads[filename])
        self.assertFalse((self.base / "assets").exists())

    def test_apk_missing_and_duplicate_assets_fail(self):
        apk = self.base / "selected.apk"
        with zipfile.ZipFile(apk, "w") as archive:
            archive.writestr("unrelated", b"value")
        with self.assertRaises(encoder.SecretError):
            encoder.read_payload(apk=apk, asset="key")
        self.archive(apk)
        with warnings.catch_warnings():
            warnings.simplefilter("ignore", UserWarning)
            with zipfile.ZipFile(apk, "a") as archive:
                archive.writestr(encoder.ASSETS["key"], b"duplicate")
        with self.assertRaises(encoder.SecretError):
            encoder.read_payload(apk=apk, asset="key")

    def test_empty_and_oversized_inputs_fail(self):
        source = self.base / "input"
        for payload in (b"", b"x" * (16 * 1024 + 1)):
            source.write_bytes(payload)
            with self.assertRaises(encoder.SecretError):
                encoder.read_payload(file=source)

    def test_clipboard_uses_stdin_not_command_arguments(self):
        encoded = b"synthetic-encoded-value"
        with patch.object(encoder.sys, "platform", "darwin"), patch.object(encoder.subprocess, "run") as run:
            encoder.copy_clipboard(encoded)
        self.assertEqual(run.call_args.args, (["pbcopy"],))
        self.assertEqual(run.call_args.kwargs["input"], encoded)
        self.assertEqual(run.call_args.kwargs["stdout"], subprocess.DEVNULL)
        self.assertEqual(run.call_args.kwargs["stderr"], subprocess.DEVNULL)

    def test_helper_errors_do_not_print_input_or_secret(self):
        source = self.base / "input"
        source.write_bytes(b"synthetic-sensitive-sentinel")
        stdout, stderr = io.StringIO(), io.StringIO()
        with patch.object(encoder, "copy_clipboard", side_effect=RuntimeError("synthetic-sensitive-sentinel")):
            with redirect_stdout(stdout), redirect_stderr(stderr):
                self.assertEqual(encoder.main(["--file", str(source), "--clipboard"]), 1)
        self.assertEqual(stdout.getvalue(), "")
        self.assertNotIn("synthetic-sensitive-sentinel", stderr.getvalue())

    def test_decode_accepts_line_wrapping_and_removes_variables(self):
        for name in self.environment:
            self.environment[name] = "\n" + self.environment[name][:4] + "\r\n" + self.environment[name][4:] + "\n"
        self.assertEqual(builder.decode_secrets(self.environment), self.payloads)
        self.assertTrue(set(builder.SECRET_FILES).isdisjoint(self.environment))

    def test_decode_missing_empty_invalid_nonascii_and_oversized_fail_safely(self):
        for invalid in ("", "  ", "not*base64-sentinel", "秘密", "=", base64.b64encode(b"x" * (16 * 1024 + 1)).decode()):
            with self.subTest(invalid_length=len(invalid)):
                environment = dict(self.environment)
                environment["DIPLAY_MFI_KEY_B64"] = invalid
                with self.assertRaises(builder.BuildError) as raised:
                    builder.decode_secrets(environment)
                if invalid.strip():
                    self.assertNotIn(invalid, str(raised.exception))
        with self.assertRaises(builder.BuildError):
            builder.decode_secrets({})

    def test_success_without_publication_deletes_all_output(self):
        with patch.object(builder.subprocess, "run", side_effect=self.fake_gradle):
            builder.build(self.root, self.work, self.environment)
        self.assertFalse(self.work.exists())
        self.assertFalse((self.root / "mobile/build").exists())
        self.assertFalse(self.publish.exists())
        self.assertNotIn("DIPLAY_AUTH_ASSETS_DIR", self.environment)

    def test_explicit_publication_contains_only_verified_apk(self):
        with patch.object(builder.subprocess, "run", side_effect=self.fake_gradle):
            builder.build(self.root, self.work, self.environment, self.publish)
        self.assertEqual([path.name for path in self.publish.iterdir()], ["DiPlay-standalone-debug.apk"])
        builder.verify_apk(self.publish / "DiPlay-standalone-debug.apk", self.payloads)
        self.assertFalse(self.work.exists())
        self.assertFalse((self.root / "mobile/build").exists())

    def test_build_failure_removes_identity_and_intermediates(self):
        def failed(command, **kwargs):
            self.fake_gradle(command, **kwargs)
            return subprocess.CompletedProcess(command, 1)
        with patch.object(builder.subprocess, "run", side_effect=failed):
            with self.assertRaises(builder.BuildError):
                builder.build(self.root, self.work, self.environment, self.publish)
        self.assertFalse(self.work.exists())
        self.assertFalse(self.publish.exists())
        self.assertFalse((self.root / "mobile/build").exists())
        self.assertTrue(set(builder.SECRET_FILES).isdisjoint(self.environment))

    def test_missing_secret_never_runs_gradle(self):
        self.environment.pop("DIPLAY_MFI_CERT_B64")
        with patch.object(builder.subprocess, "run") as run:
            with self.assertRaises(builder.BuildError):
                builder.build(self.root, self.work, self.environment)
        run.assert_not_called()
        self.assertFalse(self.work.exists())

    def test_verification_failure_never_publishes(self):
        def wrong(command, **kwargs):
            result = self.fake_gradle(command, **kwargs)
            self.archive(self.root / "mobile/build/outputs/apk/debug/mobile-debug.apk", {"identity.pk8": b"wrong"})
            return result
        with patch.object(builder.subprocess, "run", side_effect=wrong):
            with self.assertRaises(builder.BuildError):
                builder.build(self.root, self.work, self.environment, self.publish)
        self.assertFalse(self.publish.exists())
        self.assertFalse(self.work.exists())
        self.assertFalse((self.root / "mobile/build").exists())

    def test_existing_directories_are_not_deleted(self):
        self.work.mkdir()
        (self.work / "keep").write_text("keep")
        with self.assertRaises(FileExistsError):
            builder.build(self.root, self.work, self.environment)
        self.assertEqual((self.work / "keep").read_text(), "keep")
        other_work = self.base / "new-auth"
        self.publish.mkdir()
        (self.publish / "keep").write_text("keep")
        environment = {name: base64.b64encode(self.payloads[filename]).decode()
                       for name, filename in builder.SECRET_FILES.items()}
        with patch.object(builder.subprocess, "run", side_effect=self.fake_gradle):
            with self.assertRaises(FileExistsError):
                builder.build(self.root, other_work, environment, self.publish)
        self.assertEqual((self.publish / "keep").read_text(), "keep")

    def test_intermediate_cleanup_failure_still_removes_identity_and_blocks_publication(self):
        with patch.object(builder.subprocess, "run", side_effect=self.fake_gradle):
            with patch.object(builder, "cleanup_build", side_effect=OSError("synthetic cleanup failure")):
                with self.assertRaises(builder.BuildError):
                    builder.build(self.root, self.work, self.environment, self.publish)
        self.assertFalse(self.work.exists())
        self.assertFalse(self.publish.exists())
        self.assertTrue(set(builder.SECRET_FILES).isdisjoint(self.environment))
        self.assertNotIn("DIPLAY_AUTH_ASSETS_DIR", self.environment)

    def test_workflow_is_manual_auto_upload_with_no_credential_cache_save(self):
        workflow = (ROOT / ".github/workflows/build-authenticated-debug.yml").read_text()
        self.assertIn("  workflow_dispatch:", workflow)
        self.assertNotIn("  push:", workflow)
        self.assertNotIn("  pull_request", workflow)
        self.assertNotIn("publish_apk", workflow)
        self.assertNotIn("    inputs:", workflow)
        self.assertIn('--publish-dir "$AUTH_APK_DIR"', workflow)
        self.assertIn("archive: false", workflow)
        self.assertIn("retention-days: 1", workflow)
        self.assertIn("persist-credentials: false", workflow)
        self.assertIn("if: ${{ always() }}", workflow)
        self.assertNotIn("setup-gradle", workflow)
        credential_job = workflow.split("\n  build:\n", 1)[1]
        self.assertIn("needs: source-checks", credential_job)
        self.assertIn("actions/cache/restore@", credential_job)
        self.assertNotIn("actions/cache/save@", credential_job)
        self.assertNotIn("uses: actions/cache@", credential_job)
        before_credentials = workflow.split("      - name: Build and verify authenticated APK")[0]
        self.assertIn(":shared:testDebugUnitTest", before_credentials)
        self.assertIn(":common:testDebugUnitTest", before_credentials)
        self.assertIn(":mobile:lintDebug", before_credentials)
        self.assertNotIn("secrets.", before_credentials)
        self.assertEqual(workflow.count("secrets."), 2)
        automatic = (ROOT / ".github/workflows/android.yml").read_text()
        self.assertNotIn("secrets.", automatic)
        self.assertIn("unittest discover -s scripts/tests", automatic)


if __name__ == "__main__":
    unittest.main()
