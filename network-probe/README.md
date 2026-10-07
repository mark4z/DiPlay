# Local virtual-address health experiment

Independent APK: `com.diplay.networkprobe`, label **DiPlay Network Probe**. It does not replace DiPlay, depend on its identity files, or modify its CarPlay/audio/video code. This branch starts at `9c768e4dc277b7396a55a3546d7cf28328bcf3ce`, deliberately excluding the unfinished identity-import work.

## What it does

After a fresh explicit in-app confirmation, local-network permission on Android 17+, and any Android VPN consent, `VpnService.Builder.addAddress("100.96.23.17", 32)` creates a temporary address. The service binds only that address on TCP port 18080 and serves GET/HEAD `/health`. The response is fixed public text. There are no routes, DNS settings, remote connections, TUN reads/writes, packet logs, forwarding, authentication files, or domain/certificate operations. Only this APK is placed on the VPN allowed-app list. IPv6 is allowed to fall through rather than implicitly blocked. The fixed shared-address-space address is experimental, not a claim of exclusive allocation or Tesla compatibility.

The service refuses VPNs visible to Android's ConnectivityManager and overlapping visible non-default routes/interface subnets. Android/OEM visibility can be incomplete, so a warning is still required. **Stop other VPNs yourself first, including DiPlay wired VPN.** Never enable always-on or lockdown for this experiment. Always-on is opted out in the manifest. START_NOT_STICKY, a memory-only one-use start grant, no boot receiver, and no persisted configuration prevent automatic restart. Android may remember its permission, but every run still requires a fresh in-app confirmation.

Version 0.2 moves service-side conflict checks, VPN establishment, socket work and cleanup off Android's main thread. Normal Activity consent/preflight and foreground-service framework calls still use Android's main-thread lifecycle. Stop sends an explicit cancellation command to the VPN service; it does not rely on `stopService()` causing `onDestroy()`, because Android also binds to an established VPN. A session remains “Stopping” until all I/O workers exit and owned resources finish closing. Socket and VPN-descriptor cleanup run independently. A descriptor returned by a delayed VPN establish after Stop is also closed. Start stays disabled during startup or pending cleanup; any failed close requires Android-settings recovery and a force-stop before a fresh run.

If stopping takes more than two seconds, the screen reports `CLEANUP_PENDING` and asks you to disconnect the VPN in Android settings and force-stop the app if necessary. A stalled OEM/system call cannot be forcibly interrupted safely by Java; “Stopped” is not shown before cleanup completes. No fix here establishes external reachability or changes routes.

A background self-check requests only this session's exact local `/health` endpoint: 1.5-second connect timeout, 2-second total read deadline, and a separate 4-second watchdog that requests socket closure off the main thread. Its result is separate from hotspot-client accept/response counts. `PASS` proves only local request/response handling. `PASS` with zero external accepts points to the hotspot-to-virtual-address TCP path, which still needs device-specific investigation; it does not establish which route/firewall/OEM rule failed. `CONNECT_TIMEOUT`, `READ_TIMEOUT`, `TOTAL_TIMEOUT`, `RESPONSE_MISMATCH`, and `IO_FAILED` identify the local-check outcome without retaining packet data or peer addresses.

Keep the app visible and the screen unlocked during testing. Backgrounding, locking, or rotating stops the experiment rather than keeping a sleeping network change alive. Stop, notification Stop, system revoke, task removal, failed initialization and the five-minute limit request cancellation and independent closure of the listener and descriptor. The five-minute timer starts before background initialization, so it also covers slow setup. A normal process kill lets the OS close its descriptors; the app never restores the session. One client is handled at a time; request headers are capped at 4096 bytes with a two-second read timeout and deadline. Every connection closes after one response. No request body is consumed or URL reflected. No request or peer address is logged or persisted; the local self-check port exists only in memory to exclude that request from external-client counters.

## Manual device test (human-operated only)

1. Install the experiment APK yourself. Do not replace your existing DiPlay installation. Disconnect all other VPNs; do not use this while driving or on an untrusted network.
2. Enable your Android hotspot yourself. Open Network Probe. Nothing starts on launch.
3. Tap Start, read the network-change warning, confirm this run, and personally approve Android's local-network and VPN prompts if shown. Keep this screen visible and unlocked throughout. Cancellation must leave it stopped. If another VPN is detected, stop that VPN yourself before retrying.
4. Confirm the app separately reports the interface established and the socket listening. This does **not** prove outside reachability.
5. Connect a second phone to that hotspot. Manually type `http://100.96.23.17:18080/health`. Expected text: `diplay-network-probe health ok version=1`. Record the local self-check result and compare external TCP accepts and HTTP responses-written counters. Zero external accepts means the TCP connection did not reach the listener; a positive accept with no health response points to a later request/response step. A written response is not proof the remote browser received it. Do not weaken browser security settings.
6. Only after phone testing, repeat from the parked car's browser. Record the exact browser error, counters, and app result code. No TUN packets being read is expected; this experiment does not inspect TUN traffic.
7. Tap Stop; the screen must remain responsive while showing Stopping and then Stopped. Verify the URL no longer responds. If cleanup stalls, follow the displayed Android-settings/force-stop recovery instead of repeatedly starting. Repeat: Android consent Cancel, rotate while consent is pending, rapid duplicate Start, notification Stop, system VPN revoke, remove from recents, force-stop/reopen, and wait five minutes. All should require a fresh manual Start; reopening alone must not restart. Do not run DiPlay's VPN concurrently.
8. If the interface or bind fails, record the error code. Do not add broad routes or disable device/browser security as a workaround.

Success tests a specific device's local HTTP path only. It does not establish TLS certificate validity, Tesla address-range acceptance on other firmware, WebSocket/media performance or CarPlay compatibility.

## Verification

GitHub Actions `Local VPN health probe` runs synthetic Java tests, lint, and a source-only build for `:network-probe`, then checks its ZIP payload before uploading. No authenticated workflow, secrets, device VPN or actual network probe is run in CI. Existing CarPlay modules/workflows are untouched. Local Python tests only check packaging guards; Java/lint/build results require Actions. Resource-lifecycle unit tests cover blocked close, independent descriptor close, cancellation during establish, late resource registration, double-close races, multiple workers and close errors. Self-check tests cover the exact fixed destination, finite connect/read timeout settings, cancellation and response mismatch. Lifecycle and OEM networking behavior still need the manual matrix above; pure tests do not establish device reachability.

Primary API references:
- https://developer.android.com/reference/android/net/VpnService.Builder (addAddress, allowed applications, allowFamily)
- https://developer.android.com/develop/connectivity/vpn (consent, lifecycle, always-on opt-out)
- https://developer.android.com/privacy-and-security/local-network-permission (Android 17 inbound TCP permission)
- https://developer.android.com/develop/background-work/services/fgs/service-types (systemExempted VPN foreground service)

