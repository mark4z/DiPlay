"""Source guards complement Android home UI tests; no Android toolchain required."""
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[2]
JAVA = ROOT / 'common/src/main/java/com/shilapi/xcertplay'


class BackendHomeLifecycleTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.host = (JAVA / 'CarPlayHostActivity.kt').read_text()
        cls.home = (JAVA / 'DiPlayActivity.kt').read_text()

    def test_backend_no_longer_covers_progress_with_black_overlay(self):
        self.assertNotIn('No local picture or sound. You can turn off the screen.', self.host)
        self.assertIn('stageStatusView = stage', self.host)

    def test_returns_home_only_after_start_and_keeps_controller_owner(self):
        start = self.host.split('private fun startCarPlay(size: DisplaySize)', 1)[1]
        self.assertLess(start.index('startForegroundService('), start.index('returnBackendToHomeWhenReady()'))
        self.assertLess(start.index('next.start()'), start.index('returnBackendToHomeWhenReady()'))
        redirect = self.host.split('private val returnBackendToHome = Runnable {', 1)[1].split('private fun showDiPlayHome', 1)[0]
        self.assertIn('backendMode && isActivityStarted', redirect)
        self.assertIn('CarPlayBackgroundSession.isOwner(this)', redirect)
        self.assertIn('showDiPlayHome()', redirect)
        self.assertNotIn('\n            finish()', redirect)
        self.assertNotIn('shutdown(', redirect)

    def test_stopped_host_cancels_navigation_without_stopping_session(self):
        stop = self.host.split('override fun onStop()', 1)[1].split('/**', 1)[0]
        self.assertIn('removeCallbacks(returnBackendToHome)', stop)
        self.assertNotIn('shutdown(', stop)
        self.assertNotIn('finish()', stop)

    def test_backend_back_does_not_reveal_retained_host(self):
        self.assertIn('else if (backendSession() && CarPlayBackgroundSession.hasSession()) moveTaskToBack(true)', self.home)

    def test_both_home_layouts_route_the_primary_action(self):
        self.assertEqual(self.home.count('if (CarPlayBackgroundSession.hasSession()) openCurrentSession()'), 2)
        action = self.home.split('private fun openCurrentSession()', 1)[1].split('private fun openProjection()', 1)[0]
        self.assertIn('if (backendSession()) connect(AirPlayPersistence.loadWirelessEnabled(this))', action)
        self.assertIn('else openProjection()', action)
        self.assertIn('backend -> R.string.retry_carplay_connection', self.home)


if __name__ == '__main__':
    unittest.main()
