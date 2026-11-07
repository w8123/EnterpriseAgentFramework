using System.Collections.Concurrent;
using System.Net;
using System.Net.Http.Headers;
using System.Security.Cryptography;
using System.Text;
using System.Text.Json;
using System.Text.RegularExpressions;
using Microsoft.Extensions.Hosting;
using Microsoft.Extensions.Logging;

namespace ReachAI.AspNetCore;

public sealed class ReachAiRegistryClient
{
    private readonly ReachAiOptions options;
    private readonly IReachAiCredentialStore store;
    private readonly CapabilityCatalog catalog;
    private readonly HttpClient http;
    private readonly SemaphoreSlim gate = new(1, 1);
    private ReachAiCredentials? credentials;
    private ReachAiCredentials? pending;
    private bool enrollmentUncertain;
    private readonly ConcurrentDictionary<string, long> nonces = new();
    private readonly string generatedInstanceId = "csharp-" + Guid.NewGuid().ToString("N");
    internal ReachAiRegistryClient(ReachAiOptions options, IReachAiCredentialStore store, CapabilityCatalog catalog, HttpClient http)
    { this.options = options; this.store = store; this.catalog = catalog; this.http = http; }
    internal string InstanceId => string.IsNullOrWhiteSpace(options.InstanceId) ? generatedInstanceId : options.InstanceId.Trim();
    internal ReachAiCredentials Credentials => credentials ?? throw new InvalidOperationException("ReachAI credentials unavailable");

    // RegistryEnrollmentService normalizes before consuming the one-time token.
    // Require its canonical spelling before sending so the returned project code
    // cannot differ after the server has issued a non-repeatable credential.
    internal static void RequireCanonicalProjectCode(string? code)
    {
        if (code is null || !Regex.IsMatch(code, @"\A[a-z0-9_]+(?:-[a-z0-9_]+)*\z", RegexOptions.CultureInvariant))
            throw new InvalidOperationException("ReachAI project code must already be normalized (lowercase letters, digits, underscore and single internal hyphens)");
    }

