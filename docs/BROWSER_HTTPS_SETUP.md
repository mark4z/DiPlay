# Home-screen HTTPS setup

The DiPlay home screen now exposes the HTTPS certificate import and service controls before connecting CarPlay. The existing HTTPS detail screen uses the same controller.

## Setup

1. While parked on a trusted private network, choose **Import local certificate ZIP** on the Android home screen. Select the original nginx ZIP yourself on that device. The app checks the archive bounds, certificate trust, current validity, fixed hostname, and matching private key.
2. Confirm parked/trusted-network use and enable **HTTPS service**. Android VPN consent and, where required, local-network permission remain interactive system prompts. Declining either does not loop or silently retry in the same foreground visit.
3. After the TLS self-check passes, connect CarPlay. Open `https://tesla.mark4z.asia:9999/` in the car browser. The embedded page requests a same-origin connection automatically; the default Android behavior is still a per-connection approval.
4. Optionally enable **Automatically allow browser connections** after reading the LAN disclosure. This is off by default. While HTTPS is running, ready and DiPlay is in front, a valid browser connection is allowed with an Android notice. This is not device identity verification: any reachable device on that trusted LAN may try to connect.
5. Optionally enable **Start HTTPS when I open DiPlay** after its separate disclosure. This is also off by default. It uses the saved certificate; it cannot bypass a missing system VPN/local-network grant. A manual Stop or denied prompt suppresses another automatic attempt during that visit. Boot-launched activities are explicitly excluded; tap the launcher for a later normal app-open attempt. There is no boot receiver or sticky-service restoration for HTTPS.

The Android connection permission does not manufacture a browser playback gesture, activate audio playback, or grant touch ownership. Audio playback and touch retain their separate browser controls. Disabling automatic approval applies to subsequent requests; Stop revokes the current session.

## Local storage and deletion

- Only the user-selected local content URI is read. No persistable URI grant, upload, export, private-key log or bundled identity is introduced.
- After complete validation, the bounded ZIP is encrypted with AES-256-GCM using an app-scoped, non-exportable Android Keystore key. Only authenticated ciphertext is written to `Context.noBackupFilesDir`.
- A unique encrypted staging file is fsynced before atomic same-directory replacement. Commit happens only after every provider resource has closed successfully. Cancellation, invalid input, failed close or recreation retains the prior identity. Cancelled/deleted tickets cannot commit late.
- Each load authenticates/decrypts and revalidates the ZIP. A shared revision invalidates stale in-memory identity caches across home/detail screens after replace/delete.
- The two small option flags live in a separate no-backup AtomicFile and default to false on missing, corrupt or unreadable data.
- **Stop** shuts down the listener and both VPN descriptors but retains the saved identity and option choices. **Delete saved certificate and settings** stops HTTPS, invalidates pending operations, removes active/staging encrypted files, deletes the Keystore alias, and independently resets both settings. Keep the original ZIP yourself if you may need another import.
- Uninstalling/clearing app data loses the saved setup. Device/Keystore failure may require reimport. No backup or migration route is provided.

## Foreground lifetime and network boundaries

The DiPlay home, CarPlay host and HTTPS detail screen jointly own the visible session. A bounded 700 ms handoff grace covers internal navigation/configuration recreation; leaving DiPlay stops the service. Approval still requires a resumed Android UI, so no new browser can be approved during a background or activity gap. Existing native CarPlay lifecycle behavior is unchanged.

The service remains non-exported and START_NOT_STICKY. A one-use in-process grant plus current foreground state and VPN permission is required to start it. The existing two `/32` interfaces, fixed bind/Host/Origin/SNI policy, trusted TLS self-check, bounded request deadlines, one-client limit, explicit touch ownership, Stop/revoke cleanup and no-DNS/default-route/forwarding policy are retained. The auto-approval option is consulted only after the server's handshake gates.

Automatic permission is recorded as `AUTO_APPROVED`, never as `PROMPT_SHOWN`. The notification says a connection was allowed, not that media or playback already succeeded.

## Verification

New synthetic Android tests cover encrypted storage, cancellation/delete races, atomic-replace failure, malformed storage and defaults, cross-screen revision invalidation, foreground handoff, consent/Stop suppression and automatic approval without dialog or touch ownership. Existing Origin/Host/approval/single-client tests remain in place. Android tests/lint/build run in GitHub Actions; no local Gradle execution or real TLS private-key handling is required.

Physical acceptance is still required for Android Keystore behavior, VPN consent/revoke, hotspot/Tesla reachability, background/return, certificate replacement/deletion, and WebRTC audio handoff. The local TLS self-check alone does not prove Tesla reachability or audio playback.
