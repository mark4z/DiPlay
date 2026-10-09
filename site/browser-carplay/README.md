# Experimental browser CarPlay viewer

Static HTML/CSS/ES modules; no dependencies, telemetry, or browser storage.
The built-in HTTPS origin makes one automatic connection attempt on a fresh,
visible page open; external origins always require an explicit Connect. This is a
video, touch, and optional audio companion to the Android bridge, not a standalone
CarPlay receiver. Browser microphone uplink is not included.

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
   the APK on that connection. The page fills the viewport and makes one WSS
   connection attempt to this device, with no IP address entry.
3. DiPlay's configured approval mode applies. In manual mode, tap **Accept** on
   Android within 30 seconds. The protocol-v2 approval handshake is required in
   either mode; TLS alone does not authorize media or controls.
4. After approval, tap **Enter fullscreen + audio** if wanted. This real browser
   gesture requests fullscreen and starts browser audio. If fullscreen is not
   supported or allowed, the display still fills the page. Playback failures
   remain visible and keep sound on Android. There is no automatic audio start.
5. Open **Settings** to enable touch separately if wanted. It stays inactive until Android
   acknowledges ownership. Up to two contacts match the existing CarPlay HID mapper.

The built-in viewer keeps setup, transport explanations, diagnostics, audio and
touch controls in **Settings**. Drag the dedicated grip to move the compact bar;
when focused, arrow keys move it (Shift moves farther). A healthy live display
hides the bar after four seconds without control activity. **Controls** reveals
it again. Focused controls, an open Settings panel, and connection/audio/fullscreen
errors keep the bar visible. The canvas itself never becomes a reveal gesture,
so existing CarPlay single- and two-contact input is preserved. **Close settings**
or Escape closes the panel even if the bar was moved behind it. **Stop** and touch
state are in the bar; connection and audio errors stay visible outside the drawer. **Reconnect**
starts one fresh attempt after a stop, rejection, or failure. Hiding or leaving
the page closes the session. Returning to a tab or restoring it from BFCache does
not reconnect; no timer retries failed connections. Reloading the visible page
is a new opening and makes one new attempt. The embedded browser has no parked
checkbox: the explicitly enabled Android parked-use guard remains required.

After approval, the embedded viewer reports its stable display viewport in CSS
pixels after a two-second debounce. Android saves a bounded, even browser baseline,
then applies the existing resolution percentage and encoder alignment. A changed
final output can reconnect CarPlay; matching output does not reconnect. Browser
DPR, decoded video dimensions, and toolbar visibility never become the baseline.
**Automatically follow this browser’s size** can disable the browser override.
Settings shows Android's acknowledged baseline, effective output target, and
whether CarPlay is reconnecting or waiting for its next connection. Requests are
correlated by ID; duplicates, stale ACKs, and missing ACKs never create a retry
loop. A failed automatic CarPlay reconnect leaves the normal Android manual
connection controls available. These CarPlay reconnections do not relax the
browser approval handshake or trigger automatic browser-audio playback.

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
separate fields, then confirm parked use, click Connect, personally decide on
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

Click **Play audio here** after approval to move sound to the browser. A successful
user-gesture WebRTC audio start is required. **Test audio (3 seconds)** can check a short synthetic downlink after approval, even without a CarPlay source; it never mutes Android. Stopping browser audio, hiding the page,
or disconnecting returns playback to Android.

The external viewer’s `ws://` LAN WebSocket is **unencrypted**, including video,
audio signaling, and touch controls. The built-in viewer’s `wss://` link protects
those messages with TLS. Audio media uses WebRTC DTLS-SRTP encryption, but its signaling still
depends on the trusted LAN and Android approval. Use only a trusted network. Do not expose the bridge to the internet. The viewer has no
pairing credentials, persistent approvals, or raw network-data logging. HTTPS
secures page delivery; only WSS also protects the WebSocket transport.

