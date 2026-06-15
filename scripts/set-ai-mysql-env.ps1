<#
.SYNOPSIS
Set local Windows environment variables used by ReachAI MySQL connections.

.DESCRIPTION
The Spring services read AI_MYSQL_URL, AI_MYSQL_USER, and AI_MYSQL_PASSWORD.
The DBHub readonly MCP config also reads AI_MYSQL_HOST, AI_MYSQL_PORT,
AI_MYSQL_DATABASE, AI_MYSQL_USER, and AI_MYSQL_PASSWORD.

This script never stores a real password in the repository. If -Password is not
provided, it prompts for the password securely.

.EXAMPLE
.\scripts\set-ai-mysql-env.ps1

.EXAMPLE
.\scripts\set-ai-mysql-env.ps1 -UserName ai_text_service -HostName 127.0.0.1 -Port 3306 -Database ai_text_service -SetSpringUrl
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

if ([string]::IsNullOrWhiteSpace($Password)) {
    $Password = Read-PlainPassword
}

if ([string]::IsNullOrWhiteSpace($Password)) {
    throw 'AI_MYSQL_PASSWORD cannot be empty.'
}

Set-EnvValue -Name 'AI_MYSQL_PASSWORD' -Value $Password

if (-not [string]::IsNullOrWhiteSpace($UserName)) {
    Set-EnvValue -Name 'AI_MYSQL_USER' -Value $UserName
}

if (-not [string]::IsNullOrWhiteSpace($HostName)) {
    Set-EnvValue -Name 'AI_MYSQL_HOST' -Value $HostName
}

if ($PSBoundParameters.ContainsKey('Port')) {
    Set-EnvValue -Name 'AI_MYSQL_PORT' -Value ([string]$Port)
}

if (-not [string]::IsNullOrWhiteSpace($Database)) {
    Set-EnvValue -Name 'AI_MYSQL_DATABASE' -Value $Database
}

if ($SetSpringUrl) {
    if ([string]::IsNullOrWhiteSpace($HostName)) {
        throw '-SetSpringUrl requires -HostName.'
    }

    $springPort = if ($PSBoundParameters.ContainsKey('Port')) { $Port } else { 33106 }
    $springDatabase = if (-not [string]::IsNullOrWhiteSpace($Database)) { $Database } else { 'ai_text_service' }
    $url = "jdbc:mysql://$HostName`:$springPort/$springDatabase`?useUnicode=true&characterEncoding=utf-8&serverTimezone=Asia/Shanghai"
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
    Write-Host 'Restart IntelliJ/Cursor/Codex/terminal or rerun the backend service so it can read the new User environment value.'
}
