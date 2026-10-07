"""Source invariants complement JVM tests; these do not emulate Android lifecycle."""
from pathlib import Path
import unittest
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[2]
JAVA = ROOT / "network-probe/src/main/java/com/diplay/networkprobe"

class ProbeBoundaryTests(unittest.TestCase):
    def test_no_forwarding_route_dns_or_other_apps(self):
        source = (JAVA / "ProbeVpnService.java").read_text()
        for forbidden in (".addDnsServer(", ".addDisallowedApplication(", "FileInputStream(", "FileOutputStream("):
            self.assertNotIn(forbidden, source)
        self.assertIn('.addAddress(address, 32).addRoute(address, 32)', source)
        self.assertEqual(1, source.count('.addRoute('))
        self.assertIn('.allowBypass()', source)
        self.assertIn('.addAllowedApplication(getPackageName())', source)
        self.assertIn('.allowFamily(OsConstants.AF_INET6)', source)
        self.assertIn('ProbeListener.bind(listener, port, current::isCancelled)', source)
        listener = (JAVA / 'ProbeListener.java').read_text()
        self.assertIn('InetAddress.getByName(ProbePolicy.ADDRESS)', listener)
        self.assertIn('listener.bind(new InetSocketAddress(address, port), 1)', listener)
    def test_service_never_auto_starts_and_disposes_on_terminal_paths(self):
        source = (JAVA / "ProbeVpnService.java").read_text()
        self.assertIn('START_NOT_STICKY', source)
        self.assertNotIn('START_STICKY;', source)
        self.assertIn('GATE.consume(', source)
        for code in ('SYSTEM_REVOKED', 'TASK_REMOVED', 'TIME_LIMIT', 'STARTUP_TIMEOUT', 'BACKGROUND_OR_LOCKED'):
            self.assertIn(code, source)
        for resource in ('current.own(listener)', 'current.own(incoming)', 'current.own(localClient)'):
            self.assertIn(resource, source)
        self.assertIn('session.cancel()', source)
        handover = (JAVA / 'ProbeHandover.java').read_text()
        self.assertIn('session.own(descriptor)', handover)
        self.assertIn('_ESTABLISH_REJECTED', handover)
        owner = (JAVA / "ProbeSession.java").read_text()
        self.assertIn('resource.close()', owner)
        self.assertIn('cleanup.execute(() -> closeTracked(resource))', owner)
        self.assertIn('workers != 0 || closing != 0', owner)
        manifest = ET.parse(ROOT / "network-probe/src/main/AndroidManifest.xml").getroot()
        ns = '{http://schemas.android.com/apk/res/android}'
        service = manifest.find('application/service')
        self.assertEqual('false', service.get(ns + 'exported'))
        self.assertEqual('false', service.get(ns + 'stopWithTask'))
        self.assertEqual('false', service.find('meta-data').get(ns + 'value'))
        self.assertIsNone(manifest.find('application/receiver'))
    def test_screen_exit_and_android17_permissions_are_guarded(self):
        source = (JAVA / "ProbeActivity.java").read_text()
        self.assertIn('onPause()', source)
        self.assertIn('startService(new Intent(this, ProbeVpnService.class).setAction(ProbeVpnService.STOP))', source)
        self.assertIn('if (!resumed)', source)
        self.assertIn('requestStop()', source)
        self.assertIn('LOCAL_NETWORK_PERMISSION_DENIED', source)
        self.assertIn('CONSENT_CANCELLED', source)
        self.assertIn('checkSelfPermission("android.permission.ACCESS_LOCAL_NETWORK")', source)


    def test_io_setup_and_close_are_not_in_main_thread_lifecycle(self):
        source = (JAVA / "ProbeVpnService.java").read_text()
        start = source.split('private void startAndServe')[0]
        stop = source.split('private void stopProbe')[1]
        for blocked in ('.establish()', '.bind(', '.close()', 'rejectConflicts(mode);'):
            self.assertNotIn(blocked, start)
            self.assertNotIn(blocked, stop)
        self.assertIn('startWorker(() -> startAndServe(current, port, mode, interfaces)', start)
        self.assertIn('CLEANUP_PENDING_', source)

    def test_local_check_is_fixed_bounded_and_separate_from_peer_counts(self):
        check = (JAVA / "ProbeSelfCheck.java").read_text()
        service = (JAVA / "ProbeVpnService.java").read_text()
        self.assertIn('new InetSocketAddress(address, port), CONNECT_TIMEOUT_MS', check)
        self.assertIn('System.nanoTime() + READ_TIMEOUT_MS', check)
        self.assertIn('main.postDelayed(checkTimeout, ProbeSelfCheck.TOTAL_TIMEOUT_MS)', service)
        self.assertIn('current.closeAsync(localClient)', service)
        self.assertIn('boolean external = !check.isOwnConnection(incoming)', service)

    def test_mode_is_explicit_locked_and_preserved_through_consent(self):
        ui = (JAVA / "ProbeActivity.java").read_text()
        service = (JAVA / "ProbeVpnService.java").read_text()
        self.assertIn('selectedMode = ProbePolicy.SINGLE', ui)
        self.assertIn('pendingMode = selectedMode;', ui)
        self.assertIn('int mode = pendingMode;\n        cancelPendingStart();', ui)
        self.assertIn('.putExtra(ProbeVpnService.MODE_EXTRA, mode)', ui)
        self.assertIn('single.setEnabled(ready)', ui)
        self.assertIn('dual.setEnabled(ready)', ui)
        self.assertIn('!ProbeVpnService.GATE.hasPending()', ui)
        self.assertIn('!pending && !ProbeVpnService.running && !ProbeVpnService.recoveryRequired', ui)
        self.assertIn('pendingMode = 0;', ui)
        self.assertIn('final int mode = intent.getIntExtra(MODE_EXTRA, 0)', service)
        self.assertIn('if (!ProbePolicy.isTestMode(mode))', service)
        self.assertIn('final int port = ProbePolicy.DEFAULT_PORT', service)
        self.assertIn('sessionMode = mode;', service)
        self.assertIn('new ProbeSelfCheck(port)', service)
        self.assertIn('ProbePolicy.modeName(ProbeVpnService.sessionMode)', ui)

    def test_foreground_timeouts_and_all_descriptor_cleanup_remain_bounded(self):
        service = (JAVA / "ProbeVpnService.java").read_text()
        self.assertIn('main.postDelayed(startupTimeout, ProbePolicy.STARTUP_TIMEOUT_MS)', service)
        self.assertIn('main.removeCallbacks(startupTimeout)', service)
        self.assertIn('main.removeCallbacks(foregroundCheck)', service)
        self.assertIn('power.isInteractive()', service)
        self.assertIn('!keyguard.isKeyguardLocked()', service)
        self.assertIn('ProbeActivity.isForeground', service)
        self.assertIn('String[] candidates = ProbePolicy.addresses(mode)', service)
        self.assertIn('AFTER_ALL_OWNED_CLOSES', service)
        self.assertIn('afterClose.visible || afterClose.failed', service)
        for path in JAVA.glob('*.java'):
            for forbidden in ('.dup(', '.detachFd(', 'SharedPreferences', 'FileOutputStream('):
                self.assertNotIn(forbidden, path.read_text())

    def test_bind_error_keeps_real_errno_and_no_fallback_or_privilege_change(self):
        service = (JAVA / "ProbeVpnService.java").read_text()
        listener = (JAVA / "ProbeListener.java").read_text()
        self.assertIn('catch (IOException | SecurityException failure)', service)
        self.assertIn('ProbeListener.failureDescription(port, failure', service)
        self.assertIn('((ErrnoException) cause).errno', service)
        self.assertIn('OsConstants.errnoName(errno)', service)
        self.assertIn('errno=UNAVAILABLE', listener)
        self.assertEqual(1, listener.count('listener.bind('))
        self.assertNotIn('catch (', listener.split('static String failureDescription')[0])
        for path in JAVA.glob('*.java'):
            source = path.read_text()
            for forbidden in ('Runtime.getRuntime()', 'ProcessBuilder(', 'ip_unprivileged_port_start', 'setcap', 'setenforce'):
                self.assertNotIn(forbidden, source)
