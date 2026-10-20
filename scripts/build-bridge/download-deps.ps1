$ErrorActionPreference = 'Stop'
$deps = Join-Path $PSScriptRoot 'deps'
$androidJar = Join-Path $deps 'platform-34\android-34\android.jar'
$fastjsonJar = Join-Path $deps 'fastjson-1.2.83.jar'
$kotlinJar = Join-Path $deps 'kotlin-stdlib-1.3.72.jar'
$platformZip = Join-Path $deps 'platform-34.zip'

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

if (-not (Test-Path $kotlinJar)) {
    Invoke-WebRequest -Uri 'https://repo1.maven.org/maven2/org/jetbrains/kotlin/kotlin-stdlib/1.3.72/kotlin-stdlib-1.3.72.jar' -OutFile $kotlinJar
}

if (-not (Test-Path $androidJar)) {
    throw "android.jar 未找到: $androidJar"
}

Write-Host '依赖就绪:'
Get-Item $fastjsonJar, $kotlinJar, $androidJar | Format-List FullName, Length
