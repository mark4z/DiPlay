# Single / dual-TUN local HTTP experiment (v4)

Independent APK: `com.diplay.networkprobe`, **DiPlay Network Probe**, version 0.4-dual-tun. It does not replace DiPlay or use its identity files, audio/video, or CarPlay code. This is original code using public Android APIs; it contains no vendor implementation, credentials, native library, or certificate bundle.

## Scope and comparison

Nothing starts on launch. Every run needs fresh in-app confirmation, Android local-network permission on Android 17+, and Android VPN consent when the system requires it. Android can remember consent; the app cannot force that system dialog to reappear. Do not enable always-on or lockdown. Stop other VPNs yourself first, including DiPlay's wired VPN. Keep the app foreground, unlocked, and visible. Do not test while driving or on an untrusted network.

Both modes listen only at `http://100.96.23.17:18080/health`. GET/HEAD `/health` returns fixed public text, `diplay-network-probe health ok version=1`. No wildcard bind, port 80, automatic fallback, DNS, domain, TLS, forwarding, external connection, TUN read/write, or security-setting change is included.

- **SINGLE**, selected by default: establish `100.96.23.17/32` once.
- **DUAL_HANDOVER**, explicitly selected: establish primary `100.96.23.17/32`, then compatibility `192.168.247.2/32`. Keep only those original descriptors during this bounded manual test. No duplication, detached descriptors, process-level registry, or retention after Stop.

Each establishment uses exactly its own `/32` host route, `allowBypass()`, only this APK in the allowed-app list, and IPv6 fall-through. No default route or DNS server is installed. Failure to apply own-app restriction fails the run; it never broadens to other apps. The compatibility address is not an HTTP destination. All candidate addresses in the selected mode are checked before the first establishment against visible VPN networks, non-default routes, and interface subnets. Visibility can be incomplete; the addresses are not exclusive allocations. An overlap refuses the test, including leftover addresses owned by another app. Stop/force-stop the owning VPN app yourself or restart the device; the probe never removes another app's interfaces.

v3 used no explicit host route or `allowBypass`. v4 applies these same settings to both comparison modes. Therefore compare **v4 single against v4 dual** on the same device/hotspot/client; a difference from historical v3 results is not evidence that the second establish alone caused it.

Android documents only one active VPN interface, with the old interface deactivated after successful handover. Keeping its original descriptor temporarily does not promise that it remains UP or reachable. This deliberately bounded experiment observes the actual device behavior; it does not reactivate interfaces or claim a portable production solution. The old descriptor is closed at the end of this test along with every other owned resource.

## Observable results

The screen reports the selected session mode, interface snapshots after the primary establishment and after handover, listener status/bind errno, a bounded local self-check, external TCP accepts, and external HTTP health responses written. Interface snapshots include only the fixed test addresses, name/index and UP/DOWN. The original name/index is remembered during the session, so a missing address, absent interface, changed identity and read failure are distinct. Snapshot collection uses public Java networking APIs; `NOT_VISIBLE` means not visible through those APIs, not a packet trace or proof of universal kernel state.

After handover the probe attempts the same exact primary-address bind even if the first interface reports DOWN. It does not bind to compatibility/other addresses, alter flags, or weaken BPF/firewall policy. An `EADDRNOTAVAIL` or other bind failure is a legitimate result. The original errno/name is retained when available; unavailable errno is not guessed. A failed second establish is terminal: no fallback to a single-mode test.

The local self-check connects only to this session's exact primary address and 18080, with a 1.5-second connect timeout, a two-second total read deadline and independent four-second close watchdog. PASS proves local request/response only. It is excluded from external counters. Zero external accepts with self-check PASS narrows the symptom to the external TCP path; it does not identify an OEM/route/firewall cause. Responses written are not proof the browser received them. No request/peer address or packet data is logged or saved.

## Cancellation and cleanup

