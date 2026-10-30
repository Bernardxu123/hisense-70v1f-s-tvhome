<#
  TvHome 构建脚本（通用版）
  用法：
    $env:ANDROID_SDK_ROOT = 'C:\Android\Sdk'
    $env:JAVA_HOME        = 'C:\Program Files\Java\jdk-17'
    powershell -File build_app.ps1

  产物：build\TvHome.apk
#>
param(
    [string]$Project   = 'tvhome',
    [string]$ApkName   = 'TvHome',
    [string]$SdkRoot   = $env:ANDROID_SDK_ROOT,
    [string]$JavaHome  = $env:JAVA_HOME,
    [string]$BuildToolsVersion = '34.0.0',
    [string]$Platform  = 'android-28'
)
$ErrorActionPreference = 'Stop'

$HERE = Split-Path -Parent $MyInvocation.MyCommand.Path
if (-not $SdkRoot)  { throw '请设置 ANDROID_SDK_ROOT（或 -SdkRoot）' }
if (-not $JavaHome) { throw '请设置 JAVA_HOME（或 -JavaHome）' }

$BT   = Join-Path $SdkRoot "build-tools\$BuildToolsVersion"
$AJAR = Join-Path $SdkRoot "platforms\$Platform\android.jar"
$OUT  = Join-Path $HERE 'build'
$env:JAVA_HOME = $JavaHome
$env:PATH = "$JavaHome\bin;$BT;$env:PATH"

foreach ($d in @($OUT, "$OUT\obj", "$OUT\dex", "$OUT\gen")) {
    New-Item -ItemType Directory -Force -Path $d | Out-Null
}

function Step($n, $msg) { Write-Host "[$n/7] $msg" }

Step 1 'aapt2 compile'
& "$BT\aapt2.exe" compile --dir "$HERE\res" -o "$OUT\res.zip"
if ($LASTEXITCODE -ne 0) { throw 'aapt2 compile failed' }

Step 2 'aapt2 link'
& "$BT\aapt2.exe" link -o "$OUT\unsigned.apk" -I "$AJAR" --manifest "$HERE\AndroidManifest.xml" --java "$OUT\gen" "$OUT\res.zip"
if ($LASTEXITCODE -ne 0) { throw 'aapt2 link failed' }

Step 3 'javac'
if (Test-Path "$OUT\obj\com") { Remove-Item -Recurse -Force "$OUT\obj\com" }
$Sources = Get-ChildItem -Recurse -Path "$HERE\src" -Filter *.java | ForEach-Object { $_.FullName }
$Sources += Get-ChildItem -Recurse -Path "$OUT\gen" -Filter *.java | ForEach-Object { $_.FullName }
& "$JavaHome\bin\javac.exe" -source 8 -target 8 -encoding UTF-8 -bootclasspath "$AJAR" -d "$OUT\obj" @Sources
if ($LASTEXITCODE -ne 0) { throw 'javac failed' }

Step 4 'd8'
if (Test-Path "$OUT\dex\classes.dex") { Remove-Item -Force "$OUT\dex\classes.dex" }
$classFiles = Get-ChildItem -Recurse -Path "$OUT\obj" -Filter *.class | ForEach-Object { $_.FullName }
& "$BT\d8.bat" --release --min-api 21 --lib "$AJAR" --output "$OUT\dex" @classFiles
if ($LASTEXITCODE -ne 0) { throw 'd8 failed' }

Step 5 'package dex into apk'
& python "$HERE\adddex.py" "$OUT\unsigned.apk" "$OUT\dex\classes.dex"
if ($LASTEXITCODE -ne 0) { throw 'adddex failed' }

Step 6 'zipalign + sign'
& "$BT\zipalign.exe" -f 4 "$OUT\unsigned.apk" "$OUT\aligned.apk"
if ($LASTEXITCODE -ne 0) { throw 'zipalign failed' }
$ks = Join-Path $OUT 'debug.keystore'
if (!(Test-Path $ks)) {
    & "$JavaHome\bin\keytool.exe" -genkeypair -v -keystore $ks -storepass android -keypass android `
        -alias tvhome -keyalg RSA -keysize 2048 -validity 10000 -dname 'CN=TvHome,O=TvHome,C=CN'
    if ($LASTEXITCODE -ne 0) { throw 'keytool failed' }
}
& "$BT\apksigner.bat" sign --ks $ks --ks-pass pass:android --key-pass pass:android --out "$OUT\$ApkName.apk" "$OUT\aligned.apk"
if ($LASTEXITCODE -ne 0) { throw 'apksigner failed' }

Step 7 'verify'
& "$BT\apksigner.bat" verify --print-certs "$OUT\$ApkName.apk"
& "$BT\aapt2.exe" dump badging "$OUT\$ApkName.apk" | Select-String -Pattern 'package:|launchable-activity'
Write-Host "BUILD OK: $OUT\$ApkName.apk"
