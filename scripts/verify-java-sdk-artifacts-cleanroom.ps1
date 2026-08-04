[CmdletBinding()]
param(
    [string]$PlatformBaseUrl = 'http://localhost:18603',
    [string]$MavenExecutable = 'mvn',
    [string]$CleanupStaleDirectory,
    [switch]$CleanupOnly
)

$ErrorActionPreference = 'Stop'
$platformRoot = $PlatformBaseUrl.TrimEnd('/')
$version = '1.0.0-SNAPSHOT'
$expectedTempRoot = [System.IO.Path]::GetFullPath([System.IO.Path]::GetTempPath())

function Remove-ValidatedCleanroomDirectory {
    param([Parameter(Mandatory = $true)][string]$Path)

    $resolvedPath = [System.IO.Path]::GetFullPath($Path)
    $leafName = Split-Path -Leaf $resolvedPath
    if (-not $resolvedPath.StartsWith($expectedTempRoot, [System.StringComparison]::OrdinalIgnoreCase) `
            -or $leafName -notmatch '^reachai-java-sdk-cleanroom-[0-9a-f]{32}$') {
        throw "Refusing to remove an invalid clean-room directory: $resolvedPath"
    }
    if (Test-Path -LiteralPath $resolvedPath) {
        Remove-Item -LiteralPath $resolvedPath -Recurse -Force
    }
    Write-Output "[verify-java-sdk-artifacts-cleanroom] cleaned=$resolvedPath"
}

if ($CleanupStaleDirectory) {
    Remove-ValidatedCleanroomDirectory -Path $CleanupStaleDirectory
}
if ($CleanupOnly) {
    return
}

$temporaryRoot = Join-Path ([System.IO.Path]::GetTempPath()) ('reachai-java-sdk-cleanroom-' + [Guid]::NewGuid().ToString('N'))
$resolvedTemporaryRoot = [System.IO.Path]::GetFullPath($temporaryRoot)
if (-not $resolvedTemporaryRoot.StartsWith($expectedTempRoot, [System.StringComparison]::OrdinalIgnoreCase)) {
    throw 'Refusing to use a clean-room directory outside the operating-system temp root.'
}
New-Item -ItemType Directory -Path $resolvedTemporaryRoot | Out-Null

try {
    $skillZip = Join-Path $resolvedTemporaryRoot 'reachai-onboarding.zip'
    $skillExtract = Join-Path $resolvedTemporaryRoot 'skill'
    $mavenRepository = Join-Path $resolvedTemporaryRoot 'maven-repository'
    $consumerRoot = Join-Path $resolvedTemporaryRoot 'consumer'
    $consumerSourceRoot = Join-Path $consumerRoot 'src/main/java/probe'

    Invoke-WebRequest -UseBasicParsing `
        -Uri "$platformRoot/api/ai-assist/skills/reachai-onboarding/latest.zip" `
        -OutFile $skillZip
    Expand-Archive -LiteralPath $skillZip -DestinationPath $skillExtract
    $installer = Join-Path $skillExtract 'reachai-onboarding/scripts/install-java-sdk.ps1'
    if (-not (Test-Path -LiteralPath $installer)) {
        throw "Onboarding Skill does not contain its Java SDK installer: $installer"
    }

    $artifacts = @(
        'reachai-capability-sdk',
        'reachai-spring-boot2-starter'
    )
    foreach ($artifactId in $artifacts) {
        $artifactRoot = "$platformRoot/api/ai-assist/artifacts/java-sdk/$artifactId/$version"
        $jarSha256 = (Invoke-WebRequest -UseBasicParsing -Uri "$artifactRoot.jar.sha256").Content.Trim()
        $pomSha256 = (Invoke-WebRequest -UseBasicParsing -Uri "$artifactRoot.pom.sha256").Content.Trim()
        & $installer `
            -GroupId 'com.enterprise.ai' `
            -ArtifactId $artifactId `
            -Version $version `
            -JarUrl "$artifactRoot.jar" `
            -JarSha256 $jarSha256 `
            -PomUrl "$artifactRoot.pom" `
            -PomSha256 $pomSha256 `
            -MavenExecutable $MavenExecutable `
            -MavenLocalRepository $mavenRepository
        if ($LASTEXITCODE -ne 0) {
            throw "Java SDK installer failed for $artifactId"
        }
    }

    New-Item -ItemType Directory -Path $consumerSourceRoot -Force | Out-Null
    $utf8 = [System.Text.UTF8Encoding]::new($false)
    $consumerPom = @'
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
  <modelVersion>4.0.0</modelVersion>
  <groupId>probe</groupId>
  <artifactId>external-business-system-probe</artifactId>
  <version>1.0.0</version>
  <properties>
    <maven.compiler.source>8</maven.compiler.source>
    <maven.compiler.target>8</maven.compiler.target>
    <project.build.sourceEncoding>UTF-8</project.build.sourceEncoding>
  </properties>
  <dependencies>
    <dependency>
      <groupId>com.enterprise.ai</groupId>
      <artifactId>reachai-spring-boot2-starter</artifactId>
      <version>1.0.0-SNAPSHOT</version>
    </dependency>
  </dependencies>
</project>
'@
    $consumerSource = @'
package probe;

import com.enterprise.ai.reach.sdk.annotation.ReachCapability;
import com.enterprise.ai.reach.sdk.annotation.ReachParam;
import com.enterprise.ai.reach.spring.ReachAiRegistryProperties;

public class ExternalBusinessSystemProbe {
    private ReachAiRegistryProperties properties;
    private final ReachAiRegistryProperties.ScanMode scanMode =
            ReachAiRegistryProperties.ScanMode.ANNOTATED_ONLY;

    @ReachCapability(name = "probe.query", title = "Clean-room query")
    public String query(@ReachParam(name = "id", required = true) String id) {
        return properties == null ? scanMode.name() + id : properties.getProject().getCode() + id;
    }
}
'@
    [System.IO.File]::WriteAllText(
        (Join-Path $consumerRoot 'pom.xml'),
        $consumerPom,
        $utf8)
    [System.IO.File]::WriteAllText(
        (Join-Path $consumerSourceRoot 'ExternalBusinessSystemProbe.java'),
        $consumerSource,
        $utf8)

    & $MavenExecutable `
        -f (Join-Path $consumerRoot 'pom.xml') `
        "-Dmaven.repo.local=$mavenRepository" `
        package
    if ($LASTEXITCODE -ne 0) {
        throw "Clean-room consumer Maven build failed with exit code $LASTEXITCODE"
    }

    $consumerJar = Join-Path $consumerRoot 'target/external-business-system-probe-1.0.0.jar'
    if (-not (Test-Path -LiteralPath $consumerJar)) {
        throw "Clean-room consumer JAR was not produced: $consumerJar"
    }
    Write-Output '[verify-java-sdk-artifacts-cleanroom] result=PASS'
    Write-Output '[verify-java-sdk-artifacts-cleanroom] sourceCheckoutUsed=false'
    Write-Output '[verify-java-sdk-artifacts-cleanroom] initialMavenRepository=empty'
    Write-Output "[verify-java-sdk-artifacts-cleanroom] consumerJar=$consumerJar"
}
finally {
    if (Test-Path -LiteralPath $resolvedTemporaryRoot) {
        Remove-Item -LiteralPath $resolvedTemporaryRoot -Recurse -Force
    }
}
