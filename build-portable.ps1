[CmdletBinding()]
param(
    [string]$JdkHome = '',
    [string]$RuntimeArchive = '',
    [string]$OutputDirectory = '',
    [switch]$SkipBuild
)
$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
if ($env:OS -ne 'Windows_NT' -or ![Environment]::Is64BitOperatingSystem) {
    throw 'This script creates Windows x64 app-images and must run on 64-bit Windows.'
}
$root = $PSScriptRoot
$pin = Get-Content -LiteralPath (Join-Path $root 'packaging/runtime-windows-x64.json') -Raw | ConvertFrom-Json
[xml]$pom = Get-Content -LiteralPath (Join-Path $root 'pom.xml') -Raw
$version = [string]$pom.project.version
if ($version -notmatch '^\d+\.\d+\.\d+$') { throw 'A numeric x.y.z release version is required.' }
if (!$JdkHome) {
    $command = Get-Command jpackage.exe -ErrorAction Stop
    $JdkHome = Split-Path (Split-Path $command.Source -Parent) -Parent
}
$JdkHome = (Resolve-Path -LiteralPath $JdkHome).Path
$jpackage = Join-Path $JdkHome 'bin/jpackage.exe'
if (!(Test-Path -LiteralPath $jpackage)) { throw 'JdkHome must contain bin/jpackage.exe.' }
if (!$OutputDirectory) { $OutputDirectory = Join-Path $root 'target/distributions' }
$OutputDirectory = [IO.Path]::GetFullPath($OutputDirectory)
[IO.Directory]::CreateDirectory($OutputDirectory) | Out-Null
$zip = Join-Path $OutputDirectory "OpenShell-$version-windows-x64-portable.zip"
$sumPath = Join-Path $OutputDirectory 'SHA256SUMS.txt'
if ((Test-Path -LiteralPath $zip) -or (Test-Path -LiteralPath $sumPath)) {
    throw 'Release output already exists. Choose a new -OutputDirectory; existing releases are never overwritten.'
}
if (!$SkipBuild) {
    & (Join-Path $root 'build.ps1') -Goals @('verify')
}
$jar = Join-Path $root "target/openshell-$version.jar"
if (!(Test-Path -LiteralPath $jar)) { throw 'Executable JAR missing. Run build.ps1 first.' }
$cache = Join-Path $root '.build-cache/distribution'
[IO.Directory]::CreateDirectory($cache) | Out-Null
if (!$RuntimeArchive) {
    $RuntimeArchive = Join-Path $cache $pin.archive
    if (!(Test-Path -LiteralPath $RuntimeArchive)) {
        $partial = Join-Path $cache ("download-" + [guid]::NewGuid().ToString() + '.zip')
        Invoke-WebRequest -Uri $pin.url -OutFile $partial -TimeoutSec 300
        if ((Get-FileHash -LiteralPath $partial -Algorithm SHA256).Hash.ToLowerInvariant() -ne $pin.sha256) {
            throw 'Downloaded runtime SHA-256 mismatch. No runtime has been trusted or executed.'
        }
        Move-Item -LiteralPath $partial -Destination $RuntimeArchive
    }
}
$RuntimeArchive = (Resolve-Path -LiteralPath $RuntimeArchive).Path
if ((Get-FileHash -LiteralPath $RuntimeArchive -Algorithm SHA256).Hash.ToLowerInvariant() -ne $pin.sha256) {
    throw 'Runtime archive SHA-256 mismatch. Supply the exact pinned archive; no runtime has been trusted or executed.'
}
$work = Join-Path $root ('target/portable-build-' + [guid]::NewGuid().ToString())
$unpacked = Join-Path $work 'runtime-source'
[IO.Directory]::CreateDirectory($unpacked) | Out-Null
Expand-Archive -LiteralPath $RuntimeArchive -DestinationPath $unpacked
$runtimes = @(Get-ChildItem -LiteralPath $unpacked -Directory | Where-Object { Test-Path -LiteralPath (Join-Path $_.FullName 'bin/java.exe') })
if ($runtimes.Count -ne 1) { throw 'Expected exactly one JRE in the pinned archive.' }
$runtime = $runtimes[0].FullName
$runtimeRelease = Get-Content -LiteralPath (Join-Path $runtime 'release') -Raw
if ($runtimeRelease -notmatch 'JAVA_VERSION="17\.' -or $runtimeRelease -notmatch 'OS_ARCH="(x86_64|amd64)"') {
    throw 'The pinned runtime must be Java 17 for Windows x64.'
}
foreach ($required in @('bin/server/jvm.dll', 'lib/modules', 'legal', 'NOTICE')) {
    if (!(Test-Path -LiteralPath (Join-Path $runtime $required))) { throw "Runtime missing $required" }
}
$stagingInput = Join-Path $work 'input'
$notices = Join-Path $work 'third-party'
[IO.Directory]::CreateDirectory($stagingInput) | Out-Null
[IO.Directory]::CreateDirectory($notices) | Out-Null
Copy-Item -LiteralPath $jar -Destination $stagingInput
Copy-Item -LiteralPath (Join-Path $root 'packaging/THIRD-PARTY-NOTICES.md') -Destination $notices
Copy-Item -LiteralPath (Join-Path $root 'packaging/runtime-windows-x64.json') -Destination $notices
Copy-Item -LiteralPath (Join-Path $root 'build-support/maven-repository') -Destination (Join-Path $notices 'jediterm') -Recurse

