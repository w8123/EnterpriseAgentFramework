using System.Text.Json;
using Microsoft.AspNetCore.Builder;
using Microsoft.AspNetCore.Http;
using Microsoft.AspNetCore.Routing;
using Microsoft.Extensions.Configuration;
using Microsoft.Extensions.DependencyInjection;
using Microsoft.Extensions.DependencyInjection.Extensions;

namespace ReachAI.AspNetCore;

public sealed class ReachAiBuilder(IServiceCollection services, ReachAiCapabilityTypesHolder holder)
{
    public ReachAiBuilder AddCapabilitiesFrom<T>() where T : class
    {
        services.TryAddScoped<T>(); holder.Add(typeof(T)); return this;
    }
}
public sealed class ReachAiCapabilityTypesHolder
{
    internal ReachAiCapabilityTypes Types { get; } = new();
    internal void Add(Type type) => Types.Types.Add(type);
}
public static class ReachAiServiceExtensions
{
    public static ReachAiBuilder AddReachAI(this IServiceCollection services, IConfiguration configuration)
    {
        var options = new ReachAiOptions(); configuration.Bind(options);
        if (!Uri.TryCreate(options.RegistryUrl, UriKind.Absolute, out var registry) || (registry.Scheme != "https" && registry.Scheme != "http") || !Uri.TryCreate(options.BaseUrl, UriKind.Absolute, out var host) || (host.Scheme != "https" && host.Scheme != "http") || string.IsNullOrWhiteSpace(options.ProjectCode)) throw new InvalidOperationException("ReachAI registry URL, project code and base URL must be configured");
        ReachAiRegistryClient.RequireCanonicalProjectCode(options.ProjectCode);
        var holder = new ReachAiCapabilityTypesHolder(); services.AddSingleton(holder); services.AddSingleton(holder.Types);
        services.AddSingleton(options); services.AddDataProtection(); services.AddAuthorization(); services.TryAddSingleton<IReachAiCredentialStore, ProtectedFileCredentialStore>();
        services.AddSingleton<CapabilityCatalog>(); services.AddScoped<ReachAiInvocationContextAccessor>();
        services.AddSingleton(sp => new ReachAiRegistryClient(sp.GetRequiredService<ReachAiOptions>(), sp.GetRequiredService<IReachAiCredentialStore>(), sp.GetRequiredService<CapabilityCatalog>(), new HttpClient(new HttpClientHandler { AllowAutoRedirect = false }) { Timeout = TimeSpan.FromSeconds(30) }));
        services.AddSingleton<ReachAiEmbedClient>(); services.AddSingleton<ReachAiBusinessIndexClient>(); services.AddHostedService<RegistryHostedService>();
        return new ReachAiBuilder(services, holder);
    }
    public static IEndpointRouteBuilder MapReachAI(this IEndpointRouteBuilder endpoints)
    {
        endpoints.MapPost("/reachai/registry/capabilities/sync", async (HttpContext context, ReachAiRegistryClient registry, CapabilityCatalog catalog) => {
            var headers = context.Request.Headers;
            bool verified;
            try { verified = registry.VerifyCallback(headers["X-ReachAI-App-Key"].ToString(), headers["X-ReachAI-Timestamp"].ToString(), headers["X-ReachAI-Nonce"].ToString(), headers["X-ReachAI-Signature"].ToString()); }
            catch (InvalidOperationException) { verified = false; } // credentials not loaded yet
            if (!verified) return Results.Unauthorized();
            try {
                var response = await registry.SyncAsync(context.RequestAborted, "SDK_CALLBACK");
                return Results.Json(new { instanceId = registry.InstanceId, capabilityCount = catalog.Count, httpApiCount = 0, registryResponse = response.GetRawText() });
            } catch (Exception) { return Results.Json(new { error = "SYNC_FAILED" }, statusCode: 502); }
        }).AllowAnonymous();
        endpoints.MapPost("/reachai/capabilities/{name}/invoke", async (string name, HttpContext context, CapabilityCatalog catalog, ReachAiRegistryClient registry, ReachAiOptions options, ReachAiInvocationContextAccessor accessor) => {
            var handler = catalog.Find(name); if (handler == null) return Results.NotFound();
            ReachAiInvocationContext invocation;
            try { invocation = ReachAiProtocol.VerifyInvocation(context.Request.Headers["X-ReachAI-Invocation-Token"].ToString(), registry.Credentials, options.ProjectCode, name, DateTimeOffset.UtcNow); }
            catch (Exception e) when (e is UnauthorizedAccessException or InvalidOperationException) { return Results.Unauthorized(); }
            var hasBusinessIdentity = !string.IsNullOrWhiteSpace(invocation.ExternalUserId) || !string.IsNullOrWhiteSpace(invocation.GlobalUserId);
            if (handler.Declaration.BusinessIdentityRequired && !hasBusinessIdentity) return Results.Unauthorized();
            if (handler.Declaration.RequiredRoles.Length > 0 && (!hasBusinessIdentity || !invocation.Claims.TryGetProperty("roles", out var roles) || roles.ValueKind != JsonValueKind.Array || !roles.EnumerateArray().Any(role => role.ValueKind == JsonValueKind.String && handler.Declaration.RequiredRoles.Contains(role.GetString(), StringComparer.Ordinal)))) return Results.StatusCode(403);
            object?[] boundArguments;
            ReachBusinessIndexGuard.Scope? scope;
            try {
                // Bind and validate before any host bridge or business code runs: invalid input is a 400,
                // never an ambiguous "execution failed" that a caller might treat as an unknown write outcome.
                if (context.Request.ContentLength > MaxInvocationBodyBytes) return Results.Json(new { error = "INVALID_ARGUMENTS" }, statusCode: 413);
                using var limited = new MemoryStream();
                await CopyLimitedAsync(context.Request.Body, limited, context.RequestAborted);
                var arguments = JsonSerializer.Deserialize<JsonElement>(limited.ToArray(), Wire.Json);
                scope = handler.IsBusinessIndexResolver ? ReachBusinessIndexGuard.RequireScope(invocation, options.ProjectCode, name, arguments) : null;
                boundArguments = CapabilityCatalog.BindArguments(handler, arguments, context.RequestAborted);
            } catch (UnauthorizedAccessException) { return Results.Unauthorized(); }
            catch (Exception e) when (e is JsonException or ArgumentException or KeyNotFoundException or InvalidOperationException or NotSupportedException) { return Results.Json(new { error = "INVALID_ARGUMENTS" }, statusCode: 400); }
            try {
                accessor.Current = invocation;
                var bridges = context.RequestServices.GetServices<IReachAiSecurityContextBridge>().ToArray();
                var invoked = false;
                Task<object?> Execute(int index)
                {
                    if (index < bridges.Length) return bridges[index].InvokeAsync(invocation, () => Execute(index + 1), context.RequestAborted);
                    if (invoked) throw new InvalidOperationException("Capability may only execute once");
                    invoked = true;
                    return CapabilityCatalog.InvokeBoundAsync(handler, context.RequestServices, boundArguments);
                }
                var result = await Execute(0);
                if (!invoked) throw new InvalidOperationException("Security bridge did not invoke the capability");
                if (scope != null) ReachBusinessIndexGuard.Validate(scope, result);
                return Results.Json(result, Wire.Json);
            } catch (Exception) { return Results.Json(new { success = false, code = "CAPABILITY_EXECUTION_FAILED", message = "业务方法执行失败" }, statusCode: 500); }
            finally { accessor.Current = null; }
        }).AllowAnonymous();
        endpoints.MapPost("/reachai/embed/token", async (HttpContext context, ReachAiEmbedRequest input, ReachAiEmbedClient client) => {
            if (string.IsNullOrWhiteSpace(input.AgentId) || string.IsNullOrWhiteSpace(input.PageInstanceId)) return Results.BadRequest();
            var origin = context.Request.Headers.Origin.ToString();
            if (string.IsNullOrWhiteSpace(origin)) origin = input.Origin ?? "";
            else if (!string.IsNullOrWhiteSpace(input.Origin) && !string.Equals(origin, input.Origin, StringComparison.OrdinalIgnoreCase)) return Results.BadRequest();
            if (string.IsNullOrWhiteSpace(origin)) return Results.BadRequest();
            var resolver = context.RequestServices.GetService<IReachAiEmbedPrincipalResolver>();
            if (resolver == null) return Results.StatusCode(503);
            var principal = await resolver.ResolveAsync(context, input.AgentId, context.RequestAborted);
            if (principal == null || string.IsNullOrWhiteSpace(principal.ExternalUserId)) return Results.Unauthorized();
            try { return Results.Json(await client.ExchangeAsync(input with { Origin = origin }, principal, context.RequestAborted), Wire.Json); }
            catch (HttpRequestException ex) when (ex.StatusCode is System.Net.HttpStatusCode.Unauthorized or System.Net.HttpStatusCode.Forbidden) { return Results.StatusCode(403); }
            catch (Exception) { return Results.StatusCode(502); }
        }).RequireAuthorization();
        return endpoints;
    }

