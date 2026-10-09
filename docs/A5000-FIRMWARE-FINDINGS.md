# A5000 firmware findings for processing performance

Inspected the user's extracted ILCE-5000 firmware 1.10 from a local firmware extraction. The firmware files were read only. No camera communication, firmware modification or flashing was performed. This is a static investigation, not an on-camera benchmark. The installed Grain Lab APK was not changed during this investigation.

## Immediate finding: enable hardware floating point in the effect loop

Firmware `0700_part_image/dev/nflasha16_unpacked_unpacked/build.prop` contains:

```
ro.product.cpu.abi=armeabi-v7a
ro.product.cpu.abi2=armeabi
ro.build.version.release=2.3.7
ro.build.version.sdk=10
dalvik.vm.heapsize=24m
```

The ELF attributes of that partition's `lib/libjpeg.so` and `lib/libcameraseq.so` identify ARMv7, Thumb-2 and VFPv3. The kernel image also contains `ARMv7 Processor` and `ARM-CXD90014` identifiers. These provide an A5000-specific target that the generic build does not exploit.

Grain Lab currently builds the generic effect code with `-march=armv5te -mfpu=vfp -mfloat-abi=softfp -mthumb`. ARMv5 Thumb-1 cannot encode VFP arithmetic instructions. Disassembling `grain-lab/out/native/film.o` found 399 static relocation sites calling `__aeabi_f*` helpers, including floating-point arithmetic, comparisons and conversions. These are call sites in the object, not a count of calls made per image or per pixel.

A temporary recompilation of the same `native/film.c`, using `-march=armv7-a -mfpu=vfpv3-d16 -mfloat-abi=softfp -mthumb`, produced zero such single-precision helper relocation sites and 366 hardware FP instruction lines in its disassembly. The temporary object is in ignored `out/firmware-research/film-armv7.o`. No unsafe fast-math flags were used. This proves an instruction-selection improvement; it does not measure its speed on the camera.

Recommended implementation: package a separate `lib/armeabi-v7a/libgrainlab.so` with ARMv7/Thumb-2/VFPv3-D16 generic C code while retaining `lib/armeabi/libgrainlab.so` for older targets. The A5000's advertised primary ABI should select the faster library through Android's normal ABI selection. Keep runtime NEON detection in the JPEG backend: ARMv7/VFPv3 alone does not prove NEON availability.

## Sony image interfaces worth probing

The method/prototype tables in `framework/sony.cameraex.odex` declare:

- `com.sony.scalar.graphics.imagefilter.ImageFilter`: `isSupported()`, `setSource(OptimizedImage)`, `execute()`, `getOutput()` and `release()`.
- `ScaleImageFilter.setDestSize(int,int)`.
- `SoftFocusImageFilter.setEffectLevel(int)`.
- Crop, rotate, contrast, noise-reduction and super-resolution filter classes.
- `DSP.createProcessor(String)`, `setProgram(String)`, `setArg(...)`, `execute()`, `cancel()` and `release()`.
- `DeviceBuffer.read/write` overloads using byte arrays, direct byte buffers and file/input streams.
- `CameraSequence.storeImage(OptimizedImage,boolean)` and its three-argument overload.

`framework/com.sony.scalar.stillPlay.odex` declares `OptimizedImageFactory.decodeImage(String)` and its `Options` overload, returning an `OptimizedImage`. The library registration file is `etc/permissions/com.sony.scalar.stillPlay.xml`.

Native `libcameraex_jni.so` registers the corresponding image-filter, DSP and device-memory wrappers. `libcameraseq.so` defines the `ImageFilterDiadem` implementation and device-memory operations. Native `nflasha15_unpacked_unpacked/sabin/` contains `sa_jpeg_enc_1unit.bin`, `sa_jpeg_enc_1core.bin`, `sa_jpeg_dec_1unit.bin` and `sa_jpeg_dec_1core.bin`. Its `lib/libObj.so` contains JPEG-decode, soft-focus-filter and JPEG-parameter command identifiers.

