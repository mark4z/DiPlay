"""Keep private APK builds disconnected from public source automation."""
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[2]
WORKFLOWS = ROOT / ".github/workflows"


class PrivateBuildDisconnectedTest(unittest.TestCase):
    def test_automatic_bridge_workflow_is_removed(self):
        self.assertFalse((WORKFLOWS / "trigger-private-apk.yml").exists())

    def test_public_workflows_cannot_dispatch_private_builds(self):
        for workflow in sorted(WORKFLOWS.glob("*.y*ml")):
            with self.subTest(workflow=workflow.name):
                source = workflow.read_text(encoding="utf-8")
                self.assertNotIn("DiPlay-private-build", source)
                self.assertNotIn("DIPLAY_PRIVATE_BUILD_TOKEN", source)

    def test_ordinary_source_ci_remains_enabled(self):
        source = (WORKFLOWS / "android.yml").read_text(encoding="utf-8")
        self.assertIn("  push:\n", source)
        self.assertIn("feature/embedded-https-viewer", source)
        self.assertIn("  pull_request:\n", source)
        self.assertIn("node --test site/browser-carplay/tests/*.test.mjs", source)
        self.assertIn("python3 scripts/check_source_apk.py", source)


if __name__ == "__main__":
    unittest.main()
