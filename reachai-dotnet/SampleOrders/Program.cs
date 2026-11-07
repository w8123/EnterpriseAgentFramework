using System.Security.Claims;
using System.Security.Cryptography;
using System.Text;
using Microsoft.AspNetCore.Authentication;
using Microsoft.AspNetCore.Authentication.Cookies;
using ReachAI.AspNetCore;

var builder = WebApplication.CreateBuilder(args);
builder.Services.AddAuthentication(CookieAuthenticationDefaults.AuthenticationScheme).AddCookie(options => {
    options.Events.OnRedirectToLogin = context => { context.Response.StatusCode = 401; return Task.CompletedTask; };
    options.Events.OnRedirectToAccessDenied = context => { context.Response.StatusCode = 403; return Task.CompletedTask; };
});
builder.Services.AddReachAI(builder.Configuration.GetSection("ReachAI")).AddCapabilitiesFrom<OrderCapabilities>();
builder.Services.AddScoped<IReachAiEmbedPrincipalResolver, SamplePrincipalResolver>();
builder.Services.AddScoped<IReachAiSecurityContextBridge, SampleIdentityBridge>();
builder.Services.AddSingleton<OrderStore>();
builder.Services.AddSingleton<RevocationQueue>();
builder.Services.AddHostedService(sp => sp.GetRequiredService<RevocationQueue>());
var app = builder.Build();
app.UseAuthentication(); app.UseAuthorization();
app.MapPost("/demo/login", async (Login input, HttpContext http, IConfiguration config) => {
    var expected = config["REACHAI_DEMO_PASSWORD"];
    if (string.IsNullOrWhiteSpace(expected) || !CryptographicOperations.FixedTimeEquals(Encoding.UTF8.GetBytes(expected), Encoding.UTF8.GetBytes(input.Password ?? ""))) return Results.Unauthorized();
    var identity = input.User switch { "alice" => ("tenant-a", "sales"), "bob" => ("tenant-b", "manager"), _ => default };
    if (identity == default) return Results.Unauthorized();
    var session = Guid.NewGuid().ToString("N"); var expires = DateTimeOffset.UtcNow.AddHours(1);
    var claims = new[] { new Claim(ClaimTypes.NameIdentifier, input.User!), new Claim(ClaimTypes.Name, input.User!), new Claim(ClaimTypes.Role, identity.Item2), new Claim("tenant", identity.Item1), new Claim("businessSessionId", session), new Claim("businessSessionExpiry", expires.ToUnixTimeSeconds().ToString()) };
    await http.SignInAsync(CookieAuthenticationDefaults.AuthenticationScheme, new ClaimsPrincipal(new ClaimsIdentity(claims, CookieAuthenticationDefaults.AuthenticationScheme)), new AuthenticationProperties { ExpiresUtc = expires, IsPersistent = false });
    return Results.Ok(new { user = input.User, tenant = identity.Item1, role = identity.Item2 });
});
app.MapPost("/demo/logout", async (HttpContext context, ReachAiEmbedClient client, RevocationQueue queue) => {
    var session = context.User.FindFirstValue("businessSessionId");
    await context.SignOutAsync(CookieAuthenticationDefaults.AuthenticationScheme);
    if (!string.IsNullOrWhiteSpace(session)) { try { await client.RevokeBusinessSessionAsync(session, context.RequestAborted); } catch { queue.Add(session); return Results.StatusCode(502); } }
    return Results.Ok();
}).RequireAuthorization();
app.MapGet("/demo/me", (ClaimsPrincipal user) => Results.Ok(new { user = user.Identity?.Name, tenant = user.FindFirstValue("tenant"), role = user.FindFirstValue(ClaimTypes.Role) })).RequireAuthorization();
app.MapReachAI();
app.Run();

