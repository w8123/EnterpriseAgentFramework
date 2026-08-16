package com.enterprise.ai.control.aicoding.application;

import com.enterprise.ai.control.aicoding.domain.AiCodingTaskModels.ClientSetupView;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.HexFormat;

@Component
public class AiCodingPowerShellBootstrapFactory {

    static final String CLIENT_SETUP_SCHEMA = "reachai.ai-coding.client-setup.v1";
    static final String CLIENT_SETUP_SHELL = "WINDOWS_POWERSHELL";
    static final String CLIENT_SETUP_ENCODING = "BASE64_UTF8";
    static final String CLIENT_SETUP_SCRIPT_VERSION = "v1";

    public String build(
            String taskId,
            String handoffId,
            String executorProvider,
            String activationUrl,
            String activationCode) {
        return """
                $u=[Text.UTF8Encoding]::new($false)
                $t='%s';$h='%s'
                $d=[Environment]::GetEnvironmentVariable('REACHAI_AI_CODING_SESSION_ROOT')
                if(-not $d){$d=Join-Path ([Environment]::GetFolderPath('LocalApplicationData')) 'ReachAI\\ai-coding-sessions'}
                $r=Join-Path $d "$t.restore.ps1";$c=Join-Path $d "$t.dpapi";$ok=$false
                if(Test-Path -LiteralPath $r){
                  try{. $r;if($reachAiExpectedHandoffId -eq $h){$ok=$true}}catch{}
                }
                if(-not $ok){
                  Remove-Item -LiteralPath $r,$c -Force -ErrorAction SilentlyContinue
                  $reachAiActivationBody = @{
                    schema = 'reachai.ai-coding.activation.v1'
                    activationCode = '%s'
                    client = @{ provider = '%s' }
                  }
                  try {
                    $reachAiSession=Invoke-RestMethod -Method Post -Uri '%s' -ContentType 'application/json; charset=utf-8' -Body $u.GetBytes(($reachAiActivationBody|ConvertTo-Json -Depth 10 -Compress))
                  } catch {
                    throw 'ReachAI handoff activation failed. If this one-time code was already consumed, reissue the handoff.'
                  }
                  $s=$reachAiSession.clientSetup
                  if(-not $reachAiSession.taskToken -or $s.schema -ne '%s' -or $s.shell -ne '%s' -or $s.encoding -ne '%s' -or $s.scriptVersion -ne '%s'){throw 'ReachAI activation response is incomplete'}
                  $b=[Convert]::FromBase64String($s.payload);$a=[Security.Cryptography.SHA256]::Create()
                  try{$x=([BitConverter]::ToString($a.ComputeHash($b))).Replace('-','').ToLowerInvariant()}finally{$a.Dispose()}
                  if($x -ne $s.sha256){throw 'ReachAI client setup integrity check failed'}
                  Invoke-Expression $u.GetString($b)
                }
                $reachAiContext | ConvertTo-Json -Depth 100
                """.formatted(
                psLiteral(taskId),
                psLiteral(handoffId),
                psLiteral(activationCode),
                psLiteral(executorProvider),
                psLiteral(activationUrl),
                CLIENT_SETUP_SCHEMA,
                CLIENT_SETUP_SHELL,
                CLIENT_SETUP_ENCODING,
                CLIENT_SETUP_SCRIPT_VERSION);
    }

