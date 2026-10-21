$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
$projectRoot = Split-Path -Parent $root
$buildDir = $PSScriptRoot
$deps = Join-Path $buildDir 'deps'
$classes = Join-Path $buildDir 'classes'
$out = Join-Path $buildDir 'out'
$pluginAndroid = Join-Path $projectRoot 'nativeplugins\FaceUnity-Nama\android'
$androidJar = Join-Path $deps 'platform-34\android-34\android.jar'
$fastjsonJar = Join-Path $deps 'fastjson-1.2.83.jar'
$kotlinJar = Join-Path $deps 'kotlin-stdlib-1.3.72.jar'
$pluginLibs = Join-Path $pluginAndroid 'libs'
$sdkExtract = Join-Path $deps 'fu_sdk_extract'
$sdkAars = @{
    fu_core = 'fu_core_all_feature_release.aar'
    fu_beauty = 'fu_beauty_face_beauty_sticker_release.aar'
    fu_model = 'fu_model_all_feature_release.aar'
}
$namaSrcDir = Join-Path $buildDir 'src\com\faceunity\nama'
$authSrcDir = Join-Path $buildDir 'src\com\faceunity\app'

$authpackJava = Join-Path $authSrcDir 'authpack.java'
if (-not (Test-Path $authpackJava)) { throw "缺少授权文件（仅本机，不提交）: $authpackJava" }

if (-not (Test-Path $androidJar) -or -not (Test-Path $fastjsonJar) -or -not (Test-Path $kotlinJar)) {
    Write-Host '编译依赖缺失，自动执行 download-deps.ps1 ...'
    & powershell -ExecutionPolicy Bypass -File (Join-Path $buildDir 'download-deps.ps1')
}
if (-not (Test-Path $androidJar)) { throw "缺少 android.jar，请先运行 download-deps.ps1" }
if (-not (Test-Path $fastjsonJar)) { throw "缺少 fastjson jar" }
if (-not (Test-Path $kotlinJar)) { throw "缺少 kotlin-stdlib jar" }
if (-not (Test-Path $namaSrcDir)) { throw "缺少 nama 源码目录" }

foreach ($name in $sdkAars.Keys) {
    $aar = Join-Path $pluginLibs $sdkAars[$name]
    if (-not (Test-Path $aar)) { throw "缺少官方 AAR: $aar" }
}
Remove-Item -Recurse -Force $sdkExtract -ErrorAction SilentlyContinue
foreach ($name in $sdkAars.Keys) {
    $destination = Join-Path $sdkExtract $name
    New-Item -ItemType Directory -Force -Path $destination | Out-Null
    $zip = Join-Path $sdkExtract "$name.zip"
    Copy-Item (Join-Path $pluginLibs $sdkAars[$name]) $zip
    Expand-Archive -Path $zip -DestinationPath $destination -Force
    Remove-Item $zip -Force
}
$namaJar = Join-Path $sdkExtract 'fu_core\libs\nama.jar'
$coreJar = Join-Path $sdkExtract 'fu_core\classes.jar'
$beautyJar = Join-Path $sdkExtract 'fu_beauty\classes.jar'
foreach ($file in @($namaJar, $coreJar, $beautyJar)) {
    if (-not (Test-Path $file)) { throw "官方 AAR 内缺少编译依赖: $file" }
}

Remove-Item -Recurse -Force $classes, $out -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Force -Path $classes, $out | Out-Null

$stubSources = Get-ChildItem (Join-Path $buildDir 'stubs') -Recurse -Filter '*.java' | ForEach-Object { $_.FullName }
$namaSources = Get-ChildItem $namaSrcDir -Recurse -Filter '*.java' | ForEach-Object { $_.FullName }
$authSources = Get-ChildItem $authSrcDir -Filter '*.java' | ForEach-Object { $_.FullName }
$sources = $namaSources + $authSources + $stubSources
$cp = "$androidJar;$fastjsonJar;$kotlinJar;$namaJar;$coreJar;$beautyJar"

Write-Host '编译桥接模块...'
& javac -encoding UTF-8 -source 8 -target 8 -cp $cp -d $classes @sources
if ($LASTEXITCODE -ne 0) { throw 'javac 编译失败' }

$classesJar = Join-Path $out 'classes.jar'
& jar cf $classesJar -C $classes 'com/faceunity/nama' -C $classes 'com/faceunity/app'
if ($LASTEXITCODE -ne 0) { throw 'classes.jar 打包失败' }

# 打包顶栏/拍摄图标到 classes.jar，供 PreviewChromeView 从 classpath 加载
$chromeAssets = Join-Path $buildDir 'assets\fu_chrome'
if (Test-Path $chromeAssets) {
    $assetsStage = Join-Path $out 'asset_stage'
    New-Item -ItemType Directory -Force -Path (Join-Path $assetsStage 'fu_chrome') | Out-Null
    Copy-Item (Join-Path $chromeAssets '*') (Join-Path $assetsStage 'fu_chrome') -Force
    Push-Location $assetsStage
    & jar uf $classesJar fu_chrome
    if ($LASTEXITCODE -ne 0) { throw '图标打包失败' }
    Pop-Location
    Write-Host '已打包 fu_chrome 图标资源'
}

$manifest = Join-Path $out 'AndroidManifest.xml'
Set-Content -Path $manifest -Value '<?xml version="1.0" encoding="utf-8"?><manifest package="com.faceunity.nama" />' -Encoding UTF8

$aarPath = Join-Path $pluginAndroid 'FaceUnity-Nama.aar'
if (Test-Path $aarPath) { Remove-Item $aarPath -Force }
Push-Location $out
& jar cf $aarPath AndroidManifest.xml classes.jar
if ($LASTEXITCODE -ne 0) { throw 'AAR 打包失败' }
# 同步 assets 到 aar（部分基座从 aar assets 读）
if (Test-Path $chromeAssets) {
    $aarAssets = Join-Path $out 'aar_assets\assets\fu_chrome'
    New-Item -ItemType Directory -Force -Path $aarAssets | Out-Null
    Copy-Item (Join-Path $chromeAssets '*') $aarAssets -Force
    Push-Location (Join-Path $out 'aar_assets')
    & jar uf $aarPath assets
    if ($LASTEXITCODE -ne 0) { throw 'AAR 资源打包失败' }
    Pop-Location
}
Pop-Location

$hostAar = Join-Path $projectRoot 'android-local-host-5.15\simpleDemo\libs\FaceUnity-Nama.aar'
if (Test-Path (Split-Path -Parent $hostAar)) {
    Copy-Item $aarPath $hostAar -Force
}

Write-Host "已生成: $aarPath"
Get-Item $aarPath | Format-List FullName, Length
