# Experimental browser video, touch, and audio output

Status: source implementation, **not verified on Android/Tesla hardware**. Native
CarPlay remains the normal output. Browser output is off after process launch,
requires an explicit parked confirmation, and is never saved as a preference.

## Architecture and browser gate

An independently hosted **HTTPS** static page (`site/browser-carplay`) decodes
CarPlay's existing compressed H.264/HEVC access units with WebCodecs. The Android
app tees the main screen without decode/re-encode, preserving native video/audio.
Video, two-contact CarPlay touch, and explicitly selected decoded PCM audio are forwarded.
There is no browser microphone uplink, vehicle-control integration, internet media relay,
or bypass of driving restrictions.
CarPlay's current HID descriptor supports two contacts even if the browser supports more.

WebCodecs requires a secure context; visiting a second device's plain HTTP IP is
not sufficient. Chromium's [WebSocket LNA rollout discussion](https://groups.google.com/a/chromium.org/g/blink-dev/c/O6GMKt44Ups)
targets Chrome 147 and describes HTTPS pages connecting to explicit private-IP
`ws://` endpoints after the user grants Local Network Access. See the
[LNA integration draft](https://wicg.github.io/local-network-access/#integration-with-websockets)
and [WebCodecs specification](https://www.w3.org/TR/webcodecs/).
A Chromium version string or successful decoding of public samples is **not**
proof that a particular Tesla/browser build enables this permission/transport path.

The viewer fails closed if HTTPS, WebCodecs, decoder configuration, or local
WebSocket access is unavailable. It never suggests ignoring certificate errors,
changing browser security flags, routing media through a relay, or opening a router
port. User permission must be granted personally in the browser's normal prompt.

## Start / stop

1. Deploy the static viewer directory to an HTTPS origin you control.
   Verified additive viewer destination:
   `https://mark4z.github.io/tesla-browser-lab/browser-carplay/`, preserving the
   existing lab page. For that destination the exact Android allowed origin is
   `https://mark4z.github.io`. Copy this viewer directory into the lab repository's
   `browser-carplay/` only after publication is approved. The existing hosted lab
   root remains unchanged. No deployment
   is performed by building the APK. No connection credentials belong in deployment configuration.
2. Connect Android and the viewing device to the same trusted private Wi-Fi network.
   Client isolation, hotspot routing, and Tesla connectivity may prevent access.
3. Open DiPlay settings, then **Browser output (experimental)**. Select the Android
   Wi-Fi IP, confirm parked, and Start. The viewer origin is pinned to
   `https://mark4z.github.io`; arbitrary origins are not accepted. On Android 17/API 37+, personally
   grant the Android local-network permission if requested, then press Start again.
4. In the HTTPS viewer, manually enter the displayed private IPv4 address and port,
   confirm parked, and Connect. Personally grant Local Network Access if prompted.
   Keep DiPlay in the foreground: Android shows the network-observed remote IP and
   asks whether to allow this connection to view and potentially control CarPlay.
   Reject, dismissal, backgrounding, cancellation, or 30 seconds without acceptance
   denies the pending connection. Every reconnect needs a new Android approval.
   No pairing code or remembered-device access is used.
5. After approval, viewing starts with a fresh config/keyframe. Native touch remains
   available until you explicitly enable browser touch control and Android acknowledges
   the ownership transfer. Disable it to restore native touch. Stream reconfiguration
   and decoder recovery immediately release contacts and revoke active ownership. The
   viewer preserves the user’s touch choice during benign recovery on the same approved
   connection, then requests a fresh ownership acknowledgment after video resumes.
   Stream inactivity and real disconnection clear that choice. The viewer closes on hiding/leaving
   the page; reconnect is manual.
6. Click **Play audio here** in the approved viewer to move playback to the browser.
   Audio starts only after a user gesture successfully starts Web Audio. Stop browser
   audio to return sound to Android. Hiding/disconnecting the page or suspending the
   browser audio context restores native playback. Microphone uplink is not included.
7. In Android settings, **Stop** closes the listener, disconnects the viewer, and
   revokes the connection grant. App/session shutdown also stops browser output.
   Merely backgrounding/recreating the Activity does not kill the CarPlay session.

## Security and limitations

- Bind only one user-selected, assigned RFC1918 IPv4 interface, never all interfaces
  or public addresses. Pinned HTTPS Origin + exact Host checks protect against arbitrary
  websites connecting. Protocol v2 requires explicit Android approval bound to one
  live socket and a unique request ID before any media or input. Legacy token clients
  fail closed with an upgrade-required response. There is no compatibility fallback.
- One pending or approved viewer is admitted at a time; connection attempts are
  rate-limited. Touch ownership is separate from viewing and transferred serially
  on the input executor after releasing prior contacts. Native touch gestures for
  settings remain available while native CarPlay input is suppressed during remote ownership.
- **The LAN WebSocket is unencrypted.** Pairing does not protect video/audio/control
  from an attacker on the network. Approval is not network encryption. Use only a trusted, access-controlled network.
  This version is unsuitable for hostile/shared Wi-Fi; it does not claim TLS security.
- No session values are stored in preferences, URL query, local/session storage, or
  logs. No network discovery, telemetry, third-party JS, or automatic reconnection.
- Video stays bounded to three frames / 8 MiB including the in-flight frame. Transient overflow discards queued dependencies and resumes only at a fresh keyframe, without disconnecting. Control messages have separate bounded credits; persistently blocked writes still disconnect after five seconds.
  Decoder overload drops dependent compressed frames and waits for a new keyframe
  while allowing existing decoder work to drain, rather than repeatedly destroying
  the decoder before it can output. Identical video configs do not create new stream
  generations. Audio uses separate bounded queues and cannot consume control credits.
  Decoder overload waits for a new keyframe. Socket deadlines, input size limits,
  frame validation, origin validation and rate limits restrict resource use.
- Microsecond timestamps are monotonic Android receipt times: the existing media
  sink does not expose source presentation timestamps. Audio synchronization and
  B-frame reordering are not asserted. Real stream tests are required.
- Browser audio reuses Android’s existing decoded PCM output for AAC/Opus/LPCM; it
  does not depend on the browser implementing those compressed audio codecs. Native
  playback is the default, and browser playback is a per-connection user choice.
  Multiple streams are mixed locally with bounded buffering; no audio is sent before
  Android approval. Actual hardware latency, navigation prompts, calls, and A/V sync
  still need validation.
- Source-only APKs contain no accessory identity and are **not standalone iPhone
  connection test packages**. No authentication assets are read or published here.

## Acceptance checklist (parked only)

First use another Android Chromium browser, then the actual Tesla browser:

- HTTPS viewer reports secure context; browser presents/grants normal LNA permission;
  denied permission produces a clear error without a fallback or security override.
- Legacy protocol, wrong origin, second viewer, malformed messages, oversized messages,
  and non-private endpoints fail. Reject, timeout, closing the pending browser,
  stale approval buttons, and reconnect all fail closed; no media appears before approval. Stop and app shutdown revoke access immediately.
- H.264 and HEVC start on config + keyframe, preserve aspect ratio, and recover from
  disconnect/config change without displaying stale dependent frames.
- 720p/1080p then intended higher resolution: measure actual latency, queue/drop
  behavior and long-running stability. No device performance claim is made here.
- Compare 30fps and 60fps with frequent screen changes: no repeated decoder reset
  starvation, bounded queues, held contacts released during recovery, and touch
  restored only after fresh matching ownership acknowledgment. A real network
  disconnect still requires new Android approval and a fresh touch choice.
- Start/stop browser audio repeatedly; test media and navigation overlap, changing
  tracks, prolonged silence, tab hide, AudioContext suspension, and real disconnect.
  Native sound must return, stale PCM must not leak into a new connection, and
  microphone access must never be requested.
- One/two-finger down/move/up, one finger lifted while the other continues,
  pointer cancellation, outside-letterbox touch, tab hide, and network loss release
  all contacts. Settings gesture and Stop remain usable from Android.
- Background Android, detach/recreate native surface, rotate/change configuration,
  reopen settings, reconnect browser, and stop/start repeatedly. Native playback and
  CarPlay session must remain intact except an explicitly requested shutdown.

Cloud runtime network probe: installed Chromium 154 could not start with normal
security because this execution environment denies required Unix socket creation.
The cloud browser's supported page evaluation is read-only. Therefore no actual
HTTPS-to-private-IP WebSocket connection or permission prompt was verified here.

Android 17 permission reference: [Local network permission](https://developer.android.com/privacy-and-security/local-network-permission).

## Authenticated manual builds

The existing credential-bearing manual workflow still allows only `main` and
`refactor/remove-byd-hardware`. This experimental `feature/browser-carplay` branch
is **not** enabled in that allowlist. Its three guards are in
`.github/workflows/build-authenticated-debug.yml` (source-checks job, source-cache
save step, and build job). Workflow changes and any authenticated artifact
publication are left to the repository owner; this work does not dispatch them.
The source-only APK validated here cannot replace a standalone authenticated test APK.

## Protocol v2 rollout

Install the new APK and reload the updated viewer together. An old APK cannot
approve v2 requests, and an old token-based viewer cannot connect to the new APK.
The page gives upgrade guidance rather than silently weakening authorization.
The protocol grants only the current connection and never stores a token, device
identity, network address, or grant in browser storage or URL history.

## Connection diagnostics (requires the updated APK)

While Browser output is ON, Android settings provide **Copy health URL**, **Open
health on Android**, and **Connection diagnostics**. Type the displayed
`http://<selected-ip>:<port>/health` into the car browser's address bar as a manual,
top-level navigation. The endpoint returns only a fixed service/protocol/build
marker, without identity, approval state, history or media. It works independently
of a currently connected viewer and does not trigger an approval prompt. It closes
with the listener when Browser output is stopped.

A health response establishes that this browser can reach this Android address and
port over HTTP. It does **not** establish that the browser permits a WebSocket from
the HTTPS viewer, that its upgrade handshake passed, or that Android approved it.
Do not fetch this HTTP address from the HTTPS viewer or bypass browser warnings.
Opening it on Android tests Android's own route, not the car's route.

The Android report records bounded, process-local timestamps, elapsed milliseconds,
connection numbers, stage counters and fixed failure categories. It never records
remote addresses, request headers, URLs, pairing data or audio/video payloads. The
report window refreshes from memory and has an explicit copy button; the same safe
snapshot is included in the existing diagnostic export. Stopping and restarting
the listener starts a fresh report. Keep the Android app in front during approval.

The viewer's current-attempt timeline distinguishes Connect, WebSocket open,
approval pending, approval granted, first decoded video and closure, with elapsed
times. The page also distinguishes its HTTPS/secure-context status from the
unencrypted local `ws://` transport. A 1006 close is an abnormal closure indication,
not proof of a certificate, local-network permission or routing failure. Compare
its last reached stage with Android's report to find where the attempt stopped.