    public ClientSetupView clientSetup(
            String taskId,
            String handoffId,
            String executorProvider) {
        String restoreScriptBase64 = Base64.getEncoder().encodeToString(
                restoreScript(taskId, handoffId, executorProvider)
                        .getBytes(StandardCharsets.UTF_8));
        String setupSource = """
                $reachAiUtf8=[Text.UTF8Encoding]::new($false)
                Add-Type -AssemblyName System.Security
                $reachAiTaskId='%s'
                $reachAiExpectedHandoffId='%s'
                $reachAiSessionRoot=[Environment]::GetEnvironmentVariable('REACHAI_AI_CODING_SESSION_ROOT')
                if(-not $reachAiSessionRoot){$reachAiSessionRoot=Join-Path ([Environment]::GetFolderPath('LocalApplicationData')) 'ReachAI\\ai-coding-sessions'}
                if(-not $reachAiSessionRoot){throw 'ReachAI requires Windows LocalApplicationData for encrypted task-session recovery'}
                [IO.Directory]::CreateDirectory($reachAiSessionRoot)|Out-Null
                $reachAiCachePath=Join-Path $reachAiSessionRoot "$reachAiTaskId.dpapi"
                $reachAiRestorePath=Join-Path $reachAiSessionRoot "$reachAiTaskId.restore.ps1"
                $reachAiRestoreSource=$reachAiUtf8.GetString([Convert]::FromBase64String('%s'))
                [IO.File]::WriteAllText($reachAiRestorePath,$reachAiRestoreSource,$reachAiUtf8)
                $reachAiCacheEnvelope=@{
                  schema='reachai.ai-coding.session-cache.v1'
                  handoffId=$reachAiExpectedHandoffId
                  session=@{
                    schema=$reachAiSession.schema
                    taskId=$reachAiSession.taskId
                    protocolVersion=$reachAiSession.protocolVersion
                    taskToken=$reachAiSession.taskToken
                    taskRoot=$reachAiSession.taskRoot
                    tokenExpiresAt=$reachAiSession.tokenExpiresAt
                    leaseExpiresAt=$reachAiSession.leaseExpiresAt
                  }
                }|ConvertTo-Json -Depth 20 -Compress
                $reachAiCipher=[Security.Cryptography.ProtectedData]::Protect($reachAiUtf8.GetBytes($reachAiCacheEnvelope),$null,[Security.Cryptography.DataProtectionScope]::CurrentUser)
                [IO.File]::WriteAllBytes($reachAiCachePath,$reachAiCipher)
                . $reachAiRestorePath
                """.formatted(
                psLiteral(taskId),
                psLiteral(handoffId),
                restoreScriptBase64);
        byte[] setupBytes = setupSource.getBytes(StandardCharsets.UTF_8);
        return new ClientSetupView(
                CLIENT_SETUP_SCHEMA,
                CLIENT_SETUP_SHELL,
                CLIENT_SETUP_ENCODING,
                CLIENT_SETUP_SCRIPT_VERSION,
                sha256(setupBytes),
                Base64.getEncoder().encodeToString(setupBytes));
    }

