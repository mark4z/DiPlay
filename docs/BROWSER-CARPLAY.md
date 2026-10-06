# Experimental browser output, phase 1

Status: source implementation, **not verified on Android/Tesla hardware**. Native
CarPlay remains the normal output. Browser output is off after process launch,
requires an explicit parked confirmation, and is never saved as a preference.

## Architecture and browser gate

An independently hosted **HTTPS** static page (`site/browser-carplay`) decodes
CarPlay's existing compressed H.264/HEVC access units with WebCodecs. The Android
app tees the main screen without decode/re-encode, preserving native video/audio.
Only video and two-contact CarPlay touch are forwarded. No audio forwarding,
vehicle-control integration, internet media relay, or bypass of driving restrictions.
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
   is performed by building the APK. Do not put tokens into deployment configuration.
2. Connect Android and the viewing device to the same trusted private Wi-Fi network.
   Client isolation, hotspot routing, and Tesla connectivity may prevent access.
3. Open DiPlay settings, then **Browser output (experimental)**. Select the Android
   Wi-Fi IP and enter the exact HTTPS origin (scheme + hostname + optional port,
   without path), confirm parked, and Start. On Android 17/API 37+, personally
   grant the Android local-network permission if requested, then press Start again.
4. Keep the displayed `ws://IP:port/carplay` endpoint and session pairing token
   private. In the HTTPS viewer, manually enter these values, confirm parked, and
   Connect. Personally grant Local Network Access if prompted.
5. The browser requires a fresh config/keyframe before displaying video or sending
   touch. It closes on hiding/leaving the page; reconnect is manual.
6. In Android settings, **Stop** closes the listener, disconnects the viewer, and
   revokes the session token. App/session shutdown also stops browser output.
   Merely backgrounding/recreating the Activity does not kill the CarPlay session.

## Security and limitations

- Bind only one user-selected, assigned RFC1918 IPv4 interface, never all interfaces
  or public addresses. Exact HTTPS Origin + Host checks protect against arbitrary
  websites connecting; a random 192-bit memory-only token gates media/control.
- One viewer owns remote touch at a time; native touch gestures for settings remain
  available, while native CarPlay input is suppressed during remote ownership.
- **The LAN WebSocket is unencrypted.** Pairing does not protect video/token/control
  from an attacker on the network. Use only a trusted, access-controlled network.
  This version is unsuitable for hostile/shared Wi-Fi; it does not claim TLS security.
- No session values are stored in preferences, URL query, local/session storage, or
  logs. No network discovery, telemetry, third-party JS, or automatic reconnection.
- Video stays bounded to three frames / 8 MiB including the in-flight frame. Transient overflow discards queued dependencies and resumes only at a fresh keyframe, without disconnecting. Control messages have separate bounded credits; persistently blocked writes still disconnect after five seconds.
  Decoder overload waits for a new keyframe. Socket deadlines, input size limits,
  frame validation, origin validation and rate limits restrict resource use.
- Microsecond timestamps are monotonic Android receipt times: the existing media
  sink does not expose source presentation timestamps. Audio synchronization and
  B-frame reordering are not asserted. Real stream tests are required.
- Source-only APKs contain no accessory identity and are **not standalone iPhone
  connection test packages**. No authentication assets are read or published here.

## Acceptance checklist (parked only)

First use another Android Chromium browser, then the actual Tesla browser:

- HTTPS viewer reports secure context; browser presents/grants normal LNA permission;
  denied permission produces a clear error without a fallback or security override.
- Wrong token, wrong origin, second viewer, malformed messages, oversized messages,
  and non-private endpoints fail. Stop and app shutdown revoke access immediately.
- H.264 and HEVC start on config + keyframe, preserve aspect ratio, and recover from
  disconnect/config change without displaying stale dependent frames.
- 720p/1080p then intended higher resolution: measure actual latency, queue/drop
  behavior and long-running stability. No device performance claim is made here.
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