# Ask Maven for runtime dependencies, so test-only artifacts can never enter the release.
$classpathFile = Join-Path $work 'runtime-classpath.txt'
Push-Location $root
try {
    & mvn -B -ntp 'org.apache.maven.plugins:maven-dependency-plugin:3.8.1:build-classpath' '-DincludeScope=runtime' "-Dmdep.outputFile=$classpathFile"
    if ($LASTEXITCODE -ne 0) { throw 'Cannot resolve the runtime dependency list.' }
} finally { Pop-Location }
Add-Type -AssemblyName System.IO.Compression.FileSystem
$componentRoot = Join-Path $notices 'components'
[IO.Directory]::CreateDirectory($componentRoot) | Out-Null
foreach ($dependency in ((Get-Content -LiteralPath $classpathFile -Raw).Trim() -split [IO.Path]::PathSeparator)) {
    $name = [IO.Path]::GetFileNameWithoutExtension($dependency)
    $directory = Join-Path $componentRoot $name
    [IO.Directory]::CreateDirectory($directory) | Out-Null
    $archive = [IO.Compression.ZipFile]::OpenRead($dependency)
    try {
        foreach ($entry in $archive.Entries) {
            if ($entry.FullName -match '(^|/)(LICENSE[^/]*|NOTICE[^/]*|COPYING[^/]*|THIRD[_-]PARTY[^/]*|pom\.xml|pom\.properties)$' -and $entry.Name) {
                $fileName = $entry.FullName.Replace('/', '_')
                [IO.Compression.ZipFileExtensions]::ExtractToFile($entry, (Join-Path $directory $fileName), $false)
            }
        }
    } finally { $archive.Dispose() }
    $originalPom = [IO.Path]::ChangeExtension($dependency, '.pom')
    if (Test-Path -LiteralPath $originalPom) { Copy-Item -LiteralPath $originalPom -Destination (Join-Path $directory 'upstream.pom') }
}
$toolNotices = Join-Path $notices 'jpackage-tool'
[IO.Directory]::CreateDirectory($toolNotices) | Out-Null
foreach ($module in @('java.base', 'jdk.jpackage')) {
    Copy-Item -LiteralPath (Join-Path $JdkHome "legal/$module") -Destination $toolNotices -Recurse
}
Copy-Item -LiteralPath (Join-Path $JdkHome 'release') -Destination (Join-Path $toolNotices 'packager-jdk-release.txt')
$icon = Join-Path $root 'packaging/OpenShell.ico'
if (!(Test-Path -LiteralPath $icon)) { & (Join-Path $root 'build-support/Create-AppIcon.ps1') -OutputPath $icon }
$images = Join-Path $work 'image'
$packagerVersion = (& $jpackage --version | Out-String).Trim()
& $jpackage --type app-image --name OpenShell --app-version $version --vendor OpenShell `
    --description 'SSH / SFTP desktop client' --input $stagingInput --main-jar "openshell-$version.jar" `
    --main-class org.openshell.Main --runtime-image $runtime --icon $icon --dest $images
if ($LASTEXITCODE -ne 0) { throw 'jpackage app-image failed.' }
$image = Join-Path $images 'OpenShell'
Copy-Item -LiteralPath $notices -Destination (Join-Path $image 'app/third-party') -Recurse
$launcherConfiguration = Get-Content -LiteralPath (Join-Path $image 'app/OpenShell.cfg') -Raw
if ([regex]::Matches($launcherConfiguration, '(?m)^app\.classpath=').Count -ne 1) {
    throw 'Only the executable application JAR may be on the native launcher classpath.'
}
Copy-Item -LiteralPath (Join-Path $root 'packaging/README-Windows.txt') -Destination (Join-Path $image 'README.txt')
$info = [ordered]@{
    applicationVersion = $version
    architecture = 'windows-x64'
    jarSha256 = (Get-FileHash -LiteralPath $jar -Algorithm SHA256).Hash.ToLowerInvariant()
    runtimeVendor = $pin.vendor
    runtimeVersion = $pin.version
    runtimeArchiveSha256 = $pin.sha256
    packagerVersion = $packagerVersion
}
[IO.File]::WriteAllText((Join-Path $image 'build-info.json'), ($info | ConvertTo-Json), [Text.UTF8Encoding]::new($false))
foreach ($required in @('OpenShell.exe', 'app/OpenShell.cfg', 'runtime/bin/server/jvm.dll', 'runtime/legal')) {
    if (!(Test-Path -LiteralPath (Join-Path $image $required))) { throw "Incomplete application image: $required" }
}
$privateFiles = @(Get-ChildItem -LiteralPath $image -Recurse -File | Where-Object { $_.Name -match '^(hosts\.json|workspace\.json|known_hosts.*|\.env.*)$|\.(pem|key|p12|pfx|log)$' })
if ($privateFiles.Count) { throw 'Refusing to package unexpected configuration, key or log files.' }
[IO.Compression.ZipFile]::CreateFromDirectory($image, $zip, [IO.Compression.CompressionLevel]::Optimal, $true)
$hash = (Get-FileHash -LiteralPath $zip -Algorithm SHA256).Hash.ToLowerInvariant()
[IO.File]::WriteAllText($sumPath, "$hash  $([IO.Path]::GetFileName($zip))`n", [Text.UTF8Encoding]::new($false))
Write-Output "PORTABLE_BUILD_OK image=$image"
Write-Output "PORTABLE_ZIP=$zip"
Write-Output "PORTABLE_SHA256=$hash"
