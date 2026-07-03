<#
.SYNOPSIS
Set local Windows environment variables used by ReachAI MySQL connections.

.DESCRIPTION
Run without arguments to start an interactive setup wizard. The script writes
AI_MYSQL_HOST, AI_MYSQL_PORT, AI_MYSQL_DATABASE, AI_MYSQL_USER,
AI_MYSQL_PASSWORD, and AI_MYSQL_URL to the selected Windows environment target.

The Spring services read AI_MYSQL_URL, AI_MYSQL_USER, and AI_MYSQL_PASSWORD.
The DBHub readonly MCP config also reads the split host/port/database/user
variables.

This script never stores a real password in the repository. If -Password is not
provided, it prompts for the password securely.

.EXAMPLE
.\scripts\set-ai-mysql-env.ps1

.EXAMPLE
.\scripts\set-ai-mysql-env.ps1 -UserName reach_ai -HostName 127.0.0.1 -Port 3306 -Database reach_ai -SetSpringUrl

.EXAMPLE
.\scripts\set-ai-mysql-env.ps1 -Interactive -Target User
#>

[CmdletBinding()]
param(
    [string]$Password,

    [string]$UserName,

    [string]$HostName,

    [ValidateRange(1, 65535)]
    [int]$Port,

    [string]$Database,

    [switch]$SetSpringUrl,

    [switch]$Interactive,

    [ValidateSet('User', 'Machine', 'Process')]
    [string]$Target = 'User'
)

$ErrorActionPreference = 'Stop'

