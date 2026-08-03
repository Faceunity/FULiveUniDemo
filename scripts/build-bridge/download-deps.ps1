$ErrorActionPreference = 'Stop'
$deps = Join-Path $PSScriptRoot 'deps'
$androidJar = Join-Path $deps 'platform-34\android-34\android.jar'
$fastjsonJar = Join-Path $deps 'fastjson-1.2.83.jar'
$platformZip = Join-Path $deps 'platform-34.zip'
$coreAar = Join-Path $deps 'core-9.0.1.aar'
$coreClassesJar = Join-Path $deps 'core-classes.jar'
$kotlinJar = Join-Path $deps 'kotlin-stdlib-1.8.22.jar'
$fuMaven = 'https://maven.faceunity.com/repository/maven-public/com/faceunity'

New-Item -ItemType Directory -Force -Path $deps | Out-Null

if (-not (Test-Path $fastjsonJar)) {
    Write-Host '下载 fastjson-1.2.83.jar ...'
    Invoke-WebRequest -Uri 'https://repo1.maven.org/maven2/com/alibaba/fastjson/1.2.83/fastjson-1.2.83.jar' -OutFile $fastjsonJar
}

if (-not (Test-Path $androidJar)) {
    Write-Host '下载 Android platform-34 ...'
    Invoke-WebRequest -Uri 'https://dl.google.com/android/repository/platform-34-ext7_r03.zip' -OutFile $platformZip
    Expand-Archive -Path $platformZip -DestinationPath (Join-Path $deps 'platform-34') -Force
    Remove-Item $platformZip -Force
}

if (-not (Test-Path $coreAar)) {
    Write-Host 'Downloading com.faceunity:core:9.0.1 ...'
    Invoke-WebRequest -Uri "$fuMaven/core/9.0.1/core-9.0.1.aar" -OutFile $coreAar
}

if (-not (Test-Path $kotlinJar)) {
    Write-Host 'Downloading kotlin-stdlib-1.8.22.jar ...'
    Invoke-WebRequest -Uri 'https://repo1.maven.org/maven2/org/jetbrains/kotlin/kotlin-stdlib/1.8.22/kotlin-stdlib-1.8.22.jar' -OutFile $kotlinJar
}

# Re-extract compile classes when core AAR updates
$coreStamp = Join-Path $deps 'core-classes.stamp'
$needExtract = -not (Test-Path $coreClassesJar)
if (Test-Path $coreAar) {
    $aarStamp = (Get-Item $coreAar).LastWriteTimeUtc.Ticks
    if (Test-Path $coreStamp) {
        $saved = Get-Content $coreStamp -Raw
        if ($saved -ne "$aarStamp") { $needExtract = $true }
    } else {
        $needExtract = $true
    }
}
if ($needExtract) {
    Write-Host 'Extract core-classes.jar from core AAR ...'
    $extract = Join-Path $deps 'core-extract-tmp'
    Remove-Item -Recurse -Force $extract -ErrorAction SilentlyContinue
    New-Item -ItemType Directory -Force -Path $extract | Out-Null
    Copy-Item $coreAar (Join-Path $extract 'core.zip')
    Expand-Archive (Join-Path $extract 'core.zip') -DestinationPath $extract -Force
    Copy-Item (Join-Path $extract 'classes.jar') $coreClassesJar -Force
  Set-Content -Path $coreStamp -Value (Get-Item $coreAar).LastWriteTimeUtc.Ticks -NoNewline
    Remove-Item -Recurse -Force $extract -ErrorAction SilentlyContinue
}

if (-not (Test-Path $androidJar)) {
    throw "android.jar missing: $androidJar"
}
if (-not (Test-Path $coreClassesJar)) {
    throw "core-classes.jar missing"
}

Write-Host '依赖就绪:'
Get-Item $fastjsonJar, $androidJar, $coreAar, $kotlinJar, $coreClassesJar | Format-List FullName, Length
