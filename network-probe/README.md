# Local HTTPS / ordinary-hotspot and VPN comparison (v5)

Independent APK: `com.diplay.networkprobe`, **DiPlay Network Probe**, version 0.5-local-https. It does not replace DiPlay or use its identity, media or CarPlay code. There are no bundled credentials, assets, native libraries or vendor implementations.

## Fixed endpoints

Every run explicitly chooses a transport and VPN mode; no automatic fallback or simultaneous listener:

- **HOTSPOT_NO_VPN (default):** `https://test.mark4z.asia:9999/health`, exact bind to the selected, revalidated real hotspot IPv4 address. No VPN consent API, VPN service or network configuration is invoked. The reported `10.18.0.8` is not hardcoded: select the current detected gateway and confirm it against a peer connected to the hotspot. HTTP comparison uses that selected address on `:18080/health`.
- **VPN HTTPS:** `https://tesla.mark4z.asia:9999/health`, exact listener bind `100.99.9.9:9999`; VPN HTTP comparison is `http://100.99.9.9:18080/health`.
- **SINGLE:** establish `100.99.9.9/32` once
- **DUAL_HANDOVER:** establish primary `100.99.9.9/32`, then compatibility `192.168.247.2/32`

GET/HEAD `/health` returns only fixed public text: `diplay-network-probe health ok version=1`. Other paths do not expose certificates, keys, files or diagnostics. No wildcard bind or compatibility-address listener. No DNS server, default route, forwarding, TUN read/write, interface reactivation, external request, or security-setting change.

An external browser must normally resolve the selected hostname to the selected mode’s listener IP: `test.mark4z.asia` to the current real hotspot gateway, or `tesla.mark4z.asia` to `100.99.9.9` for VPN modes. The app neither changes nor verifies public DNS. DNS mapping and a valid certificate do not establish hotspot reachability. HTTPS is a transport comparison, not a fix for the earlier v4 external TCP failures. v4 used `100.96.23.17`; compare v5 HTTP versus v5 HTTPS on the same device, then compare v5 single versus dual independently.

Ordinary-hotspot discovery is read-only and conservative: require an UP, non-loopback, non-point-to-point, nonvirtual Wi-Fi/AP-named interface with a broadcast RFC1918 IPv4 host address. Exclude interfaces/addresses managed by Android network objects (including upstream Wi-Fi/cellular) and refuse visible existing VPNs. Reject network/broadcast addresses. The selected exact name/index/address/prefix is rechecked before and after bind and during serving; disappearance/change ends the run. A candidate is not proof of tethering, so a person must confirm the peer’s gateway. Unsupported vendor interface naming can yield no candidate rather than binding another interface. The app does not set public DNS. Ordinary mode uses an Activity-owned foreground-only runner, with the same 15-second startup, five-minute session and independent cleanup rules, but creates no Android service or tunnel.

Each VPN establishment uses only its own `/32` host route, `allowBypass()`, this APK's allowlist and IPv6 fall-through. Failure to apply the allowlist aborts. Before establishing anything, both candidate addresses in dual mode (only primary in single) are checked against visible VPN networks, non-default routes and interface subnets. Existing/retained TeslaMirror or other VPN interfaces can conflict with `100.99.9.9`: stop their owning app yourself. The probe never removes another app's interfaces. Visibility is incomplete, and these addresses are not exclusive allocations.

Android normally deactivates the original interface after successful handover. Retaining its original descriptor during this bounded test does not guarantee that it remains UP or reachable. The experiment does not reactivate it or claim a portable production solution.

## Phone-only credential import

