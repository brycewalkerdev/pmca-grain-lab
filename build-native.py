"""Build the row-based JPEG processor for ARMv5 Android or run native host tests."""
from pathlib import Path
import concurrent.futures
import os
import re
import subprocess
import sys

root = Path(__file__).resolve().parent
host = '--host' in sys.argv
armv7 = '--armv7' in sys.argv
backend = os.environ.get('GRAIN_JPEG', 'turbo')
def local_tool(relative):
    candidates = [root.parent / relative, root.parent.parent / relative]
    return next((p for p in candidates if p.exists()), candidates[0])

ndk = Path(os.environ.get('ANDROID_NDK', local_tool('sony-doom/tools/android-ndk-r16b')))
gcc = Path(os.environ.get('GRAIN_HOST_GCC', local_tool('sony-doom/tools/w64devkit/bin/gcc.exe')))
vendor = root / ('vendor/libjpeg-turbo-2.1.5.1' if backend == 'turbo' else 'vendor/jpeg-9f')
if backend == 'turbo':
    cmake = (vendor / 'CMakeLists.txt').read_text()
    names = re.search(r'set\(JPEG_SOURCES (.*?)\)', cmake, re.S).group(1).split()
    names += ['jaricom.c', 'jcarith.c', 'jdarith.c']
    sources = [vendor / name for name in names]
    if host: sources += [vendor / 'jsimd_none.c']
    else:
        simd_names = ['jcgray-neon.c','jcphuff-neon.c','jcsample-neon.c','jdmerge-neon.c','jdsample-neon.c',
                      'jfdctfst-neon.c','jidctred-neon.c','jquanti-neon.c','jdcolor-neon.c','jfdctint-neon.c']
        sources += [vendor / 'simd/arm' / name for name in simd_names]
        sources += [vendor / 'simd/arm/aarch32/jchuff-neon.c', vendor / 'simd/arm/aarch32/jsimd.c', vendor / 'simd/arm/aarch32/jsimd_neon.S']
else:
    makefile = (vendor / 'makefile.vc').read_text()
    names = re.search(r'LIBSOURCES= (.*?)(?=\n#)', makefile, re.S).group(1).replace('\\', '').split()
    sources = [vendor / name for name in names] + [vendor / 'jmemnobs.c']
sources += [root / 'native/grain.c', root / 'native/film.c']
out = root / ('out/host' if host else 'out/native-v7' if armv7 else 'out/native')
out.mkdir(parents=True, exist_ok=True)
(out / 'jconfig.h').write_bytes((root / 'native/jconfig.h').read_bytes())
# jinclude.h uses a quoted include; put jconfig beside the vendored headers, without changing upstream files.
headers = out / 'include'
headers.mkdir(exist_ok=True)
for header in vendor.glob('*.h'):
    (headers / header.name).write_bytes(header.read_bytes())
if backend == 'turbo':
    config = '#define JPEG_LIB_VERSION 80\n#define LIBJPEG_TURBO_VERSION 2.1.5.1\n#define LIBJPEG_TURBO_VERSION_NUMBER 2001005\n#define BITS_IN_JSAMPLE 8\n#define C_ARITH_CODING_SUPPORTED 1\n#define D_ARITH_CODING_SUPPORTED 1\n#define MEM_SRCDST_SUPPORTED 1\n'
    if not host: config += '#define WITH_SIMD 1\n'
    (headers / 'jconfig.h').write_text(config)
    (headers / 'jconfigint.h').write_text('#define BUILD "GrainLab"\n#define INLINE __inline__\n#define THREAD_LOCAL\n#define PACKAGE_NAME "libjpeg-turbo"\n#define VERSION "2.1.5.1"\n#define SIZEOF_SIZE_T '+('8' if host else '4')+'\n'+('' if host else '#define HAVE_BUILTIN_CTZL 1\n')+'#define FALLTHROUGH\n')
    (headers / 'jversion.h').write_text((vendor / 'jversion.h.in').read_text().replace('@COPYRIGHT_YEAR@','2023'))
    (headers / 'neon-compat.h').write_text((vendor / 'simd/arm/neon-compat.h.in').read_text().replace('#cmakedefine HAVE_VLD1_S16_X3', '#undef HAVE_VLD1_S16_X3').replace('#cmakedefine HAVE_VLD1_U16_X2','#undef HAVE_VLD1_U16_X2').replace('#cmakedefine HAVE_VLD1Q_U8_X4','#undef HAVE_VLD1Q_U8_X4'))
else: (headers / 'jconfig.h').write_bytes((root / 'native/jconfig.h').read_bytes())
flags = ['-O3', '-std=gnu99', '-I' + str(headers), '-I' + str(root / 'native'), '-I' + str(vendor),
         '-ffunction-sections', '-fdata-sections', '-Wall', '-Wextra', '-Wno-unused-parameter']
if host:
    compiler = gcc
    sources += [root / 'tests/native_test.c', root / 'tests/reference_film.c']
