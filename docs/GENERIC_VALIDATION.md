# Generic Android validation — 2026-10-06

Public-source baseline: `ee1799419dc338c95652f9e2f80bc794d5a4d0db`.
Implementation branch: `refactor/generic-android-variant`.

## Passed

- Shared BYD JVM/Robolectric tests: **832**
- Shared generic JVM/Robolectric tests: **596**
- Existing common-host JVM/Robolectric tests: **668**
- New generic-host JVM/Robolectric tests: **27**
- Total JVM/Robolectric: **2,123**, with zero failures, errors or skipped tests
- Python validation/authentication-helper tests: **32**, using synthetic data
- Generic, mobile and automotive debug lint: zero errors
- Generic, mobile and automotive debug APK assembly, including native libraries
- Generic source boundary and actual APK DEX/manifest/resource/asset inspection
- Public-tree credential-container check and whitespace checks
- Manual workflow YAML/shell validation, fixed target selection and rejection of invalid targets

The generic APK contains the independent package `com.shihab.diplay.generic`, targets SDK 37,
supports API 28+, includes ARM64/ARMv7/x86_64 native libraries and contains no runtime identity.
The boundary check confirms the required protocol/media/state classes are present and excludes
BYD adapters, vendor package queries, HUD assets and settings.

Final local source-only APK: 16,179,784 bytes.
SHA-256: `1fdf8c77b7b25fcd2385d4ff0161dbc8b21b8546d5d3fcbc82b127ecc8d97c8c`.
Debug signing keys and build metadata mean a later build need not have the same hash.

## Toolchain and runner notes

Validated with Temurin 25.0.4.1, the Gradle 9.5.0 wrapper, Android platform 37.0 revision 2,
and NDK 28.2.13676358. The cloud runner used an isolated source-only toolchain/cache.
Mockito was supplied as a test-JVM startup agent because this runner does not support its
automatic JVM self-attachment. The existing Same-LAN test fixture was made portable to a
loopback-only sandbox; its mocked Wi-Fi transport, addresses and assertions are unchanged.

The full aggregate passed, followed by a successful generic test/lint/build/APK-boundary rerun
after the final accessibility change. The final generic lint report has 15 warnings and zero
accessibility warnings; mobile has 18 warnings and automotive has 4. Remaining generic warnings
cover English-only UI text, SDK-guarded permission constants, the simple TV banner's dimensions,
version-catalog style and the inherited USB Lockdown trust manager. The latter is separate from
remote authentication's normal HTTPS validation and was not changed by this refactor.

## Still requires device validation

No APK was installed and no physical Android/iPhone connection test was performed. No real
authentication identity was used or uploaded during validation. The authenticated workflow's
mobile/generic routing, verification and cleanup were tested with synthetic fixtures only.

Follow the [physical-device checklist](GENERIC_ANDROID.md#verification) before claiming hardware
support. The generic UI is intentionally foreground-only and English-only; vehicle integrations
and optional parked-video playback are excluded. A usable authentication backend is still required.
