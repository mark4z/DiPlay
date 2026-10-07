# Original Android host without BYD hardware integration

This branch starts from original `main` (`ee1799419dc338c95652f9e2f80bc794d5a4d0db`).
It keeps the `mobile` application, original package IDs, home/settings UI and host
activities. It does not add a replacement app, module or product flavor.

## Scope

The change disconnects the live BYD hardware adapter. Historical vendor classes,
protocol helpers, documentation and assets can remain in the tree and APK. It is
not a claim that every occurrence of `BYD` has been deleted.

### Disabled hardware paths

- HUD/SOME-IP/AMap/instrument output, including dashboard song/call writes
- Physical DiLink display selection, ADB cluster-task routing and stock-map control
- Battery/range, wheel-speed and gear reads through private vehicle services
- BYD-specific numeric media/voice/call aliases and default `simulate-keys` wheel assignments
- The automatic wheel-service ADB restoration hook
- Exported debug HUD demonstration/bridge entry points and executable vehicle `app_process` helpers
- Recovery writes from old BYD HUD, cluster-mode or OEM-map journals

Old vehicle preferences and saved journals are not silently erased. They cannot
reactivate these hardware paths. An installation that previously changed a stock
vehicle component should restore it using the original build before switching;
this build deliberately does not alter the car to process an old recovery journal.

Parked-video playback is unavailable because there is no trusted gear source.
Unknown gear returns `null`, never `P` or `true`. Ordinary CarPlay screen video,
music, navigation audio and microphone input are retained.

### Retained generic behavior

- Original home and in-session settings, appearance, layout and saved preferences
- USB and all original wireless modes, authentication selection, reconnect/session
  ownership, foreground service and background behavior
- Audio focus/routing, buffer choices, 30/60 FPS, codec, scale, rotation, split-screen,
  side panel, system bars, PiP and language controls
- Android media, voice-assist and D-pad/knob input; user-learned generic keyboard or
  remote zoom/joystick mappings and explicit Android accessibility setup
- Android GPS/location reporting, standard ADB permissions and hotspot setup,
  and Android Wi-Fi scan recovery
- Shared iAP2 navigation/song/call parsers, Android media metadata, widgets,
  virtual map stream, center-map overlay and launcher map sharing
- Original user-initiated sanitized diagnostic TXT export, View/Share and picker
  fallback; no automatic telemetry was added

The virtual map continues to start at 1280×720 when no size is saved. Saved map
size, marker position and content choices now remain usable without a physical
BYD cluster, with the existing decoder capability fallback for enlarged streams.

## Existing boundaries changed

| Boundary | Before | After |
| --- | --- | --- |
| `BydNavigationOutputs` | Starts hardware workers and consumes shared phone frames | Keeps local phone state/turn overlay; output callbacks are inert |
| `BydOutputSettings` | Saved vehicle options can become effective on a detected head unit | Hardware availability/effective vehicle reporting are always off; metadata-only hotspot detection retained |
| `ClusterMapPresentation` / `AdbClusterRouter` | Select or launch physical BYD displays | No physical display or ADB route; original virtual map fallback retained |
| `DiPlayActivity` | Shows hardware and generic controls together | Hides only hardware controls; keeps original generic sections and gives the vehicle-data limitation |
| `WheelKeyService` | BYD wheel defaults plus user-learned mappings and automatic ADB restore | Generic learned mappings retained; vendor defaults/device mappings and automatic restore removed |
| `CarPlayMediaButton` / call policy | Android keys plus proprietary aliases | Android mappings retained; proprietary aliases pass through |
| Vendor output/probe/helper entry points | Can initialize private APIs directly | Fixed disabled guard before initialization, probing or writes |
| Debug manifest | Declares exported HUD demo/bridge and BYD permission | Those declarations and the permission are absent |

`BydAdbShell` is deliberately not blanket-disabled: the standard Android Wi-Fi
scan-recovery implementation uses it. Likewise, UsageStats permission and the
ordinary Android hotspot/accessibility helpers are retained.

## Tesla return entry

The original configurable OEM entry defaults to `Tesla` and a public Tesla logo.
A saved custom name or icon, including a previously saved `BYD` name, still wins.
Reset uses the new default. The existing callback still opens Android Home and
keeps the CarPlay session alive. See [artwork source and rights](../asset/carplay/README.md).

## Building and testing

Use the original targets:

```sh
./gradlew :shared:testDebugUnitTest :common:testDebugUnitTest :mobile:lintDebug :mobile:assembleDebug
```

The `mobile` APK remains the original universal Android receiver (Android 9+;
arm64-v8a, armeabi-v7a and x86_64). There is no `target=generic` choice for this
route. The ordinary source APK contains no accessory identity and cannot establish
a standalone authenticated CarPlay connection by itself.

The authenticated workflow uses one manual **Run workflow** action on `main` or
`refactor/remove-byd-hardware`. A successful manual run automatically uploads one
direct `.apk` download with one-day retention; there is no publication toggle.
That APK contains extractable authentication material and is publicly downloadable
under this public repository's artifact access rules. The separate cache
optimization shares only credential-free source caches and uses restore-only
task caching after Secrets are introduced. See [build boundaries](BUILD.md). This refactor does not run that
workflow, access real credentials, configure Secrets or publish an authenticated APK.

Automated validation cannot establish physical iPhone connectivity, head-unit
performance or real key routing. Reproduce a hardware issue while parked, then
use the original main Settings → Diagnostics → Save diagnostic report. Review the
TXT before sharing; sanitization reduces exposure but is not a guarantee that all
personal information is absent.