    public async Task RegisterAsync(CancellationToken ct = default)
    {
        RequireCanonicalProjectCode(options.ProjectCode);
        await gate.WaitAsync(ct);
        try
        {
            if (string.IsNullOrWhiteSpace(options.AppKey) != string.IsNullOrWhiteSpace(options.AppSecret)) throw new InvalidOperationException("AppKey and AppSecret must be supplied together");
            if (pending != null) { await store.SaveAsync(pending, ct); credentials = pending; pending = null; options.EnrollmentToken = ""; }
            if (credentials == null && !string.IsNullOrWhiteSpace(options.AppKey) && !string.IsNullOrWhiteSpace(options.AppSecret)) credentials = new(options.AppKey, options.AppSecret);
            if (credentials == null) credentials = await store.LoadAsync(ct);
            var enrollment = credentials == null;
            if (enrollment && (enrollmentUncertain || string.IsNullOrWhiteSpace(options.EnrollmentToken))) throw new InvalidOperationException("Enrollment outcome uncertain or token absent; recover project credentials before retrying");
            var body = new { projectCode = options.ProjectCode, name = string.IsNullOrWhiteSpace(options.ProjectName) ? options.ProjectCode : options.ProjectName,
                environment = options.Environment, visibility = "PRIVATE", baseUrl = options.BaseUrl, contextPath = options.ContextPath, appKey = credentials?.AppKey };
            JsonElement response;
            if (enrollment) enrollmentUncertain = true;
            response = await PostAsync("/api/registry/projects/register", body, enrollment, false, ct);
            if (enrollment)
            {
                if (response.TryGetProperty("data", out var wrapped) && wrapped.ValueKind == JsonValueKind.Object) response = wrapped;
                if (response.GetProperty("projectCode").GetString() != options.ProjectCode) throw new InvalidOperationException("Enrollment project mismatch");
                var key = response.GetProperty("appKey").GetString(); var secret = response.GetProperty("appSecret").GetString();
                if (string.IsNullOrWhiteSpace(key) || string.IsNullOrWhiteSpace(secret)) throw new InvalidOperationException("Enrollment returned no credential");
                pending = new(key, secret);
                await store.SaveAsync(pending, ct);
                credentials = pending; pending = null; options.EnrollmentToken = ""; enrollmentUncertain = false;
            }
        }
        finally { gate.Release(); }
    }
    public async Task<JsonElement> HeartbeatAsync(CancellationToken ct = default)
    {
        var response = await PostAsync(ProjectPath("instances/heartbeat"), new {
            instanceId = InstanceId, baseUrl = options.BaseUrl, host = Environment.MachineName, sdkVersion = "1.0.0",
            metadata = new { runtimePlacement = "CAPABILITY_HOST", runtimeTypes = new[] { "ASPNETCORE_CAPABILITY_HOST" }, supportsTools = true, sdkLanguage = "csharp" }
        }, false, false, ct);
        var payload = Payload(response);
        if (!payload.TryGetProperty("instance", out var instance) || instance.ValueKind != JsonValueKind.Object
            || !instance.TryGetProperty("instanceId", out var id) || id.ValueKind != JsonValueKind.String || id.GetString() != InstanceId)
            throw new InvalidOperationException("Heartbeat response is incomplete");
        return response;
    }
    public Task<JsonElement> OfflineAsync(CancellationToken ct = default) => PostAsync(ProjectPath("instances/offline"), new { instanceId = InstanceId }, false, false, ct);
    public async Task<JsonElement> SyncAsync(CancellationToken ct = default, string source = "SDK")
    {
        if (!catalog.HasConfiguredSources) throw new InvalidOperationException("No capability types registered; refusing unobserved inventory");
        var capabilities = catalog.Registrations();
        var response = await PostAsync(ProjectPath("capabilities/sync"), new {
            syncId = Guid.NewGuid().ToString(), source, apply = false, capabilities
        }, false, false, ct);
        // Capability may reuse an identical snapshot and return its original syncId; verify project and count instead.
        var payload = Payload(response);
        if (!payload.TryGetProperty("syncId", out var syncId) || syncId.ValueKind != JsonValueKind.String || string.IsNullOrWhiteSpace(syncId.GetString()) || syncId.GetString()!.Length > 64
            || !payload.TryGetProperty("projectCode", out var project) || project.ValueKind != JsonValueKind.String || project.GetString() != options.ProjectCode
            || !payload.TryGetProperty("received", out var received) || received.ValueKind != JsonValueKind.Number || !received.TryGetInt32(out var count) || count != capabilities.Length)
            throw new InvalidOperationException("Capability sync response is incomplete");
        return response;
    }
    private static JsonElement Payload(JsonElement response) => response.ValueKind == JsonValueKind.Object && response.TryGetProperty("data", out var data) && data.ValueKind == JsonValueKind.Object ? data : response;
    private string ProjectPath(string suffix) => "/api/registry/projects/" + Uri.EscapeDataString(options.ProjectCode) + "/" + suffix;

