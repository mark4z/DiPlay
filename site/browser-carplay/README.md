# Experimental browser CarPlay viewer

Static HTML/CSS/ES modules; no dependencies, telemetry, or browser storage.
The built-in HTTPS origin connects on visible page open and retries every five
seconds while disconnected. External origins start after a private address/port
and fresh parked confirmation are supplied. This is a
video and touch companion to the Android bridge, not a standalone CarPlay
receiver. Use the phone’s direct connection to the car for sound. Android’s normal
audio playback remains unchanged; no audio is forwarded to the browser.

## Deployment and connection

### Built-in HTTPS viewer (preferred)

The APK packages this directory's canonical HTML, CSS, and ES modules and serves
it at **https://tesla.mark4z.asia:9999/**. The Gradle `syncBrowserViewerAssets`
task copies only the root runtime files into `browser-carplay/` assets before
Android `preBuild`; README and tests are excluded. There is no second viewer
source tree, build-time CDN fetch, external script, font, or media dependency.

An optional build-time `DIPLAY_HTTPS_DOMAIN` selects another bare ASCII DNS
hostname. It defaults to `tesla.mark4z.asia`; the port and local VPN addresses
stay fixed. See [the public build-hook contract](../../docs/HTTPS_BUILD_HOOKS.md).
The APK's TLS checks, displayed URLs, request routing, CSP and served `config.mjs`
all use the same immutable policy. Packaging never rewrites these source files.

On that exact HTTPS origin, the viewer selects only
**wss://tesla.mark4z.asia:9999/carplay**. IP and port inputs are hidden, disabled,
and not required. No query parameter, fragment, persisted setting, server video
configuration, or hostname field can change this endpoint. The built-in page
never falls back to plaintext WS or to the externally hosted viewer after a
connection failure.

The hostname must resolve to the Android device on the trusted private LAN, and
its TLS certificate must be trusted and valid for this hostname. The viewer does
not install certificates or weaken browser security. Do not bypass certificate
warnings. The same-origin WSS path does not need the external viewer's Chrome 147
Local Network Access mixed-content exemption. WebCodecs video support and actual
H.264/H.265 decoding remain device-dependent; a Tesla browser or other in-car
browser is not guaranteed to support them.

1. Park and connect both devices to the same trusted private LAN. On DiPlay's
   home screen, import the local certificate ZIP, confirm parked/trusted-network
   use, and enable **HTTPS service**. The validated identity is encrypted in
   Android private no-backup storage for later app openings. See the
   [home-screen HTTPS setup guide](../../docs/BROWSER_HTTPS_SETUP.md) for the
   Android VPN/local-network prompts and certificate deletion controls.
2. Manually open the built-in HTTPS URL shown above. All viewer assets come from
   the APK on that connection. The page fills the viewport and starts a WSS
   connection to this device, with no IP address entry.
3. DiPlay's configured approval mode applies. In manual mode, tap **Accept** on
   Android within 30 seconds. The protocol-v2 approval handshake is required in
   either mode; TLS alone does not authorize media or controls.
4. After the first approval, the viewer tries fullscreen once. Browsers can reject
   this automatic request when a real user gesture is required; the display
   quietly keeps filling the page. Tap **Enter fullscreen** to retry manually.
   Exiting fullscreen or reconnecting does not trigger another automatic attempt.
   A failed manual attempt shows the browser fallback message.
5. Touch is requested automatically once video is ready. It stays inactive until
   Android acknowledges ownership. Up to two contacts match the existing CarPlay HID mapper.

The built-in viewer keeps setup, transport explanations, diagnostics, and
touch status in **Settings**. Drag the dedicated grip to move the compact bar;
when focused, arrow keys move it (Shift moves farther). A healthy live display
hides the bar after four seconds without control activity. **Controls** reveals
it again. Focused controls, an open Settings panel, and connection/fullscreen
errors keep the bar visible. The canvas itself never becomes a reveal gesture,
so existing CarPlay single- and two-contact input is preserved. **Close settings**
or Escape closes the panel even if the bar was moved behind it. Touch state is
shown in the bar; connection and fullscreen errors stay visible outside the drawer.
There are no viewer Connect, Reconnect, Stop, or touch-toggle buttons. A visible,
disconnected page retries after five seconds; only one socket or retry timer exists.
The embedded TLS opening handshake has a five-second timeout; the external LAN
permission prompt retains its 60-second allowance. Android approval retains its
30-second deadline and is never interrupted by a second attempt.

