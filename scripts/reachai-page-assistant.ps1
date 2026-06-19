param(
  [Parameter(Position = 0)]
  [string] $Command = "help",
  [string] $ManifestUrl = "",
  [string] $Framework = "angular",
  [string] $OutputDir = ".\src\app\shared\reachai",
  [string] $WorkspaceRoot = ".",
  [string] $FrontendUrl = "",
  [string] $Route = "",
  [string] $PageKey = "",
  [string] $StorageStatePath = "",
  [int] $RuntimeProbeTimeoutSec = 30,
  [switch] $ProbeMutatingActions,
  [switch] $ReportToPlatform,
  [switch] $Help
)

$ErrorActionPreference = "Stop"
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
$OutputEncoding = [System.Text.Encoding]::UTF8

function Write-HelpText {
  @"
ReachAI page assistant helper.

Usage:
  .\scripts\reachai-page-assistant.ps1 scaffold -ManifestUrl <url> -Framework angular -OutputDir .\src\app\shared\reachai
  .\scripts\reachai-page-assistant.ps1 verify -ManifestUrl <url> -WorkspaceRoot . -FrontendUrl http://localhost:9200 -Route /team-build/depart-management -PageKey teamArchive.list
  .\scripts\reachai-page-assistant.ps1 verify -ManifestUrl <url> -FrontendUrl http://localhost:9200 -Route /teams/archive -PageKey teamArchive.list -StorageStatePath .\playwright\.auth\user.json -ReportToPlatform

Runtime probe notes:
  - With -FrontendUrl, the script tries a real browser bridge invoke via Playwright (requires Node.js + playwright package).
  - Default readonly probes: getPageState, readTable. High-risk actions such as openRowAction are never auto-invoked.
  - Use -ProbeMutatingActions to allow setFilters/search/reset probes.
  - Without login/session, runtime returns WARN and mentions StorageState/Cookie/manual login.
  - Static file scan is not runtime verification.

Other notes:
  - Use local PowerShell/curl for localhost ReachAI APIs. Remote WebFetch often cannot reach localhost.
  - The script never reads or prints app secrets, business tokens, or full table payloads.
  - verify reports to ReachAI only when -ReportToPlatform is set.
"@
}

function Read-Manifest([string] $url) {
  if (-not $url) { throw "ManifestUrl is required" }
  if (Test-Path $url) {
    return Get-Content -Raw -Encoding UTF8 -Path $url | ConvertFrom-Json
  }
  Invoke-RestMethod -Method Get -Uri $url
}

function ConvertTo-Hashtable($value) {
  if ($null -eq $value) { return @{} }
  if ($value -is [hashtable]) { return $value }
  $table = @{}
  foreach ($property in $value.PSObject.Properties) {
    $table[$property.Name] = $property.Value
  }
  return $table
}

function Test-CommandExists([string] $name) {
  return [bool](Get-Command $name -ErrorAction SilentlyContinue)
}

