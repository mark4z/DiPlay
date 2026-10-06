"""No real identities: all file contents are generated synthetic bytes."""
import base64
from contextlib import redirect_stderr, redirect_stdout
import importlib.util
import io
import os
from pathlib import Path
import re
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
        self.target = "mobile"
        self.payloads = {"identity.pk8": b"synthetic-test-key\x00\xff", "certificate.p7b": b"synthetic-test-certificate\x00"}
        self.reset_environment()

    def reset_environment(self):
        self.environment = {name: base64.b64encode(self.payloads[filename]).decode()
                            for name, filename in builder.SECRET_FILES.items()}

    def apk_path(self, target=None):
        target = target or self.target
        return self.root / target / "build/outputs/apk/debug" / f"{target}-debug.apk"

    def archive(self, path, payloads=None):
        path.parent.mkdir(parents=True, exist_ok=True)
        with zipfile.ZipFile(path, "w") as archive:
            for filename, value in (payloads or self.payloads).items():
                archive.writestr(f"assets/offline-mfi/{filename}", value)

    def fake_gradle(self, command, **kwargs):
        self.assertEqual(command[1], f":{self.target}:assembleStandaloneDebug")
        for flag in ("--no-daemon", "--no-build-cache", "--no-configuration-cache", "--no-scan"):
            self.assertIn(flag, command)
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
        self.archive(self.apk_path())
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
        for self.target in ("mobile", "generic"):
            with self.subTest(target=self.target):
                self.reset_environment()
                for directory in builder.BUILD_DIRS:
                    (self.root / directory).mkdir(parents=True, exist_ok=True)
                    (self.root / directory / "synthetic-intermediate").write_bytes(b"synthetic")
                with patch.object(builder.subprocess, "run", side_effect=self.fake_gradle):
                    builder.build(self.root, self.work, self.environment, target=self.target)
                self.assertFalse(self.work.exists())
                for directory in builder.BUILD_DIRS:
                    self.assertFalse((self.root / directory).exists())
                self.assertFalse(self.publish.exists())
                self.assertNotIn("DIPLAY_AUTH_ASSETS_DIR", self.environment)

    def test_explicit_publication_contains_only_verified_apk(self):
        for self.target, name in (("mobile", "DiPlay-standalone-debug.apk"),
                                  ("generic", "DiPlay-generic-standalone-debug.apk")):
            with self.subTest(target=self.target):
                self.reset_environment()
                publish = self.publish / self.target
                with patch.object(builder.subprocess, "run", side_effect=self.fake_gradle):
                    builder.build(self.root, self.work, self.environment, publish, target=self.target)
                self.assertEqual([path.name for path in publish.iterdir()], [name])
                builder.verify_apk(publish / name, self.payloads)
                self.assertEqual(stat.S_IMODE((publish / name).stat().st_mode), 0o600)
                self.assertFalse(self.work.exists())
                self.assertFalse((self.root / self.target / "build").exists())

    def test_build_defaults_to_mobile_for_existing_callers(self):
        with patch.object(builder.subprocess, "run", side_effect=self.fake_gradle):
            builder.build(self.root, self.work, self.environment)

    def test_cli_defaults_to_mobile_and_accepts_generic(self):
        for target in (None, "mobile", "generic"):
            with self.subTest(target=target), patch.object(builder, "build") as build:
                arguments = ["--work-dir", str(self.work)]
                if target is not None:
                    arguments.extend(["--target", target])
                with patch.object(builder.signal, "signal"), redirect_stdout(io.StringIO()):
                    self.assertEqual(builder.main(arguments), 0)
                self.assertEqual(build.call_args.args[-1], target or "mobile")

    def test_invalid_targets_never_access_secrets_or_run_gradle(self):
        for target in ("", "Generic", "automotive", "../mobile", ":generic:assembleDebug",
                       "generic; printf invalid", "generic\nmobile"):
            with self.subTest(target=target), patch.object(builder, "decode_secrets") as decode:
                with patch.object(builder.subprocess, "run") as run:
                    with self.assertRaises(builder.BuildError):
                        builder.build(self.root, self.work, self.environment, self.publish, target=target)
                decode.assert_not_called()
                run.assert_not_called()
                self.assertFalse(self.work.exists())
                self.assertFalse(self.publish.exists())

    def test_cli_rejects_invalid_target_before_build(self):
        with patch.object(builder, "build") as build, redirect_stderr(io.StringIO()):
            with self.assertRaises(SystemExit) as raised:
                builder.main(["--work-dir", str(self.work), "--target", "../generic"])
        self.assertEqual(raised.exception.code, 2)
        build.assert_not_called()

    def test_build_failure_removes_identity_and_intermediates(self):
        def failed(command, **kwargs):
            self.fake_gradle(command, **kwargs)
            return subprocess.CompletedProcess(command, 1)
        for self.target in ("mobile", "generic"):
            with self.subTest(target=self.target):
                self.reset_environment()
                with patch.object(builder.subprocess, "run", side_effect=failed):
                    with self.assertRaises(builder.BuildError):
                        builder.build(self.root, self.work, self.environment, self.publish, target=self.target)
                self.assertFalse(self.work.exists())
                self.assertFalse(self.publish.exists())
                self.assertFalse((self.root / self.target / "build").exists())
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
            self.archive(self.apk_path(), {"identity.pk8": b"wrong"})
            return result
        for self.target in ("mobile", "generic"):
            with self.subTest(target=self.target):
                self.reset_environment()
                with patch.object(builder.subprocess, "run", side_effect=wrong):
                    with self.assertRaises(builder.BuildError):
                        builder.build(self.root, self.work, self.environment, self.publish, target=self.target)
                self.assertFalse(self.publish.exists())
                self.assertFalse(self.work.exists())
                self.assertFalse((self.root / self.target / "build").exists())

    def test_other_target_apk_is_never_used_as_selected_output(self):
        for self.target in ("mobile", "generic"):
            with self.subTest(target=self.target):
                self.reset_environment()
                other = "generic" if self.target == "mobile" else "mobile"
                def wrong_target(command, **kwargs):
                    result = self.fake_gradle(command, **kwargs)
                    self.apk_path().unlink()
                    self.archive(self.apk_path(other))
                    return result
                with patch.object(builder.subprocess, "run", side_effect=wrong_target):
                    with self.assertRaises(builder.BuildError):
                        builder.build(self.root, self.work, self.environment, self.publish, target=self.target)
                self.assertFalse(self.publish.exists())
                self.assertFalse(self.work.exists())
                self.assertFalse((self.root / "mobile/build").exists())
                self.assertFalse((self.root / "generic/build").exists())

    def test_multiple_selected_apks_never_publish(self):
        for self.target in ("mobile", "generic"):
            with self.subTest(target=self.target):
                self.reset_environment()
                def multiple(command, **kwargs):
                    result = self.fake_gradle(command, **kwargs)
                    self.archive(self.apk_path().with_name("second.apk"))
                    return result
                with patch.object(builder.subprocess, "run", side_effect=multiple):
                    with self.assertRaises(builder.BuildError):
                        builder.build(self.root, self.work, self.environment, self.publish, target=self.target)
                self.assertFalse(self.publish.exists())
                self.assertFalse(self.work.exists())
                self.assertFalse((self.root / self.target / "build").exists())

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
        for self.target in ("mobile", "generic"):
            with self.subTest(target=self.target):
                self.reset_environment()
                with patch.object(builder.subprocess, "run", side_effect=self.fake_gradle):
                    with patch.object(builder, "cleanup_build", side_effect=OSError("synthetic cleanup failure")):
                        with self.assertRaises(builder.BuildError):
                            builder.build(self.root, self.work, self.environment, self.publish, target=self.target)
                self.assertFalse(self.work.exists())
                self.assertFalse(self.publish.exists())
                self.assertTrue(set(builder.SECRET_FILES).isdisjoint(self.environment))
                self.assertNotIn("DIPLAY_AUTH_ASSETS_DIR", self.environment)

    def test_workflow_allows_only_dispatch_on_main_or_reviewed_generic_branch(self):
        workflow = (ROOT / ".github/workflows/build-authenticated-debug.yml").read_text()
        trigger = workflow.split("\non:\n", 1)[1].split("\npermissions:", 1)[0]
        self.assertEqual(re.findall(r"^  ([a-z_]+):", trigger, re.MULTILINE), ["workflow_dispatch"])
        gate = workflow.split("    if: >-\n", 1)[1].split("    runs-on:", 1)[0]
        self.assertEqual(" ".join(gate.split()),
                         "github.event_name == 'workflow_dispatch' && "
                         "(github.ref == 'refs/heads/main' || "
                         "github.ref == 'refs/heads/refactor/generic-android-variant')")

    def test_workflow_target_is_whitelisted_and_passed_as_quoted_environment_value(self):
        workflow = (ROOT / ".github/workflows/build-authenticated-debug.yml").read_text()
        target_input = workflow.split("      target:\n", 1)[1].split("      publish_apk:", 1)[0]
        self.assertIn("type: choice", target_input)
        self.assertIn("default: mobile", target_input)
        self.assertEqual(re.findall(r"^          - (.+)$", target_input, re.MULTILINE), ["mobile", "generic"])
        self.assertIn("APP_TARGET: ${{ inputs.target }}", workflow)
        self.assertEqual(workflow.count('--target "$APP_TARGET"'), 2)
        self.assertIn("path: ${{ env.AUTH_APK_DIR }}/${{ steps.target.outputs.apk_name }}", workflow)
        self.assertIn("mobile/build generic/build common/build", workflow)
        # No user expression is substituted into shell source.
        for run in re.findall(r"        run: \|\n((?:          .*\n)+)", workflow):
            self.assertNotIn("${{", run)
        validation = workflow.split("        id: target\n", 1)[1].split("      - name:", 1)[0]
        self.assertIn('case "$APP_TARGET" in', validation)
        self.assertIn("mobile) printf 'apk_name=DiPlay-standalone-debug.apk", validation)
        self.assertIn("generic) printf 'apk_name=DiPlay-generic-standalone-debug.apk", validation)
        self.assertIn("*) printf 'Unsupported build target.", validation)

    def test_workflow_is_manual_default_off_and_uncached(self):
        workflow = (ROOT / ".github/workflows/build-authenticated-debug.yml").read_text()
        self.assertIn("  workflow_dispatch:", workflow)
        self.assertNotIn("  push:", workflow)
        self.assertNotIn("  pull_request", workflow)
        self.assertIn("default: false", workflow)
        self.assertIn("retention-days: 1", workflow)
        self.assertIn("persist-credentials: false", workflow)
        self.assertIn("if: ${{ always() }}", workflow)
        self.assertNotIn("setup-gradle", workflow)
        self.assertNotIn("actions/cache", workflow)
        before_credentials = workflow.split("      - name: Build and verify authenticated APK")[0]
        self.assertIn(":shared:testDebugUnitTest", before_credentials)
        self.assertIn(":common:testDebugUnitTest", before_credentials)
        self.assertIn(":mobile:lintDebug", before_credentials)
        self.assertIn(":generic:testDebugUnitTest", before_credentials)
        self.assertIn(":generic:lintDebug", before_credentials)
        self.assertIn("python3 scripts/check_generic_boundary.py", before_credentials)
        self.assertNotIn("secrets.", before_credentials)
        self.assertEqual(workflow.count("secrets."), 2)
        automatic = (ROOT / ".github/workflows/android.yml").read_text()
        self.assertNotIn("secrets.", automatic)
        self.assertIn("unittest discover -s scripts/tests", automatic)


if __name__ == "__main__":
    unittest.main()