These are evidence of Sony-provided image/processor paths. They are not proof that any named operation is supported for an arbitrary saved JPEG, runs faster than libjpeg-turbo, exposes usable CPU-addressable buffers, or allows storing the output without capture-state side effects. A probe should first query support, then time decoding/scaling/soft-focus and output conversion on a selected JPEG. Buffer formats, ownership, stride, synchronization and memory-transfer overhead must be established before integrating this path.

## Memory and other limits

The 24 MiB Dalvik heap supports retaining small previews and using native/streamed processing. It does not describe all available native memory or Sony's reserved image memory. Avoid a full-resolution Java bitmap.

The Android `libjpeg.so` exports ordinary software JPEG functions, including an ARMv6 IDCT and tiled decoding functions. Its presence is not evidence that ordinary `BitmapFactory` decoding or JPEG compression is automatically backed by the hardware codec programs.

The kernel contains SMP support strings, but static SMP configuration does not establish how many cores are available to apps or their scheduling limits. CPU count, NEON features, clocks and practical memory/throughput limits still require observations on the running camera.

Priority: ARMv7 hardware-FP build first; compare Last save timings on the same A5000 JPEG/settings; then probe Sony decoding/scaling/soft-focus and JPEG storage if those stages remain significant.

## Native JPEG save versus database refresh

On-camera feedback established that `AvindexStore.Images.waitAndUpdateDatabase` did not add Grain Lab files to the catalog. The stillPlay DEX exposes an independent camera-save overload of `JpegExporter.encode(OptimizedImage, String mediaId, Options, onExportEventListener)`. It parses mediaId as hexadecimal and calls native EncodeImage with stream output disabled. Its listener reports `onExported(int)`; error constants are OK=0, FAILED=-1, INVALID_PARAM=-2. The listener wrapper constructs a Handler on the invoking thread, requiring a Looper. The options expose JPEG quality (FINE=2), not a destination path. This route delegates filename allocation and camera registration to Sony rather than trying to insert a manually created file. Grain Lab now uses this route, checks the callback and verifies a newly created file against the catalog. Acceptance of an arbitrary processed MAIN JPEG, metadata preservation and actual playback remain hardware checks.

## Runtime dependencies and durable save diagnostics

A second camera-card snapshot after GLAB0004/GLAB0005 showed no new DSC files and all four AVF_INFO files byte-identical to the previous snapshot. The runtime callback/error was unavailable because the earlier root-level diagnostic file was absent. The manifest now also declares optional `sony.cameraex`, whose firmware JAR contains `OptimizedImage` and its `DeviceMemory` superclass; stillPlay contains the decoder/exporter wrappers, but not those image types. The CAMERA permission is declared for the native camera pipeline. This fixes a missing declared dependency; the actual cause of these saves still needs the on-camera trace.

Diagnostics now go beside the processed JPEG in the same writable directory, are fsynced, identify the installed package build and media IDs, and record each decode/export stage. Diagnostic write errors are included in Save timings. Successful callback without native catalog readback has its own accepted-but-unconfirmed playback status. New hardware test required.

## Build 8 on-camera failure and native namespace retry

The user's photographed Save timings report confirmed `Sony MAIN decoder returned no valid image` before exporter submission, and Java FileNotFoundException/ENOENT for the long diagnostic filename under `/mnt/sdcard/DCIM/101GRAIN`. Sony processing failed in about 619 ms; CPU effects/save completed separately in 88,692 ms. The report does not establish whether the JPEG bitstream or path namespace caused rejection.

StillPlay's Java wrapper removes the URI scheme and native HAL forwards the remaining string through SDCom. The extracted native `libInfraFuFsys.so` contains `/android/mnt/sdcard`, while that library does not contain `/mnt/sdcard` alone. The MAIN reader now retries this firmware-native mount prefix after the Android path fails. This is an evidence-based compatibility attempt, not proof that the decoder accepts arbitrary files. Both paths stay MAIN/full resolution and failures are traced. No raw database edits or image overwrites are performed. The diagnostic filename is shortened to GLSAVE.TXT; the exact cause of the prior ENOENT still requires camera verification.