    private const int MaxInvocationBodyBytes = 1024 * 1024;
    private static async Task CopyLimitedAsync(Stream source, MemoryStream target, CancellationToken ct)
    {
        var chunk = new byte[8192]; int count;
        while ((count = await source.ReadAsync(chunk, ct)) > 0)
        {
            if (target.Length + count > MaxInvocationBodyBytes) throw new ArgumentException("Invocation body too large");
            target.Write(chunk, 0, count);
        }
    }
}

public sealed class ReachAiEmbedClient(ReachAiRegistryClient registry, ReachAiOptions options)
{
    public async Task<JsonElement> ExchangeAsync(ReachAiEmbedRequest request, ReachAiEmbedPrincipal principal, CancellationToken ct = default)
    {
        var response = await registry.PostAsync("/api/embed/token/exchange", new { projectCode = options.ProjectCode, agentId = request.AgentId, pageKey = request.PageKey,
            pageInstanceId = request.PageInstanceId, route = request.Route, origin = request.Origin,
            principal = new { principal.ExternalUserId, principal.GlobalUserId, principal.UserName, roles = principal.Roles ?? [], attributes = principal.Attributes ?? new() },
            businessSessionId = principal.BusinessSessionId, businessSessionExpiresAt = principal.BusinessSessionExpiresAt }, false, true, ct);
        if (response.TryGetProperty("code", out var code) && code.ToString() != "200") throw new InvalidOperationException("Embed exchange rejected");
        var payload = response.TryGetProperty("data", out var data) && data.ValueKind == JsonValueKind.Object ? data : response;
        if (!string.IsNullOrWhiteSpace(principal.BusinessSessionId) && (!payload.TryGetProperty("businessSessionBound", out var bound) || bound.ValueKind != JsonValueKind.True)) throw new InvalidOperationException("Business session binding not confirmed");
        if (principal.BusinessSessionExpiresAt != null && (!payload.TryGetProperty("businessSessionExpiryApplied", out var expiry) || expiry.ValueKind != JsonValueKind.True)) throw new InvalidOperationException("Business session expiry not confirmed");
        if (!payload.TryGetProperty("token", out var token) || string.IsNullOrWhiteSpace(token.GetString()) || !payload.TryGetProperty("expiresIn", out var ttl) || ttl.GetInt64() <= 0) throw new InvalidOperationException("Invalid Embed response");
        return payload.Clone();
    }
    public async Task RevokeBusinessSessionAsync(string businessSessionId, CancellationToken ct = default)
    {
        if (string.IsNullOrWhiteSpace(businessSessionId)) throw new ArgumentException("Business session required");
        var response = await registry.PostAsync("/api/embed/token/business-session/revoke", new { projectCode = options.ProjectCode, businessSessionId }, false, true, ct);
        if (!response.TryGetProperty("code", out var code) || code.ToString() != "200") throw new InvalidOperationException("Business session revocation rejected");
    }
}
