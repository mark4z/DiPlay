# Building DiPlay

Requirements: JDK 25, Android SDK 37, NDK 28.2.13676358 and the included Gradle wrapper.

## Source and CI builds

```sh
./gradlew :shared:testDebugUnitTest :common:testDebugUnitTest :mobile:lintDebug :mobile:assembleDebug
```

The resulting source-only APK contains no accessory identity. Standalone CarPlay requires runtime authentication provisioning. Tests generate synthetic identities at runtime; no test private-key files are tracked.

## Local release packaging

Provide an external asset directory using `DIPLAY_AUTH_ASSETS_DIR`. The directory must contain exactly the intended runtime files under `offline-mfi/identity.pk8` and `offline-mfi/certificate.p7b`. Neither file belongs in Git. The build permits those two files only when this explicit input is set and rejects unexpected credential containers elsewhere in APK assets.

Set `ANDROID_KEYSTORE_PATH`, `ANDROID_KEYSTORE_PASSWORD`, `ANDROID_KEY_ALIAS`, and `ANDROID_KEY_PASSWORD` locally for your Android signing key. Never commit these values or the keystore. Different signing keys cannot update an existing project-signed installation.

```sh
./gradlew :shared:testDebugUnitTest :common:testDebugUnitTest :mobile:lintRelease :mobile:assembleRelease
```

Output: `mobile/build/outputs/apk/release/mobile-release.apk`. The release APK deliberately contains the experimental identity described in the notices; it is extractable by recipients. The separate Android signing key is not included. The retired build-beta.py helper is not used; this Gradle workflow uses explicit environment inputs.

The public release source archive corresponds to the tagged source and excludes runtime identities, signing keys, local configuration and build output.

## Standalone car-test APK

Use `:mobile:assembleStandaloneDebug` for a test APK that must connect to an iPhone:

```sh
DIPLAY_AUTH_ASSETS_DIR=/absolute/path/to/runtime-assets ./gradlew :mobile:assembleStandaloneDebug
```

This task refuses missing or empty runtime inputs. `assembleDebug` remains an identity-free
source/CI build when the explicit asset input is absent; do not install that output as a
standalone car-test package. Before delivery, verify both `assets/offline-mfi/identity.pk8`
and `assets/offline-mfi/certificate.p7b` in the APK against the selected local inputs.
Update the existing test app without uninstalling it to preserve its settings.

## Manual GitHub Actions authenticated debug build

The **Build authenticated debug APK** workflow is manual-only and runs only on
`main`. Ordinary **Android checks** and **Build DiPlay Android TV source APK**
remain source-only and never reference these Secrets.

### Important boundaries

- Base64 is encoding, **not encryption**. Only use identity material you are
  allowed to use and send to GitHub. Company/device upload restrictions still
  apply: do not move restricted local files into Secrets, this chat, a repository,
  or a cloud runner. The helper does not download or upload anything.
- The resulting APK contains both authentication files and recipients can extract
  them. In a **public repository**, signed-in users with repository read access
  can download its Actions artifacts. Treat publishing this APK as public
  disclosure of the identity. Secrets protect the input, not the packaged APK.
- `publish_apk` is **off by default**. Leaving it off builds, verifies, and deletes
  the APK; there is no download. Turning it on explicitly publishes one APK
  artifact with **1-day retention**. Expiry/deletion cannot revoke copies already
  downloaded. The workflow does not create a GitHub Release.
- Review the current `main` commit and workflow before running. Anyone who can
  change trusted workflow/build code may cause Secrets to be disclosed. Do not
  add pull-request triggers or run unreviewed code with credentials.

### 1. Generate one Base64 value locally

Run these commands yourself on a computer permitted to access the inputs. The
helper writes only to your clipboard or a new private file, never stdout. It
reads at most 16 KiB per selected input, matching the app's runtime limit.
Clipboard history, shared desktops and clipboard sync can expose a value; turn
those off if using the clipboard and clear it after pasting.

For already-authorized standalone files (run one command, paste that value, then
run the other):

```sh
python3 scripts/encode_auth_secret.py --file /authorized/path/identity.pk8 --clipboard
python3 scripts/encode_auth_secret.py --file /authorized/path/certificate.p7b --clipboard
```

Alternatively select the matching assets from an official APK you already have
and are authorized to use. Verify the APK's provenance yourself; the helper
reads the two explicit entries without extracting the archive or authenticating
its publisher:

```sh
python3 scripts/encode_auth_secret.py --apk /authorized/path/official.apk --asset key --clipboard
python3 scripts/encode_auth_secret.py --apk /authorized/path/official.apk --asset cert --clipboard
```

For a new mode-0600 file instead, use `--output /private/non-synced/key.b64` or
`--output /private/non-synced/cert.b64` in place of `--clipboard`. The parent
folder must exist. Choose a private folder outside any checkout or synced drive.
The helper refuses to overwrite existing files or final symlinks. Private file
mode is supported on POSIX systems (macOS/Linux); Windows should use clipboard.
Open the file in a trusted local editor, paste its entire value into the matching
Secret and delete the temporary copy afterward. Do not print, commit, attach or
share these values. Do not ask an assistant to read or transfer them.

