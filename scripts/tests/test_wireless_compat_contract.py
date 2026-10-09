"""Source contracts for the narrow upstream wireless port.

Behavioral coverage lives in the Kotlin network tests, including SDK 28/29/30
Robolectric coverage. These guards run without an Android build and do not
substitute for CI or parked-device iPhone/receiver tests.
"""
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[2]
JAVA = ROOT / "shared/src/main/java/com/shilapi/xcertplay"


def between(source, start, end):
    return source.split(start, 1)[1].split(end, 1)[0]


class WirelessCompatContractTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.manual = (JAVA / "network/ManualHotspotManager.kt").read_text()
        cls.existing = (JAVA / "network/ExistingWifiManager.kt").read_text()
        cls.controller = (JAVA / "orchestration/CarPlayController.kt").read_text()

    def test_manual_ap_discovery_uses_selected_interface_and_scope(self):
        self.assertIn("NetworkInterface.getByName(selected.name)", self.manual)
        self.assertIn("existingWifiHostAddresses(Collections.list(it.inetAddresses), selected.index)", self.manual)
        self.assertIn("?: listOfNotNull(localInterface.hostAddress)", self.manual)
        self.assertIn("hostAddress = localInterface.hostAddress,", self.manual)
        self.assertIn("hostAddresses = hostAddresses,", self.manual)

    def test_advertised_secondary_addresses_have_matching_airplay_listeners(self):
        additional = "hotspotInfo.hostAddresses.filter { it != hostAddress }"
        self.assertIn("additionalBindAddresses = " + additional, self.controller)
        self.assertIn("additionalAddresses = " + additional, self.controller)

    def test_existing_lan_clear_capabilities_is_api_30_guarded(self):
        helper = self.existing.split("private fun requestWithoutDefaultCapabilities()", 1)[1]
        modern, legacy = helper.split("} else {", 1)
        self.assertIn("Build.VERSION.SDK_INT >= Build.VERSION_CODES.R", modern)
        self.assertIn("NetworkRequest.Builder().clearCapabilities()", modern)
        self.assertNotIn("clearCapabilities()", legacy)
        for capability in ("INTERNET", "NOT_RESTRICTED", "TRUSTED"):
            self.assertIn(".removeCapability(NetworkCapabilities.NET_CAPABILITY_" + capability + ")", legacy)
        self.assertIn("connectivity.registerNetworkCallback(requestWithoutDefaultCapabilities()", self.existing)
        self.assertIn(".addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN).build(), callback)", self.existing)

    def test_minimum_android_and_native_platform_remain_28(self):
        for module in ("mobile", "common"):
            self.assertIn("minSdk = 28", (ROOT / module / "build.gradle.kts").read_text())
        shared = (ROOT / "shared/build.gradle").read_text()
        self.assertIn("minSdk = 28", shared)
        self.assertIn("APP_PLATFORM=android-28", shared)



if __name__ == "__main__":
    unittest.main()
