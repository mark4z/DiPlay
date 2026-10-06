# Generic Android host

This fork adds a vendor-free Android host as `:generic`. It is an experimental source target,
not a claim that every Android device or iOS version is compatible. Android 9+ remains the
minimum; the native ABI set remains ARM64, ARMv7 and x86_64.

## Build and isolation

- `:generic` uses application ID `com.shihab.diplay.generic` and the `generic` shared flavor.
  It never imports old vehicle settings, ADB keys, recovery journals or pairing data.
- Existing `:mobile` and `:automotive` apps retain the `byd` shared flavor and package IDs.
  Do not replace an existing vehicle install with the generic app to bypass pending recovery.
- BYD navigation/cluster adapters, settings, vehicle probes, key codes, calibrated display
  helpers, package queries and HUD icons live under `shared/src/byd` or the existing `common`
  host. Neither source tree is an input to the generic APK.
- `CarPlayController` depends on `CarPlayVendorIntegration`. The generic factory resolves to
  `NoVendorIntegration`; the BYD factory forwards lifecycle, frames and cluster control to
  the original output implementation.
- Navigation and now-playing parsing live in neutral `iap2/state` classes. Generic song
  metadata is full length. BYD-specific arrow encoding and 255-byte text truncation stay in
  their wrappers.

## Retained core

- Existing iAP2/AirPlay transport, pairing and authentication implementations
- Wired USB/VPN bring-up and wireless Wi-Fi Direct, local-only hotspot, configured hotspot,
  and Existing Wi-Fi / Same LAN modes
- Main CarPlay video through MediaCodec, audio through AudioTrack, microphone uplink
- Touch, D-pad/rotary navigation, Siri, and standard Android media keys
- Independent identity/pairing/settings persistence, display sizing and explicit reconnect

The lightweight host owns a connection only while visible. Leaving it closes the connection;
returning can reconnect. It intentionally does not include the vehicle host's background
session service, boot launch, dashboard widgets/overlays, second-screen calibration or its full
cosmetic settings UI. The new host UI is currently English-only. A source build needs a usable authentication backend before an iPhone
can establish CarPlay.

## Optional capabilities and safety

No vehicle battery, wheel speed, gear, HUD, instrument cluster or OEM navigation service is
advertised by the generic host. In particular, absent gear information is **unknown**, never
"parked". Optional parked VideoInCar is disabled; ordinary CarPlay screen video remains enabled.

ADB is not a dependency of core CarPlay. The common Android ADB transport and scan-recovery
helpers are distinct from vendor services. `allowAdbWifiScanPause` is an explicit runtime
option, disabled in generic. The existing vehicle host retains its previous opt-in/approved
ADB behavior. Generic has no automatic permission grant or vehicle-hotspot repair UI.

Wireless still needs firmware support and classic Bluetooth, including in Same LAN mode.
Wired mode needs USB Host/OTG and VPN consent. Runtime microphone, nearby-device/location
and local-network permissions depend on the chosen mode and Android version; grant only the
ones needed on the actual device. No manufacturer check can prove compatibility.

## Verification

See [build commands](BUILD.md#generic-android-build). CI tests both shared flavors, existing
vehicle host regressions and the generic app; it then scans the built generic APK's DEX,
manifest, resources and asset names for forbidden vendor content. Source-only packaging is
also checked for unexpected authentication containers. These checks do not establish hardware
operation, and an APK inspection is required before claiming the artifact is vendor-free.

Before calling a device supported, verify on a real Android device and iPhone:

1. Clean separate install; permission denial, retry and settings return
2. Wired authentication, USB permission, VPN consent and cable reconnect
3. Each supported wireless backend, Bluetooth selection, handoff and reconnect
4. Correct video aspect ratio after resize/rotation, audio, calls and Siri microphone
5. Touch contacts/cancel, D-pad press/release/back, rotary input and media play/pause/next
6. Repeated start/stop, background/foreground, configuration changes and process restart
7. No parked-video feature, BYD service lookup, shell authorization dialog or vendor settings

Passing JVM tests, lint and assembly is build evidence, not a substitute for these device tests.
