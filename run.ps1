param(
    [Parameter(Mandatory = $true)][string]$Circuit,
    [string]$LogisimJar = '',
    [string]$Tests = '',
    [string[]]$Extensions = @(),
    [string]$Adapter = '',
    [string]$MarsJar = '',
    [long]$Seed = 20261101,
    [int]$RandomCount = 256,
    [int]$MaxSteps = 10000,
    [int]$TimeoutMs = 15000,
    [string]$JavaBin = '',
    [string]$OutputDirectory = ''
)
$ErrorActionPreference = 'Stop'
if (!$LogisimJar) { $LogisimJar = Join-Path $PSScriptRoot 'lib\logisim-generic-2.7.1.jar' }
if (!$Tests) { $Tests = Join-Path $PSScriptRoot 'tests\base' }
if (!$Adapter) { $Adapter = Join-Path $PSScriptRoot 'adapter.properties' }
if (!$MarsJar) { $MarsJar = Join-Path $PSScriptRoot 'lib\Mars_CO.jar' }
if (!$OutputDirectory) { $OutputDirectory = Join-Path $PSScriptRoot 'output' }
$circuitPath = (Resolve-Path -LiteralPath $Circuit).Path
$logisimPath = (Resolve-Path -LiteralPath $LogisimJar).Path
$marsPath = (Resolve-Path -LiteralPath $MarsJar).Path
$adapterPath = (Resolve-Path -LiteralPath $Adapter).Path
$testsPath = (Resolve-Path -LiteralPath $Tests).Path
$extensionPaths = @($Extensions | ForEach-Object { (Resolve-Path -LiteralPath $_).Path })
if ($JavaBin) {
    $javaPath = Join-Path $JavaBin 'java.exe'
    $javacPath = Join-Path $JavaBin 'javac.exe'
} else {
    $javaPath = (Get-Command java -ErrorAction Stop).Source
    $javacPath = (Get-Command javac -ErrorAction Stop).Source
}
$runName = (Get-Date -Format 'yyyyMMdd-HHmmss') + '-' + [guid]::NewGuid().ToString('N').Substring(0, 6)
$runPath = Join-Path $OutputDirectory $runName
$buildPath = Join-Path $runPath 'build'
New-Item -ItemType Directory -Path $buildPath -Force | Out-Null
$runPath = (Resolve-Path -LiteralPath $runPath).Path
$buildPath = (Resolve-Path -LiteralPath $buildPath).Path
$sourceNames = @('TestData.java','Generator.java','LogisimRunner.java','MarsOracle.java','MarsTest.java')
$sourceFiles = @($sourceNames | ForEach-Object { Join-Path (Join-Path $PSScriptRoot 'src') $_ })
$compileArgs = @('--release', '17', '-encoding', 'UTF-8', '-classpath', ($logisimPath + [IO.Path]::PathSeparator + $marsPath), '-d', $buildPath) + $sourceFiles
& $javacPath @compileArgs
if ($LASTEXITCODE -ne 0) { throw 'Compilation failed. Check JDK 17+ and the dependency JAR paths.' }
$extensionList = Join-Path $runPath 'extension-paths.txt'
[IO.File]::WriteAllLines($extensionList, [string[]]$extensionPaths, [Text.UTF8Encoding]::new($false))
$runArgs = @('-Djava.awt.headless=true', '-Dfile.encoding=UTF-8', '-classpath', ($buildPath + [IO.Path]::PathSeparator + $logisimPath), 'MarsTest',
    '--circuit', $circuitPath, '--adapter', $adapterPath, '--tests', $testsPath, '--out', $runPath,
    '--mars-jar', $marsPath, '--extension-list', $extensionList,
    '--seed', $Seed.ToString(), '--random-count', $RandomCount.ToString(), '--max-steps', $MaxSteps.ToString(), '--timeout-ms', $TimeoutMs.ToString())
& $javaPath @runArgs 2>&1 | Tee-Object -FilePath (Join-Path $runPath 'run.log')
exit $LASTEXITCODE