Startup has a 15-second watchdog; the five-minute session timer starts before setup. Stop, notification Stop, background/lock, rotation, system revoke, task removal, initialization failure and expiry all use one cancellation/ownership path. Public system calls cannot be forcibly interrupted: a descriptor returned after cancellation is registered and independently closed, and the app stays Stopping until all workers and resource closes finish. No new run is allowed while cleanup is pending. Original primary and compatibility descriptors, listener, incoming socket and self-check socket are independently closed, so one stalled close does not block the others. No cleanup work is placed on Android's main thread.

A foreground/unlocked check runs before establishment and every 500 ms during the session, in addition to Activity pause cancellation. Mode is locked through confirmation, system prompts, startup, operation and cleanup. Cancelled prompts/stale callbacks cannot start another mode. `START_NOT_STICKY`, manifest always-on opt-out, memory-only one-use grants, no boot receiver and no persisted configuration prevent automatic restoration.

After two seconds of delayed cleanup the UI displays `CLEANUP_PENDING` and recovery instructions. After every owned close finishes, a background snapshot checks all remembered interfaces in the selected mode. A close failure, a still-visible test interface or failed observation blocks another start and requests Android-settings disconnection/force-stop. It does not silently label that condition as fully cleaned up or keep extra descriptors alive. OS deletion can be asynchronous; this warning is conservative and is not proof a descriptor remains open.

## Human-operated device matrix

1. Install the APK yourself. Stop all other VPNs. Enable your hotspot, then open this app; opening alone must not start anything.
2. Use SINGLE first. Confirm the fixed URL and network-change warning. Personally handle any Android permission/VPN prompts. Keep the screen foreground and unlocked.
3. Record mode, interface snapshots, bind/listening result, exact error/errno, self-check, accepts and responses-written. From another phone on that hotspot manually visit `http://100.96.23.17:18080/health`. Do not let a browser silently change it to HTTPS; do not weaken browser security settings. Record the exact peer result.
4. Tap Stop. Confirm responsive Stopping, final result and after-close snapshot; the URL must no longer respond. A cleanup warning requires manual Android-settings/force-stop recovery rather than repeated Start.
5. Select DUAL_HANDOVER only after successful cleanup and explicitly confirm a new run. Repeat with the same hotspot/client/browser/URL. Record primary state before and after the second establish. No outside reachability is presumed.
6. For each mode test: consent Cancel, cancel then reopen, rotation while consent is pending, duplicate Start, background and lock during startup and operation, notification Stop, system revoke, task removal, force-stop/reopen, startup timeout and five-minute expiry. During dual setup especially exercise Stop/revoke/background while the second establish is pending. No cancelled or late result may start a server, leak an interface, or allow a stacked run.
7. Check failure paths: visible existing VPN; collision at either address; second establish rejection; listener failure; a simulated delayed/failed close in unit tests. Both descriptors must be released; second failure must never fall back to a single test.
8. Only after phone testing, repeat from the parked car's browser. This is still a device-specific HTTP experiment, not proof of TLS validity, media/WebRTC/WebSocket performance, or CarPlay compatibility.

## Verification

GitHub Actions **Local VPN health probe** runs synthetic JVM tests, Android lint and the independent source-only APK build, then rejects unexpected assets/native/credential payloads before uploading. Existing CarPlay modules/workflows are unchanged. No authenticated workflow, device VPN or actual networking is activated in CI. Local Python source/packaging checks complement those tests; no local Gradle build is used. Synthetic tests cover sequential descriptor ownership, late second establish after cancellation, second-establish failure without fallback, independent closes, identity snapshots, locked mode selection, request limits, self-check timeout/cancellation, and nested bind errno. They do not replace the device matrix or establish external reachability.

Primary references:
- https://developer.android.com/reference/android/net/VpnService.Builder#establish() (handover deactivates the old interface; descriptor lifecycle)
- https://developer.android.com/reference/android/net/VpnService.Builder (host routes, application allowlist, allowBypass and address-family behavior)
- https://developer.android.com/develop/connectivity/vpn (consent, lifecycle and always-on opt-out)
- https://developer.android.com/privacy-and-security/local-network-permission