### 2. Add repository Secrets yourself

Open your repository's **Settings → Secrets and variables → Actions → New
repository secret**. Create these exact names with their respective Base64
values (use **Secrets**, not Variables or workflow inputs):

- `DIPLAY_MFI_KEY_B64`: `offline-mfi/identity.pk8`
- `DIPLAY_MFI_CERT_B64`: `offline-mfi/certificate.p7b`

For this fork: [Actions Secrets settings](https://github.com/mark4z/DiPlay/settings/secrets/actions).
Do not grant a helper additional GitHub/OAuth permissions; it needs none. The
workflow uses `contents: read` and a checkout without persisted credentials.

### 3. Start the workflow yourself

Open **Actions → Build authenticated debug APK → Run workflow**, select `main`,
review the current source, and choose whether to enable `publish_apk` after
reading the disclosure warning above. Click **Run workflow** yourself. If you
choose publication and the run succeeds, download
`DiPlay-authenticated-debug-<run ID>` from that run's **Artifacts** section before
it expires. It contains `DiPlay-standalone-debug.apk`.

For this fork: [manual build workflow](https://github.com/mark4z/DiPlay/actions/workflows/build-authenticated-debug.yml).

### What the workflow verifies and removes

First it rejects credentials in tracked source, tests the helpers using synthetic
bytes, then runs `:shared:testDebugUnitTest`, `:common:testDebugUnitTest` and
`:mobile:lintDebug` **without either Secret**. Only after those pass does it decode
the two Secrets into a mode-0700 directory outside the checkout (files mode 0600),
set `DIPLAY_AUTH_ASSETS_DIR`, and run `:mobile:assembleStandaloneDebug`. Empty,
invalid Base64 and oversized values fail with fixed messages that contain no
values. Decoding does not prove the identity/certificate pair is valid or accepted
by an iPhone.

The build verifies exactly one APK and byte-for-byte matching, uniquely named
`assets/offline-mfi/identity.pk8` and `assets/offline-mfi/certificate.p7b` entries.
Credential-stage tool output is suppressed, including failures; no secret values,
checksums or sensitive build logs are published. If that stage fails, check the
source-only test/lint logs, then verify your selected inputs privately.

No shared Gradle/cache action is used. The isolated Gradle user home is discarded;
build/configuration caches and Gradle scans are disabled. The helper removes
identity files and build intermediates on success, failure and ordinary
cancellation, and an `always()` step also removes the staged APK and caches. A
hard-killed runner cannot guarantee cleanup steps execute, so this workflow uses
GitHub-hosted ephemeral runners, never a persistent self-hosted runner. Only the
explicitly requested uploaded APK survives the runner.

This is the `.hudtest` debug application, signed with a newly generated runner
**debug key**. It may not update an existing app signed by a different key; do not
uninstall an existing test app casually because that loses its data/settings.
No Android signing key or signing password is saved, restored, or supplied by
this workflow. A successful build is not a physical CarPlay connectivity test.

See GitHub's [Secrets guide](https://docs.github.com/en/actions/how-tos/write-workflows/choose-what-workflows-do/use-secrets)
and [artifact access guide](https://docs.github.com/en/actions/how-tos/manage-workflow-runs/download-workflow-artifacts).


## Generic Android build

The separate `:generic` app selects the `generic` flavor of `:shared`, has no dependency on
`:common`, and uses the independent package `com.shihab.diplay.generic`. The existing `:mobile`
and `:automotive` hosts explicitly select the `byd` flavor and keep their original package IDs.
The legacy `:shared:testDebugUnitTest` entry point runs both shared flavor suites.

```sh
python3 scripts/check_public_tree.py
python3 scripts/check_generic_boundary.py
python3 -m unittest discover -s scripts/tests -v
./gradlew :shared:testDebugUnitTest :common:testDebugUnitTest :generic:testDebugUnitTest \
  :generic:lintDebug :mobile:lintDebug :automotive:lintDebug \
  :generic:assembleDebug :mobile:assembleDebug :automotive:assembleDebug
python3 scripts/check_generic_boundary.py \
  --apk generic/build/outputs/apk/debug/generic-debug.apk --source-only
```

The ordinary generic APK contains no accessory identity. Core authentication is unchanged:
explicit local runtime assets, CH341 hardware, board I²C, and remote authentication remain options.
`generic:assembleStandaloneDebug` validates the same explicit `DIPLAY_AUTH_ASSETS_DIR` input as
mobile, but no generic build command imports or extracts an identity automatically. Do not publish
an authenticated APK unless exposing its extractable identity is intended and authorized.

See [Generic Android scope and validation](GENERIC_ANDROID.md) for supported host behavior,
intentional omissions, and the physical-device checklist.
