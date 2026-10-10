"""Source guards for native setup boundaries; runtime behavior is covered by Robolectric."""
from pathlib import Path
import re
import unittest
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[2]
JAVA = ROOT / "common/src/main/java/com/shilapi/xcertplay"


def method(source, name):
    start = source.index("    private fun " + name + "(")
    following = re.search(r"\n    (?:private fun |override fun |private val |private companion object)", source[start + 1:])
    return source[start:start + 1 + following.start()] if following else source[start:]


class NativeSetupContractTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.host = (JAVA / "CarPlayHostActivity.kt").read_text()
        cls.home = (JAVA / "DiPlayActivity.kt").read_text()
        cls.permissions = (JAVA / "WirelessPermissions.kt").read_text()

    def test_permission_policy_is_used_only_by_the_existing_native_flow(self):
        policy = method(self.host, "requiredWirelessPermissions")
        self.assertIn("WirelessPermissions.required(wirelessHotspotMode, Build.VERSION.SDK_INT)", policy)
        request = method(self.host, "requestWirelessPermissions")
        self.assertIn("val permissions = requiredWirelessPermissions()", request)
        self.assertIn("checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED", request)
        self.assertIn("wirelessPermissions.launch(permissions.toTypedArray())", request)
        self.assertNotIn("grantPermissions", request)
        self.assertNotIn("requestPermissions", self.permissions)
        self.assertNotIn(".launch(", self.permissions)

    def test_local_network_request_is_version_gated_without_other_new_scopes(self):
        self.assertIn("if (sdkInt >= Build.VERSION_CODES.CINNAMON_BUN)", self.permissions)
        self.assertIn("permissions + Manifest.permission.ACCESS_LOCAL_NETWORK", self.permissions)
        self.assertNotIn("RECORD_AUDIO", self.permissions)
        self.assertNotIn("ACCESS_BACKGROUND_LOCATION", self.permissions)
        manifest = ET.parse(ROOT / "shared/src/main/AndroidManifest.xml").getroot()
        name = "{http://schemas.android.com/apk/res/android}name"
        self.assertEqual(1, sum(node.get(name) == "android.permission.ACCESS_LOCAL_NETWORK"
                               for node in manifest.findall("uses-permission")))

    def test_vpn_prepare_and_launch_fail_closed_for_expected_firmware_failures(self):
        request = method(self.host, "requestVpnConsent")
        self.assertEqual(2, request.count("catch (_: ActivityNotFoundException)"))
        self.assertEqual(2, request.count("catch (_: SecurityException)"))
        unavailable = method(self.host, "onVpnConsentUnavailable")
        self.assertIn("awaitingVpnConsent = false", unavailable)
        self.assertIn("vpnReady = false", unavailable)
        self.assertNotIn("maybeStartCarPlay", unavailable)
        self.assertNotIn(".launch(", unavailable)
        result = method(self.host, "onVpnConsentResult")
        self.assertLess(result.index("if (!awaitingVpnConsent) return"), result.index("vpnReady ="))
        self.assertIn("vpnReady = resultCode == RESULT_OK", result)

    def test_vpn_diagnostics_have_no_raw_exception_or_intent(self):
        unavailable = method(self.host, "onVpnConsentUnavailable")
        self.assertIn("Log.w(TAG, diagnostic)", unavailable)
        self.assertIn("appendLog(diagnostic)", unavailable)
        for forbidden in (".message", "flattenToString", "stackTrace", "toUri(", "Log.w(TAG, diagnostic,"):
            self.assertNotIn(forbidden, unavailable)

    def test_manual_credentials_keep_explicit_security_until_outer_save(self):
        save = method(self.home, "saveHotspotCredentials")
        self.assertIn("AirPlayPersistence.saveManualHotspotSecurity(this, security)", save)
        self.assertNotIn("securityFor(password)", save)
        dialog = method(self.home, "askHotspotCredentials")
        self.assertIn("var protectedSecurity = AirPlayPersistence.loadManualHotspotSecurity(this)", dialog)
        self.assertIn("var pendingSecurity = protectedSecurity", dialog)
        self.assertIn("protectedSecurity = pendingSecurity", dialog)
        self.assertIn("if (secret.isEmpty()) ManualHotspotSecurity.OPEN else protectedSecurity", dialog)
        self.assertIn("done(name, secret, security)", dialog)
        self.assertNotIn("AirPlayPersistence.save", dialog)

    def test_same_lan_keeps_its_separate_credential_path(self):
        controls = method(self.home, "wirelessLinkControls")
        self.assertEqual(2, controls.count("askHotspotCredentials(existingWifi = true) { ssid, password, _ ->"))
        self.assertEqual(2, controls.count("AirPlayPersistence.saveExistingWifiCredentials(this, ssid, password)"))
        self.assertIn("if (!existingWifi)", method(self.home, "askHotspotCredentials"))

    def test_credential_save_still_applies_on_next_connection(self):
        apply = method(self.home, "applyWirelessLink")
        self.assertIn("saved_for_your_next_connection", apply)
        self.assertNotIn("connect(", apply)
        self.assertNotIn("restartCarPlay(", apply)
        self.assertNotIn("markReconnectNeeded", method(self.home, "saveHotspotCredentials"))

    def test_vpn_recovery_message_is_available_in_every_shipped_locale(self):
        for locale in ("values", "values-ar", "values-es", "values-ru", "values-uk", "values-zh-rCN", "values-zh-rTW"):
            root = ET.parse(ROOT / "common/src/main/res" / locale / "strings.xml").getroot()
            found = [node for node in root.findall("string") if node.get("name") == "vpn_authorization_unavailable"]
            self.assertEqual(1, len(found), locale)
            self.assertTrue(found[0].text, locale)
        self.assertIn("message == getString(R.string.vpn_authorization_unavailable) -> message", self.host)


if __name__ == "__main__":
    unittest.main()
