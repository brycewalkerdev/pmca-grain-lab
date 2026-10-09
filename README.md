# Grain Lab

A standalone PlayMemories camera app that adds grain, halation, bloom and other film/lens effects to a selected JPEG, entirely on the camera. It does not open the capture pipeline or change Recipe Lab or the camera's stored settings. Recipe Lab provides the base color look; this app adds spatial effects and texture.

**Development disclaimer:** Grain Lab's application code was generated agentically using GPT-6.1-Sol. Vendored third-party libraries retain their original authorship and licenses.

## Install

Download `GrainLab.apk` from [GitHub Releases](https://github.com/brycewalkerdev/pmca-grain-lab/releases/latest), or build it locally into `dist/GrainLab.apk`. Install through Sony-PMCA-RE / PMCA-GUI's **Install app from file**, then launch **Grain Lab** from the camera's Application List. It has a separate package (`com.bryce.grainlab`) and can coexist with Recipe Lab.

This prototype targets Android API 10 and includes `armeabi` and `armeabi-v7a` implementations. Processing, native JPEG export and standard camera playback have been tested on a Sony A5000. Other bodies remain unverified. Native saving previously lost portrait orientation metadata. The save path now reconciles the new copy through Sony's rotation API and verifies EXIF readback; the correction still needs on-camera validation.

## Recent changes

- **Sony playback rotation support:** newly registered copies are compared with the processed backup and corrected through Sony's rotation API when their orientation differs. Already-correct images are left as they are; existing copies are not automatically repaired. Unsupported mirrored orientations and failures are recorded in `GLSAVE.TXT`. The new correction still needs an on-camera test.
- **GitHub release workflow:** builds and tests both ARM implementations, signs with the persistent installation key, and creates releases containing the APK, SHA-256 checksum and build timestamp. It supports draft releases and dry runs and does not overwrite existing build tags. See [release setup](docs/RELEASING.md) for signing secrets and usage; the workflow must be on the default branch before manual dispatch is available.
- **Visible build identity:** the browser, editor and save diagnostics show the build number and UTC timestamp so installation updates can be verified on camera.

## Use

- The browser shows six thumbnails per page and a larger preview of the highlighted JPEG. It prefers embedded EXIF thumbnails, falls back to bounded JPEG decoding, and corrects orientation. A larger selected preview falls back to the JPEG when the embedded thumbnail is too small. At most twelve small images are cached; loading is asynchronous. Empty reserved files and `.TMP` files are hidden.
- Wheel / left/right: select a JPEG. Up/down moves by two tiles. Centre: open it. On touchscreens, tap a tile to select it and tap the large preview to open it.
- Up/down: select one of the twenty-three settings/actions in the scrolling editor. From the first setting, Up wraps directly to Save copy.
- Wheel / left/right: change the selected value. Centre: increment a value, select a preset, store the user preset, toggle comparison, or save. Every effect amount can be set to Off independently.
- TRASH in the editor: switch between an approximate fit preview and a centre crop for inspecting fine texture. Orientation is respected. Neither preview requires decoding a full-resolution Java bitmap.
- MENU: return to the browser; from the browser, exit. During saving, MENU requests cancellation.
- TRASH in the browser: switch between original JPEGs and processed copies. Touch controls provide comparison, presets and saving along the bottom of the editor. Last-used settings are remembered.

## Effects and presets

| Control | Behavior |
|---|---|
| Grain / size / pattern | Monochrome procedural grain; size is normalized to a 6000-pixel long edge, pattern changes the repeatable seed |
| Halation / spread | Warm red-orange fringes outside bright sources; suppresses tint within the bright source |
| Highlight threshold | Shared halation/bloom threshold, from 40 to 95 percent of encoded brightness |
| Bloom / spread | Broad light scatter retaining the source highlight's RGB proportions |
| Diffusion | Blend toward a softly blurred image, retaining most fine detail at moderate amounts |
| Vignette | Smooth radial edge darkening |
| Light leak | Seeded warm illumination entering from one side of the frame |
| Dust / scratches | Seeded sparse dark particles and intermittent vertical marks |
| Chromatic aberration | Radial separation of red and blue, retaining full-resolution source detail |
| Lens distortion | Signed radial remapping: positive barrel, negative pincushion |
| Edge softness | Increasing diffusion toward the image perimeter |
| Output resolution | Full resolution by default; Half width / height creates roughly one-quarter as many output pixels |

Amount controls range from 0 to 100; spread controls are relative to the image's long edge. Grain size ranges from 1 to 8. The built-in presets are **Grain only**, **Soft print**, **Night glow**, and **Aged compact**. These are texture/effect combinations, not color grades or calibrated simulations of specific film stocks. **Store user preset** saves one reusable configuration in app-private storage; select **User preset** to reload it. Saving a preset replaces the previous user slot. Uninstalling the app removes that slot, but leaves processed JPEGs on the card.

Originals are discovered under `DCIM`, excluding the app's DCF grain folders. New results are saved separately under a dedicated folder such as `DCIM/101GRAIN/GLAB0001.JPG`. The allocator chooses an unused three-digit DCF folder number, reuses existing `NNNGRAIN` folders, and avoids existing file numbers across JPEG/RAW/other filename prefixes. Original files are opened read-only. Old root-level `GRAIN` files are retained and remain accessible in the processed-copies browser.

Full resolution retains the original dimensions; Half width / height uses JPEG IDCT downsampling (6000×4000 becomes 3000×2000) and updates EXIF/TIFF image dimensions while preserving orientation. Saving uses a temporary file, and the result is published only after a successful card flush. Cancellation and failures before publication remove only files owned by the current job. A power loss can leave a `.TMP` file and a zero-byte reserved `.JPG`; these can be removed from the card on a computer. Existing nonempty JPEGs are never replaced.

## Standard camera playback

The A5000 camera test showed that `waitAndUpdateDatabase` does not import app-written JPEGs. The Sony provider also rejects arbitrary inserts and updates. DCF-compatible naming alone is insufficient.

After publishing the grain backup in `DCIM/NNNGRAIN`, Grain Lab resolves the original photo's Sony catalog identifier and creates a separate scaler-produced image at the processed JPEG's dimensions. Full resolution uses an intermediate half-size image to avoid a same-size passthrough alias. The memory descriptors are checked for allocation overlap with the original. The temporary DSP descriptor helper is released before encoding.

A native uploader reads the processed JPEG as full-range YCbCr scanlines, packs UYVY with paired chroma averaging, fills padding with black, and transfers blocks of at most 64 KiB through Sony's buffer API. It checks dimensions, viewport offsets and the actual Sony allocation size. No full-resolution Java bitmap is allocated. Pixel data comes from the edited JPEG, not the original's catalog entry. Original files are opened read-only.

The asynchronous Sony JpegExporter camera-save overload receives the populated image and external media ID. Sony chooses the normal DCIM filename/folder. A successful callback is followed by short bounded retries looking for exactly one newly created JPEG confirmed in Sony's native catalog. A preexisting file cannot confirm a new save. Failure retains the grain backup. Callback success without catalog confirmation remains explicitly unconfirmed for playback. Save timings and the backup folder's `GLSAVE.TXT` describe upload, callback and destination.

The camera-save wait is bounded to 90 seconds; outstanding native resources remain alive until the real callback and further native saves are blocked in the meantime. No raw database insert/edit or automatic retry of the camera save is performed. Native camera saving and playback have been confirmed on the A5000. Portrait orientation is now reconciled through Sony's rotation API after registration; verify the correction on-camera and in a PC viewer. Existing grain copies are not imported automatically, and the selected source must have a Sony catalog entry.

## Saving speed and diagnostics

The optimized processor packages two native implementations. `armeabi-v7a` targets ARMv7/Thumb-2/VFPv3-D16, enabling hardware floating-point arithmetic throughout the effect loop; `armeabi` retains the older compatibility implementation. Android selects the A5000's advertised primary ABI automatically. The ARMv7 build is checked for VFP instructions and absence of single-precision soft-float helper calls in `film.o`. Both use libjpeg-turbo with runtime-detected NEON acceleration and scalar fallback; hardware floating point does not require NEON. The API 10 baseline and native-import audit remain in place. Confirm **ARMv7 hardware FP** in Last save timings on the camera.

With distortion and chromatic aberration Off, rows are processed directly without lens-coordinate calculations or bilinear source sampling. Grain-only processing skips linear-light conversion. Grain lattice rows, normalized coordinates, leak factors, scratch patterns, dust positions, interpolation positions and transfer tables are cached; disabled effects skip their calculations. The effect equations are checked against the frozen pre-optimization reference. Full-resolution, all-effects-Off saves are byte-for-byte copies, retaining the original embedded thumbnails and auxiliary images without recompression.

Select **Last save timings** in the editor to inspect preparation, decode/read, effect processing, encode/write, final flush and total native time, plus the codec's selected NEON/scalar path. These results persist across app restarts. Encoding time includes writes made inside the encoder; on the copy-only path it records file copying. Total covers the native processing call, including cleanup, and excludes the short Java publishing/preferences step. Saving progress stays below 100% until the card flush completes; the UI explicitly shows the final writing stage. The durable flush remains enabled.

Desktop median wall times from three runs on the same 24-megapixel input (a repository sample enlarged to 6000×4000, with scalar host codec):

| Settings | Previous build | Optimized build |
|---|---:|---:|
| Grain only | 2.23 s | 0.73 s |
| Bloom only | 3.04 s | 0.92 s |
| Combined effects, full resolution | 5.17 s | 2.40 s |
| Combined effects, half width / height | — | 0.68 s |

These are host comparisons, not camera predictions or SD-card measurements. `benchmark.py --input photo.jpg --baseline out/baseline/grain-process.exe` reproduces comparisons against a retained build; the JSON reports and output copies are stored under ignored `out/benchmark`.

## Processing

The processor combines interpolated grain with finer noise, adding a common encoded-luminance perturbation to RGB and attenuating it toward blacks and highlights. Optical effects work in linear-light RGB using the sRGB transfer function. There is no global color grading or LUT stage. sRGB camera JPEGs are the intended input; Adobe RGB or unusual ICC profiles are copied but are not converted into a managed working color space, so optical behavior on those files is approximate.

Halation, bloom and softness build linear-light maps with a maximum 640-pixel long edge in an initial JPEG decoding pass. The second pass uses full-resolution scanlines and a rolling row buffer for geometric lens effects. The row buffer is capped at 16 MiB; a lens configuration that would exceed that bound is rejected. Map buffers and the temporary blur workspace together use at most about 10 MiB, plus JPEG codec/metadata and preview allocations. No full-resolution Java bitmap is allocated.

Preview and export share the same native effect engine. Fit previews sample grain at reduced resolution and are approximate. Centre crops use full-resolution source pixels and whole-image glow maps, so off-crop highlights still contribute. Glow maps approximate small highlight structures, and the model is aesthetic rather than a complete physical film simulation. Saving applies all selected effects in one encoding pass.

For processed or resized images, JPEG quality is 95, source chroma sampling factors are retained for three-component camera JPEGs, and no full-image optimization pass is used. EXIF (including orientation and MakerNotes), ICC, XMP and comments are copied. The EXIF pointer to the old embedded thumbnail is cleared so viewers do not show an unprocessed thumbnail. A new embedded thumbnail is not generated; the browser falls back to decoding the processed JPEG. Re-encoding is lossy. The full-resolution, all-effects-Off copy path preserves the complete original file without recompression or metadata edits.

For re-encoded copies, Sony MPF indexes are removed because they contain offsets into the original JPEG and auxiliary images, which are not copied into the processed file. ICC APP2 markers are retained separately.

The initial version accepts baseline, single-scan, 8-bit grayscale or YCbCr JPEGs. Progressive, multi-scan, arithmetic-coded and CMYK JPEGs are rejected before full-image buffering. RAW files are excluded. This includes typical camera JPEGs, but unusual files transferred onto the card may be unsupported.

Saved images can be browsed inside Grain Lab and copied off the card. Native playback registration is requested and checked as described above; physical-camera playback and transfer-tool behavior require verification.

## Sony image acceleration test

Select **Sony acceleration test** in the editor to benchmark the firmware's MAIN JPEG decode, half-size scaling, mild soft-focus filtering, and JPEG stream encoding. The test uses Sony device-memory images, retains ownership of its input, releases buffers/filters/exporters, and drains encoded streams without creating a camera image. It does not modify capture settings. Unsupported operations and exceptions are reported.

These proprietary paths are not automatically substituted into the effect pipeline because their speed, transfer costs, appearance and export behavior have not been measured on the A5000. The hardware-FP processor is active independently of this test. Optional Sony shared-library declarations are included; proprietary firmware/JAR binaries are not packaged in the app.

## Build and tests (Windows)

GitHub can build signed APKs and create draft releases using the **Create release**
workflow. See [release setup and commands](docs/RELEASING.md) for the persistent
signing-key secrets and dry-run option.

```powershell
powershell -ExecutionPolicy Bypass -File build.ps1
powershell -ExecutionPolicy Bypass -File build.ps1 -TestsOnly
```

Requirements:

- Python 3; no Python packages required by the build.
- JDK 17 and Android build tools (`aapt`, D8, `zipalign`, `apksigner`) plus `android.jar`. Set `GRAIN_ANDROID_TOOLS` to a directory with `java/<jdk>/bin` and `android/<platform-or-build-tools>` children. The local fallback uses the existing `flappy-bird/tools` bundle beside this workspace.
- NDK r16b; set `ANDROID_NDK`. The local fallback uses `sony-doom/tools/android-ndk-r16b` beside this workspace. Compilation limits headers to API 9; linking uses r16b's oldest available API 14 stubs, with an audited list of legacy Bionic imports.
- GCC for Windows host tests; set `GRAIN_HOST_GCC` to `gcc.exe`. The local fallback uses `sony-doom/tools/w64devkit/bin/gcc.exe`.

`GRAIN_JPEG=ijg` selects the previous scalar IJG backend for comparison; the default is `turbo`. Both use unmodified vendored library sources, with generated configuration headers in ignored `out/`. The libjpeg-turbo dispatcher is compiled without thread-local storage to support the old loader; native jobs are serialized by the app's single worker. SIMD C/assembly is compiled separately from scalar code. No `-ffast-math` is used. `python build-native.py --armv7` builds the hardware-FP implementation; the normal APK build includes it alongside the generic fallback.

The build runs Java file-discovery, navigation, effect-range and preset tests before packaging, including migration of existing sixteen-field user presets to full-resolution defaults. Native tests include 162,000 optimized/reference channel comparisons (allowing one code value; the host run found zero difference), combined effects at 6000×4000, half-resolution saves with little- and big-endian EXIF dimension updates, halation/bloom highlight behavior, diffusion, vignette, seeded defects, lens resampling against an independent full-image reference, both preview modes, metadata/orientation preservation, copy-only byte integrity, cancellation, invalid input and progressive-JPEG rejection. The APK is checked for API 10 platform members, DEX 035, ARM ELF format, absence of text relocations, zip alignment and v1 signatures. A persistent local development signing key is generated in ignored `out/`.

`out/host/grain-process.exe` is a host verification tool built alongside the native tests. It can process test photos with the same C engine as the camera app: `grain-process input.jpg output.jpg` uses a soft default combination; sixteen optional effect values and an optional seventeenth output-resolution value (0 full / 1 half) match `FilmSettings.java`. The tool prints the timing breakdown.

English, Simplified Chinese and Traditional Chinese labels are included, with small static CJK fonts for the old camera renderer. To regenerate font assets after translation edits, install `fonttools` and run `python subset-font.py`; the upstream font sources are pinned by commit and SHA-256. Normal builds use the checked-in subsets and do not download fonts.

## Camera test

1. Put a JPEG on the card by taking a normal camera photo. Install and open Grain Lab.
2. Browse several thumbnail pages, including landscape JPEGs and orientation-tagged portrait JPEGs. Verify the selected large preview follows the wheel.
3. Compare grain strength 0 / 35 / 70 and size 1 / 2 / 4. Try Soft print and Night glow on a scene containing bright lights; compare Original / With effects and fit / detail views. Save a copy.
4. Check that the copy appears under Grain Lab's processed-copies browser, with the correct orientation and a generated preview. Test both signs of distortion, chromatic aberration and edge softness on a detailed scene.
5. Cancel another save, then verify the original is intact and no partial result is presented as complete.
6. Store and reload a user preset. Compare Full resolution and Half width / height saves, including an orientation-tagged portrait JPEG. Open Last save timings and record the stage breakdown and NEON/scalar backend. Repeat several saves, observe responsiveness, then power-cycle and reopen.
7. Copy originals and outputs to a computer; check image dimensions, EXIF and visual grain at 100%. Measure camera performance here rather than inferring it from host test timing.
8. Confirm the backend reports ARMv7 hardware FP on the A5000. Run Sony acceleration test and capture its report. Confirm each processed copy appears in normal Sony playback after leaving Grain Lab, including after a power cycle. If not, record whether the app reported confirmed playback save or unconfirmed export.

## Third-party acknowledgement

This software is based in part on the work of the Independent JPEG Group.

The unmodified IJG JPEG 9f source is in `vendor/jpeg-9f`; its copyright and distribution terms are in `vendor/jpeg-9f/README`. Grain Lab adds a separate `native/jconfig.h` build configuration. The upstream download is `https://www.ijg.org/files/jpegsrc.v9f.tar.gz`, SHA-256 `04705c110cb2469caa79fb71fba3d7bf834914706e9641a4589485c1f832565b`.

The unmodified libjpeg-turbo 2.1.5.1 source is in `vendor/libjpeg-turbo-2.1.5.1`. Its notices and distribution terms are retained in `LICENSE.md`, `README.ijg` and the source files. Download: `https://github.com/libjpeg-turbo/libjpeg-turbo/archive/refs/tags/2.1.5.1.tar.gz`, SHA-256 `61846251941e5791005fb7face196eec24541fce04f12570c308557529e92c75`.

The browser and editor show the build number and UTC build timestamp directly beneath the app title. The same identity appears in Last save timings and new Sony save reports. The timestamp is generated when building the APK, so it identifies the installed artifact rather than the camera clock. `dist/GrainLab.build.txt` records the expected display for the accompanying APK.

The MAIN reader retries the firmware-native `/android/mnt/sdcard/...` path if the Android `/mnt/sdcard/...` path fails. Both attempts and results are recorded. This is a fallback based on the inspected native filesystem path; support for arbitrary processed JPEGs still requires a camera test. Diagnostics use the short `GLSAVE.TXT` filename for compatibility with the camera card filesystem.

Sony acceleration test now requires an indexed original and obtains its actual `avindex://` identifier from the Sony catalog. Both filesystem decoder paths failed on an original A5000 JPEG in the camera test. The updated diagnostic tests catalog-based MAIN decode, scaling and JPEG stream output without saving a new camera image. The Save copy action now uses a separate processed-pixel upload and native camera-save path; playback registration has been confirmed on the A5000.

Every Sony acceleration test writes its complete result, build stamp and selected image path to `GLTEST.TXT` in the SD card root, replacing the previous test report. The file is flushed before completion. Success or write failure is shown in the test dialog and kept in the app-private diagnostic record. This also records decoder failures, without requiring another processed save.

The Sony diagnostic writes GLTEST.TXT before the first operation and updates it before catalog lookup, MAIN decode, filter configuration/execution, JPEG encode/read and cleanup. A dedicated probe thread keeps the general worker free once the 30-second wait limit expires. A timed-out native operation is not forcibly cancelled or freed; subsequent Sony saves/tests are blocked until it actually completes or the camera restarts. The partial file identifies the last operation reached. The dialog displays a report-write failure if the card cannot be written.

The A5000 camera log confirmed original catalog-based MAIN decoding, scaling and JPEG stream encoding work. Sony SoftFocus execute stalled, so the default diagnostic skips it. This does not change Grain Lab's CPU film effects. Playback registration of processed JPEGs still requires a working processed-image input path.

The Sony acceleration test also probes public DSP buffers: a 4096-byte scratch round trip, a bounded offset transfer, a separate 64x48 image allocation, and memory/canvas/target descriptors. The original image is queried only. Results go to root GLTEST.TXT. This gathers the data needed for processed-pixel import; it does not yet write pixels into a Sony image or register processed JPEGs.

The diagnostic now includes a native read-only sample bridge. It calibrates against the public scratch buffer before reading three small original-image samples. Reads are limited and checked against Sony's reported allocation size; no image bytes are written. GLTEST.TXT records sample offsets and hex data to establish pixel ordering before implementing import. The diagnostic is separate from the Save copy registration path.

After successful native reads and source-JPEG comparison, the diagnostic now tests writes in a separate 64x48 image. It calibrates the scratch write path, fills UYVY color bars, verifies sample rows and writes GLBUF.JPG to the SD root through Sony's JPEG stream encoder. This test artifact is not registered in playback; GLTEST.TXT records every stage. Host inspection of its dimensions/colors is required before using the path for processed photos. Originals are not supplied to the writer.

The bare 64x48 DSP image accepted pixel writes/readback on the A5000 but could not be stream-encoded. The write diagnostic now uses a separately scaled half-resolution copy, paints a small center color-bar patch and exports GLBUF.JPG. Allocation overlap checks prevent original-buffer writes; the patch coordinates appear in GLTEST.TXT. This remains a stream-export test, not a playback-registration implementation.

The current JPEG write control releases extra DSP probe allocations before encoding, then encodes the unchanged scaled template before applying the patch. It keeps one exporter alive across both stream operations and records exact native callback codes in GLTEST.TXT. Failure of the unchanged control prevents image writes. This diagnostic itself does not register a playback image; the Save copy action does.

Build 19 replaces the failed file-URI processed-image decoder route with the processed-pixel upload and native camera-save path. The earlier diagnostic history above describes development tests, not confirmation of production registration.

The font subset helper is adapted from Recipe Lab under the MIT terms in `tools/LICENSE.recipe-lab`. Font assets retain their SIL Open Font License notice; vendored JPEG libraries retain their upstream licenses.

This repository is standalone. For a portable build, configure `GRAIN_ANDROID_TOOLS`, `ANDROID_NDK`, and `GRAIN_HOST_GCC`; local fallbacks also recognize sibling tool repositories. Font regeneration uses the bundled helper and does not require a Recipe Lab checkout.

After a newly created JPEG is uniquely confirmed in the Sony catalog, Grain Lab compares its EXIF orientation with the processed backup. Standard 0/90/180/270-degree differences are corrected using Sony's own rotate operation, then checked by reading the saved EXIF tag. Already-correct copies are not rotated. Unsupported mirrored orientations and API failures are reported in GLSAVE.TXT; no separate EXIF rewrite or original-file edit is performed. Existing saved copies are not automatically repaired.
