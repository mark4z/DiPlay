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
The Android sender accepts/emits only host candidates with RFC1918, loopback,
link-local, IPv6 ULA, or `.local` mDNS addresses. It rejects public host addresses
and server-reflexive/peer-reflexive/relay candidates in SDP and trickle messages.
The Java API does not pin ICE to one physical interface: this is a restriction
on signaled endpoints, not a claim of interface binding. mDNS is resolved by the
WebRTC stack, never by this candidate validator.

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
stall recovery, bounded answers, candidate types and local-address filtering.
The user-requested full compilation, Android tests, lint and APK validation run
in GitHub Actions. Physical Android-to-browser sound, permission behavior,
stereo fidelity, native route restoration, and long-session timing still require
device testing; source/bytecode inspection is not a hardware test.
