$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
$projectRoot = Split-Path -Parent $root
$buildDir = $PSScriptRoot
$deps = Join-Path $buildDir 'deps'
$classes = Join-Path $buildDir 'classes'
$buildOut = Join-Path $buildDir 'out'
$pluginAndroid = Join-Path $projectRoot 'nativeplugins\FaceUnity-Nama\android'
$pluginLibs = Join-Path $pluginAndroid 'libs'
$androidJar = Join-Path $deps 'platform-34\android-34\android.jar'
$fastjsonJar = Join-Path $deps 'fastjson-1.2.83.jar'
$coreClassesJar = Join-Path $deps 'core-classes.jar'
$kotlinJar = Join-Path $deps 'kotlin-stdlib-1.8.22.jar'
$coreAar = Join-Path $deps 'core-9.0.1.aar'
$namaJar = Join-Path $pluginLibs 'nama.jar'
$namaSrcDir = Join-Path $buildDir 'src\com\faceunity\nama'
$authSrcDir = Join-Path $buildDir 'src\com\faceunity\app'

if (-not (Test-Path $androidJar) -or -not (Test-Path $fastjsonJar) -or -not (Test-Path $coreClassesJar)) {
    Write-Host '编译依赖缺失，自动执行 download-deps.ps1 ...'
    & powershell -ExecutionPolicy Bypass -File (Join-Path $buildDir 'download-deps.ps1')
}
if (-not (Test-Path $androidJar)) { throw "缺少 android.jar，请先运行 download-deps.ps1" }
if (-not (Test-Path $fastjsonJar)) { throw "缺少 fastjson jar" }
if (-not (Test-Path $coreClassesJar)) { throw "缺少 core-classes.jar" }
if (-not (Test-Path $kotlinJar)) { throw "缺少 kotlin-stdlib jar" }
if (-not (Test-Path $coreAar)) { throw "缺少 core-9.0.1.aar" }
if (-not (Test-Path $namaSrcDir)) { throw "缺少 nama 源码目录" }
$authpackJava = Join-Path $authSrcDir 'authpack.java'
if (-not (Test-Path $authpackJava)) {
    throw "缺少 authpack.java（证书方提供，本地放入 $authpackJava，勿提交 git）"
}

# 编译期 nama.jar：优先插件 libs，否则从 core AAR 解出
$namaCompileJar = $namaJar
if (-not (Test-Path $namaCompileJar)) {
    Write-Host '从 core AAR 解出 nama.jar（编译用）...'
    $namaExtract = Join-Path $deps 'nama-extract'
    Remove-Item -Recurse -Force $namaExtract -ErrorAction SilentlyContinue
    New-Item -ItemType Directory -Force -Path $namaExtract | Out-Null
    Copy-Item $coreAar (Join-Path $namaExtract 'core.zip')
    Expand-Archive (Join-Path $namaExtract 'core.zip') -DestinationPath $namaExtract -Force
    $namaCompileJar = Join-Path $namaExtract 'libs\nama.jar'
    if (-not (Test-Path $namaCompileJar)) { throw 'core AAR 内未找到 libs/nama.jar' }
}

Remove-Item -Recurse -Force $classes, $buildOut -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Force -Path $classes, $buildOut, $pluginLibs | Out-Null

$stubSources = Get-ChildItem (Join-Path $buildDir 'stubs') -Recurse -Filter '*.java' | ForEach-Object { $_.FullName }
$namaSources = Get-ChildItem $namaSrcDir -Filter '*.java' | ForEach-Object { $_.FullName }
$authSources = Get-ChildItem $authSrcDir -Filter '*.java' | ForEach-Object { $_.FullName }
$sources = $namaSources + $authSources + $stubSources
$cp = "$androidJar;$fastjsonJar;$coreClassesJar;$kotlinJar;$namaCompileJar"

Write-Host '编译桥接模块 ...'
& javac -encoding UTF-8 -source 8 -target 8 -cp $cp -d $classes @sources
if ($LASTEXITCODE -ne 0) { throw 'javac 编译失败' }

