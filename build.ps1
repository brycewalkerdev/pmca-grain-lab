param([string]$AndroidTools = $env:GRAIN_ANDROID_TOOLS, [string]$Python = 'python', [switch]$TestsOnly)
$ErrorActionPreference = 'Stop'
$taskRoot = $PSScriptRoot
$taskBuildNumber = 19
$taskBuildTime = [DateTime]::UtcNow.ToString("yyyy-MM-dd HH:mm:ss 'UTC'", [Globalization.CultureInfo]::InvariantCulture)
Set-Location -LiteralPath $taskRoot
function Checked([string]$Executable, [string[]]$Arguments) {
    & $Executable @Arguments
    if ($LASTEXITCODE -ne 0) { throw "Build tool failed: $Executable ($LASTEXITCODE)" }
}
if (!$AndroidTools) {
    $taskToolCandidates = @((Join-Path (Split-Path $taskRoot) 'flappy-bird\tools'), (Join-Path (Split-Path (Split-Path $taskRoot)) 'flappy-bird\tools'))
    $AndroidTools = $taskToolCandidates | Where-Object { Test-Path -LiteralPath $_ } | Select-Object -First 1
    if (!$AndroidTools) { throw 'Set GRAIN_ANDROID_TOOLS to the Java + Android tool bundle.' }
}
$taskJava = (Get-ChildItem "$AndroidTools\java" -Directory | Select-Object -First 1).FullName + '\bin'
$taskBT = Split-Path (Get-ChildItem "$AndroidTools\android" -Recurse -Filter aapt.exe | Select-Object -First 1).FullName
$taskJar = (Get-ChildItem "$AndroidTools\android" -Recurse -Filter android.jar | Select-Object -First 1).FullName
if (!$taskJar -or !(Test-Path "$taskJava\javac.exe")) { throw 'Set GRAIN_ANDROID_TOOLS to the Java + Android tool bundle.' }
New-Item -ItemType Directory -Force out\tests | Out-Null
Checked "$taskJava\javac.exe" @('--release','8','-encoding','UTF-8','-d','out\tests',
    'src\com\bryce\grainlab\GrainFiles.java','src\com\bryce\grainlab\FilmSettings.java','src\com\bryce\grainlab\FilmPresets.java',
    'tests\com\bryce\grainlab\GrainFilesTest.java','tests\com\bryce\grainlab\FilmSettingsTest.java')
Checked "$taskJava\java.exe" @('-cp','out\tests','com.bryce.grainlab.GrainFilesTest')
Checked "$taskJava\java.exe" @('-cp','out\tests','com.bryce.grainlab.FilmSettingsTest')
$taskSonyTests=@(Get-ChildItem tests\android,tests\com\sony -Recurse -Filter '*.java' | ForEach-Object FullName)
Checked "$taskJava\javac.exe" (@('--release','8','-encoding','UTF-8','-cp','out\tests','-d','out\tests',
    'src\com\bryce\grainlab\SonyGallery.java','src\com\bryce\grainlab\SonyCameraSave.java','src\com\bryce\grainlab\SonyImageUpload.java','src\com\bryce\grainlab\SonyImageReader.java','src\com\bryce\grainlab\SonyProbe.java','src\com\bryce\grainlab\SonyBufferProbe.java','src\com\bryce\grainlab\SonyNativeSamples.java','src\com\bryce\grainlab\SonyWriteProbe.java','src\com\bryce\grainlab\SonyJpegStream.java','src\com\bryce\grainlab\NativeGrain.java','src\com\bryce\grainlab\SonyAcceleration.java','tests\com\bryce\grainlab\SonyIntegrationTest.java')+$taskSonyTests)