function Get-TemplateContent([string] $name) {
  switch ($name) {
    "reachai-page-action.types.ts" {
@'
export type ReachAiPageActionStatus = 'SUCCESS' | 'WARN' | 'ERROR';

export interface ReachAiPageActionResult<T = unknown> {
  status: ReachAiPageActionStatus;
  message?: string;
  data?: T;
  error?: { code?: string; message: string };
  metadata?: Record<string, unknown>;
}

export interface ReachAiPageActionContext {
  pageKey: string;
  actionKey: string;
  confirmed?: boolean;
  requestId?: string;
}

export type ReachAiPageActionHandler<TArgs = unknown, TResult = unknown> = (
  args: TArgs,
  context: ReachAiPageActionContext,
) => ReachAiPageActionResult<TResult> | Promise<ReachAiPageActionResult<TResult>>;

export interface ReachAiPageActionRegistration {
  pageKey: string;
  actionKey: string;
  metadata?: Record<string, unknown>;
}

export interface ReachAiPageBridge {
  register<TArgs = unknown, TResult = unknown>(
    pageKey: string,
    actionKey: string,
    handler: ReachAiPageActionHandler<TArgs, TResult>,
    metadata?: Record<string, unknown>,
  ): void;
  unregisterPage(pageKey: string): void;
  execute<TArgs = unknown, TResult = unknown>(
    pageKey: string,
    actionKey: string,
    args?: TArgs,
    options?: { confirmed?: boolean; requestId?: string },
  ): Promise<ReachAiPageActionResult<TResult>>;
  list(pageKey?: string): ReachAiPageActionRegistration[];
}

declare global {
  interface Window {
    __REACHAI_PAGE_BRIDGE__?: ReachAiPageBridge;
  }
}
'@
    }
    "reachai-page-action.service.ts" {
@'
import { Injectable, OnDestroy } from '@angular/core';
import {
  ReachAiPageActionHandler,
  ReachAiPageActionRegistration,
  ReachAiPageActionResult,
  ReachAiPageBridge,
} from './reachai-page-action.types';

@Injectable({ providedIn: 'root' })
export class ReachAiPageActionService implements ReachAiPageBridge, OnDestroy {
  private readonly handlers = new Map<string, ReachAiPageActionHandler>();
  private readonly metadata = new Map<string, Record<string, unknown>>();

  constructor() {
    window.__REACHAI_PAGE_BRIDGE__ = this;
    window.addEventListener('message', this.onMessage);
  }

  register<TArgs = unknown, TResult = unknown>(
    pageKey: string,
    actionKey: string,
    handler: ReachAiPageActionHandler<TArgs, TResult>,
    metadata: Record<string, unknown> = {},
  ): void {
    const key = this.keyOf(pageKey, actionKey);
    this.handlers.set(key, handler as ReachAiPageActionHandler);
    this.metadata.set(key, { ...metadata, pageKey, actionKey });
  }

  unregisterPage(pageKey: string): void {
    for (const key of Array.from(this.handlers.keys())) {
      if (key.startsWith(`${pageKey}::`)) {
        this.handlers.delete(key);
        this.metadata.delete(key);
      }
    }
  }

  async execute<TArgs = unknown, TResult = unknown>(
    pageKey: string,
    actionKey: string,
    args?: TArgs,
    options: { confirmed?: boolean; requestId?: string } = {},
  ): Promise<ReachAiPageActionResult<TResult>> {
    const handler = this.handlers.get(this.keyOf(pageKey, actionKey));
    if (!handler) {
      return {
        status: 'ERROR',
        message: `Page action handler not found: ${pageKey}/${actionKey}`,
        error: { code: 'HANDLER_NOT_FOUND', message: 'Page action handler not found' },
      };
    }
    try {
      return await handler(args, {
        pageKey,
        actionKey,
        confirmed: options.confirmed,
        requestId: options.requestId,
      }) as ReachAiPageActionResult<TResult>;
    } catch (error) {
      return {
        status: 'ERROR',
        message: error instanceof Error ? error.message : 'Page action failed',
        error: {
          code: 'HANDLER_ERROR',
          message: error instanceof Error ? error.message : String(error),
        },
      };
    }
  }

  list(pageKey?: string): ReachAiPageActionRegistration[] {
    return Array.from(this.metadata.values())
      .filter((item) => !pageKey || item.pageKey === pageKey)
      .map((item) => ({
        pageKey: String(item.pageKey),
        actionKey: String(item.actionKey),
        metadata: item,
      }));
  }

  ngOnDestroy(): void {
    window.removeEventListener('message', this.onMessage);
    if (window.__REACHAI_PAGE_BRIDGE__ === this) {
      delete window.__REACHAI_PAGE_BRIDGE__;
    }
  }

  private readonly onMessage = async (event: MessageEvent): Promise<void> => {
    const payload = event.data || {};
    if (payload.type !== 'reachai.pageAction.execute') return;
    const result = await this.execute(payload.pageKey, payload.actionKey, payload.args, {
      confirmed: payload.confirmed,
      requestId: payload.requestId,
    });
    const target = event.source as { postMessage: (message: unknown, targetOrigin: string) => void } | null;
    target?.postMessage({
      type: 'reachai.pageAction.result',
      requestId: payload.requestId,
      pageKey: payload.pageKey,
      actionKey: payload.actionKey,
      result,
    }, event.origin || '*');
  };

  private keyOf(pageKey: string, actionKey: string): string {
    return `${pageKey}::${actionKey}`;
  }
}
'@
    }
    "page-registry.example.ts" {
@'
import { ReachAiPageActionService } from './reachai-page-action.service';

export const reachAiPageKey = 'replace.with.pageKey';

export function registerReachAiPageActions(
  bridge: ReachAiPageActionService,
  page: {
    getPageState: () => unknown;
    setFilters: (filters: Record<string, unknown>) => void;
    search: () => Promise<unknown> | unknown;
    reset: () => Promise<unknown> | unknown;
    readTable: () => unknown;
    openRowAction?: (args: Record<string, unknown>) => Promise<unknown> | unknown;
  },
): void {
  bridge.register(reachAiPageKey, 'getPageState', async () => ({
    status: 'SUCCESS',
    data: page.getPageState(),
    metadata: { mode: 'readonly', riskLevel: 'LOW' },
  }));

  bridge.register(reachAiPageKey, 'setFilters', async (args) => {
    page.setFilters((args || {}) as Record<string, unknown>);
    return { status: 'SUCCESS', data: page.getPageState(), metadata: { riskLevel: 'LOW' } };
  });

  bridge.register(reachAiPageKey, 'search', async () => {
    const data = await page.search();
    return { status: 'SUCCESS', data, metadata: { riskLevel: 'LOW' } };
  });

  bridge.register(reachAiPageKey, 'reset', async () => {
    const data = await page.reset();
    return { status: 'SUCCESS', data, metadata: { riskLevel: 'LOW' } };
  });

  bridge.register(reachAiPageKey, 'readTable', async () => ({
    status: 'SUCCESS',
    data: page.readTable(),
    metadata: { mode: 'readonly', riskLevel: 'LOW' },
  }));

  if (page.openRowAction) {
    bridge.register(reachAiPageKey, 'openRowAction', async (args, context) => {
      if (!context.confirmed) {
        return {
          status: 'WARN',
          message: 'openRowAction requires user confirmation',
          metadata: { riskLevel: 'HIGH', confirmRequired: true },
        };
      }
      const data = await page.openRowAction?.((args || {}) as Record<string, unknown>);
      return { status: 'SUCCESS', data, metadata: { riskLevel: 'HIGH', confirmRequired: true } };
    }, { riskLevel: 'HIGH', confirmRequired: true });
  }
}
'@
    }
    default { throw "Unknown template: $name" }
  }
}

