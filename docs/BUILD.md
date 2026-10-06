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
`main` or the exact `refactor/remove-byd-hardware` branch. Ordinary **Android checks** and **Build DiPlay Android TV source APK**
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
- **Every successful manual run uploads the authenticated APK automatically.**
  There is no publication toggle or verify-only workflow mode. Starting this
  workflow requests publication of the extractable identity. The direct `.apk`
  download has **1-day retention**, with no extra ZIP wrapper. Expiry/deletion
  cannot revoke copies already downloaded. No GitHub Release is created.
- Review the selected branch's current commit and workflow before running. Anyone who can
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

Open **Actions → Build authenticated debug APK → Run workflow**, select `main`
or `refactor/remove-byd-hardware`, and review that branch's current source and
publication warning above. Click **Run workflow** yourself only when you want
its authenticated APK uploaded. There are no additional input fields. After a
successful run, download `DiPlay-standalone-debug.apk` directly from that run's
**Artifacts** section before its 1-day expiry. There is no ZIP to unpack.

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

### Cache reuse without changing the manual build flow

Use **Run workflow** on `main` or `refactor/remove-byd-hardware`. One manual
run performs two jobs automatically and uploads the verified authenticated APK:

1. **source-checks** runs the same unit tests and lint without either Secret.
   Gradle dependency downloads and task outputs can be reused. After the checks
   pass, an explicit cache-save step saves only `wrapper/dists`, `caches/modules-2`
   and `caches/build-cache-1` inside that job's temporary Gradle user home. No whole
   user home, checkout, APK directory, identity directory, configuration cache,
   daemon log or signing key is archived.
2. **build** starts on a fresh hosted runner after that job succeeds. It restores
   only the exact source snapshot key from this run, then uses the existing
   Secrets to build and verify the same authenticated APK. Gradle's local task
   cache is read-only (`push=false`); remote build caches, configuration snapshots
   and scans are disabled. This job has no cache-save action or post-job save hook.

The `auth-source-original-v1` namespace is written only by the manual source-check
job on `main` or the exact `refactor/remove-byd-hardware` branch. Keys include OS, architecture, JDK, mobile target,
build/dependency fingerprint, commit and unique run/attempt. Only the
credential-free job may fall back within that source namespace; the credential
job never falls back to old or unrelated caches. A miss or eviction still permits
a normal cold build. Ordinary source CI retains its separate source cache,
enables task-output caching, and makes pull requests read-only cache consumers.
No test task is removed.

GitHub caches in public repositories are readable by eligible pull requests,
including forks; they are not a place to store identity material. See the
[GitHub dependency-cache security reference](https://docs.github.com/en/actions/reference/workflows-and-actions/dependency-caching).
The first run can still be cold. Use later source-job `FROM-CACHE` results and
whole-run timings to assess warm-build gains; no fixed speedup is guaranteed.

The helper removes identity files and build intermediates on success, failure
and ordinary cancellation. An `always()` step also removes the staged APK and
the credential job's temporary Gradle home. A hard-killed runner cannot guarantee
cleanup steps execute, so this workflow uses GitHub-hosted ephemeral runners,
never a persistent self-hosted runner. Only the uploaded authenticated APK
survives that runner; the saved source snapshot has never been
exposed to credentials.

Cache-policy regression checks are included in `python3 -m unittest discover -s
scripts/tests -v`. To exercise actual Gradle cache behavior with synthetic text
and an isolated temporary project/home, run this with an already-installed Gradle
executable (no Android build or identity inputs are used):

```sh
python3 scripts/test_gradle_cache_policy.py --gradle /absolute/path/to/gradle
```

This is the `.hudtest` debug application, signed with a newly generated runner
**debug key**. It may not update an existing app signed by a different key; do not
uninstall an existing test app casually because that loses its data/settings.
No Android signing key or signing password is saved, restored, or supplied by
this workflow. A successful build is not a physical CarPlay connectivity test.

See GitHub's [Secrets guide](https://docs.github.com/en/actions/how-tos/write-workflows/choose-what-workflows-do/use-secrets)
and [artifact access guide](https://docs.github.com/en/actions/how-tos/manage-workflow-runs/download-workflow-artifacts).

APK uploads use pinned [`actions/upload-artifact` v7.0.0](https://github.com/actions/upload-artifact/releases/tag/v7.0.0)
with `archive: false` and one exact file path. The artifact/download name comes
from the APK filename. The separate **Build DiPlay Android TV source APK**
workflow likewise provides `mobile-debug.apk` directly, without an identity,
and retains its existing 7-day expiry. Test-report bundles remain zipped.