function Read-PlainPassword {
    $secure = Read-Host -Prompt 'Enter AI_MYSQL_PASSWORD' -AsSecureString
    $ptr = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($secure)
    try {
        [Runtime.InteropServices.Marshal]::PtrToStringBSTR($ptr)
    }
    finally {
        [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($ptr)
    }
}

function Read-ValueWithDefault {
    param(
        [Parameter(Mandatory = $true)]
        [string]$Prompt,

        [Parameter(Mandatory = $true)]
        [string]$DefaultValue
    )

    $answer = Read-Host -Prompt "$Prompt [$DefaultValue]"
    if ([string]::IsNullOrWhiteSpace($answer)) {
        return $DefaultValue
    }

    return $answer.Trim()
}

function Get-EnvOrDefault {
    param(
        [Parameter(Mandatory = $true)]
        [string]$Name,

        [Parameter(Mandatory = $true)]
        [string]$DefaultValue
    )

    $value = [Environment]::GetEnvironmentVariable($Name, $Target)
    if ([string]::IsNullOrWhiteSpace($value)) {
        return $DefaultValue
    }

    return $value
}

function Set-EnvValue {
    param(
        [Parameter(Mandatory = $true)]
        [string]$Name,

        [Parameter(Mandatory = $true)]
        [string]$Value
    )

    [Environment]::SetEnvironmentVariable($Name, $Value, $Target)
    Set-Item -Path "Env:$Name" -Value $Value
}

function Resolve-InteractivePort {
    param(
        [Parameter(Mandatory = $true)]
        [string]$DefaultValue
    )

    while ($true) {
        $rawPort = Read-ValueWithDefault -Prompt 'AI_MYSQL_PORT' -DefaultValue $DefaultValue
        $parsedPort = 0
        if ([int]::TryParse($rawPort, [ref]$parsedPort) -and $parsedPort -ge 1 -and $parsedPort -le 65535) {
            return $parsedPort
        }

        Write-Host 'Port must be a number between 1 and 65535.' -ForegroundColor Yellow
    }
}

function Resolve-Password {
    param(
        [string]$ProvidedPassword,

        [bool]$PromptForPassword
    )

    if (-not [string]::IsNullOrWhiteSpace($ProvidedPassword)) {
        return $ProvidedPassword
    }

    $existingPassword = [Environment]::GetEnvironmentVariable('AI_MYSQL_PASSWORD', $Target)
    if ($PromptForPassword -and -not [string]::IsNullOrWhiteSpace($existingPassword)) {
        $replace = Read-Host -Prompt 'AI_MYSQL_PASSWORD already exists. Replace it? (y/N)'
        if ($replace -notin @('y', 'Y', 'yes', 'YES', 'Yes')) {
            return $existingPassword
        }
    }

    return Read-PlainPassword
}

$connectionParameterNames = @('Password', 'UserName', 'HostName', 'Port', 'Database', 'SetSpringUrl')
$hasConnectionParameters = $false
foreach ($name in $connectionParameterNames) {
    if ($PSBoundParameters.ContainsKey($name)) {
        $hasConnectionParameters = $true
        break
    }
}

$runInteractiveWizard = $Interactive -or (-not $hasConnectionParameters)

if ($runInteractiveWizard) {
    Write-Host 'ReachAI MySQL environment setup' -ForegroundColor Cyan
    Write-Host 'Press Enter to keep the value shown in brackets.'
    Write-Host ''

    $HostName = Read-ValueWithDefault -Prompt 'AI_MYSQL_HOST' -DefaultValue (Get-EnvOrDefault -Name 'AI_MYSQL_HOST' -DefaultValue 'localhost')
    $Port = Resolve-InteractivePort -DefaultValue (Get-EnvOrDefault -Name 'AI_MYSQL_PORT' -DefaultValue '3306')
    $Database = Read-ValueWithDefault -Prompt 'AI_MYSQL_DATABASE' -DefaultValue (Get-EnvOrDefault -Name 'AI_MYSQL_DATABASE' -DefaultValue 'reach_ai')
    $UserName = Read-ValueWithDefault -Prompt 'AI_MYSQL_USER' -DefaultValue (Get-EnvOrDefault -Name 'AI_MYSQL_USER' -DefaultValue 'reach_ai')
    $SetSpringUrl = $true
}

$Password = Resolve-Password -ProvidedPassword $Password -PromptForPassword $runInteractiveWizard

if ([string]::IsNullOrWhiteSpace($Password)) {
    throw 'AI_MYSQL_PASSWORD cannot be empty.'
}

Set-EnvValue -Name 'AI_MYSQL_PASSWORD' -Value $Password

if ($runInteractiveWizard -or -not [string]::IsNullOrWhiteSpace($UserName)) {
    if ([string]::IsNullOrWhiteSpace($UserName)) {
        throw 'AI_MYSQL_USER cannot be empty.'
    }
    Set-EnvValue -Name 'AI_MYSQL_USER' -Value $UserName
}

if ($runInteractiveWizard -or -not [string]::IsNullOrWhiteSpace($HostName)) {
    if ([string]::IsNullOrWhiteSpace($HostName)) {
        throw 'AI_MYSQL_HOST cannot be empty.'
    }
    Set-EnvValue -Name 'AI_MYSQL_HOST' -Value $HostName
}

if ($runInteractiveWizard -or $PSBoundParameters.ContainsKey('Port')) {
    Set-EnvValue -Name 'AI_MYSQL_PORT' -Value ([string]$Port)
}

if ($runInteractiveWizard -or -not [string]::IsNullOrWhiteSpace($Database)) {
    if ([string]::IsNullOrWhiteSpace($Database)) {
        throw 'AI_MYSQL_DATABASE cannot be empty.'
    }
    Set-EnvValue -Name 'AI_MYSQL_DATABASE' -Value $Database
}

if ($runInteractiveWizard -or $SetSpringUrl) {
    $springHost = if (-not [string]::IsNullOrWhiteSpace($HostName)) { $HostName } else { Get-EnvOrDefault -Name 'AI_MYSQL_HOST' -DefaultValue 'localhost' }
    $springPort = if (($runInteractiveWizard -or $PSBoundParameters.ContainsKey('Port')) -and $Port -gt 0) { $Port } else { [int](Get-EnvOrDefault -Name 'AI_MYSQL_PORT' -DefaultValue '3306') }
    $springDatabase = if (-not [string]::IsNullOrWhiteSpace($Database)) { $Database } else { Get-EnvOrDefault -Name 'AI_MYSQL_DATABASE' -DefaultValue 'reach_ai' }
    $url = "jdbc:mysql://$springHost`:$springPort/$springDatabase`?useUnicode=true&characterEncoding=utf-8&serverTimezone=Asia/Shanghai"
    Set-EnvValue -Name 'AI_MYSQL_URL' -Value $url
}

Write-Host 'Updated environment variables:' -ForegroundColor Green
Write-Host "  Target: $Target"
Write-Host '  AI_MYSQL_PASSWORD: ********'

foreach ($name in @('AI_MYSQL_USER', 'AI_MYSQL_HOST', 'AI_MYSQL_PORT', 'AI_MYSQL_DATABASE', 'AI_MYSQL_URL')) {
    $value = [Environment]::GetEnvironmentVariable($name, $Target)
    if (-not [string]::IsNullOrWhiteSpace($value)) {
        Write-Host "  ${name}: $value"
    }
}

if ($Target -ne 'Process') {
    Write-Host ''
    Write-Host 'Important: already-running apps cannot see changed Windows environment variables.' -ForegroundColor Yellow
    Write-Host 'Restart IntelliJ IDEA, Cursor, Codex, PowerShell, and any Spring Boot run configurations before starting the backend again.' -ForegroundColor Yellow
}
