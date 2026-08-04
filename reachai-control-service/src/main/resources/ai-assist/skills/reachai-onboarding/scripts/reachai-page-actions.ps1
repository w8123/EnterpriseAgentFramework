param(
    [Parameter(Mandatory = $true)]
    [ValidateSet('scaffold-angular', 'verify-static')]
    [string] $Mode,

    [Parameter(Mandatory = $true)]
    [string] $FrontendRoot,

    [Parameter(Mandatory = $true)]
    [string] $PageKey,

    [string[]] $ActionKeys = @('getPageState', 'readTable'),

    [string] $TargetRelativePath = 'src/app/shared/reachai',

    [switch] $Force
)

$ErrorActionPreference = 'Stop'
$resolvedFrontendRoot = (Resolve-Path -LiteralPath $FrontendRoot).Path
$skillRoot = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '..')).Path
$templateRoot = Join-Path $skillRoot 'templates/angular'
$targetRoot = Join-Path $resolvedFrontendRoot $TargetRelativePath

function Write-StructuredResult {
    param([hashtable] $Value)
    $Value | ConvertTo-Json -Depth 8
}

if ($Mode -eq 'scaffold-angular') {
    if (-not (Test-Path -LiteralPath $templateRoot -PathType Container)) {
        throw "Angular templates not found: $templateRoot"
    }
    New-Item -ItemType Directory -Path $targetRoot -Force | Out-Null
    $written = @()
    $skipped = @()
    foreach ($name in @(
        'reachai-page-action.types.ts',
        'reachai-page-action.service.ts',
        'page-registry.example.ts'
    )) {
        $source = Join-Path $templateRoot $name
        $target = Join-Path $targetRoot $name
        if ((Test-Path -LiteralPath $target) -and -not $Force) {
            $skipped += $target
            continue
        }
        $content = Get-Content -LiteralPath $source -Raw -Encoding UTF8
        if ($name -eq 'page-registry.example.ts') {
            $content = $content.Replace('replace.with.pageKey', $PageKey)
        }
        Set-Content -LiteralPath $target -Value $content -Encoding UTF8
        $written += $target
    }
    Write-StructuredResult @{
        schema = 'reachai.page-action-helper-result.v1'
        mode = $Mode
        status = 'PASS'
        written = $written
        skippedExisting = $skipped
        next = 'Adapt the registry example to the real page component; do not report runtime PASS yet.'
    }
    exit 0
}

$scanRoot = Join-Path $resolvedFrontendRoot 'src'
if (-not (Test-Path -LiteralPath $scanRoot -PathType Container)) {
    $scanRoot = $resolvedFrontendRoot
}
$sourceFiles = Get-ChildItem -LiteralPath $scanRoot -Recurse -File -Include '*.ts', '*.tsx', '*.js', '*.jsx'
$bridgeMatches = @($sourceFiles | Select-String -SimpleMatch '__REACHAI_PAGE_BRIDGE__')
$pageMatches = @($sourceFiles | Select-String -SimpleMatch $PageKey)
$missingActions = @()
foreach ($actionKey in $ActionKeys) {
    if (-not ($sourceFiles | Select-String -SimpleMatch $actionKey -Quiet)) {
        $missingActions += $actionKey
    }
}
$status = if ($bridgeMatches.Count -gt 0 -and $pageMatches.Count -gt 0 -and $missingActions.Count -eq 0) {
    'PASS'
} else {
    'FAIL'
}
Write-StructuredResult @{
    schema = 'reachai.page-action-helper-result.v1'
    mode = $Mode
    status = $status
    scope = 'STATIC_ONLY'
    pageKey = $PageKey
    expectedActionKeys = $ActionKeys
    missingActionKeys = $missingActions
    bridgeSourceCount = $bridgeMatches.Count
    pageKeySourceCount = $pageMatches.Count
    next = 'Use an authenticated browser and a fresh Embed session for runtime verification.'
}
if ($status -ne 'PASS') {
    exit 1
}