Hide/leave the page, lock the screen, or click Stop/Disconnect to close the
session. Unchecking parked use also disconnects the external viewer. Touch is released on pointer cancel/lost capture, focus loss,
video reconfiguration, and resize. On reconnect, click Connect/Reconnect, complete
the Android approval handshake, and explicitly enable touch again. The external checkbox and the Android parked-use guard are user declarations,
not vehicle-speed sensors.

## Connection diagnostics and LAN checks

The **Connection diagnostics** panel (inside Settings in the built-in viewer) shows page scheme, secure-context
status, and the actual connection target's `ws://` or `wss://` transport separately.
WSS encrypts the WebSocket link with TLS. HTTPS delivery alone does not encrypt
the external viewer's plaintext WS video, audio signaling, or controls. Audio
media is separately protected by WebRTC DTLS-SRTP. These labels describe transport,
not a browser permission verdict or a promise that a connection will work.

Every valid connection attempt starts a fresh in-memory timeline:

1. **Connect requested**: endpoint validation passed and the attempt began.
2. **WebSocket opened**: the browser reported a successful WebSocket handshake.
3. **Android approval pending**: protocol-v2 `approvalPending` was received.
4. **Approved on Android**: the required approval handshake completed.
5. **First video decoded**: a usable video decoder output arrived. This marks
   decoding, not a guarantee that a canvas draw or audio playback succeeded.
6. **Connection ended**: includes the numeric CloseEvent code if one was observed.
   Local cancellation, timeout, or early failure may have no close event code.

Elapsed times are measured from Connect with a monotonic clock. The panel retains
at most these six milestones for the latest attempt, including after disconnect.
Recovery does not append per-frame entries, and a new Connect replaces the previous
attempt. There are no raw server reasons, private IPs, tokens, SDP, media payloads,
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
session; return to the HTTPS viewer and explicitly Connect again. An unavailable
health endpoint alone can also mean an older APK; inspect the APK’s version and
status rather than inferring a permission failure.

## Wire protocol

One WebSocket client to `/carplay`. Protocol **v2** replaces token authentication
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
cannot extend this deadline. Disconnect cancels the request; retrying within the same page always
requires another explicit click and the Android approval handshake. A freshly
opened visible built-in page starts one attempt automatically.

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
close the session. There is no automatic reconnect.

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
not expose source presentation timestamps; audio sync and B-frame reordering are not
asserted in this phase. Payload is capped at 4 MiB. A keyframe must contain all relevant parameter sets
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

Touch ownership is a separate, explicit opt-in for the current video stream:

```json
{"type":"setTouchOwnership","enabled":true,"streamId":1,"requestId":1}
```

The checkbox requests ownership; it does **not** immediately enable browser
pointer control. Only the matching acknowledgment enables touch:

```json
{"type":"touchOwnership","enabled":true,"streamId":1,"requestId":1}
```

`requestId` is a positive safe integer, monotonically increasing for the lifetime
of the page session object. The acknowledgment must match both the latest
`requestId` and current `streamId`. Delayed or unsolicited enables cannot restore
control, including rapid uncheck/recheck. An `enabled:false` acknowledgment for
the current request revokes ownership. Unchecking sends a new explicit
`setTouchOwnership` with `enabled:false` and disables pointer control immediately.
Configuration replacement and decoder recovery clear active ownership, pending
requests, and held contacts, but preserve the user’s touch choice on the same
approved connection. Once fresh video is available, a new ownership request must
receive a matching acknowledgment before touch resumes. Identical configs do not
create a new stream generation. Inactive status and real disconnect clear both
ownership and the user’s choice; a fresh opt-in is then required.

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

## Optional WebRTC/Opus browser audio