1. On the phone, use **Import fullchain + key**. Personally choose two local files using Android's Storage Access Framework: PEM fullchain first (leaf certificate first, followed by intermediates), then its private key. ZIP is not accepted.
2. Supported keys are unencrypted PKCS#1 RSA (`RSA PRIVATE KEY`) or PKCS#8 RSA/EC (`PRIVATE KEY`); RSA must be at least 2048 bits, EC at least 256 bits. No user conversion is needed for supported PKCS#1 RSA. Encrypted keys, SEC1 `EC PRIVATE KEY`, multi-prime RSA and other algorithms fail with a fixed error code.
3. Validation checks bounded PEM/DER, key structure, local signing/verifying proof of certificate-key match, dates, DNS SAN for the selected mode’s fixed hostname (`test.mark4z.asia` for ordinary hotspot; `tesla.mark4z.asia` for VPN modes; no CN fallback), leaf purpose and supplied chain order/signatures/CA constraints. It also requires the chain to reach a system trust anchor. It never installs a CA or accepts a self-signed/untrusted certificate. A valid single-label wildcard SAN can match the hostname. KU/EKU are enforced when present.
4. Only after validation and all provider-stream closes succeed does the new identity replace the current one. Cancel, malformed files, mismatches and read failures preserve the previous identity. Start/mode/protocol/import controls cannot stack operations. Import has a 15-second cancellation watchdog; a stalled provider close/open can delay final cleanup, with further imports/start blocked until it completes.
5. The identity remains in this Activity/process memory only. **Forget certificate**, ordinary backgrounding, lock/rotation or process termination drops it; import again when returning. System file-picker/VPN-permission pauses are the explicit exception. Stop removes the service's references after owned cleanup; the foreground Activity can retain its identity for the next comparison.

The app writes no credential file, preferences, saved instance state, key-bearing Intent or logs; it requests no persistent document URI grant. Backup is disabled. File-provider streams close on success, errors and cancellation, including late opens. Temporary byte arrays are zeroed where controllable; Java/provider/crypto objects may keep memory copies until garbage collection, so this is not a secure-memory erasure claim. Selected source files remain the user's responsibility. Use replacement credentials if a private key was ever publicly exposed.

The source, CI, APK and this development environment must never contain the user's actual private key. Synthetic test identities are generated at runtime only.

## HTTPS and diagnostics

Import trust validation is not a revocation, browser trust or Certificate Transparency guarantee. Android 17/API 37 enables CT by default; the hostname-aware self-check and external browser may reject a certificate under their normal policies. No policy opt-out or user CA trust expansion is included.

The TLS server uses the imported identity through an in-memory KeyStore/KeyManager and enables supported TLS 1.2/1.3 only; client authentication is not required. Private keys are used only for TLS authentication, never returned by the HTTP handler. Failed handshakes produce a fixed diagnostic without peer/request/key content.

The local self-check connects its raw socket to the numeric selected hotspot gateway (or `100.99.9.9` in VPN modes), then wraps it with the selected fixed domain (`test.mark4z.asia` or `tesla.mark4z.asia`) as SNI and verification hostname. It uses the default platform trust factory and HTTPS endpoint identification. There is no trust-all manager, disabled hostname verification or public-DNS dependency in that self-check. Wrong domain, expired or untrusted certificates fail normally. The raw and wrapped sockets are both session-owned. A 1.5-second connect limit, two-second socket/read limits and independent four-second close watchdog bound the check. PASS proves only the local TLS/request/response path, not external DNS, browser trust or hotspot reachability.

The screen shows mode, selected URL, import status (public key algorithm/expiry only), interface snapshots, bind errno, self-check, external TCP accepts and external health responses written. Self-check traffic is excluded from external counters. Responses written do not prove that a browser received them. No peer address or request/packet data is logged or saved.

## Cancellation and cleanup

Nothing starts on launch. Every run requires a fresh in-app confirmation, Android local-network permission on Android 17+, and VPN consent if Android requires it. Do not enable always-on or lockdown. Stop other VPNs first, including DiPlay's wired VPN. Keep the app foreground, unlocked and visible. Test only while parked and on a trusted network.

