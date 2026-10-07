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
        self.assertIn('server.bind(new InetSocketAddress(address, ProbePolicy.PORT), 1)', source)
    def test_service_never_auto_starts_and_disposes_on_terminal_paths(self):
        source = (JAVA / "ProbeVpnService.java").read_text()
        self.assertIn('START_NOT_STICKY', source)
        self.assertNotIn('START_STICKY;', source)
        self.assertIn('GATE.consume(', source)
        for code in ('SYSTEM_REVOKED', 'TASK_REMOVED', 'TIME_LIMIT', 'BIND_FAILED', 'ESTABLISH_REJECTED'):
            self.assertIn(code, source)
        for close in ('client.close()', 'server.close()', 'tun.close()'):
            self.assertIn(close, source)
        manifest = ET.parse(ROOT / "network-probe/src/main/AndroidManifest.xml").getroot()
        ns = '{http://schemas.android.com/apk/res/android}'
        service = manifest.find('application/service')
        self.assertEqual('false', service.get(ns + 'exported'))
        self.assertEqual('false', service.find('meta-data').get(ns + 'value'))
        self.assertIsNone(manifest.find('application/receiver'))
    def test_screen_exit_and_android17_permissions_are_guarded(self):
        source = (JAVA / "ProbeActivity.java").read_text()
        self.assertIn('onPause()', source)
        self.assertIn('stopService(new Intent(this, ProbeVpnService.class))', source)
        self.assertIn('LOCAL_NETWORK_PERMISSION_DENIED', source)
        self.assertIn('CONSENT_CANCELLED', source)
        self.assertIn('checkSelfPermission("android.permission.ACCESS_LOCAL_NETWORK")', source)