Checked "$taskJava\java.exe" @('-cp','out\tests','com.bryce.grainlab.SonyIntegrationTest')
Checked $Python @('build-native.py','--host')
if ($TestsOnly) { return }
Checked $Python @('build-native.py')
Checked $Python @('build-native.py','--armv7')
$taskBuild = Join-Path $taskRoot ('out\apk-' + [guid]::NewGuid().ToString('N'))
foreach ($taskDir in @('gen','classes','dex','package\lib\armeabi','package\lib\armeabi-v7a')) {
    New-Item -ItemType Directory -Force "$taskBuild\$taskDir" | Out-Null
}
# Standalone development identity; the existing Recipe Lab manifest is never edited.
$taskManifest = Join-Path $taskBuild 'AndroidManifest.xml'
$taskPackageIdentity = 'package="com.bryce.grainlab" android:versionCode="' + $taskBuildNumber + '" android:versionName="prototype-build-stamp"'
$taskManifestText = [IO.File]::ReadAllText("$taskRoot\AndroidManifest.xml").Replace('package="com.bryce.grainlab"', $taskPackageIdentity)
[IO.File]::WriteAllText($taskManifest, $taskManifestText, [Text.UTF8Encoding]::new($false))
Checked "$taskBT\aapt.exe" @('package','-f','-m','-J',"$taskBuild\gen",'-M',$taskManifest,'-S','res','-A','assets','-I',$taskJar)
$taskInfoDir = Join-Path $taskBuild 'gen\com\bryce\grainlab'
New-Item -ItemType Directory -Force -Path $taskInfoDir | Out-Null
$taskInfoSource = 'package com.bryce.grainlab; final class BuildInfo { static final int NUMBER = ' + $taskBuildNumber + '; static final String BUILT_AT = "' + $taskBuildTime + '"; private BuildInfo() {} }'
[IO.File]::WriteAllText((Join-Path $taskInfoDir 'BuildInfo.java'), $taskInfoSource, [Text.UTF8Encoding]::new($false))
$taskSources = @(Get-ChildItem src,"$taskBuild\gen" -Recurse -Filter '*.java' | ForEach-Object FullName)
Checked "$taskJava\javac.exe" (@('--release','8','-encoding','UTF-8','-classpath',$taskJar,'-d',"$taskBuild\classes") + $taskSources)
$taskApiDatabase = Join-Path (Split-Path $taskJar) 'data\api-versions.xml'
if (!(Test-Path -LiteralPath $taskApiDatabase)) { throw 'Android platform data/api-versions.xml is needed for the API 10 audit.' }
Checked $Python @('check-api.py',"$taskJava\javap.exe",$taskApiDatabase,"$taskBuild\classes")
$taskClasses = @(Get-ChildItem "$taskBuild\classes" -Recurse -Filter '*.class' | ForEach-Object FullName)
Checked "$taskJava\java.exe" (@('-cp',"$taskBT\lib\d8.jar",'com.android.tools.r8.D8','--release','--min-api','10','--lib',$taskJar,'--output',"$taskBuild\dex") + $taskClasses)
$taskDex = [IO.File]::ReadAllBytes("$taskBuild\dex\classes.dex")
if ([Text.Encoding]::ASCII.GetString($taskDex,0,7) -ne "dex`n035") { throw 'Incorrect DEX version' }
Checked "$taskBT\aapt.exe" @('package','-f','-M',$taskManifest,'-S','res','-A','assets','-I',$taskJar,'-F',"$taskBuild\unsigned.apk")
Push-Location "$taskBuild\dex"
try { Checked "$taskBT\aapt.exe" @('add',"$taskBuild\unsigned.apk",'classes.dex') } finally { Pop-Location }
Copy-Item -LiteralPath out\native\libgrainlab.so -Destination "$taskBuild\package\lib\armeabi\libgrainlab.so"
Copy-Item -LiteralPath out\native-v7\libgrainlab.so -Destination "$taskBuild\package\lib\armeabi-v7a\libgrainlab.so"
Push-Location "$taskBuild\package"
try { Checked "$taskBT\aapt.exe" @('add',"$taskBuild\unsigned.apk",'lib/armeabi/libgrainlab.so','lib/armeabi-v7a/libgrainlab.so') } finally { Pop-Location }
Checked "$taskBT\zipalign.exe" @('-f','4',"$taskBuild\unsigned.apk","$taskBuild\aligned.apk")
$taskKey = "$taskRoot\out\grain-lab.keystore"
if (!(Test-Path -LiteralPath $taskKey)) {
    Checked "$taskJava\keytool.exe" @('-genkeypair','-keystore',$taskKey,'-alias','grainlab','-keyalg','RSA','-keysize','2048',
        '-validity','10000','-storepass','android','-keypass','android','-dname','CN=Grain Lab Development')
}
New-Item -ItemType Directory -Force dist | Out-Null
$taskAPK = "$taskRoot\dist\GrainLab.apk"
Checked "$taskJava\java.exe" @('-jar',"$taskBT\lib\apksigner.jar",'sign','--ks',$taskKey,'--ks-pass','pass:android','--key-pass','pass:android',
    '--min-sdk-version','10','--v1-signing-enabled','true','--v2-signing-enabled','false','--v3-signing-enabled','false','--v4-signing-enabled','false',
    '--out',$taskAPK,"$taskBuild\aligned.apk")
Checked "$taskJava\java.exe" @('-jar',"$taskBT\lib\apksigner.jar",'verify','--verbose','--min-sdk-version','10',$taskAPK)
Checked "$taskBT\zipalign.exe" @('-c','4',$taskAPK)
$taskBadging = @(Checked "$taskBT\aapt.exe" @('dump','badging',$taskAPK)); $taskBadging | Write-Output
$taskMetadata = $taskBadging -join "`n"
if ($taskMetadata -notmatch "(?m)^sdkVersion:'10'$" -or $taskMetadata -notmatch "(?m)^targetSdkVersion:'10'$" -or $taskMetadata -notmatch "(?m)^native-code:.*'armeabi'.*" -or $taskMetadata -notmatch "(?m)^native-code:.*'armeabi-v7a'.*") {
    throw 'APK does not contain both the API 10 generic and ARMv7 implementations'
}
(Get-FileHash $taskAPK -Algorithm SHA256).Hash.ToLowerInvariant() + '  GrainLab.apk' | Set-Content dist\GrainLab.apk.sha256 -Encoding ASCII
('Build ' + $taskBuildNumber + ' - ' + $taskBuildTime) | Set-Content dist\GrainLab.build.txt -Encoding ASCII
Write-Output ('BUILD ID: ' + $taskBuildNumber + ' - ' + $taskBuildTime)
Write-Output "BUILD OK: $taskAPK"
