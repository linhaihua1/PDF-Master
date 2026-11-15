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

# 调用外部程序并只按退出码判定成败。
#
# 为什么不能直接 `& $exe args`：PowerShell 会把外部程序写到 stderr 的内容
# （Gradle 的弃用警告、jpackage 的进度提示）包装成 ErrorRecord，再叠加
# $ErrorActionPreference='Stop' 就变成致命异常——即使退出码是 0。表现为
# "Gradle 构建失败"，但单独跑 gradle 明明是 BUILD SUCCESSFUL。
# 所以执行期间临时把策略降为 Continue，只认 $LASTEXITCODE。
function Invoke-Native {
    param([string]$Exe, [string[]]$CmdArgs, [string]$FailMessage)
    $prev = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try {
        & $Exe @CmdArgs
        $code = $LASTEXITCODE
    } finally {
        $ErrorActionPreference = $prev
    }
    if ($code -ne 0) { throw "$FailMessage（退出码 $code）" }
}
$env:JAVA_HOME = 'C:\dev\jdk-17.0.13'
$gradle = 'C:\dev\gradle-8.14\bin\gradle.bat'
$jpackage = Join-Path $env:JAVA_HOME 'bin\jpackage.exe'

Write-Host "==> 工程目录 $root" -ForegroundColor Cyan
Set-Location $root

$task = if ($SkipTest) { 'installDist' } else { 'test', 'installDist' }
Invoke-Native $gradle (@($task) + '--console=plain') "Gradle 构建失败"

if (-not $Package) {
    Write-Host "==> 构建完成（未打包）。加 -Package 生成 exe。" -ForegroundColor Green
    exit 0
}

$lib = Join-Path $root 'build\install\pdfkit\lib'
if (-not (Test-Path $lib)) { throw "找不到 $lib，请先执行 installDist" }

$common = @(
    '--name', 'PdfKit',
    '--app-version', '1.2.1',
    '--vendor', 'PdfKit',
    '--description', 'PDF 工具箱 · 离线版',
    '--input', $lib,
    '--main-jar', 'pdfkit-1.2.1.jar',
    '--main-class', 'io.pdfkit.App',
    '--java-options', '-Dfile.encoding=UTF-8',
    '--java-options', '-Xmx1024m'
)

Write-Host "==> 生成绿色版 (app-image)" -ForegroundColor Cyan
$appImage = Join-Path $root 'dist'
if (Test-Path $appImage) { Remove-Item $appImage -Recurse -Force }
Invoke-Native $jpackage (@('--type', 'app-image') + $common + @('--dest', $appImage)) "app-image 生成失败"

$wix = 'C:\dev\wix314'
if (Test-Path (Join-Path $wix 'light.exe')) {
    $env:PATH = "$wix;$env:PATH"
    Write-Host "==> 生成安装包 (exe)" -ForegroundColor Cyan
    $installer = Join-Path $root 'dist-installer'
    if (Test-Path $installer) { Remove-Item $installer -Recurse -Force }
    Invoke-Native $jpackage (@('--type', 'exe') + $common + @('--dest', $installer,
        '--win-menu', '--win-shortcut', '--win-dir-chooser')) "安装包生成失败"
    Get-ChildItem $installer -Filter *.exe | ForEach-Object {
        Write-Host ("    {0}  {1:N1} MB" -f $_.Name, ($_.Length / 1MB)) -ForegroundColor Green
    }
} else {
    Write-Warning "未找到 WiX Toolset（$wix），跳过安装包。绿色版已生成。"
}

Write-Host "==> 完成" -ForegroundColor Green