    String restoreScript(
            String taskId,
            String handoffId,
            String executorProvider) {
        return """
                $reachAiUtf8 = [System.Text.UTF8Encoding]::new($false)
                Add-Type -AssemblyName System.Security
                $reachAiTaskId = '%s'
                $reachAiExpectedHandoffId = '%s'
                $reachAiExecutorProvider = '%s'
                $reachAiSessionRoot = [Environment]::GetEnvironmentVariable('REACHAI_AI_CODING_SESSION_ROOT')
                if (-not $reachAiSessionRoot) {
                  $reachAiSessionRoot = Join-Path ([Environment]::GetFolderPath('LocalApplicationData')) 'ReachAI\\ai-coding-sessions'
                }
                $reachAiCachePath = Join-Path $reachAiSessionRoot "$reachAiTaskId.dpapi"
                $reachAiRestorePath = Join-Path $reachAiSessionRoot "$reachAiTaskId.restore.ps1"

                function Remove-ReachAiTaskSession {
                  Remove-Item -LiteralPath $reachAiCachePath -Force -ErrorAction SilentlyContinue
                  Remove-Item -LiteralPath $reachAiRestorePath -Force -ErrorAction SilentlyContinue
                }

                function Import-ReachAiTaskSession {
                  if (-not [System.IO.File]::Exists($reachAiCachePath)) {
                    throw 'ReachAI encrypted task-session cache is missing. Reissue the handoff.'
                  }
                  try {
                    $cipher = [System.IO.File]::ReadAllBytes($reachAiCachePath)
                    $plain = [System.Security.Cryptography.ProtectedData]::Unprotect(
                      $cipher,
                      $null,
                      [System.Security.Cryptography.DataProtectionScope]::CurrentUser
                    )
                    $cacheEnvelope = $reachAiUtf8.GetString($plain) | ConvertFrom-Json
                  } catch {
                    throw 'ReachAI encrypted task-session cache cannot be decrypted by the current Windows user. Reissue the handoff.'
                  }
                  if ($cacheEnvelope.schema -ne 'reachai.ai-coding.session-cache.v1' -or $cacheEnvelope.handoffId -ne $reachAiExpectedHandoffId) {
                    throw 'ReachAI encrypted task-session cache does not belong to the current handoff. Reissue the handoff.'
                  }
                  if (-not $cacheEnvelope.session.taskToken -or -not $cacheEnvelope.session.taskRoot) {
                    throw 'ReachAI encrypted task-session cache is incomplete. Reissue the handoff.'
                  }
                  return $cacheEnvelope.session
                }

                function Invoke-ReachAiJson {
                  param(
                    [Parameter(Mandatory = $true)][string]$Method,
                    [Parameter(Mandatory = $true)][string]$Uri,
                    [hashtable]$Headers,
                    [Parameter(Mandatory = $true)][object]$Body
                  )
                  $json = $Body | ConvertTo-Json -Depth 100 -Compress
                  $bytes = $reachAiUtf8.GetBytes($json)
                  $invokeArgs = @{
                    Method = $Method
                    Uri = $Uri
                    ContentType = 'application/json; charset=utf-8'
                    Body = $bytes
                  }
                  if ($Headers) { $invokeArgs.Headers = $Headers }
                  return Invoke-RestMethod @invokeArgs
                }

                $reachAiSession = Import-ReachAiTaskSession
                $reachAiHeaders = @{ Authorization = "Bearer $($reachAiSession.taskToken)" }
                try {
                  $reachAiContext = Invoke-RestMethod `
                    -Method Get `
                    -Uri "$($reachAiSession.taskRoot)/context" `
                    -Headers $reachAiHeaders
                } catch {
                  $reachAiStatusCode = 0
                  try { $reachAiStatusCode = [int]$_.Exception.Response.StatusCode } catch {}
                  if ($reachAiStatusCode -eq 401) {
                    Remove-ReachAiTaskSession
                    throw 'ReachAI task token has expired or was revoked. Reissue the handoff.'
                  }
                  if ($reachAiStatusCode -eq 409) {
                    Remove-ReachAiTaskSession
                    throw 'ReachAI task is no longer active; its encrypted session cache was removed.'
                  }
                  throw 'ReachAI context cannot be loaded with the cached task session. Retry after the service is available, or reissue the handoff if the token was revoked.'
                }

                function Send-ReachAiHeartbeat {
                  return Invoke-RestMethod `
                    -Method Post `
                    -Uri $reachAiContext.endpoints.heartbeatUrl `
                    -Headers $reachAiHeaders
                }

                function Send-ReachAiEvent {
                  param(
                    [Parameter(Mandatory = $true)]
                    [ValidateSet('STARTED', 'PROGRESS', 'RESUMED', 'FAILED')]
                    [string]$EventType,
                    [Parameter(Mandatory = $true)][string]$Message,
                    [object]$Payload = @{}
                  )
                  $body = [ordered]@{
                    schema = 'reachai.ai-coding.event.v1'
                    clientEventId = [guid]::NewGuid().ToString()
                    eventType = $EventType
                    message = $Message
                    payload = $Payload
                    reportedBy = $reachAiExecutorProvider
                  }
                  $response = Invoke-ReachAiJson `
                    -Method Post `
                    -Uri $reachAiContext.endpoints.eventsUrl `
                    -Headers $reachAiHeaders `
                    -Body $body
                  if (@('COMPLETED', 'FAILED', 'CANCELLED') -contains $response.executionStatus) {
                    Remove-ReachAiTaskSession
                  }
                  return $response
                }

                function Send-ReachAiQuestion {
                  param(
                    [Parameter(Mandatory = $true)][string]$QuestionId,
                    [Parameter(Mandatory = $true)][string]$Title,
                    [Parameter(Mandatory = $true)][string]$Body,
                    [string[]]$Options = @()
                  )
                  $request = [ordered]@{
                    schema = 'reachai.ai-coding.question.v1'
                    clientEventId = [guid]::NewGuid().ToString()
                    questionId = $QuestionId
                    title = $Title
                    body = $Body
                    options = $Options
                    askedBy = $reachAiExecutorProvider
                  }
                  return Invoke-ReachAiJson `
                    -Method Post `
                    -Uri $reachAiContext.endpoints.questionsUrl `
                    -Headers $reachAiHeaders `
                    -Body $request
                }

                function Get-ReachAiQuestions {
                  return Invoke-RestMethod `
                    -Method Get `
                    -Uri $reachAiContext.endpoints.questionsUrl `
                    -Headers $reachAiHeaders
                }

                function Get-ReachAiJsonProperty {
                  param([object]$Value, [Parameter(Mandatory = $true)][string]$Name)
                  if ($null -eq $Value) { return $null }
                  if ($Value -is [System.Collections.IDictionary] -or $Value -is [System.Collections.Generic.IDictionary[string, object]]) {
                    Write-Output -NoEnumerate $Value[$Name]
                    return
                  }
                  $property = $Value.PSObject.Properties[$Name]
                  if ($property) {
                    Write-Output -NoEnumerate $property.Value
                    return
                  }
                  return $null
                }

                function Test-ReachAiJsonProperty {
                  param([object]$Value, [Parameter(Mandatory = $true)][string]$Name)
                  if ($null -eq $Value) { return $false }
                  if ($Value -is [System.Collections.IDictionary] -or $Value -is [System.Collections.Generic.IDictionary[string, object]]) {
                    if ($null -ne $Value.PSObject.Methods['ContainsKey']) { return $Value.ContainsKey($Name) }
                    return $Value.Contains($Name)
                  }
                  return $null -ne $Value.PSObject.Properties[$Name]
                }

                function Get-ReachAiJsonProperties {
                  param([object]$Value)
                  if ($null -eq $Value) { return @() }
                  if ($Value -is [System.Collections.IDictionary] -or $Value -is [System.Collections.Generic.IDictionary[string, object]]) { return @($Value.Keys | ForEach-Object { [string]$_ }) }
                  return @($Value.PSObject.Properties | ForEach-Object { $_.Name })
                }

                function Get-ReachAiSchemaPointer {
                  param([object]$Root, [Parameter(Mandatory = $true)][string]$Pointer)
                  $current = $Root
                  $segments = $Pointer.TrimStart('#').TrimStart('/').Split('/', [System.StringSplitOptions]::RemoveEmptyEntries)
                  foreach ($segment in $segments) {
                    $name = $segment.Replace('~1', '/').Replace('~0', '~')
                    $current = Get-ReachAiJsonProperty -Value $current -Name $name
                    if ($null -eq $current) { throw "Artifact schema reference cannot be resolved: $Pointer" }
                  }
                  return $current
                }

                function Resolve-ReachAiSchemaReference {
                  param([Parameter(Mandatory = $true)][string]$Reference, [object]$Root)
                  $parts = $Reference.Split('#', 2)
                  $fileName = $parts[0]
                  $fragment = if ($parts.Count -gt 1) { '#' + $parts[1] } else { '' }
                  $referenceRoot = $Root
                  if ($fileName) {
                    $referenceRoot = Get-ReachAiJsonProperty -Value $script:reachAiArtifactReferencedSchemas -Name $fileName
                    if ($null -eq $referenceRoot) { throw "Artifact contract did not include referenced schema: $fileName" }
                  }
                  $schema = if ($fragment) { Get-ReachAiSchemaPointer -Root $referenceRoot -Pointer $fragment } else { $referenceRoot }
                  return @{ schema = $schema; root = $referenceRoot }
                }

                function Test-ReachAiSchemaType {
                  param([object]$Value, [string]$Type)
                  switch ($Type) {
                    'null' { return $null -eq $Value }
                    'object' { return $Value -is [System.Collections.IDictionary] -or $Value -is [System.Collections.Generic.IDictionary[string, object]] -or $Value -is [pscustomobject] }
                    'array' { return $null -ne $Value -and $Value -isnot [string] -and $Value -is [System.Collections.IEnumerable] -and $Value -isnot [System.Collections.IDictionary] }
                    'string' { return $Value -is [string] }
                    'boolean' { return $Value -is [bool] }
                    'integer' { return $Value -is [sbyte] -or $Value -is [byte] -or $Value -is [int16] -or $Value -is [uint16] -or $Value -is [int32] -or $Value -is [uint32] -or $Value -is [int64] -or $Value -is [uint64] }
                    'number' { return $Value -is [System.ValueType] -and $Value -isnot [bool] -and $Value -isnot [char] }
                    default { throw "Artifact schema uses unsupported type: $Type" }
                  }
                }

                function Assert-ReachAiArtifactSchema {
                  param([object]$Value, [object]$Schema, [object]$Root, [string]$Path = '$')
                  if ($null -eq $Schema) { throw "Artifact schema is missing at $Path" }
                  $reference = Get-ReachAiJsonProperty -Value $Schema -Name '$ref'
                  if ($reference) {
                    $resolved = Resolve-ReachAiSchemaReference -Reference ([string]$reference) -Root $Root
                    Assert-ReachAiArtifactSchema -Value $Value -Schema $resolved.schema -Root $resolved.root -Path $Path
                    return
                  }
                  $anyOf = Get-ReachAiJsonProperty -Value $Schema -Name 'anyOf'
                  if ($anyOf) {
                    $matched = $false
                    foreach ($option in $anyOf) {
                      try { Assert-ReachAiArtifactSchema -Value $Value -Schema $option -Root $Root -Path $Path; $matched = $true; break } catch {}
                    }
                    if (-not $matched) { throw "Artifact contract violation at ${Path}: does not match any allowed schema" }
                    return
                  }
                  $type = Get-ReachAiJsonProperty -Value $Schema -Name 'type'
                  if ($type) {
                    $types = [System.Collections.Generic.List[string]]::new()
                    if ($type -is [string]) {
                      $types.Add([string]$type)
                    } else {
                      foreach ($candidateType in $type) { $types.Add([string]$candidateType) }
                    }
                    if (-not (@($types | Where-Object { Test-ReachAiSchemaType -Value $Value -Type ([string]$_) }).Count)) {
                      throw "Artifact contract violation at ${Path}: has an invalid JSON type; expected $($types -join ', ')"
                    }
                  }
                  $constant = Get-ReachAiJsonProperty -Value $Schema -Name 'const'
                  if ($null -ne $constant -and (($Value | ConvertTo-Json -Depth 100 -Compress) -ne ($constant | ConvertTo-Json -Depth 100 -Compress))) {
                    throw "Artifact contract violation at ${Path}: must equal $constant"
                  }
                  $enum = Get-ReachAiJsonProperty -Value $Schema -Name 'enum'
                  if ($enum) {
                    $actual = $Value | ConvertTo-Json -Depth 100 -Compress
                    if (-not (@($enum | Where-Object { ($_ | ConvertTo-Json -Depth 100 -Compress) -eq $actual }).Count)) {
                      throw "Artifact contract violation at ${Path}: must be one of the contract enum values"
                    }
                  }
                  if ($null -eq $Value) { return }
                  if (Test-ReachAiSchemaType -Value $Value -Type 'object') {
                    $required = Get-ReachAiJsonProperty -Value $Schema -Name 'required'
                    if ($required) {
                      foreach ($name in $required) {
                        if (-not (Test-ReachAiJsonProperty -Value $Value -Name ([string]$name))) { throw "Artifact contract violation at ${Path}.${name}: is required" }
                      }
                    }
                    $properties = Get-ReachAiJsonProperty -Value $Schema -Name 'properties'
                    if ($properties) {
                      foreach ($name in Get-ReachAiJsonProperties -Value $properties) {
                        if (Test-ReachAiJsonProperty -Value $Value -Name $name) {
                          Assert-ReachAiArtifactSchema -Value (Get-ReachAiJsonProperty -Value $Value -Name $name) -Schema (Get-ReachAiJsonProperty -Value $properties -Name $name) -Root $Root -Path "${Path}.${name}"
                        }
                      }
                    }
                    if ((Get-ReachAiJsonProperty -Value $Schema -Name 'additionalProperties') -eq $false) {
                      foreach ($name in Get-ReachAiJsonProperties -Value $Value) {
                        if (-not (Test-ReachAiJsonProperty -Value $properties -Name $name)) { throw "Artifact contract violation at ${Path}.${name}: is not allowed by the artifact contract" }
                      }
                    }
                    return
                  }
                  if (Test-ReachAiSchemaType -Value $Value -Type 'array') {
                    $items = [System.Collections.Generic.List[object]]::new()
                    foreach ($item in $Value) { $items.Add($item) }
                    $minimum = Get-ReachAiJsonProperty -Value $Schema -Name 'minItems'
                    $maximum = Get-ReachAiJsonProperty -Value $Schema -Name 'maxItems'
                    if ($null -ne $minimum -and $items.Count -lt [int]$minimum) { throw "Artifact contract violation at ${Path}: must contain at least $minimum items" }
                    if ($null -ne $maximum -and $items.Count -gt [int]$maximum) { throw "Artifact contract violation at ${Path}: must contain at most $maximum items" }
                    $uniqueItems = Get-ReachAiJsonProperty -Value $Schema -Name 'uniqueItems'
                    if ($null -ne $uniqueItems -and $uniqueItems -isnot [bool]) { throw "Artifact schema contains invalid uniqueItems at $Path" }
                    if ($uniqueItems -eq $true) {
                      $seenItems = New-Object 'System.Collections.Generic.HashSet[string]'
                      for ($index = 0; $index -lt $items.Count; $index++) {
                        $fingerprint = $items[$index] | ConvertTo-Json -Depth 100 -Compress
                        if (-not $seenItems.Add($fingerprint)) { throw "Artifact contract violation at ${Path}[$index]: must be unique within the array" }
                      }
                    }
                    $itemSchema = Get-ReachAiJsonProperty -Value $Schema -Name 'items'
                    if ($itemSchema) {
                      for ($index = 0; $index -lt $items.Count; $index++) { Assert-ReachAiArtifactSchema -Value $items[$index] -Schema $itemSchema -Root $Root -Path "${Path}[$index]" }
                    }
                    return
                  }
                  if ($Value -is [string]) {
                    $characterCount = [int]([System.Text.Encoding]::UTF32.GetByteCount($Value) / 4)
                    $minimum = Get-ReachAiJsonProperty -Value $Schema -Name 'minLength'
                    $maximum = Get-ReachAiJsonProperty -Value $Schema -Name 'maxLength'
                    if ($null -ne $minimum -and $characterCount -lt [int]$minimum) { throw "Artifact contract violation at ${Path}: must contain at least $minimum characters" }
                    if ($null -ne $maximum -and $characterCount -gt [int]$maximum) { throw "Artifact contract violation at ${Path}: must contain at most $maximum characters" }
                    $pattern = Get-ReachAiJsonProperty -Value $Schema -Name 'pattern'
                    if ($null -ne $pattern) {
                      if ($pattern -isnot [string]) { throw "Artifact schema contains invalid pattern at $Path" }
                      try { $regularExpression = New-Object System.Text.RegularExpressions.Regex ([string]$pattern) } catch { throw "Artifact schema contains invalid pattern at ${Path}: $($_.Exception.Message)" }
                      if (-not $regularExpression.IsMatch($Value)) { throw "Artifact contract violation at ${Path}: must match pattern $pattern" }
                    }
                    if ((Get-ReachAiJsonProperty -Value $Schema -Name 'format') -eq 'date-time') {
                      $parsed = [datetimeoffset]::MinValue
                      $isoDateTime = '^\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}(?::\\d{2}(?:\\.\\d{1,9})?)?(?:Z|[+-]\\d{2}:\\d{2})?$'
                      if ($Value -notmatch $isoDateTime -or -not [datetimeoffset]::TryParse($Value, [System.Globalization.CultureInfo]::InvariantCulture, [System.Globalization.DateTimeStyles]::RoundtripKind, [ref]$parsed)) { throw "Artifact contract violation at ${Path}: must be an ISO-8601 date-time" }
                    }
                    return
                  }
                  if (Test-ReachAiSchemaType -Value $Value -Type 'number') {
                    $minimum = Get-ReachAiJsonProperty -Value $Schema -Name 'minimum'
                    $maximum = Get-ReachAiJsonProperty -Value $Schema -Name 'maximum'
                    if ($null -ne $minimum -and [decimal]$Value -lt [decimal]$minimum) { throw "Artifact contract violation at ${Path}: must be at least $minimum" }
                    if ($null -ne $maximum -and [decimal]$Value -gt [decimal]$maximum) { throw "Artifact contract violation at ${Path}: must be at most $maximum" }
                  }
                }

                function Test-ReachAiArtifact {
                  param([Parameter(Mandatory = $true)][object]$Content)
                  try {
                    $schema = $reachAiContext.artifactContract.jsonSchema
                    $script:reachAiArtifactReferencedSchemas = $reachAiContext.artifactContract.referencedSchemas
                    Assert-ReachAiArtifactSchema -Value $Content -Schema $schema -Root $schema -Path '$'
                    return @{ valid = $true; contract = $reachAiContext.artifactContract.key + '/' + $reachAiContext.artifactContract.version; message = 'Artifact content matches the locally bundled contract schema.' }
                  } catch {
                    return @{ valid = $false; contract = $reachAiContext.artifactContract.key + '/' + $reachAiContext.artifactContract.version; message = $_.Exception.Message; location = $_.InvocationInfo.PositionMessage }
                  }
                }

                function Send-ReachAiArtifact {
                  param(
                    [Parameter(Mandatory = $true)][string]$ArtifactKey,
                    [Parameter(Mandatory = $true)][object]$Content,
                    [string]$SessionRef
                  )
                  $localValidation = Test-ReachAiArtifact -Content $Content
                  if (-not $localValidation.valid) { throw "ReachAI artifact local validation failed: $($localValidation.message) $($localValidation.location)" }
                  $request = [ordered]@{
                    schema = 'reachai.ai-coding.artifact.v1'
                    clientEventId = [guid]::NewGuid().ToString()
                    artifactKey = $ArtifactKey
                    contract = @{
                      key = $reachAiContext.artifactContract.key
                      version = $reachAiContext.artifactContract.version
                    }
                    content = $Content
                    reportedBy = @{
                      provider = $reachAiExecutorProvider
                      sessionRef = $SessionRef
                    }
                  }
                  $response = Invoke-ReachAiJson `
                    -Method Post `
                    -Uri $reachAiContext.endpoints.artifactsUrl `
                    -Headers $reachAiHeaders `
                    -Body $request
                  if (@('ACCEPTANCE_READY', 'COMPLETED') -contains $response.task.executionStatus) {
                    Remove-ReachAiTaskSession
                  }
                  return $response
                }
                """.formatted(
                psLiteral(taskId),
                psLiteral(handoffId),
                psLiteral(executorProvider));
    }

    private static String psLiteral(String value) {
        if (value == null) {
            throw new IllegalArgumentException("PowerShell bootstrap value is required");
        }
        return value.replace("'", "''");
    }

    private static String sha256(byte[] value) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(value));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is not available", exception);
        }
    }
}
