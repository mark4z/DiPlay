# Credits and license notices

## Receiver

DiPlay is a modified version of [xcertplay by shilapi](https://github.com/shilapi/xcertplay). The upstream receiver is licensed under GNU GPL version 3; the full text is in `LICENSE` and the original README is retained in `docs/UPSTREAM-README.md`.

Upstream credits [LIVI](https://github.com/f-io/LIVI) and [Showcase](https://github.com/amineross/showcase) for protocol research. Existing source comments and attribution are preserved.

## Home and settings UI

`common/src/main/java/com/shilapi/xcertplay/DiPlayActivity.kt` adapts the palette, visual arrangement and interface copy of the [DiAuto project](https://github.com/shihabal3amri/DiAuto). DiAuto's source is licensed under AGPL version 3. The UI file is marked AGPL-3.0-only; its license text is included in `docs/licenses/DiAuto-AGPL-3.0.txt`.

## CarPlay icon

The unmodified icon was obtained from Apple's developer site at:

https://developer.apple.com/assets/elements/icons/carplay/carplay-96x96_2x.png

CarPlay and the CarPlay icon are Apple Inc. marks/assets. This asset is not covered by the project's open-source code license. Its use here does not imply Apple approval or certification.

## Runtime dependencies

### Optional WebRTC video transport

The JNI bridge and narrow media-send feedback fix are adapted from
[WheelPlay](https://github.com/fython/wheelplay/tree/c1badd5711159fe0ae9057773fbc240d0e462191)
(GPL-3.0). DiPlay uses upstream H.264/H.265 packetizers, without WheelPlay's custom
packetizer or SRTP optimization patches. Encoded video is forwarded without transcoding.

Native dependency commits are recorded in
`common/src/main/cpp/dependencies.lock.json`. The libdatachannel commit pins its
submodules. Full dependency notices are packaged in
`docs/licenses/dependencies/WebRTC-LICENSES.txt`:

- libdatachannel 0.24.1 and libjuice 1.7.0 — MPL-2.0
- Mbed TLS 3.6.5 — Apache-2.0 OR GPL-2.0-or-later
- libSRTP 2.7.0 and usrsctp — BSD-style licenses
- plog 1.1.10 — MIT

The source distribution includes the dependency pins and all local patches.
The patches preserve the upstream files' MPL-2.0 notices. No media codec
implementation, audio transport or third-party signaling server is included.

### Managed dependencies

- AndroidX and Jetpack Compose — Android Open Source Project; Apache License 2.0.
- Kotlin standard library — JetBrains; Apache License 2.0.
- Bouncy Castle 1.79 — The Legion of the Bouncy Castle Inc.; Bouncy Castle license (MIT-style).
- JmDNS 3.6.3 — JmDNS contributors; Apache License 2.0.
- SLF4J — QOS.ch; MIT license.
- AndroidX Media3 — Android Open Source Project; Apache License 2.0.
  The native video player uses ExoPlayer, HLS and player UI components.

Gradle dependency declarations and version catalog accompany the source. License files available in the resolved artifacts are included under `docs/licenses/dependencies/`.

## Experimental authentication data

The public preview APK includes an accessory certificate/key pair recovered from public Carlinkit C2Air Allwinner V821 firmware during the owner's local investigation. These data are not newly generated Apple-issued credentials for DiPlay and are not relicensed as project source code. They are bundled in the preview APK to reproduce the offline experiment; continued acceptance and suitability for general distribution are unresolved. The source archive does not contain the private key, and the separate Android APK-signing key is never distributed.

## Download website

The static site layout, CSS and generator adapt DiAuto (AGPL-3.0). The AGPL license text is included with the source.

## BYD HUD maneuver icons

Required Notice: Copyright AndyShaman (https://github.com/AndyShaman/BYDMate)

The maneuver PNGs under `shared/src/main/assets/byd-hud-icons` were imported from BYDMate. Its PolyForm Noncommercial 1.0.0 terms and required notice are included alongside the assets. These files are separate from the project code license; upstream describes them as donor assets and their original provenance is not independently established. The validated DiLink5.1 windshield path uses factory turn codes rather than these images.

## Default OEM return-entry artwork

The Tesla return-entry logo uses a pinned public vector source. Source, rendering
and trademark details are in [the artwork notice](../asset/carplay/README.md).
This changes the configurable CarPlay return-entry default, not the app icon.

