# Browser-audio WebRTC dependency

The browser-audio sender pins `io.github.webrtc-sdk:android:150.7871.01` from
Maven Central. It does not use the retired `org.webrtc:google-webrtc` artifact,
a floating version, or a locally modified WebRTC binary.

## Artifact provenance and size

- [Versioned publisher README](https://github.com/webrtc-sdk/android/blob/v150.7871.01/README.md)
- [Publisher release](https://github.com/webrtc-sdk/android/releases/tag/v150.7871.01)
- [Maven Central POM](https://repo.maven.apache.org/maven2/io/github/webrtc-sdk/android/150.7871.01/android-150.7871.01.pom)
- [Maven Central AAR](https://repo.maven.apache.org/maven2/io/github/webrtc-sdk/android/150.7871.01/android-150.7871.01.aar)
- Download checked 2026-10-07: **49,147,033 bytes** (46.87 MiB).
- AAR SHA-256: `0a1627b1a48c2bc17d9a40d62fc47bd45166f44a311e95917f147c402de379b0`.

The AAR contains `classes.jar` and `libjingle_peerconnection_so.so` for
`arm64-v8a`, `armeabi-v7a`, `x86_64`, and `x86`. The existing app ABI filters
retain the first three. Their native libraries total 35,263,068 uncompressed
bytes; the final APK size must be measured in Actions rather than inferred from
the download size. The AAR manifest declares minSdk 21 and no permissions;
the app remains minSdk 28. No new microphone permission is introduced.

The published sources JAR is a placeholder, so API and no-capture behavior were
also inspected directly in the downloaded `classes.jar`, using `javap` to check
signatures and bytecode. In particular, this version provides:

- `JavaAudioDeviceModule.Builder.setAudioBufferCallback(...)`
- `AudioBufferCallback.onBuffer(ByteBuffer, int, int, int, int, long): long`
- `JavaAudioDeviceModule.setAudioRecordEnabled(boolean)`
- `AudioTrack.setAudioProcessingOptions(AudioProcessingOptions.raw())`
- Unified Plan send-only audio transceivers, sender codec preferences, and Opus.

The source audit also checked the corresponding API contract and disabled-input
branches at WebRTC fork commit `73cb8180f7258ee292878d6edd05177f41883962`:
[JavaAudioDeviceModule](https://github.com/webrtc-sdk/webrtc/blob/73cb8180f7258ee292878d6edd05177f41883962/sdk/android/api/org/webrtc/audio/JavaAudioDeviceModule.java)
and [WebRtcAudioRecord](https://github.com/webrtc-sdk/webrtc/blob/73cb8180f7258ee292878d6edd05177f41883962/sdk/android/src/java/org/webrtc/audio/WebRtcAudioRecord.java).
The Maven artifact was inspected independently rather than treating a source
snapshot alone as proof of the shipped Java behavior. Its Java classes target
class-file version 61 (Java 17); the project already uses JDK 25/AGP 9.3 in CI.

## Microphone-free PCM injection

`WebRtcAudioPeer` creates the Java ADM, immediately calls
`setAudioRecordEnabled(false)`, and only then supplies it to the native peer
factory. The dependency's `WebRtcAudioRecord.initRecordingImpl` branches on
`useAudioRecord` before calling `initAudioRecord`; its disabled branch creates
the PCM buffer without constructing or starting Android `AudioRecord`.
The disabled branch's capture loop calls the buffer callback with silence and
then delivers its contents to native WebRTC. `bytesRead` is zero in this mode;
it must not be interpreted as an empty application PCM frame.

This is deliberately different from muting a real microphone. Never enable
recording, prewarm/request microphone recording, request microphone permission,
or replace this dependency without rechecking its disabled-recording branch.
The sender uses only decoded CarPlay PCM already supplied by the existing audio
tap. Native-output routing remains a separate connection-scoped concern.

The disabled branch has **no pacing of its own**. Our callback uses an
interruptible monotonic 10 ms scheduler and fills exactly 480 stereo signed
16-bit little-endian frames at 48 kHz (1,920 bytes). A delayed callback rebases
instead of emitting an unbounded catch-up burst. Silence is prefilled for every
frame. Silence is still paced during teardown, avoiding a hot-spin between
closing the route and stopping the native capture thread. The PCM provider must
never wait for network work or retain WebRTC's buffer.

Hardware AEC and noise suppression are disabled before ADM creation. The track
uses `AudioProcessingOptions.raw()` plus explicit disabled audio-source
constraints, disabling software echo cancellation, automatic gain, noise
suppression and high-pass filtering. The single send-only track prefers only
Opus and caps its sender bitrate at 128 kbps. Local playout is disabled.
No video or data-channel transport is added.

## LAN signaling and lifecycle

Both sides use an empty ICE-server list. There are no public STUN/TURN services.
Both sides accept standard host, server-reflexive, peer-reflexive and relay
candidates in SDP and trickle messages, with no private-address or host-only
topology gate. Valid IPv4/IPv6 literals (including public and CGNAT ranges) and
bounded `.local` mDNS names are accepted. Candidate syntax, priority, port,
media ID, size and count remain bounded. Arbitrary DNS names and scoped interface
strings are not accepted; mDNS is resolved by WebRTC, never by this validator.
Accepting standard candidate types does not configure a STUN/TURN server or
create a relay. ICE selects a working pair without application address-range
classification; normal TCP candidate gathering is no longer explicitly disabled.

Both SDP directions retain one audio-only DTLS-SRTP media section, SHA-256
fingerprint, RTCP mux, negotiated stereo Opus and the appropriate send/receive
direction. Plain RTP, SDES keys, video and data-channel media are rejected.

### Embedded HTTPS / application-owned VPN

The embedded HTTPS listener uses this app's synthetic, non-forwarding TUN. Audio
needs a working network path rather than that synthetic interface. The default Android WebRTC monitor is
unsuitable for that combination: it considers an interface unavailable when it
cannot find a ConnectivityManager network handle, and binds ICE sockets on
known interfaces to that network. A hotspot downstream interface may not have
such a handle, while a socket bound to the synthetic TUN cannot carry the audio
datagrams to the browser.

For an active embedded HTTPS session only, `WebRtcAudioNetworkPolicy` supplies
factory-local `disableNetworkMonitor = true` and ignores VPN adapters because
that session's synthetic TUN does not forward media. Cellular and loopback
adapters are no longer excluded by this policy. The pinned WebRTC implementation
then enumerates running native interfaces (including hotspot interfaces without Android network
handles) and binds candidate sockets to their local addresses using normal OS
routing. This neither binds the process nor changes Android network/VPN
settings. Ordinary LAN-viewer audio retains the default Android network policy.
DTLS encryption, receive-only browser media, connected ICE/peer state, RTP
progression and browser playback proof remain required. Selected-pair metadata
is diagnostic only; missing/redacted stats, candidate type or address range do
not block an otherwise connected and playing session. No public STUN/TURN service
or alternate audio transport is added.

Pinned implementation evidence:

- [Factory option to native network-monitor selection](https://github.com/webrtc-sdk/webrtc/blob/73cb8180f7258ee292878d6edd05177f41883962/sdk/android/src/jni/pc/peer_connection_factory.cc#L316-L320)
- [Missing Android interface handles are unavailable](https://github.com/webrtc-sdk/webrtc/blob/73cb8180f7258ee292878d6edd05177f41883962/sdk/android/src/jni/android_network_monitor.cc#L618-L648)
- [Native fallback interface enumeration and availability](https://github.com/webrtc-sdk/webrtc/blob/73cb8180f7258ee292878d6edd05177f41883962/rtc_base/network.cc#L635-L758)
- [Monitor-provided socket network binding](https://github.com/webrtc-sdk/webrtc/blob/73cb8180f7258ee292878d6edd05177f41883962/rtc_base/network.cc#L1049-L1071)

This corrects a source-level incompatibility with the embedded-HTTPS topology;
it is not proof that the user's timeout had this sole cause. Hardware validation
must verify the selected path and audible playback with the actual
Android hotspot, synthetic TUN and Tesla browser.

### Bounded handoff diagnostics

Setup still has a 15-second deadline, followed by at most five seconds awaiting
the Android handoff acknowledgment. Native audio remains enabled until the
browser's existing playback/RTP/connected-ICE proof has arrived and the
acknowledgment has been queued successfully. Failure, disconnect, teardown or missing playback
heartbeats restores native output.

Android timeout codes now distinguish offer, answer, ICE, application-PCM capture
and browser-readiness stalls. ICE gathering with no well-formed local candidates
fails explicitly. The browser reports the failed stage and bounded counts of
signaled browser/Android candidates rather than suggesting a generic APK update.
Its in-memory diagnostic snapshot also distinguishes DTLS, missing track, RTP,
selected-pair visibility, playback and acknowledgment. The last setup snapshot
survives teardown for inspection and resets on the next audio request. It contains
only fixed state names, booleans and counts, never addresses, SDP, credentials,
raw error text or media. Diagnostics do not weaken any handoff prerequisite.

Signaling remains in the existing paired LAN connection and is capped at a
6,000-character SDP, 1,024-character candidates, and 32 candidates per side.
Only a single receive-only audio answer is accepted. Native peer operations and
callbacks run on one bounded 64-entry worker queue. Teardown invalidates queued
work, then disposes the peer, track, source, factory, and ADM on that worker;
it never joins/disposes from WebRTC's capture callback. Connection loss restores
the native route through the route owner.

## Licenses

The POM identifies BSD-3-Clause for WebRTC. The publisher's packaging repository
is MIT. The aggregate includes WebRTC and its separately licensed third-party
components, including Opus; it is kept verbatim at the exact release tag:

- [Upstream aggregate](https://github.com/webrtc-sdk/android/blob/v150.7871.01/Licenses/WEBRTC.md)
  → `licenses/dependencies/WebRTC-150.7871.01-THIRD-PARTY.md`
- [Packaging MIT license](https://github.com/webrtc-sdk/android/blob/v150.7871.01/LICENSE)
  → `licenses/dependencies/WebRTC-SDK-MIT-LICENSE.txt`

Retain these notices with source and binary distributions. The AAR itself does
not embed this notice corpus; `shared` also includes `docs/licenses` as an asset
source, so the notice files accompany every APK under `assets/dependencies/`.

## Validation boundaries

The artifact manifest, ABI contents, API signatures, and Java no-AudioRecord
branches were inspected without running a local Gradle build. Pure unit tests
cover PCM ownership/silence/endianness, monotonic scheduling, interruption and
stall recovery, bounded audio-only DTLS/Opus descriptions, standard candidate
types/address ranges, malformed signaling and the embedded-HTTPS factory scope.
The user-requested full compilation, Android tests, lint and APK validation run
in GitHub Actions. Physical Android-to-browser sound, permission behavior,
stereo fidelity, native route restoration, and long-session timing still require
device testing; source/bytecode inspection is not a hardware test.


