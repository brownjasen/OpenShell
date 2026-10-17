param(
    [string]$LocalRepository = (Join-Path $PSScriptRoot '.build-cache\m2'),
    [string[]]$Goals = @('clean', 'verify')
)
$ErrorActionPreference = 'Stop'
if (!(Get-Command mvn -ErrorAction SilentlyContinue)) { throw '请先安装 JDK 17+ 和 Maven，并加入 PATH。' }
# Use the same .mvn configuration as a normal Maven invocation and IDEA import.
# Resolve an explicit cache path before changing the working directory.
$resolvedRepository = [IO.Path]::GetFullPath($LocalRepository)
Push-Location $PSScriptRoot
try {
    & mvn -B -ntp "-Dmaven.repo.local=$resolvedRepository" @Goals
    if ($LASTEXITCODE -ne 0) { throw "Maven 构建失败，退出码 $LASTEXITCODE" }
} finally {
    Pop-Location
}