Hiding or leaving the page closes the session and cancels retries. Returning to
a visible embedded tab or BFCache page reconnects; external pages require fresh
parked confirmation. A newly approved browser takes over the active display and
releases the previous browser's touch. The replaced browser receives WebSocket
4001 / `superseded` and stays idle until reloaded, including across focus/tab returns.
An explicitly rejected browser also waits for reload rather than repeating consent
prompts. The embedded browser has no parked checkbox: the explicitly enabled
Android parked-use guard remains required. Android's service Stop remains available.

Settings shows the Android-confirmed effective output target separately from the
actual decoded `VideoFrame.displayWidth × displayHeight`. These read-only metrics
never change the viewport target. Actual video clears while no current frame is
available; target clears until the current request receives its own Android ACK.
A saved target is not proof that the current video has reached that size.

After approval, the embedded viewer reports its stable video container content box
in rendering-device pixels after a two-second debounce. It uses
`ResizeObserver.devicePixelContentBoxSize` when available, converting logical axes
according to writing mode; the fallback is CSS content width/height ×
`devicePixelRatio` ([Resize Observer specification](https://www.w3.org/TR/resize-observer/#resize-observer-interface)).
This is the browser rendering area, not native panel resolution. Android saves the validated, even-aligned rendering-pixel baseline without a 1080p quality cap,
then applies the existing resolution percentage, UI scale and encoder alignment.
Only the final output is proportionally bounded to 3840 pixels on the long side and 2160 on the short side. A changed
final output can reconnect CarPlay; matching output does not reconnect. Browser
DPR is applied once in the browser fallback only; decoded video dimensions and
overlay toolbar visibility never become the baseline. The browser does not apply
a percentage: Android retains the user-selected percentage (including 80%) and
applies it once. Requests use `units: "device-pixels"` with integer `width` and
`height`, and no `dpr` field. Android rejects missing/unknown units and redundant
DPR; acknowledgments and saved baselines carry the same units. Legacy unitless
CSS baselines fall back to normal Android sizing until the updated viewer reports
a fresh size. Reload the embedded viewer after updating the APK. Density changes
are observed even when the CSS size is unchanged. Inputs remain limited to 320–16384 pixels per axis and 3:1 aspect; even-alignment, stability and reconnect limits remain in force.
In browser mode, the configured physical-size reference calibrates the current
browser viewport's width or height (according to the chosen basis), with the
other dimension derived from its aspect ratio. It is not a measurement of the
browser device. Android host pixels, DPR and resolution quality do not change
this physical calibration. Saved resolution/UI percentages are never rewritten
when the final canvas reaches the 4K ceiling. The existing local hardware decoder
check still applies outside relay-only mode; an unsupported output is reported
as a failure instead of silently switching to Android's dimensions. The browser
also checks its WebCodecs configuration. These checks do not guarantee iPhone
negotiation or real-time performance.
**Automatically follow this browser’s size** can disable the browser override.
Settings shows Android's acknowledged baseline, effective output target, and
whether CarPlay is reconnecting or waiting for its next connection. Requests are
correlated by ID; duplicates, stale ACKs, and missing ACKs never create a retry
loop. A failed automatic CarPlay reconnect leaves the normal Android manual
connection controls available. These CarPlay reconnections do not relax the
browser approval handshake.

Android's **Start HTTPS when I open DiPlay** and **Automatically allow browser
connections** are separate opt-ins, both off by default. HTTPS remains tied to
DiPlay's visible foreground session, including the handoff to CarPlay; leaving
the app stops it. Stop retains the saved certificate and choices. Delete saved
certificate and settings stops HTTPS and clears the identity plus both options.

### External GitHub Pages viewer (compatibility)

The externally published viewer remains at
https://mark4z.github.io/tesla-browser-lab/browser-carplay/, with the exact allowed
origin **https://mark4z.github.io**. This source change does not publish it.
External pages continue to require Chrome 147+ with WebCodecs and Local Network
Access support. Chrome's
[WebSocket launch notes](https://groups.google.com/a/chromium.org/g/blink-dev/c/O6GMKt44Ups)
describe the permission-gated exemption for explicit local IP addresses.

Enter the private IPv4 address and plaintext WebSocket port shown by DiPlay in
separate fields, then confirm parked use, personally decide on
Chrome's local-network permission, and Accept on Android. This destination is
always `ws://<RFC1918 IPv4>:<port>/carplay` with port 1–65535. No hostname, URL,
credentials, alternate path, parameter, discovery, or scan is accepted. The fixed
embedded hostname cannot be entered as a private IP or used as an endpoint override.

Do not add `upgrade-insecure-requests` to the external hosting policy: its
`ws://` link deliberately uses Chrome's Local Network Access exemption. Hosting
may set a `frame-ancestors 'none'` CSP response header in addition to the page's
top-level guard. That directive cannot be enforced through a meta element.
Browser policy, rollouts, and embedded browser limitations can still prevent
this external connection; there is no HTTP or codec-library workaround.

The external viewer’s `ws://` LAN WebSocket is **unencrypted**, including video
and touch controls. The built-in viewer’s `wss://` link protects those messages
with TLS. Both modes still require Android approval. Use only a trusted network. Do not expose the bridge to the internet. The viewer has no
pairing credentials, persistent approvals, or raw network-data logging. HTTPS
secures page delivery; only WSS also protects the WebSocket transport.

Hide/leave the page or lock the screen to close the session. Unchecking parked
use also disconnects the external viewer. Touch is released on pointer cancel/lost
capture, focus loss, video reconfiguration, and resize. Every reconnection repeats
the Android approval handshake. Touch is requested automatically after video is
ready and still requires a matching Android acknowledgment. The external checkbox
and Android parked-use guard are user declarations, not vehicle-speed sensors.

## Connection diagnostics and LAN checks

The **Connection diagnostics** panel (inside Settings in the built-in viewer) shows page scheme, secure-context
status, and the actual connection target's `ws://` or `wss://` transport separately.
WSS encrypts the WebSocket link with TLS. HTTPS delivery alone does not encrypt
the external viewer's plaintext WS video or controls. These labels describe transport,
not a browser permission verdict or a promise that a connection will work.

Every valid connection attempt starts a fresh in-memory timeline:

1. **Connect requested**: endpoint validation passed and the attempt began.
2. **WebSocket opened**: the browser reported a successful WebSocket handshake.
3. **Android approval pending**: protocol-v2 `approvalPending` was received.
4. **Approved on Android**: the required approval handshake completed.
5. **First video decoded**: a usable video decoder output arrived. This marks
   decoding, not a guarantee that a canvas draw succeeded.
6. **Connection ended**: includes the numeric CloseEvent code if one was observed.
   Local cancellation, timeout, or early failure may have no close event code.

Elapsed times are measured from Connect with a monotonic clock. The panel retains
at most these six milestones for the latest attempt, including after disconnect.
Recovery does not append per-frame entries, and a new Connect replaces the previous
attempt. There are no raw server reasons, private IPs, tokens, media payloads,
console logs, persistence, or diagnostic uploads. A close code identifies an
observation, not a root cause: for example, **1006** means an abnormal closure with
no normal close frame, not proof of a specific permission, origin, or LAN failure.

For the external viewer, to check the route independently, while parked manually navigate to the HTTP
health URL shown by the diagnostic APK, for example
`http://192.168.1.20:8765/health`. A successful response identifying
`service=diplay-browser`, `protocol=2`, and `build=connection-diag-v1` shows that
this browser reached that APK’s LAN HTTP endpoint. It does **not** prove that the
HTTPS viewer has Local Network Access permission, that `/carplay` can complete a
WebSocket handshake, or that the Android approval flow succeeded. Confirm the
address matches the APK; do not bypass browser security or certificate warnings.

The viewer never fetches, preflights, embeds, or auto-opens that HTTP endpoint from
this HTTPS page. Such a cross-scheme fetch can be blocked as mixed content and
must not be mistaken for a failed LAN route. Navigating away disconnects an active
session; return to the HTTPS viewer and confirm parked use again. An unavailable
health endpoint alone can also mean an older APK; inspect the APK’s version and
status rather than inferring a permission failure.

## Wire protocol

One active approved WebSocket client to `/carplay`, with bounded pending candidates. Protocol **v2** replaces token authentication
with Android-side approval. Manual mode shows an explicit consent prompt; the
embedded auto-approval opt-in still uses the same versioned handshake. The first
client text message is:

```json
{"type":"requestApproval","version":2}
```

The server must first reply `{"type":"approvalPending","version":2}`, then only
after Android authorizes the connection send `{"type":"authenticated","version":2}`.
No video, config, keyframe request, touch ownership request, or touch packet is
accepted before the authenticated acknowledgment. The viewer closes if approval
has not completed within 30 seconds of its request. Repeated pending messages
cannot extend this deadline. Disconnect cancels the request. Retrying always
repeats the Android approval handshake. Ordinary network failures retry after five
seconds while visible; rejection and supersession require a page reload.

Reject, timeout, and protocol mismatch use `{ "type":"error", "version":2,
"code":"approvalRejected" }`, with `approvalTimeout` or `upgradeRequired` as the
other codes. The server can instead close with WebSocket code 1008 and one of
those exact reasons. The viewer maps only those constants to fixed safe text;
arbitrary error strings or close reasons are never displayed or logged. Numeric
close codes and the lifecycle phase remain available for troubleshooting.
Unversioned, legacy, mismatched, or out-of-order handshakes fail closed and advise
installing the latest DiPlay APK and reloading this viewer together.

While no stream is available, an approved connection may receive
`{"type":"status","code":"waiting"}` or code `disconnected`. These clear video
and touch ownership but keep the approved socket waiting for config. Other errors
close the session; ordinary failures follow the visible-page retry policy.

When video is available, send e.g.:

```json
{"type":"config","streamId":1,"codec":"avc1.64001f","width":1280,"height":720}
```

`streamId` is a mandatory positive integer no greater than JavaScript's
`Number.MAX_SAFE_INTEGER`. The bridge advances it on media replacement,
reconfiguration, and stream inactivity. The viewer keeps it separate from the
WebCodecs configuration and clears it immediately on configuration replacement,
waiting/inactive status, or disconnect. Stale asynchronous codec probes cannot
restore an earlier identifier.

The codec is derived from the actual stream. AVC (`avc1`/`avc3`) and HEVC
(`hvc1`/`hev1`) are probed using `VideoDecoder.isConfigSupported`. Dimensions are
bounded to 4096 per axis. `description` **must be absent**: this protocol uses
Annex B, not length-prefixed AVC/HEVC configuration records. Each binary WebSocket
message contains one complete encoded access unit:

| Byte offset | Meaning |
| --- | --- |
| 0 | 1 = keyframe; 2 = delta frame |
| 1–8 | unsigned 64-bit, big-endian monotonic receipt timestamp in microseconds |
| 9 onward | Annex B access unit, with start codes |

The Android bridge uses monotonic frame-receipt time because the existing sink does
not expose source presentation timestamps; B-frame reordering is not asserted
in this phase. Payload is capped at 4 MiB. A keyframe must contain all relevant parameter sets
(AVC SPS/PPS; HEVC VPS/SPS/PPS) and the random-access picture. See the
[AVC](https://www.w3.org/TR/webcodecs-avc-codec-registration/) and
[HEVC](https://www.w3.org/TR/webcodecs-hevc-codec-registration/) WebCodecs registrations.

Client sends `{"type":"requestKeyframe"}` on configuration, overload, or decode
error (at most once/second). The decoder queue is bounded to four encoded chunks;
overload discards dependent deltas and lets submitted work drain, then replaces
the decoder at a fresh keyframe. There is no application-level encoded-frame
backlog. Decoder errors have bounded retries; recovery with no usable output
for ten seconds closes the session. Transient saturation does not itself spend
the decoder-error retry budget. One pending decoded frame is kept for the next paint;
superseded, stale, rendered, and cancelled frames are closed. Browser/network
WebSocket buffers are outside JavaScript's control; the server must also bound
its output queue.

Touch ownership is a separate automatic request for the current live video stream:

```json
{"type":"setTouchOwnership","enabled":true,"streamId":1,"requestId":1}
```

Decoded live video triggers the ownership request; it does **not** immediately
enable browser pointer control. Only the matching acknowledgment enables touch:

```json
{"type":"touchOwnership","enabled":true,"streamId":1,"requestId":1}
```

`requestId` is a positive safe integer, monotonically increasing for the lifetime
of the page session object. The acknowledgment must match both the latest
`requestId` and current `streamId`. Delayed or unsolicited enables cannot restore
control. An `enabled:false` acknowledgment for the current request revokes
ownership. Configuration replacement and decoder recovery immediately clear active
ownership, pending requests, and held contacts. Once fresh video is available, a
new ownership request must receive a matching acknowledgment before touch resumes.
Identical configs do not create a new stream generation. Inactive status and real
disconnect clear ownership; a fresh live stream requests ownership automatically.

Touch snapshots are `{"type":"touch","streamId":1,"contacts":[{"id":0,"x":0.5,"y":0.5}]}`.
The identifier must match the current video configuration, and the viewer must
hold acknowledged touch ownership. The server rejects
stale or missing identifiers, even for empty releases, and releases native
contacts itself during media reconfiguration. This prevents in-flight gestures
from controlling a replacement CarPlay stream over the same WebSocket.
IDs are stable CarPlay slots 0/1 for each pointer's lifetime. Coordinates are
normalized 0–1 against the actual displayed video rectangle, accounting for black
bars and canvas/CSS scaling. Down in a black bar is ignored; captured moves outside
the picture clamp to the edge. Up/cancel immediately sends only surviving contacts,
including `[]` for final release. The bridge must synthesize releases and clear
all native contacts if the connection closes. Slow outbound control links close
instead of accumulating stale moves or dropping a release.

## Audio output

Use the phone’s direct connection to the car for audio. Browser output carries
only video and touch; opening, stopping, or reconnecting the viewer does not
change Android’s normal audio playback. There are no browser audio buttons,
audio forwarding, test tones, microphone requests, or audio transport diagnostics.
Audio/video timing through the separate phone-to-car path must be checked on the
intended hardware; the viewer does not synchronize that path.

For parked manual validation, play music and navigation prompts through the
phone-to-car connection while using browser video and touch. Confirm sound keeps
working through fullscreen, video recovery, viewer replacement, tab hide, and reconnect.
Also verify normal Android audio playback with browser output both off and on.

## Validation

From the repository root, with Node 20+:

```sh
node --test site/browser-carplay/tests/*.test.mjs
node --check site/browser-carplay/viewer.mjs
node --check site/browser-carplay/session.mjs
node --check site/browser-carplay/diagnostics.mjs
```

The dependency-free tests exercise exact-origin WSS selection, forbidden endpoint
overrides, external viewer compatibility, packaged local-asset dependency closure,
strict endpoint validation, framing, codec
configuration, letterbox geometry, stable contacts, approval/version gating,
rejection/expiry, stale touch acknowledgments, timeouts, codec negotiation races,
backpressure, recovery, and automatic reconnect. Diagnostic tests cover exact
milestone times, pre-open failure, rejection, timeout, received-versus-local close
codes, deduplication across video recovery, stale callbacks, privacy-safe output,
bounded retention, new-attempt reset, and absence of automatic HTTP health probes.
Removal regression tests ensure retired audio assets, controls, and browser media
APIs cannot return, and obsolete audio messages and packets fail closed.
Both embedded TLS and external LAN UI flows exercise approval, touch ownership,
repeated connection triggers, hide/return, cancellation, and automatic reconnect. Entry
tests cover exactly one embedded opening attempt, hidden openings/BFCache,
preapproval gating, one quiet automatic fullscreen attempt, synchronous manual
fullscreen retries, explicit exits without re-entry, rejected/unsupported fullscreen,
stale async results, readable resolution On/Off state,
visible connection errors, five-second retries, bounded connect timeouts, and
rejection/supersession standby without retry loops. The Gradle source-contract checks do not replace building and inspecting a real APK.
Tests use synthetic bytes and identifiers only. Real TLS certificate/hostname
validation, DNS routing, HTTPS-to-LAN browser permission,
hardware AVC/HEVC decoding, physical two-finger gestures, background suspension,
and CarPlay hardware integration still require a parked-device test.

