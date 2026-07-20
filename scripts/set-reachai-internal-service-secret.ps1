<#
.SYNOPSIS
Configure the shared secret used for trusted ReachAI Control-to-Runtime calls.

.DESCRIPTION
Sets REACHAI_INTERNAL_SERVICE_SECRET for the selected Windows environment
target. When no secret is supplied, the script reuses the existing value or
generates a cryptographically secure 48-byte value. The secret is never
printed or written into the repository.

.EXAMPLE
.\scripts\set-reachai-internal-service-secret.ps1

.EXAMPLE
.\scripts\set-reachai-internal-service-secret.ps1 -Rotate -Target User

.EXAMPLE
.\scripts\set-reachai-internal-service-secret.ps1 -Secret '<shared-secret>' -Target Process
#>

[CmdletBinding()]
param(
    [string]$Secret,

    [switch]$Rotate,

    [ValidateSet('User', 'Machine', 'Process')]
    [string]$Target = 'User'
)

$ErrorActionPreference = 'Stop'

function New-InternalServiceSecret {
    $bytes = New-Object byte[] 48
    $rng = [Security.Cryptography.RandomNumberGenerator]::Create()
    try {
        $rng.GetBytes($bytes)
    }
    finally {
        $rng.Dispose()
    }
    return [Convert]::ToBase64String($bytes)
}

$existing = [Environment]::GetEnvironmentVariable('REACHAI_INTERNAL_SERVICE_SECRET', $Target)
$resolved = $Secret

if ([string]::IsNullOrWhiteSpace($resolved) -and -not $Rotate -and -not [string]::IsNullOrWhiteSpace($existing)) {
    $resolved = $existing
}

if ([string]::IsNullOrWhiteSpace($resolved)) {
    $resolved = New-InternalServiceSecret
}

$resolved = $resolved.Trim()
if ($resolved.Length -lt 32) {
    throw 'REACHAI_INTERNAL_SERVICE_SECRET must contain at least 32 characters.'
}

[Environment]::SetEnvironmentVariable('REACHAI_INTERNAL_SERVICE_SECRET', $resolved, $Target)
Set-Item -Path 'Env:REACHAI_INTERNAL_SERVICE_SECRET' -Value $resolved

Write-Host 'REACHAI_INTERNAL_SERVICE_SECRET is configured.' -ForegroundColor Green
Write-Host "  Target: $Target"
Write-Host '  Value:  ******** (not printed)'

if ($Target -ne 'Process') {
    Write-Host ''
    Write-Host 'Restart IntelliJ IDEA and both ReachAI Control/Runtime run configurations.' -ForegroundColor Yellow
    Write-Host 'Already-running JVM processes cannot see the updated Windows environment.' -ForegroundColor Yellow
}
