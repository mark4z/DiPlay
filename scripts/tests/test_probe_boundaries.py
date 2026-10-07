"""Source invariants complement JVM tests; these do not emulate Android lifecycle."""
from pathlib import Path
import unittest
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[2]
JAVA = ROOT / "network-probe/src/main/java/com/diplay/networkprobe"

class ProbeBoundaryTests(unittest.TestCase):
    def test_no_forwarding_route_dns_or_other_apps(self):
        source = (JAVA / "ProbeVpnService.java").read_text()
        for forbidden in (".addRoute(", ".addDnsServer(", ".addDisallowedApplication(", "FileInputStream(", "FileOutputStream("):
            self.assertNotIn(forbidden, source)
        self.assertIn('.addAddress(ProbePolicy.ADDRESS, 32)', source)
        self.assertIn('.addAllowedApplication(getPackageName())', source)
        self.assertIn('.allowFamily(OsConstants.AF_INET6)', source)
        self.assertIn('listener.bind(new InetSocketAddress(address, ProbePolicy.PORT), 1)', source)
    def test_service_never_auto_starts_and_disposes_on_terminal_paths(self):
        source = (JAVA / "ProbeVpnService.java").read_text()
        self.assertIn('START_NOT_STICKY', source)
        self.assertNotIn('START_STICKY;', source)
        self.assertIn('GATE.consume(', source)
        for code in ('SYSTEM_REVOKED', 'TASK_REMOVED', 'TIME_LIMIT', 'BIND_FAILED', 'ESTABLISH_REJECTED'):
            self.assertIn(code, source)
        for resource in ('current.own(tun)', 'current.own(listener)', 'current.own(incoming)', 'current.own(localClient)'):
            self.assertIn(resource, source)
        self.assertIn('session.cancel()', source)
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
        for blocked in ('.establish()', '.bind(', '.close()', 'rejectConflicts();'):
            self.assertNotIn(blocked, start)
            self.assertNotIn(blocked, stop)
        self.assertIn('startWorker(() -> startAndServe(current)', start)
        self.assertIn('CLEANUP_PENDING_', source)

    def test_local_check_is_fixed_bounded_and_separate_from_peer_counts(self):
        check = (JAVA / "ProbeSelfCheck.java").read_text()
        service = (JAVA / "ProbeVpnService.java").read_text()
        self.assertIn('new InetSocketAddress(address, ProbePolicy.PORT), CONNECT_TIMEOUT_MS', check)
        self.assertIn('System.nanoTime() + READ_TIMEOUT_MS', check)
        self.assertIn('main.postDelayed(checkTimeout, ProbeSelfCheck.TOTAL_TIMEOUT_MS)', service)
        self.assertIn('current.closeAsync(localClient)', service)
        self.assertIn('boolean external = !check.isOwnConnection(incoming)', service)