    internal async Task<JsonElement> PostAsync(string path, object body, bool enrollment, bool bodySigned, CancellationToken ct, bool projectOnly = false)
    {
        var bytes = JsonSerializer.SerializeToUtf8Bytes(body, Wire.Json);
        using var request = new HttpRequestMessage(HttpMethod.Post, options.RegistryUrl.TrimEnd('/') + path);
        request.Content = new ByteArrayContent(bytes); request.Content.Headers.ContentType = new MediaTypeHeaderValue("application/json");
        if (enrollment) request.Headers.Add("X-ReachAI-Registry-Enrollment-Token", options.EnrollmentToken);
        else
        {
            var credential = Credentials; var timestamp = DateTimeOffset.UtcNow.ToUnixTimeMilliseconds().ToString(); var nonce = Guid.NewGuid().ToString();
            request.Headers.Add("X-ReachAI-App-Key", credential.AppKey); request.Headers.Add("X-ReachAI-Timestamp", timestamp); request.Headers.Add("X-ReachAI-Nonce", nonce);
            var legacySignature = ReachAiProtocol.RegistrySignature(options.ProjectCode, timestamp, nonce, credential.AppSecret);
            var projectSignature = bodySigned ? ReachAiProtocol.BodySignature("POST", path, options.ProjectCode, credential.AppKey, timestamp, nonce, bytes, credential.AppSecret) : null;
            request.Headers.Add("X-ReachAI-Signature", projectOnly ? projectSignature! : legacySignature);
            if (bodySigned) { request.Headers.Add("X-ReachAI-Body-SHA256", Convert.ToHexStringLower(SHA256.HashData(bytes))); if (!projectOnly) request.Headers.Add("X-ReachAI-Body-Signature", projectSignature); }
        }
        using var response = await http.SendAsync(request, HttpCompletionOption.ResponseHeadersRead, ct);
        if (!response.IsSuccessStatusCode) throw new HttpRequestException("ReachAI upstream request failed", null, response.StatusCode);
        if (response.Content.Headers.ContentLength > 8 * 1024 * 1024) throw new InvalidOperationException("ReachAI response too large");
        await using var stream = await response.Content.ReadAsStreamAsync(ct);
        using var buffer = new MemoryStream(); var chunk = new byte[8192]; int count;
        while ((count = await stream.ReadAsync(chunk, ct)) > 0) { if (buffer.Length + count > 8 * 1024 * 1024) throw new InvalidOperationException("ReachAI response too large"); await buffer.WriteAsync(chunk.AsMemory(0, count), ct); }
        using var document = JsonDocument.Parse(buffer.Length == 0 ? "{}"u8.ToArray() : buffer.ToArray());
        var root = document.RootElement;
        if (root.ValueKind != JsonValueKind.Object) throw new InvalidOperationException("Invalid ReachAI response");
        if (root.TryGetProperty("code", out var code) && code.ToString() != "200") throw new InvalidOperationException("ReachAI application request failed");
        return root.Clone();
    }
    internal bool VerifyCallback(string key, string timestamp, string nonce, string signature)
    {
        var cred = Credentials;
        var current = DateTimeOffset.UtcNow.ToUnixTimeMilliseconds();
        if (key != cred.AppKey || !long.TryParse(timestamp, out var time) || string.IsNullOrWhiteSpace(nonce) || time < current - 300000 || time > current + 300000) return false;
        var expected = ReachAiProtocol.RegistrySignature(options.ProjectCode, timestamp, nonce, cred.AppSecret);
        if (!CryptographicOperations.FixedTimeEquals(Encoding.ASCII.GetBytes(expected), Encoding.ASCII.GetBytes(signature.ToLowerInvariant()))) return false;
        var now = DateTimeOffset.UtcNow.ToUnixTimeMilliseconds(); foreach (var entry in nonces) if (entry.Value < now - 300000) nonces.TryRemove(entry.Key, out _);
        return nonces.TryAdd(nonce, now);
    }
}

internal sealed class RegistryHostedService(ReachAiRegistryClient client, ReachAiOptions options, ILogger<RegistryHostedService> logger) : BackgroundService
{
    protected override async Task ExecuteAsync(CancellationToken stoppingToken)
    {
        var registered = false;
        while (!stoppingToken.IsCancellationRequested)
        {
            try
            {
                if (!registered) { await client.RegisterAsync(stoppingToken); registered = true; }
                await client.HeartbeatAsync(stoppingToken);
            }
            catch (OperationCanceledException) when (stoppingToken.IsCancellationRequested) { break; }
            catch (Exception ex) { logger.LogWarning("ReachAI registry operation failed: {Type}", ex.GetType().Name); }
            await Task.Delay(TimeSpan.FromSeconds(Math.Max(5, options.HeartbeatSeconds)), stoppingToken);
        }
    }
    public override async Task StopAsync(CancellationToken cancellationToken)
    {
        await base.StopAsync(cancellationToken);
        try { await client.OfflineAsync(cancellationToken); } catch (Exception ex) { logger.LogWarning("ReachAI offline notification failed: {Type}", ex.GetType().Name); }
    }
}
