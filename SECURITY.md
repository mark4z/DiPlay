# Credentials and release packaging

Public release distribution has resumed at the maintainer's request. The APK intentionally bundles the experimental accessory certificate and matching key described in docs/THIRD_PARTY_NOTICES.md. Anyone with the APK can extract them. Local compilation, Git history removal and obfuscation do not make a bundled shared key confidential or revoke previous copies.

The public Git tree and corresponding source archive exclude accessory keys and Android release-signing secrets. CI checks reject credential containers and private-key blocks in tracked files. Synthetic test identities are generated at runtime. Source builds have no automatic private-asset import; release packaging requires an explicit local directory and permits only the two expected runtime files.

The Android APK-signing key is separate, stays local and is never bundled in the APK. Current acceptance of the experimental accessory identity does not establish Apple certification or guarantee future compatibility.

Review diagnostic reports before posting. Never include credentials or pairing records in public issues. Use GitHub private vulnerability reporting for sensitive findings.


The manual authenticated-debug workflow is limited to `main` and the exact
`refactor/remove-byd-hardware` branch. Every successful manual run automatically
uploads the verified authenticated APK for direct `.apk` download with 1-day
retention; there is no publication toggle. Starting the workflow requests this
public disclosure of the extractable accessory identity. Artifact expiry does
not revoke downloaded copies. Review the selected branch and
[build instructions](docs/BUILD.md) before running. Credentials enter only the
fresh build job, whose source-cache restore is read-only and never saved back.
