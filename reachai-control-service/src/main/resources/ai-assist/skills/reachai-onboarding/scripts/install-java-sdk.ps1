[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [ValidatePattern('^[A-Za-z0-9_.-]+$')]
    [string]$GroupId,

    [Parameter(Mandatory = $true)]
    [ValidatePattern('^[A-Za-z0-9_.-]+$')]
    [string]$ArtifactId,

    [Parameter(Mandatory = $true)]
    [ValidatePattern('^[A-Za-z0-9_.-]+$')]
    [string]$Version,

    [Parameter(Mandatory = $true)]
    [ValidatePattern('^https?://')]
    [string]$JarUrl,

    [Parameter(Mandatory = $true)]
    [ValidatePattern('^[a-fA-F0-9]{64}$')]
    [string]$JarSha256,

    [Parameter(Mandatory = $true)]
    [ValidatePattern('^https?://')]
    [string]$PomUrl,

    [Parameter(Mandatory = $true)]
    [ValidatePattern('^[a-fA-F0-9]{64}$')]
    [string]$PomSha256,

    [string]$MavenExecutable = 'mvn',

    [string]$MavenLocalRepository = ''
)

$ErrorActionPreference = 'Stop'
$temporaryRoot = Join-Path ([System.IO.Path]::GetTempPath()) ('reachai-java-sdk-' + [Guid]::NewGuid().ToString('N'))
$resolvedTemporaryRoot = [System.IO.Path]::GetFullPath($temporaryRoot)
$expectedTempRoot = [System.IO.Path]::GetFullPath([System.IO.Path]::GetTempPath())

if (-not $resolvedTemporaryRoot.StartsWith($expectedTempRoot, [System.StringComparison]::OrdinalIgnoreCase)) {
    throw 'Refusing to use a temporary directory outside the operating-system temp root.'
}

New-Item -ItemType Directory -Path $resolvedTemporaryRoot | Out-Null

try {
    $jarPath = Join-Path $resolvedTemporaryRoot ($ArtifactId + '-' + $Version + '.jar')
    $pomPath = Join-Path $resolvedTemporaryRoot ($ArtifactId + '-' + $Version + '.pom')

    Invoke-WebRequest -UseBasicParsing -Uri $JarUrl -OutFile $jarPath
    Invoke-WebRequest -UseBasicParsing -Uri $PomUrl -OutFile $pomPath

    $actualJarSha256 = (Get-FileHash -LiteralPath $jarPath -Algorithm SHA256).Hash.ToLowerInvariant()
    $actualPomSha256 = (Get-FileHash -LiteralPath $pomPath -Algorithm SHA256).Hash.ToLowerInvariant()
    if ($actualJarSha256 -ne $JarSha256.ToLowerInvariant()) {
        throw "JAR SHA-256 mismatch for $GroupId`:$ArtifactId`:$Version"
    }
    if ($actualPomSha256 -ne $PomSha256.ToLowerInvariant()) {
        throw "POM SHA-256 mismatch for $GroupId`:$ArtifactId`:$Version"
    }

    $mavenArguments = @(
        'install:install-file',
        "-Dfile=$jarPath",
        "-DpomFile=$pomPath",
        '-DgeneratePom=false'
    )
    if (-not [string]::IsNullOrWhiteSpace($MavenLocalRepository)) {
        $resolvedMavenRepository = [System.IO.Path]::GetFullPath($MavenLocalRepository)
        New-Item -ItemType Directory -Path $resolvedMavenRepository -Force | Out-Null
        $mavenArguments += "-Dmaven.repo.local=$resolvedMavenRepository"
    }

    Push-Location -LiteralPath $resolvedTemporaryRoot
    try {
        & $MavenExecutable @mavenArguments
        $mavenExitCode = $LASTEXITCODE
    }
    finally {
        Pop-Location
    }
    if ($mavenExitCode -ne 0) {
        throw "Maven install failed with exit code $mavenExitCode"
    }

    Write-Output "[install-java-sdk] installed=$GroupId`:$ArtifactId`:$Version"
    Write-Output "[install-java-sdk] jarSha256=$actualJarSha256"
    Write-Output "[install-java-sdk] pomSha256=$actualPomSha256"
}
finally {
    if (Test-Path -LiteralPath $resolvedTemporaryRoot) {
        Remove-Item -LiteralPath $resolvedTemporaryRoot -Recurse -Force
    }
}
