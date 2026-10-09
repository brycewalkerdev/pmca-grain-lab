# GitHub releases

The standalone Grain Lab repository has a **Create release** workflow at
`.github/workflows/create-release.yml`. It builds the selected commit on Windows,
runs all Java and native host tests, builds both ARM variants, audits API 10 and
native imports, and verifies the signed APK. A separate job creates the GitHub
release only after the build passes.

## One-time signing setup

Release APKs must use the same key as existing installations. Preserve the local
`out/grain-lab.keystore` and configure these repository Actions secrets:

| Secret | Value |
|---|---|
| `GRAIN_KEYSTORE_B64` | Base64 contents of that existing keystore |
| `GRAIN_STORE_PASSWORD` | Keystore password (`android` for the current development key) |
| `GRAIN_KEY_PASSWORD` | Key password (`android` for the current development key) |

The alias is `grainlab`. For example, from the standalone repository directory:

```powershell
$grainKey = [Convert]::ToBase64String([IO.File]::ReadAllBytes((Resolve-Path 'out/grain-lab.keystore').Path))
$grainKey | gh secret set GRAIN_KEYSTORE_B64 --repo brycewalkerdev/pmca-grain-lab
gh secret set GRAIN_STORE_PASSWORD --repo brycewalkerdev/pmca-grain-lab
gh secret set GRAIN_KEY_PASSWORD --repo brycewalkerdev/pmca-grain-lab
Remove-Variable grainKey
```

The last two commands prompt for the passwords. Do not commit the key, paste its
contents into an issue, or generate a new key for each release. CI refuses to
build a release without the configured signing identity. The decoded key is
placed in runner temporary storage and removed after signing.

## Run the workflow

Commit and merge the workflow into the repository's default branch first. Open
**Actions → Create release → Run workflow**, select the source branch, and run it.
By default it creates a **draft** for review. Select `dry_run` to build and upload
the downloadable Actions artifact without creating a tag or release. Dry runs
still require the signing secrets.

```powershell
gh workflow run create-release.yml --repo brycewalkerdev/pmca-grain-lab --ref main -f dry_run=true
gh workflow run create-release.yml --repo brycewalkerdev/pmca-grain-lab --ref main -f draft=true
```

Each release uses `build-N`, where `N` is the build number in `build.ps1`, and tags
the exact built commit. Existing tags and releases are never overwritten; advance
the source build number for a new release. The same number becomes Android's
versionCode and the on-camera build label. CI does not change the checked-in
manifest or build number. The UTC timestamp identifies the particular build.

The release and Actions artifact contain `GrainLab.apk`, its SHA-256 checksum,
and `GrainLab.build.txt`. Release notes include the build stamp, commit, and
GitHub-generated changes. Inspect the draft, test the APK on camera, then publish
the draft through GitHub. Automated checks cannot validate Sony playback behavior.

## Toolchains

The workflow installs Temurin JDK 17, Python 3.12, Android platform 28 / build tools
35.0.0, MinGW64 GCC for host tests, and NDK r16b. The NDK download is checked against
Google's published checksum before extraction and cached for subsequent runs.
The bundled font subsets and vendored JPEG sources require no extra downloads.
`GRAIN_JAVA_HOME` and `GRAIN_ANDROID_SDK` select the standard Java / Android SDK
layout; the existing local `GRAIN_ANDROID_TOOLS` bundle remains supported.

References: [NDK archive and checksums](https://github.com/android/ndk/wiki/Unsupported-Downloads),
[GitHub release CLI](https://cli.github.com/manual/gh_release_create).