## Build 9 original-photo test: both filesystem paths fail

The photographed acceleration test on original DSC00463.JPG rejected both file:///mnt/sdcard and file:///android/mnt/sdcard MAIN URIs. Thus rejection is not limited to our processed JPEG bitstream. The firmware's AvindexGraphics uses the Sony catalog unique identifier with OptimizedImageFactory, not a filesystem URI. The diagnostic now looks up the selected original's DCF folder/file numbers in Images.Media, retrieves its _data avindex:// identifier and decodes that exact identifier as MAIN. An unindexed grain copy cannot borrow another photo's identifier. This test must establish working native decode/export before designing a processed-pixel transfer path. Filesystem fallback has not been validated and playback registration remains unresolved.

## Build 12 native catalog path succeeds; SoftFocus execute blocks

Copied F:/GLTEST.TXT to ignored out/camera-evidence-build12/GLTEST.TXT. Selected DSC00463.JPG resolved to avindex://1000/00000001-default/00000079-00000C2D. MAIN decode succeeded in 956 ms at 5456x3632. Scale executed in 85 ms to 2728x1816; JPEG stream encoding and draining took 308 ms for 2,795,276 bytes. The last checkpoint was Executing SoftFocusImageFilter. Earlier failures of file:// paths therefore did not establish a JPEG compatibility problem. The catalog URI is proven for original-image decoding on this A5000. The default diagnostic now omits Sony SoftFocus to finish reliably; Grain Lab's CPU diffusion/edge softness remain implemented independently. Importing an unindexed processed JPEG is still unresolved and cannot be accomplished by passing an original's catalog ID (that would decode the original pixels).

## Public buffer/image APIs and first transfer probe

Firmware cameraex DEX declares DSP.createProcessor(String), createBuffer(int), createImage(int,int), and getProperty(DeviceMemory,String). DeviceType.SONY_DI_DSP is sony-di-dsp. DeviceBuffer exposes byte[] read/write, including read(dst,dstOffset,size,srcOffset) and write(src,srcOffset,size,dstOffset). DSP property keys include memory-size, image-data-offset, image-canvas-width/height, image-target-width/height and image-target-x/y-offset. OptimizedImage extends DeviceMemory, not DeviceBuffer, and exposes dimensions and a format tag but no public byte[] transfer methods.

The inspected libcameraseq.so exports DeviceMemoryDiadem::readBuffer, writeBuffer and getPropertyDiadem. Disassembly shows shared-memory mapping and bounds checks in buffer transfers. These native methods are promising for a later bridge but their applicability to opaque optimized images and ABI/ownership must be verified; private-handle aliases or raw physical-pointer writes are not implemented.

The added camera probe uses the public SDK only. It allocates its own 4096-byte DeviceBuffer, verifies an exact pattern round trip and a 64-byte offset round trip, then allocates a separate 64x48 image and logs descriptors for it and the original decoded image. It does not write image pixels, run DSP programs, export a photo, alter image registration or change camera settings. Each operation is checkpointed in root GLTEST.TXT with the existing bounded wait. Owned buffer/image resources are released before the processor; the borrowed original is released by the parent pipeline only. Host mocks verify reflection signatures, offset ordering, mismatch detection and cleanup, but cannot establish device format, stride or hardware support.

## Bounded native read bridge

The bridge resolves the verified libcameraseq exports DeviceMemoryDiadem::getType(), getPropertyDiadem(int,int*) and readBuffer(void*,unsigned,unsigned) at runtime. JNI obtains the SDK mNativeMemory field by name after checking DeviceMemory type and isValid; it does not dereference raw image pointers or assume C++ member offsets. Only memory types 1/2 are accepted, property 2 supplies the firmware's actual size, and requests are limited to 256 bytes with subtraction-based bounds checks. The native method neither writes, aliases, retains nor releases Sony memory. libdl calls are audited API-compatible additions; Sony libraries are not bundled.