Audio remains on Android by default. A click on **Play audio here** creates one
`RTCPeerConnection` with `iceServers: []`, a receive-only audio transceiver, and an
unmuted audio element. Its `play()` call starts synchronously inside the gesture. If this initial empty-stream
request has not started, track arrival makes one additional playback attempt.
A browser permission rejection keeps the same route pending and shows **Play audio
here** for another real gesture on that populated stream. It does not extend the
15-second setup deadline, create another peer, or weaken readiness checks.
Other pending starts remain cancellable with the audio button; Stop always closes
the session. Playback diagnostics distinguish pending/blocked playback, paused or
muted output, track state, and recent RTP progress without recording raw errors.
There is no browser microphone request, capture API, recording, AudioWorklet,
WebSocket PCM transport, custom jitter buffer, or automatic audio reconnect.
WebRTC supplies Opus decoding, packet-loss concealment, and jitter handling.
The existing AVC/HEVC WebCodecs video path is unchanged and separately timed.
No end-to-end latency or A/V synchronization guarantee is made.

Audio signaling travels only over the approved protocol-v2 WebSocket:

```json
{"type":"audioMode","enabled":true,"requestId":1,"transport":"webrtc-opus"}
{"type":"audioOffer","requestId":1,"epoch":2,"transport":"webrtc-opus","sdp":"..."}
{"type":"audioAnswer","requestId":1,"epoch":2,"transport":"webrtc-opus","sdp":"..."}
{"type":"audioIce","requestId":1,"epoch":2,"transport":"webrtc-opus","candidate":"candidate:...","sdpMid":"0","sdpMLineIndex":0}
{"type":"audioReady","requestId":1,"epoch":2,"transport":"webrtc-opus"}
{"type":"audioState","enabled":true,"requestId":1,"epoch":2,"transport":"webrtc-opus"}
{"type":"audioAlive","requestId":1,"epoch":2,"transport":"webrtc-opus"}
{"type":"audioMode","enabled":false,"requestId":1,"transport":"webrtc-opus"}
```

Android offers exactly one send-only DTLS/Opus audio section. The browser answers
receive-only, requests Opus `stereo=1`, and never creates a video/data/microphone
track. ICE accepts standard host, server-reflexive, peer-reflexive and relay
candidate types with bounded valid IP literals or `.local` mDNS names, without
private-address or candidate-type topology restrictions. No STUN/TURN server or
internet relay is configured. Peers still need a mutually reachable network;
client isolation, firewalls, mDNS, and browser policy can prevent connectivity.
The approved secure signaling session carries the DTLS fingerprint; WebRTC
verifies the peer and encrypts media. There is no insecure transport fallback.

`requestId` advances on each user-initiated audio attempt. A disable refers to that
same attempt. Android assigns a positive route `epoch`; offer/answer, trickle ICE,
readiness, liveness, and state must match the current request and epoch. ICE arriving
before its offer is bounded and held until remote SDP is accepted. Delayed promises,
old callbacks, and stale acknowledgments cannot revive or stop a newer route.
SDP is ASCII and at most 6,000 characters; candidates are at most 1,024 characters,
with at most 32 candidates in each direction and 64 incoming controls per attempt.

The browser sends `audioReady` only when it has answered the offer, ICE and the
peer connection are connected, a live audio track exists, the audio element's
playback promise succeeded, and inbound audio RTP packets increased across
successive stats polls. Candidate-pair stats are diagnostic only: hidden addresses,
missing pair metadata, and ordinary non-host paths do not block playback.
Diagnostics retain bounded packet counts and candidate types, never addresses,
SDP or candidate correlation metadata. Android keeps
native output on until its own peer is connected and this matching readiness is
accepted, then confirms `audioState enabled:true`. A pre-readiness enabled ACK or
legacy PCM audio request/response fails closed with APK/viewer upgrade guidance.
No fallback to the old PCM WebSocket pipeline is attempted.