$classesJar = Join-Path $buildOut 'classes.jar'
& jar cf $classesJar -C $classes 'com/faceunity/nama' -C $classes 'com/faceunity/app'

# 打包顶栏/拍摄图标到 classes.jar，供 PreviewChromeView 从 classpath 加载
$chromeAssets = Join-Path $buildDir 'assets\fu_chrome'
if (Test-Path $chromeAssets) {
    $assetsStage = Join-Path $buildOut 'asset_stage'
    New-Item -ItemType Directory -Force -Path (Join-Path $assetsStage 'fu_chrome') | Out-Null
    Copy-Item (Join-Path $chromeAssets '*') (Join-Path $assetsStage 'fu_chrome') -Force
    Push-Location $assetsStage
    & jar uf $classesJar fu_chrome
    Pop-Location
    Write-Host '已打包 fu_chrome 图标资源'
}

$manifest = Join-Path $buildOut 'AndroidManifest.xml'
Set-Content -Path $manifest -Value '<?xml version="1.0" encoding="utf-8"?><manifest package="com.faceunity.nama" />' -Encoding UTF8

$aarPath = Join-Path $pluginAndroid 'FaceUnity-Nama.aar'
if (Test-Path $aarPath) { Remove-Item $aarPath -Force }
Push-Location $buildOut
& jar cf $aarPath AndroidManifest.xml classes.jar
if (Test-Path $chromeAssets) {
    $aarAssets = Join-Path $buildOut 'aar_assets\assets\fu_chrome'
    New-Item -ItemType Directory -Force -Path $aarAssets | Out-Null
    Copy-Item (Join-Path $chromeAssets '*') $aarAssets -Force
    Push-Location (Join-Path $buildOut 'aar_assets')
    & jar uf $aarPath assets
    Pop-Location
}
Pop-Location

# Runtime: nama.jar + .so only. Kotlin stdlib comes from HBuilderX cloud build.
Write-Host 'Sync native libs (nama.jar + .so)...'
$staleKotlin = Join-Path $pluginLibs 'kotlin-stdlib-1.8.22.jar'
if (Test-Path $staleKotlin) {
    Remove-Item $staleKotlin -Force
    Write-Host 'Removed stale kotlin-stdlib from plugin libs'
}

$coreExtract = Join-Path $buildOut 'core-sync'
Remove-Item -Recurse -Force $coreExtract -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Force -Path $coreExtract | Out-Null
Copy-Item $coreAar (Join-Path $coreExtract 'core.zip')
Expand-Archive (Join-Path $coreExtract 'core.zip') -DestinationPath $coreExtract -Force
Copy-Item (Join-Path $coreExtract 'libs\nama.jar') $namaJar -Force
foreach ($abi in @('arm64-v8a', 'armeabi-v7a')) {
    $abiDir = Join-Path $pluginLibs $abi
    New-Item -ItemType Directory -Force -Path $abiDir | Out-Null
    Copy-Item (Join-Path $coreExtract "jni\$abi\libCNamaSDK.so") (Join-Path $abiDir 'libCNamaSDK.so') -Force
    Copy-Item (Join-Path $coreExtract "jni\$abi\libfuai.so") (Join-Path $abiDir 'libfuai.so') -Force
}
Remove-Item -Recurse -Force $coreExtract -ErrorAction SilentlyContinue

# 清理历史大包（Core/Model 整包 AAR 不再随插件分发）
foreach ($stale in @('FaceUnity-Core.aar', 'FaceUnity-Model.aar')) {
    $p = Join-Path $pluginAndroid $stale
    if (Test-Path $p) {
        Remove-Item $p -Force
        Write-Host "已移除冗余: $stale"
    }
}

$totalBytes = (Get-ChildItem $pluginAndroid -Recurse -File | Measure-Object -Property Length -Sum).Sum
Write-Host "已生成: $aarPath"
Write-Host ("插件 android/ 合计: {0:N2} MB" -f ($totalBytes / 1MB))
Get-Item $aarPath | Format-List FullName, Length