Before image reads the probe compares 64-byte native reads at scratch offsets 0 and 128 with the pattern verified by the public SDK buffer API. Any mismatch prevents image sampling. If metadata exactly matches a two-byte padded canvas and zero data offset, it reads three 64-byte samples at top-left, candidate center and candidate bottom row. Coordinates are a sampling hypothesis only; pixel format is still unknown. Hex samples are checkpointed in GLTEST.TXT for comparison with the source JPEG on the host. Host tests exercise bounds/overflow, sampling descriptor validation and calibration rejection; actual native image access requires the camera test. The existing timed-out-call guard retains all owners until native operations return.

## Build 15 native image reads and pixel packing

Copied the completed camera log and source DSC00463.JPG to ignored out/camera-evidence-build15. Native/public scratch reads agreed at offsets 0 and 128, and all three image reads succeeded. Comparing 96 sampled pixels against Pillow decoding of the same source JPEG gave full-range UYVY a mean absolute RGB error of 0.329/255 and maximum 1.814. Limited-range UYVY averaged 10.181; VYUY full-range 21.339; YUYV/YVYU interpretations exceeded 80. This strongly supports packed Cb,Y0,Cr,Y1 with full-range BT.601 conversion and a 5504*2=11008-byte stride for this image. The saved comparison table is pixel-layout-comparison.json. It is sampled evidence, not proof for all camera modes.

The next isolated probe resolves DeviceMemoryDiadem::writeBuffer(void const*,unsigned,unsigned). Writes are bounded to 256 bytes and actual allocation size. It first calibrates native scratch writes against public SDK reads. It then fills only the separately allocated 64x48 image, requiring matching target dimensions, zero offsets and a small even canvas. Active pixels are eight UYVY color bars; padding is black. It checks three rows through the read bridge and exports GLBUF.JPG via Sony's stream encoder for host color/dimension validation. The selected original is not passed to the write probe. No native camera-save or database mutation is attempted in this test. Host tests cover pixel packing, padded stride and refusal of all image writes on failed scratch calibration. Camera test still required.

## Build 16 writes succeed, bare image encoding rejected

Copied completed GLTEST.TXT to out/camera-evidence-build16. Native scratch writes matched public reads; UYVY rows written into the separately allocated 64x48 image matched native readback. JpegExporter.encode returned no stream for that image, so no GLBUF.JPG was created. This confirms transfer but not encodability. The log does not distinguish a minimum-size restriction from missing image/source context.

