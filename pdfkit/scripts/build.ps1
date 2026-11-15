# PdfKit 一键构建脚本
# 用法：
#   .\scripts\build.ps1              # 编译 + 测试
#   .\scripts\build.ps1 -Package     # 编译 + 测试 + 绿色版 + 安装包
param(
    [switch]$Package,
    [switch]$SkipTest
)

$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
$env:JAVA_HOME = 'C:\dev\jdk-17.0.13'
$gradle = 'C:\dev\gradle-8.14\bin\gradle.bat'
$jpackage = Join-Path $env:JAVA_HOME 'bin\jpackage.exe'

Write-Host "==> 工程目录 $root" -ForegroundColor Cyan
Set-Location $root

$task = if ($SkipTest) { 'installDist' } else { 'test', 'installDist' }
& $gradle @task --console=plain
if ($LASTEXITCODE -ne 0) { throw "Gradle 构建失败" }

if (-not $Package) {
    Write-Host "==> 构建完成（未打包）。加 -Package 生成 exe。" -ForegroundColor Green
    exit 0
}

$lib = Join-Path $root 'build\install\pdfkit\lib'
if (-not (Test-Path $lib)) { throw "找不到 $lib，请先执行 installDist" }

$common = @(
    '--name', 'PdfKit',
    '--app-version', '1.1.0',
    '--vendor', 'PdfKit',
    '--description', 'PDF 工具箱 · 离线版',
    '--input', $lib,
    '--main-jar', 'pdfkit-1.1.0.jar',
    '--main-class', 'io.pdfkit.App',
    '--java-options', '-Dfile.encoding=UTF-8',
    '--java-options', '-Xmx1024m'
)

Write-Host "==> 生成绿色版 (app-image)" -ForegroundColor Cyan
$appImage = Join-Path $root 'dist'
if (Test-Path $appImage) { Remove-Item $appImage -Recurse -Force }
& $jpackage --type app-image @common --dest $appImage
if ($LASTEXITCODE -ne 0) { throw "app-image 生成失败" }

$wix = 'C:\dev\wix314'
if (Test-Path (Join-Path $wix 'light.exe')) {
    $env:PATH = "$wix;$env:PATH"
    Write-Host "==> 生成安装包 (exe)" -ForegroundColor Cyan
    $installer = Join-Path $root 'dist-installer'
    if (Test-Path $installer) { Remove-Item $installer -Recurse -Force }
    & $jpackage --type exe @common --dest $installer --win-menu --win-shortcut --win-dir-chooser
    if ($LASTEXITCODE -ne 0) { throw "安装包生成失败" }
    Get-ChildItem $installer -Filter *.exe | ForEach-Object {
        Write-Host ("    {0}  {1:N1} MB" -f $_.Name, ($_.Length / 1MB)) -ForegroundColor Green
    }
} else {
    Write-Warning "未找到 WiX Toolset（$wix），跳过安装包。绿色版已生成。"
}

Write-Host "==> 完成" -ForegroundColor Green
