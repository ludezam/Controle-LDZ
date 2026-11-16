$ErrorActionPreference = 'Stop'
$buildLock = [IO.File]::Open((Join-Path $PSScriptRoot 'work\build.lock'), [IO.FileMode]::OpenOrCreate, [IO.FileAccess]::ReadWrite, [IO.FileShare]::None)
try {
$toolRoot = $env:ANDROID_BUILD_TOOLS_ROOT
if ([string]::IsNullOrWhiteSpace($toolRoot)) { $toolRoot = Join-Path $PSScriptRoot 'tools\android-build-tools' }
$toolRoot = [IO.Path]::GetFullPath($toolRoot)
$javaHome = $env:JAVA_HOME
if ([string]::IsNullOrWhiteSpace($javaHome)) { $javaHome = Join-Path $toolRoot 'jdk\jdk-17.0.20.1+1' }
$env:JAVA_HOME = $javaHome
$jdk = Join-Path $javaHome 'bin'
$sdk = $env:ANDROID_SDK_ROOT
if ([string]::IsNullOrWhiteSpace($sdk)) { $sdk = Join-Path $toolRoot 'android-sdk' }
$buildTools = Join-Path $sdk 'build-tools\34.0.0'
$androidJar = Join-Path $sdk 'platforms\android-34\android.jar'
$zxing = Join-Path $PSScriptRoot 'libs\zxing-core-3.5.3.jar'
foreach ($required in @("$jdk\javac.exe","$buildTools\aapt.exe","$buildTools\d8.bat","$androidJar",$zxing)) {
    if (-not (Test-Path -LiteralPath $required)) { throw "Ferramenta/arquivo ausente: $required. Configure ANDROID_BUILD_TOOLS_ROOT e consulte o README." }
}
function Native([string]$Command, [string[]]$Arguments) { & $Command @Arguments; if ($LASTEXITCODE -ne 0) { throw "Falha em $Command ($LASTEXITCODE)" } }
$source = Join-Path $PSScriptRoot 'app\src\main'
$build = Join-Path $PSScriptRoot ('work\build\' + [guid]::NewGuid().ToString('N'))
$classes = Join-Path $build 'classes'
$generated = Join-Path $build 'generated'
$dex = Join-Path $build 'dex'
$outputs = Join-Path $PSScriptRoot 'outputs'
$keys = Join-Path $PSScriptRoot '.android'
foreach ($path in @($classes,$generated,$dex,$outputs,$keys)) { New-Item -ItemType Directory -Force -Path $path | Out-Null }
$unsigned = Join-Path $build 'unsigned.apk'
Native "$buildTools\aapt.exe" @('package','-f','-M',"$source\AndroidManifest.xml",'-S',"$source\res",'-A',"$source\assets",'-I',$androidJar,'-J',$generated,'-F',$unsigned,'--min-sdk-version','26','--target-sdk-version','32')
$javaFiles = @(Get-ChildItem "$source\java",$generated -Filter '*.java' -Recurse | ForEach-Object { '"' + $_.FullName.Replace('\','/') + '"' })
$sourceList = Join-Path $build 'sources.txt'
[IO.File]::WriteAllLines($sourceList,$javaFiles,[Text.UTF8Encoding]::new($false))
Native "$jdk\javac.exe" @('-encoding','UTF-8','-source','8','-target','8','-bootclasspath',"$androidJar;$buildTools\core-lambda-stubs.jar",'-classpath',$zxing,'-d',$classes,"@$sourceList")
# Empacotar as classes antes do D8 evita limites de tamanho da linha de comando.
$compiled = Join-Path $build 'compiled.jar'
Native "$jdk\jar.exe" @('cf',$compiled,'-C',$classes,'.')
Native "$buildTools\d8.bat" @('--release','--lib',$androidJar,'--min-api','26','--output',$dex,$compiled,$zxing)
Native "$jdk\jar.exe" @('uf',$unsigned,'-C',$dex,'classes.dex')
$aligned = Join-Path $build 'aligned.apk'
Native "$buildTools\zipalign.exe" @('-f','4',$unsigned,$aligned)
$key = Join-Path $keys 'controle-ldz-test.keystore'
if (-not (Test-Path -LiteralPath $key)) { Native "$jdk\keytool.exe" @('-genkeypair','-keystore',$key,'-storepass','android','-keypass','android','-alias','controle','-dname','CN=Controle LDZ Test','-keyalg','RSA','-keysize','2048','-validity','10000','-noprompt') }
$apk = Join-Path $outputs 'Controle LDZ 1.3.1.apk'
$verified = Join-Path $build 'verified.apk'
Native "$jdk\java.exe" @('-jar',"$buildTools\lib\apksigner.jar",'sign','--ks',$key,'--ks-key-alias','controle','--ks-pass','pass:android','--key-pass','pass:android','--out',$verified,$aligned)
Native "$jdk\java.exe" @('-jar',"$buildTools\lib\apksigner.jar",'verify','--verbose',$verified)
Native "$buildTools\zipalign.exe" @('-c','4',$verified)
Native "$buildTools\aapt.exe" @('dump','badging',$verified)
Copy-Item -LiteralPath $verified -Destination $apk -Force
$hash=$null
for ($attempt=0; $attempt -lt 8; $attempt++) {
    try { $hash=(Get-FileHash -LiteralPath $apk -Algorithm SHA256).Hash.ToLowerInvariant(); break }
    catch { if ($attempt -eq 7) { throw }; Start-Sleep -Milliseconds 500 }
}
[IO.File]::WriteAllText((Join-Path $outputs 'SHA256SUMS.txt'),$hash+'  '+[IO.Path]::GetFileName($apk)+"`n",[Text.UTF8Encoding]::new($false))
Write-Output "APK pronto: $apk"
} finally { $buildLock.Dispose() }
