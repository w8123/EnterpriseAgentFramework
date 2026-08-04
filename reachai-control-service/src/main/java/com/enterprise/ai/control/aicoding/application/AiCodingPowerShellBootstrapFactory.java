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

                function Send-ReachAiArtifact {
                  param(
                    [Parameter(Mandatory = $true)][string]$ArtifactKey,
                    [Parameter(Mandatory = $true)][object]$Content,
                    [string]$SessionRef
                  )
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