public sealed record Login(string? User, string? Password);
public sealed record OrderView(string Id, string Tenant, string Status, decimal Amount);
public sealed class OrderStore
{
    private readonly Dictionary<string, OrderView> orders = new() { ["A-100"] = new("A-100", "tenant-a", "OPEN", 18.5m), ["B-100"] = new("B-100", "tenant-b", "OPEN", 26m) };
    public OrderView Read(string id, string tenant) { lock (orders) return orders.TryGetValue(id, out var order) && order.Tenant == tenant ? order : throw new UnauthorizedAccessException(); }
    public OrderView Complete(string id, string tenant) { lock (orders) { var order = Read(id, tenant); return orders[id] = order with { Status = "COMPLETED" }; } }
}
public sealed class OrderCapabilities(OrderStore orders, ReachAiInvocationContextAccessor access)
{
    [ReachCapability("order.query", Title = "查询订单", Description = "查询当前员工所在租户的订单", SideEffect = ReachSideEffect.Read, BusinessIdentityRequired = true)]
    public OrderView Query([ReachParam("orderId")] string orderId) => orders.Read(orderId, access.Current?.TenantId ?? throw new UnauthorizedAccessException());
    [ReachCapability("order.complete", Title = "完成订单", Description = "将当前租户的订单标记为完成", SideEffect = ReachSideEffect.Write, BusinessIdentityRequired = true, RequiredRoles = new[] { "manager" })]
    public OrderView Complete([ReachParam("orderId")] string orderId)
    {
        var context = access.Current ?? throw new UnauthorizedAccessException();
        if ((context.ExternalUserId ?? context.GlobalUserId) != "bob" || context.TenantId != "tenant-b") throw new UnauthorizedAccessException();
        return orders.Complete(orderId, context.TenantId ?? throw new UnauthorizedAccessException());
    }
}
public sealed class SampleIdentityBridge : IReachAiSecurityContextBridge
{
    public async Task<object?> InvokeAsync(ReachAiInvocationContext context, Func<Task<object?>> next, CancellationToken cancellationToken)
    {
        var user = context.ExternalUserId ?? context.GlobalUserId;
        if (!((user == "alice" && context.TenantId == "tenant-a") || (user == "bob" && context.TenantId == "tenant-b"))) throw new UnauthorizedAccessException();
        await Task.Yield(); cancellationToken.ThrowIfCancellationRequested(); return await next();
    }
}
public sealed class RevocationQueue(ReachAiEmbedClient client) : BackgroundService
{
    private readonly System.Threading.Channels.Channel<string> pending = System.Threading.Channels.Channel.CreateUnbounded<string>();
    public void Add(string session) => pending.Writer.TryWrite(session);
    protected override async Task ExecuteAsync(CancellationToken stoppingToken)
    {
        await foreach (var session in pending.Reader.ReadAllAsync(stoppingToken))
        {
            while (!stoppingToken.IsCancellationRequested)
            {
                try { await client.RevokeBusinessSessionAsync(session, stoppingToken); break; }
                catch (OperationCanceledException) when (stoppingToken.IsCancellationRequested) { return; }
                catch { await Task.Delay(TimeSpan.FromSeconds(5), stoppingToken); }
            }
        }
    }
}
public sealed class SamplePrincipalResolver : IReachAiEmbedPrincipalResolver
{
    public Task<ReachAiEmbedPrincipal?> ResolveAsync(HttpContext context, string agentId, CancellationToken cancellationToken)
    {
        var user = context.User;
        if (user.Identity?.IsAuthenticated != true) return Task.FromResult<ReachAiEmbedPrincipal?>(null);
        var result = new ReachAiEmbedPrincipal(user.FindFirstValue(ClaimTypes.NameIdentifier)!, UserName: user.Identity.Name,
            Roles: user.FindAll(ClaimTypes.Role).Select(c => c.Value).ToArray(), Attributes: new() { ["tenantId"] = user.FindFirstValue("tenant") },
            BusinessSessionId: user.FindFirstValue("businessSessionId"), BusinessSessionExpiresAt: long.Parse(user.FindFirstValue("businessSessionExpiry")!));
        return Task.FromResult<ReachAiEmbedPrincipal?>(result);
    }
}