else:
    toolchain = ndk / 'toolchains/arm-linux-androideabi-4.9/prebuilt/windows-x86_64'
    compiler = toolchain / 'bin/arm-linux-androideabi-gcc.exe'
    flags += ['--sysroot=' + str(ndk / 'sysroot'), '-isystem', str(ndk / 'sysroot/usr/include/arm-linux-androideabi'),
              '-D__ANDROID_API__=9', '-fPIC', '-march=armv7-a' if armv7 else '-march=armv5te', '-mfloat-abi=softfp',
              '-mfpu=vfpv3-d16' if armv7 else '-mfpu=vfp', '-mthumb']
    sources += [root / 'native/jni.c',root / 'native/sony_upload.c']

def call(args):
    result = subprocess.run(list(map(str, args)), stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True)
    if result.returncode:
        print(result.stdout)
        raise RuntimeError('Build failed: ' + str(args[0]))
    # Keep our compiler diagnostics visible; upstream JPEG warnings are not actionable here.
    return result.stdout

def compile_source(source):
    obj = out / (source.stem + '.o')
    extra = []
    if not host and backend == 'turbo' and source.parent in [vendor / 'simd/arm',vendor / 'simd/arm/aarch32'] and source.name != 'jsimd.c':
        extra = ['-march=armv7-a','-mfpu=neon','-mfloat-abi=softfp','-marm']
    diagnostics = call([compiler, *flags, *extra, '-c', source, '-o', obj])
    if source.parent == root / 'native' and diagnostics:
        print(diagnostics)
    return obj

print('Compiling JPEG processor for ' + ('host tests' if host else 'armeabi-v7a / VFPv3-D16' if armv7 else 'armeabi / API 9 native ABI'), flush=True)
with concurrent.futures.ThreadPoolExecutor(max_workers=6) as pool:
    objects = list(pool.map(compile_source, sources))
if host:
    executable = out / 'native-test.exe'
    call([compiler, *objects, '-Wl,--gc-sections', '-o', executable, '-lm'])
    print(call([executable, out]), flush=True)
    cli = compile_source(root / 'native/cli.c')
    tool = out / 'grain-process.exe'
    call([compiler, *[o for o in objects if o.stem not in ['native_test','reference_film']], cli, '-Wl,--gc-sections', '-o', tool, '-lm'])
else:
    library = out / 'libgrainlab.so'
    libs = ndk / 'platforms/android-14/arch-arm/usr/lib'
    call([compiler, *flags, '-shared', '-nostdlib', '-Wl,--gc-sections', '-Wl,--no-undefined',
          '-Wl,--hash-style=sysv', '-Wl,-soname,libgrainlab.so', libs / 'crtbegin_so.o', *objects,
          '-L' + str(libs), '-lm', '-lc', '-ldl', '-lgcc', libs / 'crtend_so.o', '-o', library])
    readelf = toolchain / 'bin/arm-linux-androideabi-readelf.exe'
    report = call([readelf, '-h', '-d', '-A', library])
    (out / 'elf-report.txt').write_text(report)
    if 'ELF32' not in report or not re.search(r'Machine:\s+ARM', report) or 'TEXTREL' in report or '(HASH)' not in report:
        raise RuntimeError('Unexpected ARM ELF format or text relocations')
    symbols = call([readelf, '--dyn-syms', '--wide', library])
    (out / 'symbols.txt').write_text(symbols)
    allowed = set('''__cxa_finalize __cxa_atexit __errno __sF abort calloc fclose fflush fopen
        fprintf fread free fwrite longjmp malloc memcmp memcpy memset raise setjmp snprintf
        strcmp strlen __gnu_Unwind_Find_exidx __cxa_begin_cleanup __cxa_type_match
        __cxa_call_unexpected fflush fsync fileno exit ferror getenv sprintf sscanf strncpy powf expf ceilf floorf
        clock_gettime fseek ftell fgets feof strchr strstr _ctype_ strncmp isspace dlopen dlsym dlclose'''.split())
    imports = set()
    for line in symbols.splitlines():
        if ' UND ' in line:
            parts = line.split()
            if len(parts) > parts.index('UND') + 1:
                imports.add(parts[parts.index('UND') + 1].split('@')[0])
    if imports - allowed:
        raise RuntimeError('Unaudited old-Android imports: ' + str(sorted(imports - allowed)))
    if armv7:
        objdump = toolchain / 'bin/arm-linux-androideabi-objdump.exe'
        disassembly = call([objdump, '-dr', out / 'film.o'])
        (out / 'effect-disassembly.txt').write_text(disassembly)
        if re.search(r'R_ARM_THM_CALL\s+__aeabi_f', disassembly) or not re.search(r'\b(vadd|vmul|vdiv|vcvt)\.', disassembly):
            raise RuntimeError('ARMv7 effect code is not using hardware floating point')
    call([toolchain / 'bin/arm-linux-androideabi-strip.exe', '--strip-unneeded', library])
    print('Built ' + str(library), flush=True)
