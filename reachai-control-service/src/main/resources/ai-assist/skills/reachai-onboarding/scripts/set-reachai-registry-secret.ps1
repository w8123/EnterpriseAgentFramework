[CmdletBinding()]
param(
    [ValidateSet('Process', 'User')]
    [string]$Target = 'User',

    [switch]$Clear
)

$ErrorActionPreference = 'Stop'
$variableName = 'REACHAI_REGISTRY_APP_SECRET'

if ($Clear) {
    [Environment]::SetEnvironmentVariable($variableName, $null, $Target)
    Write-Output "[set-reachai-registry-secret] cleared=$variableName target=$Target"
    exit 0
}

$secureValue = Read-Host "Enter $variableName (input is hidden)" -AsSecureString
$credential = [System.Management.Automation.PSCredential]::new('reachai', $secureValue)
$plainValue = $credential.GetNetworkCredential().Password
if ([string]::IsNullOrWhiteSpace($plainValue)) {
    throw "$variableName must not be empty."
}

try {
    [Environment]::SetEnvironmentVariable($variableName, $plainValue, $Target)
    Write-Output "[set-reachai-registry-secret] configured=$variableName target=$Target"
}
finally {
    $plainValue = $null
    $credential = $null
    $secureValue = $null
}
