"""Offline contract tests for the secret-bearing, checkout-free push bridge.

Only synthetic credentials and mocked transports are used. The security-sensitive
YAML header is deliberately checked exactly, avoiding a runtime YAML dependency.
"""
from contextlib import redirect_stderr, redirect_stdout
import copy
import io
import json
import os
from pathlib import Path
import tempfile
import textwrap
import types
import unittest
from unittest.mock import Mock, patch
from urllib.error import HTTPError, URLError
from urllib.request import HTTPRedirectHandler, ProxyHandler

ROOT = Path(__file__).resolve().parents[2]
WORKFLOW = ROOT / ".github/workflows/trigger-private-apk.yml"
WORKFLOW_TEXT = WORKFLOW.read_text(encoding="utf-8")
HEADER, RUN_BLOCK = WORKFLOW_TEXT.split("        run: |\n")
SHELL = textwrap.dedent(RUN_BLOCK)
PREFIX = "set +x\npython3 - <<'PY'\n"
SUFFIX = "PY\n"
if not SHELL.startswith(PREFIX) or not SHELL.endswith(SUFFIX):
    raise AssertionError("The dispatch step must contain only the trace-disabled inline Python.")
INLINE_PYTHON = SHELL[len(PREFIX):-len(SUFFIX)]
BRIDGE = types.ModuleType("private_push_bridge")
exec(compile(INLINE_PYTHON, str(WORKFLOW), "exec"), BRIDGE.__dict__)

# Any new step, action, trigger, permission or secret exposure must be reviewed.
EXPECTED_HEADER = """name: Request private APK build

on:
  push:
    branches:
      - feature/embedded-https-viewer

permissions: {}

concurrency:
  group: private-apk-dispatch-${{ github.sha }}
  cancel-in-progress: false

jobs:
  dispatch:
    if: >-
      github.event_name == 'push' &&
      github.ref == 'refs/heads/feature/embedded-https-viewer' &&
      github.repository == 'mark4z/DiPlay' &&
      github.repository_id == '1405948725' &&
      github.repository_owner == 'mark4z' &&
      github.repository_owner_id == '36187602' &&
      github.event.repository.private == false &&
      github.actor == 'mark4z' &&
      github.triggering_actor == 'mark4z' &&
      github.event.deleted == false
    runs-on: ubuntu-latest
    timeout-minutes: 2
    steps:
      - name: Send bounded private build request
        shell: bash
        env:
          DIPLAY_PRIVATE_BUILD_TOKEN: ${{ secrets.DIPLAY_PRIVATE_BUILD_TOKEN }}
"""

SHA = "a1" * 20
TOKEN = "synthetic-dispatch-token-never-a-real-credential"
PRIVATE_METADATA = "private-run-url-and-artifact-test-canary"
SUCCESS = "Private build request accepted.\n"
SKIP = "::notice::Private build request skipped: untrusted push context.\n"
ERROR = "::error::Private build request was not confirmed; no automatic retry was attempted.\n"


class PrivatePushBridgeTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.event_path = Path(self.temporary.name) / "push.json"
        self.environment = {
            "GITHUB_EVENT_PATH": str(self.event_path),
            "GITHUB_EVENT_NAME": "push",
            "GITHUB_REF": "refs/heads/feature/embedded-https-viewer",
            "GITHUB_SHA": SHA,
            "GITHUB_REPOSITORY": "mark4z/DiPlay",
            "GITHUB_REPOSITORY_ID": "1405948725",
            "GITHUB_REPOSITORY_OWNER": "mark4z",
            "GITHUB_REPOSITORY_OWNER_ID": "36187602",
            "GITHUB_ACTOR": "mark4z",
            "GITHUB_TRIGGERING_ACTOR": "mark4z",
            "DIPLAY_PRIVATE_BUILD_TOKEN": TOKEN,
        }
        # The intended public repository is itself an owner-controlled fork.
        self.event = {
            "ref": self.environment["GITHUB_REF"],
            "after": SHA,
            "deleted": False,
            "repository": {
                "id": 1405948725,
                "full_name": "mark4z/DiPlay",
                "fork": True,
                "private": False,
                "owner": {"login": "mark4z", "id": 36187602},
            },
        }
        self.event_path.write_text(json.dumps(self.event), encoding="utf-8")
        self.response = Mock(status=204)
        self.response.read.side_effect = AssertionError("Response bodies must never be read.")
        self.response.__enter__ = Mock(return_value=self.response)
        self.response.__exit__ = Mock(return_value=False)
        self.opener = Mock()
        self.opener.open.return_value = self.response
        self.factory = self.enterContext(patch.object(BRIDGE, "build_opener", return_value=self.opener))

    def run_bridge(self, environment=None, event=None):
        if event is not None:
            self.event_path.write_text(json.dumps(event), encoding="utf-8")
        output, errors = io.StringIO(), io.StringIO()
        with patch.dict(os.environ, environment or self.environment, clear=True):
            with redirect_stdout(output), redirect_stderr(errors):
                result = BRIDGE.main()
        text = output.getvalue() + errors.getvalue()
        self.assertNotIn(TOKEN, text)
        self.assertNotIn(PRIVATE_METADATA, text)
        self.assertNotIn("Traceback", text)
        self.assertNotIn("https://", text)
        self.assertNotIn(SHA, text)
        self.response.read.assert_not_called()
        return result, text

    def assert_skipped(self, environment=None, event=None):
        self.factory.reset_mock()
        self.opener.reset_mock()
        self.assertEqual(self.run_bridge(environment, event), (0, SKIP))
        self.factory.assert_not_called()
        self.opener.open.assert_not_called()

    def test_workflow_has_only_the_sealed_push_dispatch_step(self):
        self.assertEqual(HEADER, EXPECTED_HEADER)
        self.assertEqual(WORKFLOW_TEXT.count("${{ secrets."), 1)
        self.assertNotIn("${{", INLINE_PYTHON)
        self.assertNotIn("uses:", WORKFLOW_TEXT)
        self.assertEqual(SHELL, PREFIX + INLINE_PYTHON + SUFFIX)

    def test_trusted_owner_fork_posts_exact_sha_once_to_fixed_target(self):
        self.assertEqual(self.run_bridge(), (0, SUCCESS))
        self.opener.open.assert_called_once()
        args, kwargs = self.opener.open.call_args
        self.assertEqual(kwargs, {"timeout": 20})
        request, = args
        self.assertEqual(request.full_url, "https://api.github.com/repos/mark4z/DiPlay-private-build/actions/workflows/private-apk.yml/dispatches")
        self.assertEqual(request.get_method(), "POST")
        self.assertEqual(json.loads(request.data), {"ref": "main", "inputs": {"source_sha": SHA}})
        self.assertEqual(request.get_header("Authorization"), "Bearer " + TOKEN)
        self.assertEqual(request.get_header("Content-type"), "application/json")
        self.assertEqual(request.get_header("X-github-api-version"), "2022-11-28")
        handlers = self.factory.call_args.args
        self.assertEqual(len(handlers), 2)
        self.assertIsInstance(handlers[0], ProxyHandler)
        self.assertEqual(handlers[0].proxies, {})
        self.assertIsInstance(handlers[1], BRIDGE.NoRedirect)

    def test_current_api_success_ignores_private_response_body(self):
        self.response.status = 200
        self.response.headers = {"X-Private-Run": PRIVATE_METADATA}
        self.assertEqual(self.run_bridge(), (0, SUCCESS))
        self.opener.open.assert_called_once()

    def test_no_redirect_handler_refuses_all_redirect_statuses(self):
        handler = BRIDGE.NoRedirect()
        self.assertIsInstance(handler, HTTPRedirectHandler)
        for status in (301, 302, 303, 307, 308):
            with self.subTest(status=status):
                self.assertIsNone(handler.redirect_request(Mock(), Mock(), status, TOKEN,
                                                          {"Location": PRIVATE_METADATA}, "https://untrusted.invalid/"))

    def test_unexpected_status_fails_closed_without_retry(self):
        for status in (201, 202, 301, 302, 303, 307, 308, 400, 401, 403, 404, 422, 429, 500, 503):
            with self.subTest(status=status):
                self.opener.reset_mock()
                self.response.status = status
                self.assertEqual(self.run_bridge(), (1, ERROR))
                self.opener.open.assert_called_once()

    def test_http_errors_never_log_private_body_url_or_exception(self):
        for status in (302, 403, 404, 422, 429, 500):
            with self.subTest(status=status):
                body = Mock()
                body.read.side_effect = AssertionError("Error bodies must never be read.")
                self.opener.reset_mock()
                self.opener.open.side_effect = HTTPError("https://private.invalid/" + PRIVATE_METADATA,
                                                        status, TOKEN, {"Private": PRIVATE_METADATA}, body)
                self.assertEqual(self.run_bridge(), (1, ERROR))
                self.opener.open.assert_called_once()
                body.read.assert_not_called()

    def test_ambiguous_transport_errors_are_sanitized_and_not_retried(self):
        for exception in (URLError(TOKEN + PRIVATE_METADATA), TimeoutError(TOKEN),
                          OSError(TOKEN), ValueError(TOKEN), RuntimeError(TOKEN)):
            with self.subTest(exception=type(exception).__name__):
                self.opener.reset_mock()
                self.opener.open.side_effect = exception
                self.assertEqual(self.run_bridge(), (1, ERROR))
                self.opener.open.assert_called_once()

    def test_opener_setup_failure_is_sanitized(self):
        self.factory.side_effect = RuntimeError(TOKEN + PRIVATE_METADATA)
        self.assertEqual(self.run_bridge(), (1, ERROR))
        self.opener.open.assert_not_called()

    def test_missing_or_blank_token_warns_owner_and_skips_without_request(self):
        for token in (None, "", " \t\n"):
            with self.subTest(token=token):
                environment = dict(self.environment)
                environment.pop("DIPLAY_PRIVATE_BUILD_TOKEN")
                if token is not None:
                    environment["DIPLAY_PRIVATE_BUILD_TOKEN"] = token
                result, output = self.run_bridge(environment)
                self.assertEqual(result, 0)
                self.assertEqual(output, "::warning::Repository owner must configure the DIPLAY_PRIVATE_BUILD_TOKEN Actions secret before private builds can be requested.\nPrivate build request skipped: nothing was dispatched.\n")
                self.factory.assert_not_called()
                self.opener.open.assert_not_called()

    def test_environment_gates_reject_non_owner_replays_other_repos_and_events(self):
        cases = {
            "GITHUB_EVENT_NAME": ("pull_request", "pull_request_target", "workflow_dispatch", "schedule"),
            "GITHUB_REF": ("refs/heads/main", "refs/pull/1/merge", "refs/tags/feature/embedded-https-viewer"),
            "GITHUB_REPOSITORY": ("other/DiPlay", "mark4z/another-repo"),
            "GITHUB_REPOSITORY_ID": ("999",),
            "GITHUB_REPOSITORY_OWNER": ("other",),
            "GITHUB_REPOSITORY_OWNER_ID": ("999",),
            "GITHUB_ACTOR": ("other", "github-actions[bot]"),
            "GITHUB_TRIGGERING_ACTOR": ("other", "github-actions[bot]"),
            "GITHUB_SHA": ("", "0" * 40, "A1" * 20, "a1" * 19, "a1" * 21, "$(false)"),
        }
        for field, values in cases.items():
            for value in values:
                with self.subTest(field=field, value=value):
                    environment = dict(self.environment, **{field: value})
                    self.assert_skipped(environment)

    def test_each_required_environment_field_must_be_present(self):
        for field in self.environment:
            if field in ("GITHUB_EVENT_PATH", "DIPLAY_PRIVATE_BUILD_TOKEN"):
                continue
            with self.subTest(field=field):
                environment = dict(self.environment)
                environment.pop(field)
                self.assert_skipped(environment)

    def test_other_fork_or_reused_repository_name_is_rejected(self):
        for changes in ({"id": 999}, {"full_name": "other/DiPlay"}, {"private": True},
                        {"owner": {"login": "other", "id": 36187602}},
                        {"owner": {"login": "mark4z", "id": 999}}):
            with self.subTest(changes=changes):
                event = copy.deepcopy(self.event)
                event["repository"].update(changes)
                self.assert_skipped(event=event)

    def test_deleted_push_and_mismatched_event_head_or_ref_are_rejected(self):
        for changes in ({"deleted": True}, {"deleted": None}, {"deleted": 0},
                        {"ref": "refs/heads/main"}, {"after": "b2" * 20}):
            with self.subTest(changes=changes):
                self.assert_skipped(event=dict(self.event, **changes))

    def test_missing_event_fields_fail_closed(self):
        for field in self.event:
            with self.subTest(field=field):
                event = copy.deepcopy(self.event)
                event.pop(field)
                self.assert_skipped(event=event)
        for field in ("id", "full_name", "private", "owner"):
            with self.subTest(repository_field=field):
                event = copy.deepcopy(self.event)
                event["repository"].pop(field)
                self.assert_skipped(event=event)

    def test_malformed_event_shapes_do_not_raise_or_dispatch(self):
        for event in ([], "text", 1, {"repository": []}, {"repository": {"owner": []}}):
            with self.subTest(event=event):
                self.assert_skipped(event=event)

    def test_unreadable_or_invalid_event_fails_without_exposing_details(self):
        for content in (None, TOKEN + PRIVATE_METADATA):
            with self.subTest(content=content):
                if content is None:
                    self.event_path.unlink()
                else:
                    self.event_path.write_text(content, encoding="utf-8")
                result, output = self.run_bridge()
                self.assertEqual(result, 1)
                self.assertEqual(output, "::error::Private build request stopped: push context could not be verified.\n")
                self.factory.assert_not_called()

    def test_untrusted_context_is_checked_before_missing_secret(self):
        environment = dict(self.environment, GITHUB_ACTOR="other")
        environment.pop("DIPLAY_PRIVATE_BUILD_TOKEN")
        self.assert_skipped(environment)


if __name__ == "__main__":
    unittest.main()
