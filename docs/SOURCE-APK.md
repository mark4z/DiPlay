# Credential-free CI APK

The **Android checks** workflow automatically uploads `mobile-debug.apk` after
all existing unit tests, lint checks and source-only debug builds succeed on its
configured pushes and pull requests. It also supports a manual run. Open the
successful run's **Artifacts** section to download the APK directly, with no
extra ZIP wrapper. Artifacts are retained for seven days; GitHub sign-in may be
required. This does not create a GitHub Release.

Only `mobile/build/outputs/apk/debug/mobile-debug.apk` is included in the APK
artifact. Before upload, `scripts/check_source_apk.py` inspects its ZIP entry
names and rejects bundled `offline-mfi` content, credential containers under
assets, and malformed or ambiguous archive names. The check never extracts or
prints asset contents. It checks packaging, not APK signatures or arbitrary
secrets hidden under unrelated names. Public-tree checks also run before build.
No accessory identity is restored, downloaded, supplied from Secrets or bundled
by this workflow. The existing test-report artifact remains separate; build
intermediates and caches are not APK artifacts.

An identity-free install needs a valid, authorized identity provisioned locally
on the Android device before standalone CarPlay authentication can work. A
successful CI build is not a physical CarPlay connectivity test.

## Debug signing and device data

This remains the `.hudtest` debug application, using the existing Android debug
signing configuration. CI uses an ephemeral runner debug signing key; it does
not save, restore or install a persistent signing key. An APK from another run
may therefore have a different signing identity and fail to update an existing
installation.

Do not assume settings or an imported identity survive installing a later CI
APK. A signing mismatch may require uninstalling the existing app, which erases
its private data and settings and requires importing the identity again. Keep
your authorized original identity files somewhere safe outside the app before
uninstalling. Stable signing and data-preserving upgrades need a separate,
explicitly authorized signing setup; this workflow does not provide one.

## Synthetic regression checks

```sh
python3 -m unittest discover -s scripts/tests -v
```

These checks use synthetic ZIP entries and bytes only. They cover clean APKs,
identity assets, unexpected credential containers, malformed names, duplicates,
missing files and ZIP/manifest failures, plus exact-path and success-gated
workflow uploads. They do not require Gradle or real identities.
