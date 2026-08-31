param(
  [string] $BaseUrl = "http://localhost:18603",
  [string] $ProjectCode = "qmssmp-teams-construction-service",
  [string] $AppKey = "qmssmp-teams-construction-service",
  [Parameter(Mandatory = $true)]
  [string] $AppSecret,
  [string] $AgentId = "team-archive-assistant",
  [string] $AdminUser = "admin",
  [string] $AdminPassword = "admin123",
  [string] $PageKey = "teamArchive.list",
  [string] $ActionKey = "qmssmp.teamArchive.search",
  [string] $RemovedActionKey = "",
  [string] $Route = "/team-build/depart-management",
  [string] $Origin = "http://localhost:9200"
)

$ErrorActionPreference = "Stop"

function Normalize-BaseUrl([string] $value) {
  if (-not $value) { throw "BaseUrl is required" }
  return $value.TrimEnd("/")
}

function Unwrap-ApiResult($value) {
  if ($null -ne $value.PSObject.Properties["data"]) {
    return $value.data
  }
  return $value
}

function New-ReachAiSignatureHeaders([string] $projectCode, [string] $appKey, [string] $appSecret) {
  $timestamp = [DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds().ToString()
  $nonce = [guid]::NewGuid().ToString("N")
  $message = "$projectCode`n$timestamp`n$nonce"
  $hmac = [System.Security.Cryptography.HMACSHA256]::new([Text.Encoding]::UTF8.GetBytes($appSecret))
  $bytes = $hmac.ComputeHash([Text.Encoding]::UTF8.GetBytes($message))
  $signature = -join ($bytes | ForEach-Object { $_.ToString("x2") })
  return @{
    "X-ReachAI-App-Key" = $appKey
    "X-ReachAI-Timestamp" = $timestamp
    "X-ReachAI-Nonce" = $nonce
    "X-ReachAI-Signature" = $signature
  }
}

function Invoke-JsonPost([string] $url, $body, $headers = @{}) {
  Invoke-RestMethod `
    -Method Post `
    -Uri $url `
    -Headers $headers `
    -ContentType "application/json; charset=utf-8" `
    -Body ($body | ConvertTo-Json -Depth 30 -Compress)
}

function Invoke-JsonGet([string] $url, $headers = @{}) {
  Invoke-RestMethod -Method Get -Uri $url -Headers $headers
}

$base = Normalize-BaseUrl $BaseUrl
$pageInstanceId = "verify-page-action-" + [guid]::NewGuid().ToString("N")
$removedActionKeyValue = if ($RemovedActionKey) { $RemovedActionKey } else { "$ActionKey.removed-by-verify" }
$step = "init"

try {
  $step = "register page catalog with added action"
  $registerBody = @{
    pageKey = $PageKey
    name = "Team archive list"
    routePattern = $Route
    origin = $Origin
    pageInstanceId = $pageInstanceId
    replaceActions = $true
    metadata = @{
      url = "$Origin/#$Route"
      source = "verify-script"
    }
    actions = @(
      @{
        actionKey = $ActionKey
        title = "Search team archive initial"
        description = "Refresh the current team archive list by filters."
        confirmRequired = $false
        allowedAgentIds = @($AgentId)
        inputSchema = @{
          type = "object"
          properties = @{
            teamName = @{ type = "string" }
          }
          required = @()
        }
        outputSchema = @{
          type = "object"
          properties = @{
            total = @{ type = "number" }
            records = @{ type = "array" }
          }
        }
        sampleArgs = @{ teamName = "Team A" }
        metadata = @{
          source = "verify-script"
        }
      },
      @{
        actionKey = $removedActionKeyValue
        title = "Temporary action to remove"
        description = "This action verifies replaceActions removal semantics."
        confirmRequired = $false
        allowedAgentIds = @($AgentId)
        inputSchema = @{
          type = "object"
          properties = @{}
          required = @()
        }
        outputSchema = @{
          type = "object"
          properties = @{}
        }
        sampleArgs = @{}
        metadata = @{
          source = "verify-script"
          lifecycle = "remove-on-next-register"
        }
      }
    )
  }
  $register = Unwrap-ApiResult (Invoke-JsonPost `
    "$base/api/registry/projects/$ProjectCode/pages/register" `
    $registerBody `
    (New-ReachAiSignatureHeaders $ProjectCode $AppKey $AppSecret))

  $step = "replace page catalog and remove missing action"
  $replaceBody = $registerBody.Clone()
  $replaceBody.actions = @(
    @{
      actionKey = $ActionKey
      title = "Search team archive verified"
      description = "Refresh the current team archive list by filters. Updated by verification."
      confirmRequired = $false
      allowedAgentIds = @($AgentId)
      inputSchema = @{
        type = "object"
        properties = @{
          teamName = @{ type = "string" }
        }
        required = @()
      }
      outputSchema = @{
        type = "object"
        properties = @{
          total = @{ type = "number" }
          records = @{ type = "array" }
        }
      }
      sampleArgs = @{ teamName = "Team A" }
      metadata = @{
        source = "verify-script"
        lifecycle = "updated-on-second-register"
      }
    }
  )
  $register = Unwrap-ApiResult (Invoke-JsonPost `
    "$base/api/registry/projects/$ProjectCode/pages/register" `
    $replaceBody `
    (New-ReachAiSignatureHeaders $ProjectCode $AppKey $AppSecret))

  $step = "exchange embed token"
  $principal = @{
    externalUserId = "page-action-verify-user"
    globalUserId = "page-action-verify-user"
    tenantId = "default"
    userName = "Page Action Verify User"
    roles = @("tester")
  }
  $tokenResponse = Unwrap-ApiResult (Invoke-JsonPost `
    "$base/api/embed/token/exchange" `
    @{
      projectCode = $ProjectCode
      agentId = $AgentId
      pageKey = $PageKey
      pageInstanceId = $pageInstanceId
      route = $Route
      origin = $Origin
      principal = $principal
    } `
    (New-ReachAiSignatureHeaders $ProjectCode $AppKey $AppSecret))
  if (-not $tokenResponse.token) {
    throw "token exchange returned no token"
  }

  $embedHeaders = @{ Authorization = "Bearer " + $tokenResponse.token }

  $step = "create embed session"
  $session = Unwrap-ApiResult (Invoke-JsonPost `
    "$base/api/embed/chat/sessions" `
    @{
      pageKey = $PageKey
      pageInstanceId = $pageInstanceId
      route = $Route
      bridgeActions = @($ActionKey)
      sdkVersion = "verify-script"
    } `
    $embedHeaders)

  $step = "platform login"
  $loginRaw = Invoke-JsonPost "$base/api/platform/auth/login" @{
    username = $AdminUser
    password = $AdminPassword
  }
  $login = Unwrap-ApiResult $loginRaw
  $platformToken = if ($login.PSObject.Properties["token"]) {
    $login.token
  } elseif ($login.PSObject.Properties["accessToken"]) {
    $login.accessToken
  } elseif ($loginRaw.PSObject.Properties["token"]) {
    $loginRaw.token
  } else {
    $null
  }
  if (-not $platformToken) {
    throw "platform login returned no token"
  }
  $platformHeaders = @{ Authorization = "Bearer " + $platformToken }

  $step = "query page action catalog"
  $catalog = Unwrap-ApiResult (Invoke-JsonGet `
    "$base/api/platform/embed/page-actions/catalog?projectCode=$ProjectCode&pageKey=$PageKey" `
    $platformHeaders)
  $action = @($catalog) | Where-Object { $_.actionKey -eq $ActionKey } | Select-Object -First 1
  if (-not $action) {
    throw "page action catalog item not found: $ActionKey"
  }
  if ($action.status -ne "ACTIVE") {
    throw "page action catalog item is not ACTIVE: $($action.status)"
  }
  if ($action.title -ne "Search team archive verified") {
    throw "page action catalog item was not updated: $($action.title)"
  }
  if (-not $action.inputSchemaJson -or $action.inputSchemaJson -notmatch "teamName") {
    throw "page action input schema was not persisted for $ActionKey"
  }
  if (-not $action.outputSchemaJson -or $action.outputSchemaJson -notmatch "records") {
    throw "page action output schema was not persisted for $ActionKey"
  }
  $removedAction = @($catalog) | Where-Object { $_.actionKey -eq $removedActionKeyValue } | Select-Object -First 1
  if (-not $removedAction) {
    throw "removed page action catalog item not found: $removedActionKeyValue"
  }
  if ($removedAction.status -ne "REMOVED") {
    throw "missing page action was not marked REMOVED: $($removedAction.status)"
  }

  $step = "request page action debug"
  $debug = Unwrap-ApiResult (Invoke-JsonPost `
    "$base/api/platform/embed/page-actions/catalog/$($action.id)/debug" `
    @{
      sessionId = $session.sessionId
      args = @{ teamName = "Team A" }
    } `
    $platformHeaders)
  if ($debug.status -ne "REQUESTED") {
    throw "debug request was not accepted: $($debug.status) $($debug.message)"
  }

  Start-Sleep -Milliseconds 300

  $step = "poll pending page action"
  $pending = Unwrap-ApiResult (Invoke-JsonGet `
    "$base/api/embed/chat/sessions/$($session.sessionId)/page-actions/pending" `
    $embedHeaders)
  $pendingAction = @($pending) | Where-Object { $_.requestId -eq $debug.requestId } | Select-Object -First 1
  if (-not $pendingAction) {
    throw "pending page action not found for requestId $($debug.requestId)"
  }

  $step = "post page action result"
  $result = Unwrap-ApiResult (Invoke-JsonPost `
    "$base/api/embed/chat/sessions/$($session.sessionId)/page-actions/$($pendingAction.requestId)/result" `
    @{
      type = "page.action.result"
      protocolVersion = "1.0"
      requestId = $pendingAction.requestId
      actionKey = $ActionKey
      status = "SUCCESS"
      data = @{
        total = 1
        records = @(@{ teamName = "Team A" })
      }
      error = $null
    } `
    $embedHeaders)

  $step = "query debug result"
  $event = Unwrap-ApiResult (Invoke-JsonGet `
    "$base/api/platform/embed/page-actions/debug/$($pendingAction.requestId)" `
    $platformHeaders)
  if ($event.status -ne "SUCCESS") {
    throw "debug event did not complete successfully: $($event.status)"
  }

  [pscustomobject]@{
    ok = $true
    baseUrl = $base
    projectCode = $ProjectCode
    pageKey = $register.pageKey
    actionKey = $ActionKey
    actionId = $action.id
    actionTitle = $action.title
    removedActionKey = $removedActionKeyValue
    removedActionStatus = $removedAction.status
    sessionId = $session.sessionId
    pageInstanceId = $pageInstanceId
    requestId = $pendingAction.requestId
    resultStatus = $result.status
    eventStatus = $event.status
  } | ConvertTo-Json -Depth 10
} catch {
  [pscustomobject]@{
    ok = $false
    step = $step
    error = $_.Exception.Message
    detail = $_.ErrorDetails.Message
  } | ConvertTo-Json -Depth 10
  exit 1
}