Startup has a 15-second watchdog; the five-minute timer begins before setup. Stop, notification Stop, background/lock, rotation, system revoke, task removal, failure and expiry share one ownership/cancellation path. Late descriptors are independently closed. Original primary/compatibility descriptors, listener, incoming client, raw self-check and layered TLS sockets are tracked. No descriptor duplication, detaching, registry, background restoration or post-test retention.

Stop never performs cleanup on Android's main thread. The app remains Stopping while workers or closes remain. After two seconds it reports `CLEANUP_PENDING`. Once owned closes finish, a background snapshot checks the remembered interfaces. A failed close, visible interface or failed observation blocks a new test and asks the user to disconnect in Android settings and force-stop. OS interface deletion can be asynchronous; this conservative warning is not proof that a descriptor remains open. No cleanup condition is silently called successful.

## Manual phone/device matrix

- Fresh launch and process restart: no listener/VPN/key; HTTPS Start disabled until a valid import. HTTP can run without importing a key.
- Import success: RSA PKCS#1, RSA PKCS#8, EC PKCS#8. Try wrong key, wrong host, expired/not-yet-valid, incomplete/reversed/untrusted chain, encrypted/SEC1, malformed/truncated/oversized file and provider/read failure. No failed replacement may discard the old valid identity or disclose input/error text.
- Cancel at either picker, Back, repeat clicks, rotate/background/lock during validation, cancel after validation but before close, delayed stream close/open, and return/relaunch. No stale callback may replace credentials or start a VPN. A cancelled or hung task must not allow stacked imports.
- Ordinary hotspot first: read/select the actual gateway and verify it against the connected peer. Personally set `test.mark4z.asia` DNS to that current address, import its covering certificate/key, and test HTTPS. Compare HTTP if needed. No VPN prompt should appear. Stop and confirm the URL is closed before any next run.
- VPN SINGLE comparison: explicitly select HTTP or HTTPS, confirm, handle Android prompts personally. Record snapshots, listener bind/errno, local self-check, accepts/responses. For HTTPS use the hostname URL in the external browser; do not bypass certificate warnings or navigate to the numeric IP as a substitute for a hostname certificate.
- Stop; verify prompt responsive cleanup and URL no longer responding. A cleanup warning requires manual recovery. Only then run the other transport or DUAL_HANDOVER, explicitly confirmed, keeping peer/browser/hotspot unchanged.
- In each mode exercise consent Cancel, stale/duplicate starts, background/lock/startup, notification Stop, revoke, task removal, force-stop, startup timeout and five-minute expiry. Stop during the second establish must close both original descriptors, even when establishment returns late. A second-establish failure must not fall back to SINGLE.
- Check existing VPN/address collisions, primary bind failure, TLS handshake rejection and incomplete cleanup. First complete phone-to-phone tests; only then repeat from the parked car browser. Device reachability and Android lifecycle behavior are not established by synthetic tests.

## Verification

No local Gradle build. Authorized GitHub Actions runs the existing credential guard, Python guard tests, JVM tests, Android lint, source-only APK build and APK payload guard; it must reject credential containers and bundled assets/native payloads before upload. The fixture code generates synthetic certificates/private keys at runtime rather than embedding PEM or identity files. Tests never activate a real Android VPN or use the user's actual credentials.

For the prepared, unpublished v5 changes, local source/packaging checks and pure-Java compiler/harness results are reported separately from unrun Android lint/build/device checks. The user authorized publishing the credential-free changes to the existing experiment branch and using Actions. The exact remote commit and terminal CI outcome must be checked before calling the APK verified.

References:
- https://developer.android.com/training/data-storage/shared/documents-files
- https://docs.oracle.com/en/java/javase/11/docs/api/java.base/javax/net/ssl/SSLParameters.html
- https://docs.oracle.com/en/java/javase/11/docs/api/java.base/javax/net/ssl/SSLSocketFactory.html
- https://developer.android.com/reference/android/net/VpnService.Builder#establish()
- https://developer.android.com/develop/connectivity/vpn

- https://developer.android.com/privacy-and-security/security-config#CertificateTransparency