function Invoke-Scaffold {
  $manifest = Read-Manifest $ManifestUrl
  if ($Framework -ne "angular") { throw "Only angular scaffold is supported in V1" }
  $templates = @("reachai-page-action.types.ts", "reachai-page-action.service.ts", "page-registry.example.ts")
  New-Item -ItemType Directory -Force -Path $OutputDir | Out-Null
  $created = @()
  $skipped = @()
  foreach ($template in $templates) {
    $target = Join-Path $OutputDir $template
    if (Test-Path $target) {
      $skipped += $target
      continue
    }
    Set-Content -Path $target -Value (Get-TemplateContent $template) -Encoding UTF8
    $created += $target
  }
  [pscustomobject]@{
    ok = $true
    command = "scaffold"
    schema = $manifest.schema
    framework = "angular"
    outputDir = (Resolve-Path $OutputDir).Path
    created = $created
    skipped = $skipped
  } | ConvertTo-Json -Depth 20
}

function Get-RelativePath([string] $root, [string] $path) {
  $rootPath = (Resolve-Path $root).Path.TrimEnd("\", "/")
  $fullPath = (Resolve-Path $path).Path
  if ($fullPath.StartsWith($rootPath)) {
    return $fullPath.Substring($rootPath.Length).TrimStart("\", "/")
  }
  return $fullPath
}

function Get-FileHashSafe([string] $path) {
  try { return (Get-FileHash -Algorithm SHA256 -Path $path).Hash.ToLowerInvariant() } catch { return "" }
}

function Find-ReachAiFiles([string] $root, [string] $bridgeGlobal, [string] $pageKey) {
  $files = Get-ChildItem -Path $root -Recurse -File -Include *.ts,*.tsx,*.js,*.jsx,*.vue -ErrorAction SilentlyContinue |
    Where-Object {
      $_.FullName -notmatch "\\node_modules\\" -and
      $_.FullName -notmatch "\\dist\\" -and
      $_.FullName -notmatch "\\target\\"
    }
  $matches = @()
  foreach ($file in $files) {
    $content = Get-Content -Raw -Encoding UTF8 -Path $file.FullName -ErrorAction SilentlyContinue
    if ($content -and ($content.Contains($bridgeGlobal) -or ($pageKey -and $content.Contains($pageKey)))) {
      $role = if ($content.Contains($bridgeGlobal)) { "bridge-or-handler" } else { "page-registry" }
      $matches += [pscustomobject]@{
        path = Get-RelativePath $root $file.FullName
        role = $role
        exists = $true
        sha256 = Get-FileHashSafe $file.FullName
      }
    }
  }
  return $matches
}

function New-ActionDefinition([string] $actionKey) {
  $readonly = $actionKey -eq "getPageState" -or $actionKey -eq "readTable"
  $highRisk = $actionKey -eq "openRowAction"
  [pscustomobject]@{
    actionKey = $actionKey
    title = $actionKey
    description = if ($readonly) { "Read current page state from the visible page." } else { "Execute page action through the current page instance." }
    confirmRequired = $highRisk
    inputSchema = @{ type = "object"; properties = @{} }
    outputSchema = @{ type = "object" }
    sampleArgs = @{}
    allowedAgentIds = @()
    metadata = @{
      riskLevel = if ($highRisk) { "HIGH" } else { "LOW" }
      mode = if ($readonly) { "readonly" } else { "page-action" }
    }
  }
}

function Get-RuntimeProbeNodeScript {
  @'
const fs = require('fs');
const args = JSON.parse(process.argv[2] || '{}');

function redactResult(actionKey, raw) {
  const data = raw && raw.data && typeof raw.data === 'object' ? raw.data : {};
  const rows = Array.isArray(data.rows) ? data.rows : (Array.isArray(data.items) ? data.items : null);
  return {
    actionKey,
    status: raw && raw.status ? raw.status : 'ERROR',
    errorCode: raw && raw.error && raw.error.code ? raw.error.code : null,
    rowCount: rows ? rows.length : (typeof data.rowCount === 'number' ? data.rowCount : null),
    hasFilters: !!(data.filters && Object.keys(data.filters).length),
    hasPagination: !!data.pagination,
  };
}

(async () => {
  let playwright;
  try {
    playwright = require('playwright');
  } catch (error) {
    process.stdout.write(JSON.stringify({
      ok: false,
      code: 'PLAYWRIGHT_MISSING',
      message: 'Node.js playwright package is not installed. Run npm install -D playwright, or provide StorageState/login manually and retry.',
      bridgeExists: false,
      listedActions: [],
      invokedActions: [],
      redactedResults: [],
    }));
    return;
  }

  const browser = await playwright.chromium.launch({ headless: true });
  const contextOptions = {};
  if (args.storageStatePath && fs.existsSync(args.storageStatePath)) {
    contextOptions.storageState = args.storageStatePath;
  }
  const context = await browser.newContext(contextOptions);
  const page = await context.newPage();
  const result = {
    ok: false,
    bridgeExists: false,
    listedActions: [],
    invokedActions: [],
    redactedResults: [],
    loginLikelyRequired: false,
    message: '',
    finalUrl: '',
  };

  try {
    const response = await page.goto(args.targetUrl, {
      timeout: args.timeoutMs || 30000,
      waitUntil: 'domcontentloaded',
    });
    await page.waitForTimeout(2500);
    result.finalUrl = page.url();

    const title = await page.title();
    const bodyText = ((await page.textContent('body')) || '').toLowerCase();
    const looksLikeLogin = /login|sign in|password|登录|口令|账号/.test(bodyText)
      || /login|signin|auth/.test(result.finalUrl.toLowerCase())
      || (response && (response.status() === 401 || response.status() === 403));

    const probe = await page.evaluate(async ({ bridgeGlobal, pageKey, readonlyActions, mutatingActions }) => {
      const bridge = window[bridgeGlobal] || window.__REACHAI_PAGE_BRIDGE__;
      const output = {
        bridgeExists: !!bridge,
        listedActions: [],
        invokedActions: [],
        redactedResults: [],
      };
      if (!bridge) return output;

      if (typeof bridge.list === 'function') {
        output.listedActions = bridge.list(pageKey).map((item) => item.actionKey);
      }

      const candidates = readonlyActions.filter((key) => output.listedActions.includes(key));
      for (const actionKey of candidates) {
        try {
          const raw = typeof bridge.execute === 'function'
            ? await bridge.execute(pageKey, actionKey, {})
            : { status: 'ERROR', error: { code: 'HANDLER_ERROR', message: 'execute is unavailable' } };
          output.invokedActions.push(actionKey);
          const data = raw && raw.data && typeof raw.data === 'object' ? raw.data : {};
          const rows = Array.isArray(data.rows) ? data.rows : (Array.isArray(data.items) ? data.items : null);
          output.redactedResults.push({
            actionKey,
            status: raw && raw.status ? raw.status : 'ERROR',
            errorCode: raw && raw.error && raw.error.code ? raw.error.code : null,
            rowCount: rows ? rows.length : (typeof data.rowCount === 'number' ? data.rowCount : null),
            hasFilters: !!(data.filters && Object.keys(data.filters).length),
            hasPagination: !!data.pagination,
          });
        } catch (error) {
          output.redactedResults.push({
            actionKey,
            status: 'ERROR',
            errorCode: 'HANDLER_ERROR',
            message: error && error.message ? error.message : String(error),
          });
        }
      }

      if (mutatingActions && mutatingActions.length) {
        for (const actionKey of mutatingActions.filter((key) => output.listedActions.includes(key))) {
          try {
            const raw = await bridge.execute(pageKey, actionKey, {});
            output.invokedActions.push(actionKey);
            output.redactedResults.push({
              actionKey,
              status: raw && raw.status ? raw.status : 'ERROR',
              errorCode: raw && raw.error && raw.error.code ? raw.error.code : null,
              mutating: true,
            });
          } catch (error) {
            output.redactedResults.push({
              actionKey,
              status: 'ERROR',
              errorCode: 'HANDLER_ERROR',
              mutating: true,
            });
          }
        }
      }
      return output;
    }, {
      bridgeGlobal: args.bridgeGlobal,
      pageKey: args.pageKey,
      readonlyActions: args.readonlyActions || ['getPageState', 'readTable'],
      mutatingActions: args.mutatingActions || [],
    });

    result.bridgeExists = !!probe.bridgeExists;
    result.listedActions = probe.listedActions || [];
    result.invokedActions = probe.invokedActions || [];
    result.redactedResults = probe.redactedResults || [];
    result.loginLikelyRequired = looksLikeLogin;

    const successCount = result.redactedResults.filter((item) => item.status === 'SUCCESS').length;
    if (successCount > 0) {
      result.ok = true;
      result.message = 'Runtime bridge invoke succeeded for readonly actions.';
    } else if (!result.bridgeExists) {
      result.message = looksLikeLogin
        ? 'Page loaded but login/session is likely required before the bridge is available. Provide StorageState/Cookie or login manually, then retry.'
        : 'Page loaded but window bridge was not found. Ensure the target route is open and handlers are registered.';
    } else if (result.listedActions.length === 0) {
      result.message = 'Bridge exists but no actions are registered for the target pageKey.';
    } else {
      result.message = looksLikeLogin
        ? 'Bridge detected but invoke failed, likely due to missing login/session. Provide StorageState/Cookie or login manually.'
        : 'Bridge detected but readonly invoke did not return SUCCESS.';
    }
  } catch (error) {
    result.message = 'Runtime browser probe failed: ' + (error && error.message ? error.message : String(error));
    result.loginLikelyRequired = /login|auth|401|403/i.test(result.message);
  } finally {
    await browser.close();
  }

  process.stdout.write(JSON.stringify(result));
})().catch((error) => {
  process.stdout.write(JSON.stringify({
    ok: false,
    code: 'PROBE_FAILED',
    message: error && error.message ? error.message : String(error),
    bridgeExists: false,
    listedActions: [],
    invokedActions: [],
    redactedResults: [],
  }));
});
'@
}

function Invoke-RuntimeBridgeProbe {
  param(
    [string] $TargetUrl,
    [string] $PageKey,
    [string] $BridgeGlobal,
    [string[]] $ReadonlyActions,
    [string[]] $MutatingActions,
    [string] $StorageStatePath,
    [int] $TimeoutSec
  )

  if (-not (Test-CommandExists "node")) {
    return [pscustomobject]@{
      status = "WARN"
      message = "Node.js is not available; runtime bridge invoke skipped. Install Node.js + playwright for real runtime PASS."
      bridgeExists = $false
      listedActions = @()
      invokedActions = @()
      redactedResults = @()
      probeEngine = "none"
    }
  }

  $bridgeKey = $BridgeGlobal
  if ($bridgeKey.StartsWith("window.")) {
    $bridgeKey = $bridgeKey.Substring(7)
  }

  $probeArgs = @{
    targetUrl = $TargetUrl
    pageKey = $PageKey
    bridgeGlobal = $bridgeKey
    readonlyActions = $ReadonlyActions
    mutatingActions = $MutatingActions
    storageStatePath = $StorageStatePath
    timeoutMs = $TimeoutSec * 1000
  } | ConvertTo-Json -Compress

  $tempScript = Join-Path ([System.IO.Path]::GetTempPath()) ("reachai-runtime-probe-" + [guid]::NewGuid().ToString("N") + ".cjs")
  try {
    Set-Content -Path $tempScript -Value (Get-RuntimeProbeNodeScript) -Encoding UTF8
    $output = & node $tempScript $probeArgs 2>&1 | Out-String
    $parsed = $null
    try { $parsed = $output.Trim() | ConvertFrom-Json } catch { $parsed = $null }
    if ($null -eq $parsed) {
      return [pscustomobject]@{
        status = "WARN"
        message = "Runtime probe did not return JSON. Ensure playwright is installed and the dev server is reachable."
        bridgeExists = $false
        listedActions = @()
        invokedActions = @()
        redactedResults = @()
        probeEngine = "node"
        rawOutput = ($output | Select-Object -First 500)
      }
    }

    $successCount = @($parsed.redactedResults | Where-Object { $_.status -eq "SUCCESS" }).Count
    $status = "WARN"
    $message = [string]$parsed.message
    if ($parsed.code -eq "PLAYWRIGHT_MISSING") {
      $status = "WARN"
      $message = [string]$parsed.message
    } elseif ($successCount -gt 0) {
      $status = "PASS"
      $message = "Runtime bridge invoke succeeded for readonly actions (getPageState/readTable)."
    } elseif ($parsed.loginLikelyRequired) {
      $status = "WARN"
      $message = if ($message) { $message } else { "Login/session likely required. Provide StorageState/Cookie or login manually before runtime PASS." }
    } elseif ($parsed.bridgeExists -and @($parsed.listedActions).Count -gt 0) {
      $status = "WARN"
      $message = if ($message) { $message } else { "Bridge exists but readonly invoke did not succeed." }
    } elseif (-not $parsed.bridgeExists) {
      $status = "WARN"
      $message = if ($message) { $message } else { "Bridge was not found in the loaded page." }
    }

    return [pscustomobject]@{
      status = $status
      message = $message
      bridgeExists = [bool]$parsed.bridgeExists
      listedActions = @($parsed.listedActions)
      invokedActions = @($parsed.invokedActions)
      redactedResults = @($parsed.redactedResults)
      probeEngine = "playwright"
      loginLikelyRequired = [bool]$parsed.loginLikelyRequired
      finalUrl = [string]$parsed.finalUrl
    }
  } finally {
    if (Test-Path $tempScript) { Remove-Item -Force $tempScript -ErrorAction SilentlyContinue }
  }
}

function Build-BrowserRuntimeVerification {
  param(
    [string] $FrontendUrl,
    [string] $Route,
    [string] $PageKey,
    [string] $BridgeGlobal,
    [string[]] $CandidateActions,
    [switch] $AllowMutating
  )

  if (-not $FrontendUrl) {
    return @{
      status = "SKIPPED"
      message = "Runtime browser verification skipped: FrontendUrl was not provided."
      frontendUrl = ""
      route = $Route
      pageKey = $PageKey
      bridgeExists = $false
      listedActions = @()
      invokedActions = @()
      redactedResults = @()
    }
  }

  $targetUrl = $FrontendUrl.TrimEnd("/") + $Route
  $readonlyActions = @("getPageState", "readTable") | Where-Object { $CandidateActions -contains $_ }
  if ($readonlyActions.Count -eq 0) { $readonlyActions = @("getPageState", "readTable") }
  $mutatingActions = @()
  if ($AllowMutating) {
    $mutatingActions = @("setFilters", "search", "reset") | Where-Object { $CandidateActions -contains $_ }
  }

  $probe = Invoke-RuntimeBridgeProbe `
    -TargetUrl $targetUrl `
    -PageKey $PageKey `
    -BridgeGlobal $BridgeGlobal `
    -ReadonlyActions $readonlyActions `
    -MutatingActions $mutatingActions `
    -StorageStatePath $StorageStatePath `
    -TimeoutSec $RuntimeProbeTimeoutSec

  return @{
    status = $probe.status
    message = $probe.message
    frontendUrl = $FrontendUrl
    route = $Route
    pageKey = $PageKey
    bridgeExists = $probe.bridgeExists
    listedActions = @($probe.listedActions)
    invokedActions = @($probe.invokedActions)
    redactedResults = @($probe.redactedResults)
    probeEngine = $probe.probeEngine
    loginLikelyRequired = [bool]$probe.loginLikelyRequired
    targetUrl = $targetUrl
  }
}

function Invoke-Verify {
  $manifest = Read-Manifest $ManifestUrl
  $contract = ConvertTo-Hashtable $manifest.pageActionContract
  $target = ConvertTo-Hashtable $manifest.target
  $endpoints = ConvertTo-Hashtable $manifest.endpoints
  $bridgeGlobal = if ($contract.bridgeGlobal) { [string]$contract.bridgeGlobal } else { "__REACHAI_PAGE_BRIDGE__" }
  $resolvedPageKey = if ($PageKey) { $PageKey } elseif ($target.pageKey) { [string]$target.pageKey } else { "" }
  $resolvedRoute = if ($Route) { $Route } elseif ($target.routePattern) { [string]$target.routePattern } else { "" }
  $actions = @()
  if ($target.actionKeys) { $actions = @($target.actionKeys) }
  if ($actions.Count -eq 0 -and $contract.recommendedActions) { $actions = @($contract.recommendedActions) }
  if ($actions.Count -eq 0) { $actions = @("getPageState", "setFilters", "search", "reset", "readTable") }

  $root = (Resolve-Path $WorkspaceRoot).Path
  $files = @(Find-ReachAiFiles $root $bridgeGlobal $resolvedPageKey)
  $hasBridge = $files.Count -gt 0
  $staticStatus = if ($hasBridge -and $resolvedPageKey) { "PASS" } else { "WARN" }
  $staticMessage = if ($hasBridge -and $resolvedPageKey) {
    "Static handler evidence found (static only)."
  } else {
    "No bridge or handler files were found for static verification."
  }

  $browserRuntime = Build-BrowserRuntimeVerification `
    -FrontendUrl $FrontendUrl `
    -Route $resolvedRoute `
    -PageKey $resolvedPageKey `
    -BridgeGlobal $bridgeGlobal `
    -CandidateActions $actions `
    -AllowMutating:$ProbeMutatingActions

  $verification = [ordered]@{
    build = @{ status = "WARN"; message = "Build command was not executed by this helper. Run the business frontend minimal build separately." }
    staticHandler = @{
      status = $staticStatus
      bridgeGlobal = $bridgeGlobal
      handlerCount = $files.Count
      message = $staticMessage
    }
    browserStatic = @{ status = $staticStatus; message = $staticMessage }
    browserRuntime = $browserRuntime
    browser = $browserRuntime
  }
  $result = [ordered]@{
    ok = $true
    command = "verify"
    schema = $manifest.schema
    pageKey = $resolvedPageKey
    routePattern = $resolvedRoute
    bridgeGlobal = $bridgeGlobal
    files = $files
    actions = $actions
    verification = $verification
    runtimeVerification = $browserRuntime
  }

  if ($ReportToPlatform) {
    if (-not $endpoints.registerPageUrl) { throw "registerPageUrl is missing from manifest" }
    $body = @{
      sessionId = $manifest.session.sessionId
      toolName = "PowerShell verify"
      pageKey = $resolvedPageKey
      pageName = $resolvedPageKey
      routePattern = $resolvedRoute
      framework = "angular"
      frameworkVersion = ""
      bridgeGlobal = $bridgeGlobal
      replaceActions = $true
      files = $files
      actions = @($actions | ForEach-Object { New-ActionDefinition ([string]$_) })
      verification = $verification
      handoffSummary = "reachai-page-assistant verify reported static and runtime evidence"
    }
    $report = Invoke-RestMethod -Method Post -Uri $endpoints.registerPageUrl -ContentType "application/json; charset=utf-8" -Body ($body | ConvertTo-Json -Depth 50 -Compress)
    $result.reportedToPlatform = $true
    $result.platformResponse = $report

    if ($endpoints.checksRunUrl) {
      $checkBody = @{
        pageKey = $resolvedPageKey
        routePattern = $resolvedRoute
        actionKeys = @($actions)
        frontendUrl = $FrontendUrl
        runtimeVerification = $browserRuntime
      }
      try {
        $checkReport = Invoke-RestMethod -Method Post -Uri $endpoints.checksRunUrl -ContentType "application/json; charset=utf-8" -Body ($checkBody | ConvertTo-Json -Depth 50 -Compress)
        $result.platformCheckResponse = $checkReport
      } catch {
        $result.platformCheckError = $_.Exception.Message
      }
    }
  }
  $result | ConvertTo-Json -Depth 50
}

if ($Help -or $Command -eq "help" -or $Command -eq "-Help") {
  Write-HelpText
  exit 0
}

switch ($Command) {
  "scaffold" { Invoke-Scaffold }
  "verify" { Invoke-Verify }
  default {
    Write-HelpText
    throw "Unsupported command: $Command"
  }
}