Negotiation has a 15-second deadline; a ready browser waits at most five seconds
for Android's acknowledgment. While active, the browser sends `audioAlive` at most
once per second, only with new RTP progress. A three-second RTP stall, paused/muted
output, ended track, failed/disconnected ICE, explicit stop, or tab hide returns
playback to Android. Android also has a four-second liveness watchdog in case the
browser's timers are suspended. Disconnect/source replacement closes the route.
Video-only recovery does not alter a healthy audio route. New sockets need fresh
Android approval and a new audio-button click.

**Test audio (3 seconds)** sends the same enable with `"source":"test"`. It works
after Android approval without active CarPlay media. Android emits a finite quiet
440 Hz test tone through the same WebRTC/Opus route, never mutes native playback,
and closes with `audioState enabled:false` / `code:"test-complete"`. The browser
shows this as a completed test. It is never launched automatically.

For parked manual validation:

1. Connect and approve Android, then run **Test audio (3 seconds)**. Confirm the
   tone and automatic return to native mode; verify Android stayed audible.
2. Start CarPlay music and choose **Play audio here**. Verify native output is
   muted only after the browser starts receiving; listen for correct stereo.
3. Test stop/start, visibility changes, Wi-Fi loss, source replacement, and a
   stalled browser. Confirm Android resumes and no stale audio is replayed.
4. Exercise navigation/prompt mixing and longer playback alongside unchanged
   HEVC video. Real output quality, loss behavior, latency, and A/V timing require
   the intended browser/Android/accessory hardware; unit tests do not establish them.

A bounded read-only snapshot can be retrieved in the console with:

```js
(await import('./viewer.mjs?v=embedded-https-v1')).getAudioDiagnostics()
```

It includes packet count, jitter in milliseconds, concealed sample count, and
selected candidate-pair types/protocol when the browser supplies those stats.
It never exposes IP addresses, SDP, raw candidate strings, tokens, or media.
Missing stats are `null`, not proof that ICE failed. Counters remain only in page
memory, reset for the next attempt, and are neither logged nor uploaded.

## Validation

From the repository root, with Node 20+:

```sh
node --test site/browser-carplay/tests/*.test.mjs
node --check site/browser-carplay/viewer.mjs
node --check site/browser-carplay/session.mjs
node --check site/browser-carplay/diagnostics.mjs
node --check site/browser-carplay/audio.mjs
node --check site/browser-carplay/audio-protocol.mjs
```

The dependency-free tests exercise exact-origin WSS selection, forbidden endpoint
overrides, external viewer compatibility, packaged local-asset dependency closure,
strict endpoint validation, framing, codec
configuration, letterbox geometry, stable contacts, approval/version gating,
rejection/expiry, stale touch acknowledgments, timeouts, codec negotiation races,
backpressure, recovery, and explicit reconnect. Diagnostic tests cover exact
milestone times, pre-open failure, rejection, timeout, received-versus-local close
codes, deduplication across video recovery, stale callbacks, privacy-safe output,
bounded retention, new-attempt reset, and absence of automatic HTTP health probes.
Audio tests cover readiness ordering, Opus stereo negotiation, local ICE/size/count
bounds, legacy fail-closed behavior, delayed promises, repeated/cancelled gestures,
RTP liveness, explicit test tone signaling, privacy-safe stats, and native fallback.
Both embedded TLS and external LAN UI flows exercise approval, touch ownership,
audio, repeated Connect, hide/return, cancellation, and explicit reconnect. Entry
tests cover exactly one embedded opening attempt, hidden openings/BFCache,
preapproval gesture gating, synchronous audio/fullscreen invocation, repeated
clicks, rejected/unsupported fullscreen, blocked playback, stale async results,
and visible connection errors without automatic retry. The Gradle source-contract checks do not replace building and inspecting a real APK.
Tests use synthetic bytes and identifiers only. Real TLS certificate/hostname
validation, DNS routing, HTTPS-to-LAN browser permission,
hardware AVC/HEVC decoding, physical two-finger gestures, background suspension,
and CarPlay hardware integration still require a parked-device test.