The revised test uses another half-resolution ScaleImageFilter output, matching the image type/size that has repeatedly encoded successfully. It verifies different Java objects and non-overlapping allocations (normalizing the SDK's documented 30-bit DSP address space), checks the target descriptor against requested dimensions, writes only a center 64x32 UYVY patch, verifies three rows and attempts stream export to GLBUF.JPG. The source photo file is not written. The patch position is logged for host color inspection. Host tests verify bounds, preservation outside the patch and address-alias overlap refusal. Native camera save/index registration is still not invoked by this diagnostic.

## Build 17 patched scaler readback succeeds, encoding still rejected

Copied and SHA-256 verified F:/GLTEST.TXT to out/camera-evidence-build17. No GLBUF.JPG existed. The new scaled template had target 2728x1816 and padded canvas 2736x1824, with zero data offset. Allocation non-overlap passed, native/public scratch writes agreed, and the 64x32 patch at 1328,888 passed row readback. Encoding again returned no stream.

Inspection of StillPlayJNI shows JpegExporter.Init delegates to StillPlayIf.startAccess and Release to endAccess. endAccess decrements a global reference count and destroys the HAL at zero. ImageFilterDiadem.release is a no-op and its destructor closes its SDCom reference; the inspected destructor does not free the Java output memory, so the existing separate output release is consistent with that native implementation. MemoryInputStream.close releases its native stream handle.

The next control frees the generic DSP probe image, scratch buffer and processor before encoding, while preserving the separately scaled target and parent original. One exporter remains alive for unchanged-template encoding and patched-template encoding. A diagnostic adapter invokes the same native stream operation used by the public wrapper, implementing the internal onEventListener interface to retain its exact callback status. Its wait deliberately retains owners until a callback; the outer probe still bounds the UI wait. An unchanged-template failure exits before image writes. These changes isolate setup/resource issues from pixel transfer, without yet attributing the earlier failures to a specific cause.

## Build 18 successful changed-pixel JPEG export and production upload implementation

Copied and SHA-256 verified GLTEST.TXT, GLBUF.JPG and the source JPEG. Original source hash matched the earlier snapshot. With extra DSP allocations released and one exporter retained, unchanged-template and patched-template callback codes were both 0 with streams present. Exported JPEG dimensions were 2728x1816. All eight patch colors matched expected RGB values: mean channel error per bar 0.167 to 0.5/255, with individual sampled color means at most one level from expected. Artifacts and color-patch-validation.json are in out/camera-evidence-build18. This validates UYVY writes becoming visible in Sony's JPEG output. The separate contributions of resource pressure and exporter lifecycle were not isolated.

Build 19 connects the save action to source-catalog decoding, non-overlapping scaler-produced templates and a native JPEG-to-UYVY uploader. It streams YCbCr/grayscale rows, checks viewport/allocation bounds, writes no more than 64 KiB per SDK buffer transfer and rejects progressive/non-supported JPEG layouts. Full output creates an intermediate half-size template before returning to original dimensions; this full-resolution allocation/upscaling path is not yet camera-tested. DSP inspection objects are released before encode. The populated target is submitted through the async camera-save overload, with resources held until callback. New file/catalog confirmation excludes all snapshot paths and briefly retries delayed publication; callbacks reporting failure cannot be treated as registered. Backup JPEGs remain intact. Host tests cover padded chroma packing, geometry rejection, full/half template cleanup, upload-before-save ordering, preservation of source file, upload failures preventing export and native save errors. Physical registration and orientation/metadata correctness remain hardware validation.

## On-camera native save result

The user confirmed the processed image now appears in Sony playback. For a portrait image, Sony playback showed a 90-degree rotation while the PC viewer displayed it correctly. The likely remaining issue is disagreement between EXIF orientation and Sony playback rotation state; this has not yet been resolved.

## Portrait orientation evidence and reconciliation

Copied and verified DSC00463.JPG, GLAB0012.JPG, DSC00464.JPG and GLSAVE.TXT plus AVF_INFO into ignored out/orientation-evidence. All three JPEGs have physical dimensions 5456x3632. Original and grain backup have EXIF orientation 8; the native registered DSC copy has orientation 1. This establishes loss of the orientation tag in native saving; the earlier speculation about conflicting catalog state was not demonstrated. The correctly displayed PC file may have been the grain backup rather than the registered copy.

Firmware Images.Media.rotateImage resolves the new record's unique ID and delegates to public InfraScalarMprWrapper.rotateImage(String,int). Its relative-angle enum is plus90=0, plus180=1, plus270=2, minus90=3, minus180=4, minus270=5. After new-file catalog confirmation the implementation compares backup and destination EXIF, requests the relative rotation needed to match standard tags 1/3/6/8, and verifies EXIF readback. It uses the native rotation operation rather than rewriting only the file's EXIF, so Sony can reconcile its playback metadata/thumbnail state. No-op cases skip the call; mirrored transforms are explicitly left unmodified. The repair is limited to newly created registered copies and never operates on the source or backup. Host tests verify enum mapping and idempotence, but the API's actual file/catalog behavior still requires the new camera save test.
