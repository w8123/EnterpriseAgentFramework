using System.Text.Json;
using System.Text.Json.Serialization;
using System.Text.RegularExpressions;

namespace ReachAI.AspNetCore;

[AttributeUsage(AttributeTargets.Method)]
public sealed class ReachBusinessIndexResolverAttribute : Attribute;

public sealed record ReachBusinessIndexResolverRequest(string ResourceType, string ResourceId, string ExpectedSourceVersion);

public sealed class ReachBusinessIndexAccessScope
{
    public string TenantId { get; }
    public string CurrentUserId { get; }
    public string ProjectCode { get; }
    public string ResourceType { get; }
    public string ResourceId { get; }
    private ReachBusinessIndexAccessScope(string tenant, string user, string project, string type, string id)
    { TenantId = tenant; CurrentUserId = user; ProjectCode = project; ResourceType = type; ResourceId = id; }
    public static ReachBusinessIndexAccessScope AuthorizeRow(string invocationTenantId, string authoritativeRowTenantId, string currentUserId, string projectCode, string resourceType, string requestedResourceId, string authoritativeResourceId, bool visibleToCurrentUser)
    {
        var tenant = Identifier(invocationTenantId, 96); var actualTenant = Identifier(authoritativeRowTenantId, 96);
        var user = Required(currentUserId, 128); var project = Identifier(projectCode, 96); var type = Identifier(resourceType, 96);
        var requested = Required(requestedResourceId, 256); var actual = Required(authoritativeResourceId, 256);
        if (tenant != actualTenant || requested != actual || !visibleToCurrentUser) throw new UnauthorizedAccessException("Business row authorization failed");
        return new(tenant, user, project, type, actual);
    }
    internal static string Required(string? value, int max) { var result = value?.Trim(); return string.IsNullOrWhiteSpace(result) || result.Length > max ? throw new ArgumentException("Invalid business index field") : result; }
    internal static string Identifier(string? value, int max) { var result = Required(value, max); return Regex.IsMatch(result, "^[A-Za-z0-9._:-]+$") ? result : throw new ArgumentException("Invalid business index identifier"); }
}

public sealed class ReachBusinessIndexResolution
{
    public string Schema => "reachai-business-index-resolution-v1";
    public string TenantId { get; }
    public string ProjectCode { get; }
    public string SourceSystem { get; }
    public string ResourceType { get; }
    public string ResourceId { get; }
    public string SourceVersion { get; }
    public IReadOnlyDictionary<string, object?> Data { get; }
    public string ResolvedAt { get; }
    [JsonIgnore] internal bool RowAuthorizationVerified { get; }
    [JsonIgnore] internal string AuthorizedUserId { get; }
    private ReachBusinessIndexResolution(ReachBusinessIndexAccessScope scope, string sourceSystem, string sourceVersion, IReadOnlyDictionary<string, object?> data)
    {
        TenantId = scope.TenantId; AuthorizedUserId = scope.CurrentUserId; ProjectCode = scope.ProjectCode; ResourceType = scope.ResourceType; ResourceId = scope.ResourceId;
        SourceSystem = ReachBusinessIndexAccessScope.Identifier(sourceSystem, 96); SourceVersion = ReachBusinessIndexAccessScope.Required(sourceVersion, 128);
        Data = new Dictionary<string, object?>(data ?? throw new ArgumentNullException(nameof(data))); ResolvedAt = DateTimeOffset.UtcNow.ToString("O"); RowAuthorizationVerified = true;
    }
    public static ReachBusinessIndexResolution CreateAuthorized(ReachBusinessIndexAccessScope scope, string sourceSystem, string sourceVersion, IReadOnlyDictionary<string, object?> data) => new(scope ?? throw new ArgumentNullException(nameof(scope)), sourceSystem, sourceVersion, data);
}

internal static class ReachBusinessIndexGuard
{
    internal sealed record Scope(string Tenant, string User, string Project, string ResourceType, string ResourceId);
    public static Scope RequireScope(ReachAiInvocationContext invocation, string projectCode, string capabilityName, JsonElement arguments)
    {
        var claims = invocation.Claims;
        var tenant = ReachBusinessIndexAccessScope.Identifier(invocation.TenantId, 96);
        var user = invocation.ExternalUserId;
        if (string.IsNullOrWhiteSpace(user) && claims.TryGetProperty("globalUserId", out var global) && global.ValueKind == JsonValueKind.String) user = global.GetString();
        user = ReachBusinessIndexAccessScope.Required(user, 128);
        if (claims.GetProperty("projectCode").GetString() != projectCode || claims.GetProperty("capabilityName").GetString() != capabilityName) throw new UnauthorizedAccessException();
        var request = arguments.TryGetProperty("request", out var wrapped) && wrapped.ValueKind == JsonValueKind.Object ? wrapped : arguments;
        var type = ReachBusinessIndexAccessScope.Identifier(request.GetProperty("resourceType").GetString(), 96);
        var id = ReachBusinessIndexAccessScope.Required(request.GetProperty("resourceId").GetString(), 256);
        ReachBusinessIndexAccessScope.Required(request.GetProperty("expectedSourceVersion").GetString(), 128);
        return new(tenant, user, projectCode, type, id);
    }
    public static void Validate(Scope scope, object? result)
    {
        if (result is not ReachBusinessIndexResolution response || !response.RowAuthorizationVerified || response.TenantId != scope.Tenant || response.AuthorizedUserId != scope.User || response.ProjectCode != scope.Project || response.ResourceType != scope.ResourceType || response.ResourceId != scope.ResourceId) throw new UnauthorizedAccessException("Business index resolution scope mismatch");
    }
}

public sealed class ReachAiBusinessIndexClient(ReachAiRegistryClient registry, ReachAiOptions options)
{
    public Task<JsonElement> UpsertAsync(string indexCode, object record, CancellationToken ct = default) => Send(indexCode, "upsert", record, ct);
    public Task<JsonElement> BatchUpsertAsync(string indexCode, IEnumerable<object> records, CancellationToken ct = default) => Send(indexCode, "batch", new { items = records }, ct);
    public Task<JsonElement> DeleteRecordAsync(string indexCode, string bizId, CancellationToken ct = default) => Send(indexCode, "delete", new { bizId }, ct);
    private Task<JsonElement> Send(string indexCode, string operation, object body, CancellationToken ct)
    {
        if (!Regex.IsMatch(options.ProjectCode, "^[a-z0-9][a-z0-9_-]{0,95}$") || !Regex.IsMatch(indexCode, "^[A-Za-z][A-Za-z0-9_]{1,62}$")) throw new ArgumentException("Invalid business index path");
        ArgumentNullException.ThrowIfNull(body);
        var path = $"/api/knowledge-ingress/projects/{Uri.EscapeDataString(options.ProjectCode)}/biz-index/{Uri.EscapeDataString(indexCode)}/{operation}";
        return registry.PostAsync(path, body, false, true, ct, projectOnly: true);
    }
}
