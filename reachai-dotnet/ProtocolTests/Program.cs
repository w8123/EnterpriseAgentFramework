using System.Text;
using System.Text.Json;
using ReachAI.AspNetCore;
using Microsoft.Extensions.DependencyInjection;

var file = args.Length > 0 ? args[0] : Path.GetFullPath(Path.Combine(AppContext.BaseDirectory, "../../../../../ai-runtime-contract/src/main/resources/contracts/reachai-sdk-protocol-v1.vectors.json"));
using var document = JsonDocument.Parse(File.ReadAllBytes(file));
string Get(JsonElement element, string key) => element.GetProperty(key).GetString()!;
void Assert(bool value, string message) { if (!value) throw new Exception(message); }
foreach (var vector in document.RootElement.GetProperty("registry").EnumerateArray())
    Assert(ReachAiProtocol.RegistrySignature(Get(vector, "projectCode"), Get(vector, "timestamp"), Get(vector, "nonce"), Get(vector, "appSecret")) == Get(vector, "signature"), "registry vector mismatch");
foreach (var vector in document.RootElement.GetProperty("projectRequest").EnumerateArray())
    Assert(ReachAiProtocol.BodySignature(Get(vector, "method"), Get(vector, "path"), Get(vector, "projectCode"), Get(vector, "appKey"), Get(vector, "timestamp"), Get(vector, "nonce"), Encoding.UTF8.GetBytes(Get(vector, "body")), Get(vector, "appSecret")) == Get(vector, "signature"), "body vector mismatch");
foreach (var vector in document.RootElement.GetProperty("invocation").EnumerateArray())
{
    var claims = vector.GetProperty("claims"); var credentials = new ReachAiCredentials(Get(claims, "appKey"), Get(vector, "appSecret")); var now = DateTimeOffset.FromUnixTimeMilliseconds(vector.GetProperty("issuedAtMillis").GetInt64());
    var context = ReachAiProtocol.VerifyInvocation(Get(vector, "token"), credentials, Get(claims, "projectCode"), Get(claims, "capabilityName"), now);
    Assert(context.ExternalUserId == Get(claims, "externalUserId"), "identity mismatch");
    try { ReachAiProtocol.VerifyInvocation(Get(vector, "token"), credentials, Get(claims, "projectCode"), "other", now); throw new Exception("wrong capability accepted"); } catch (UnauthorizedAccessException) { }
    try { ReachAiProtocol.VerifyInvocation(Get(vector, "token") + "A", credentials, Get(claims, "projectCode"), Get(claims, "capabilityName"), now); throw new Exception("tampered token accepted"); } catch (UnauthorizedAccessException) { }
    try { ReachAiProtocol.VerifyInvocation(Get(vector, "token"), credentials, Get(claims, "projectCode"), Get(claims, "capabilityName"), now.AddMinutes(2)); throw new Exception("expired token accepted"); } catch (UnauthorizedAccessException) { }
}
var types = new ReachAiCapabilityTypes(); types.Types.Add(typeof(TestCapabilities));
var catalog = new CapabilityCatalog(types, new ReachAiOptions { BaseUrl = "https://orders.example" });
Assert(catalog.Count == 1, "capability discovery failed");
var receipt = JsonSerializer.SerializeToElement(catalog.Registrations(), new JsonSerializerOptions(JsonSerializerDefaults.Web))[0];
var parameter = receipt.GetProperty("parameters")[0];
Assert(parameter.GetProperty("children").GetArrayLength() == 2, "DTO fields missing");
Assert(receipt.GetProperty("metadata").GetProperty("resultSchema").GetProperty("properties").TryGetProperty("result", out _), "output schema missing");
var services = new ServiceCollection().AddScoped<TestCapabilities>().BuildServiceProvider();
await using var scope = services.CreateAsyncScope();
var handler = catalog.Find("test.dto")!;
var flat = JsonSerializer.SerializeToElement(new { number = "10", active = "true" });
var wrapped = JsonSerializer.SerializeToElement(new { request = new { number = 10, active = true } });
Assert(((TestResult)(await CapabilityCatalog.InvokeAsync(handler, scope.ServiceProvider, flat, default))!).Result == 11, "flat DTO binding failed");
Assert(((TestResult)(await CapabilityCatalog.InvokeAsync(handler, scope.ServiceProvider, wrapped, default))!).Result == 11, "wrapped DTO binding failed");
foreach (var invalid in new[] { JsonSerializer.SerializeToElement(new { request = new { number = 1, active = true }, number = 1 }), JsonSerializer.SerializeToElement(new { number = 1, active = true, other = 1 }), JsonSerializer.SerializeToElement(new { number = (int?)null, active = true }) })
{
    try { await CapabilityCatalog.InvokeAsync(handler, scope.ServiceProvider, invalid, default); throw new Exception("invalid DTO accepted"); } catch (ArgumentException) { }
}
var signedClaims = JsonSerializer.SerializeToElement(new { projectCode = "orders", capabilityName = "order.resolve", tenantId = "tenant-a", externalUserId = "alice" });
var signedContext = new ReachAiInvocationContext(signedClaims);
var indexArguments = JsonSerializer.SerializeToElement(new { resourceType = "order", resourceId = "A-100", expectedSourceVersion = "v1" });
var indexScope = ReachBusinessIndexGuard.RequireScope(signedContext, "orders", "order.resolve", indexArguments);
var authorizedRow = ReachBusinessIndexAccessScope.AuthorizeRow("tenant-a", "tenant-a", "alice", "orders", "order", "A-100", "A-100", true);
ReachBusinessIndexGuard.Validate(indexScope, ReachBusinessIndexResolution.CreateAuthorized(authorizedRow, "orders", "v2", new Dictionary<string, object?> { ["status"] = "open" }));
try { ReachBusinessIndexAccessScope.AuthorizeRow("tenant-a", "tenant-b", "alice", "orders", "order", "A-100", "A-100", true); throw new Exception("cross-tenant row accepted"); } catch (UnauthorizedAccessException) { }
try { ReachBusinessIndexAccessScope.AuthorizeRow("tenant-a", "tenant-a", "alice", "orders", "order", "A-100", "A-101", true); throw new Exception("different object accepted"); } catch (UnauthorizedAccessException) { }
try { ReachBusinessIndexAccessScope.AuthorizeRow("tenant-a", "tenant-a", "alice", "orders", "order", "A-100", "A-100", false); throw new Exception("unauthorized row accepted"); } catch (UnauthorizedAccessException) { }
var other = ReachBusinessIndexAccessScope.AuthorizeRow("tenant-a", "tenant-a", "alice", "orders", "order", "A-101", "A-101", true);
try { ReachBusinessIndexGuard.Validate(indexScope, ReachBusinessIndexResolution.CreateAuthorized(other, "orders", "v2", new Dictionary<string, object?>())); throw new Exception("wrong resolution accepted"); } catch (UnauthorizedAccessException) { }
var otherUser = ReachBusinessIndexAccessScope.AuthorizeRow("tenant-a", "tenant-a", "bob", "orders", "order", "A-100", "A-100", true);
try { ReachBusinessIndexGuard.Validate(indexScope, ReachBusinessIndexResolution.CreateAuthorized(otherUser, "orders", "v2", new Dictionary<string, object?>())); throw new Exception("resolution authorized for another user accepted"); } catch (UnauthorizedAccessException) { }
var resolverTypes = new ReachAiCapabilityTypes(); resolverTypes.Types.Add(typeof(TestResolver));
var resolverCatalog = new CapabilityCatalog(resolverTypes, new ReachAiOptions { BaseUrl = "https://orders.example" });
var resolverDeclaration = JsonSerializer.SerializeToElement(resolverCatalog.Registrations(), new JsonSerializerOptions(JsonSerializerDefaults.Web))[0];
Assert(resolverDeclaration.GetProperty("metadata").GetProperty("tags")[0].GetString() == "business-index-resolver", "resolver tag missing");
var resolverServices = new ServiceCollection().AddScoped<TestResolver>().BuildServiceProvider();
await using var resolverServiceScope = resolverServices.CreateAsyncScope();
var resolution = await CapabilityCatalog.InvokeAsync(resolverCatalog.Find("order.resolve")!, resolverServiceScope.ServiceProvider, indexArguments, default);
ReachBusinessIndexGuard.Validate(indexScope, resolution);
var options = new ReachAiOptions { RegistryUrl = "https://example.test", ProjectCode = "orders", BaseUrl = "https://orders.example", AppKey = "key", AppSecret = "secret" };
foreach (var invalidCode in new[] { "Orders", " orders", "orders ", "orders--test", "-orders", "orders-", "orders.test", "中文", "orders\n" })
{
    var unissued = new ReachAiOptions { RegistryUrl = "https://example.test", ProjectCode = invalidCode,
        BaseUrl = "https://orders.example", EnrollmentToken = "one-time-token" };
    var noRequest = new CaptureHandler();
    var candidate = new ReachAiRegistryClient(unissued, new TestStore(), catalog, new HttpClient(noRequest));
    try { await candidate.RegisterAsync(); throw new Exception("non-canonical enrollment project accepted"); }
    catch (InvalidOperationException) { }
    Assert(noRequest.LastPath is null, "invalid enrollment project consumed a remote request");
}
ReachAiRegistryClient.RequireCanonicalProjectCode("orders_12-a");
var enrollmentOptions = new ReachAiOptions { RegistryUrl = "https://example.test", ProjectCode = "orders",
    BaseUrl = "https://orders.example", EnrollmentToken = "one-time-token" };
var enrollmentTransport = new EnrollmentHandler();
var recoverableStore = new FlakyStore();
var enrollmentRegistry = new ReachAiRegistryClient(enrollmentOptions, recoverableStore, catalog, new HttpClient(enrollmentTransport));
try { await enrollmentRegistry.RegisterAsync(); throw new Exception("credential persistence failure ignored"); }
catch (IOException) { }
Assert(enrollmentTransport.EnrollmentCalls == 1 && recoverableStore.SaveCalls == 1, "first enrollment must run exactly once");
await enrollmentRegistry.RegisterAsync();
Assert(enrollmentTransport.EnrollmentCalls == 1 && enrollmentTransport.SignedCalls == 1 && recoverableStore.SaveCalls == 2,
    "retry must persist the pending credential before signed registration, never replay enrollment");
var reloadedOptions = new ReachAiOptions { RegistryUrl = "https://example.test", ProjectCode = "orders",
    BaseUrl = "https://orders.example", EnrollmentToken = "obsolete-one-time-token" };
var reloaded = new ReachAiRegistryClient(reloadedOptions, recoverableStore, catalog, new HttpClient(enrollmentTransport));
await reloaded.RegisterAsync();
Assert(enrollmentTransport.EnrollmentCalls == 1 && enrollmentTransport.SignedCalls == 2, "stored credentials not recovered after restart");
var lostTransport = new EnrollmentHandler { LoseEnrollmentResponse = true };
var lostOptions = new ReachAiOptions { RegistryUrl = "https://example.test", ProjectCode = "orders",
    BaseUrl = "https://orders.example", EnrollmentToken = "one-time-token" };
var lostRegistry = new ReachAiRegistryClient(lostOptions, new TestStore(), catalog, new HttpClient(lostTransport));
try { await lostRegistry.RegisterAsync(); throw new Exception("lost response ignored"); } catch (HttpRequestException) { }
try { await lostRegistry.RegisterAsync(); throw new Exception("uncertain enrollment retried"); } catch (InvalidOperationException) { }
Assert(lostTransport.EnrollmentCalls == 1, "uncertain enrollment was sent again");
var transport = new CaptureHandler();
var registry = new ReachAiRegistryClient(options, new TestStore(), catalog, new HttpClient(transport));
await registry.RegisterAsync();
await new ReachAiBusinessIndexClient(registry, options).UpsertAsync("orders_index", new { bizId = "A-100", text = "中文" });
Assert(transport.LastPath == "/api/knowledge-ingress/projects/orders/biz-index/orders_index/upsert", "index path mismatch");
Assert(transport.BodySignatureValid, "index body signature mismatch");
var peer = new ReachAiRegistryClient(options, new TestStore(), catalog, new HttpClient(new CaptureHandler()));
Assert(registry.InstanceId == registry.InstanceId && registry.InstanceId != peer.InstanceId, "generated instances must be stable and unique");
var fixedOptions = new ReachAiOptions { InstanceId = " explicitly-configured " };
var fixedInstance = new ReachAiRegistryClient(fixedOptions, new TestStore(), catalog, new HttpClient(new CaptureHandler()));
Assert(fixedInstance.InstanceId == "explicitly-configured", "explicit instance id ignored");

var absentCatalog = new CapabilityCatalog(new ReachAiCapabilityTypes(), options);
var absentTransport = new CaptureHandler();
var absentRegistry = new ReachAiRegistryClient(options, new TestStore(), absentCatalog, new HttpClient(absentTransport));
await absentRegistry.RegisterAsync();
try { await absentRegistry.SyncAsync(); throw new Exception("unobserved inventory sent"); } catch (InvalidOperationException) { }
Assert(absentTransport.LastPath == "/api/registry/projects/register", "unobserved inventory reached platform");
var emptyTypes = new ReachAiCapabilityTypes(); emptyTypes.Types.Add(typeof(EmptyCapabilities));
var emptyCatalog = new CapabilityCatalog(emptyTypes, options);
var emptyTransport = new CaptureHandler();
var emptyRegistry = new ReachAiRegistryClient(options, new TestStore(), emptyCatalog, new HttpClient(emptyTransport));
await emptyRegistry.RegisterAsync();
await emptyRegistry.SyncAsync();
using (var emptyBody = JsonDocument.Parse(emptyTransport.LastBody!))
{
    Assert(emptyBody.RootElement.GetProperty("capabilities").GetArrayLength() == 0, "complete empty inventory missing");
    Assert(!emptyBody.RootElement.TryGetProperty("httpApis", out _), "unsupported HTTP inventory must be omitted");
}
foreach (var invalidType in new[] { typeof(DuplicateCapabilities), typeof(DictionaryInput), typeof(ReadOnlyDictionaryInput), typeof(DictionaryOutput), typeof(ResolverWithWrongRequestName), typeof(OptionalNonNullReference), typeof(OptionalNonNullValue) })
{
    var invalidTypes = new ReachAiCapabilityTypes(); invalidTypes.Types.Add(invalidType);
    try { _ = new CapabilityCatalog(invalidTypes, options); throw new Exception($"invalid capability type accepted: {invalidType}"); }
    catch (InvalidOperationException) { }
}
var voidTypes = new ReachAiCapabilityTypes(); voidTypes.Types.Add(typeof(VoidCapabilities));
var voidCatalog = new CapabilityCatalog(voidTypes, options);
var voidServices = new ServiceCollection().AddScoped<VoidCapabilities>().BuildServiceProvider();
await using var voidScope = voidServices.CreateAsyncScope();
foreach (var name in new[] { "void.completed", "void.async" })
    Assert(await CapabilityCatalog.InvokeAsync(voidCatalog.Find(name)!, voidScope.ServiceProvider, JsonSerializer.SerializeToElement(new { }), default) == null, $"{name} returned non-null");
var optionalTypes = new ReachAiCapabilityTypes(); optionalTypes.Types.Add(typeof(ValidOptionalCapabilities));
var optionalCatalog = new CapabilityCatalog(optionalTypes, options);
var optionalServices = new ServiceCollection().AddScoped<ValidOptionalCapabilities>().BuildServiceProvider();
await using var optionalScope = optionalServices.CreateAsyncScope();
var emptyArguments = JsonSerializer.SerializeToElement(new { });
Assert((string?)await CapabilityCatalog.InvokeAsync(optionalCatalog.Find("optional.default")!, optionalScope.ServiceProvider, emptyArguments, default) == "default", "optional default not applied");
Assert((string?)await CapabilityCatalog.InvokeAsync(optionalCatalog.Find("optional.nullable")!, optionalScope.ServiceProvider, emptyArguments, default) == "missing", "optional nullable not applied");
try { await CapabilityCatalog.InvokeAsync(optionalCatalog.Find("required.default")!, optionalScope.ServiceProvider, emptyArguments, default); throw new Exception("required parameter was omitted"); }
catch (ArgumentException) { }
Console.WriteLine("Shared protocol vectors and capability contracts passed");

public sealed record TestRequest(int Number, bool Active);
public sealed record TestResult(int Result);
public sealed class TestCapabilities
{
    [ReachCapability("test.dto", SideEffect = ReachSideEffect.Read)]
    public async Task<TestResult> Execute([ReachParam("request")] TestRequest request, CancellationToken cancellationToken)
    { await Task.Yield(); cancellationToken.ThrowIfCancellationRequested(); return new TestResult(request.Number + (request.Active ? 1 : 0)); }
}
public sealed class EmptyCapabilities { }
public sealed class DuplicateCapabilities
{
    [ReachCapability("duplicate")] public void One() { }
    [ReachCapability("duplicate")] public void Two() { }
}
public sealed class DictionaryInput
{
    [ReachCapability("dictionary.input")] public void Execute(Dictionary<string, string> input) { }
}
public sealed class ReadOnlyDictionaryInput
{
    [ReachCapability("readonly-dictionary.input")] public void Execute(IReadOnlyDictionary<string, string> input) { }
}
public sealed class DictionaryOutput
{
    [ReachCapability("dictionary.output")] public IDictionary<string, string> Execute() => new Dictionary<string, string>();
}
public sealed class OptionalNonNullReference
{
    [ReachCapability("invalid.reference")] public void Execute([ReachParam("value", Required = false)] string value) { }
}
public sealed class OptionalNonNullValue
{
    [ReachCapability("invalid.value")] public void Execute([ReachParam("value", Required = false)] int value) { }
}
public sealed class ValidOptionalCapabilities
{
    [ReachCapability("optional.default")] public string Default([ReachParam("value", Required = false)] string value = "default") => value;
    [ReachCapability("optional.nullable")] public string Nullable([ReachParam("value", Required = false)] string? value) => value ?? "missing";
    [ReachCapability("required.default")] public string Required([ReachParam("value", Required = true)] string value = "default") => value;
}
public sealed class VoidCapabilities
{
    [ReachCapability("void.completed")] public Task Completed() => Task.CompletedTask;
    [ReachCapability("void.async")] public async Task Async() { await Task.Yield(); }
}
public sealed class TestStore : IReachAiCredentialStore
{
    public Task<ReachAiCredentials?> LoadAsync(CancellationToken ct) => Task.FromResult<ReachAiCredentials?>(null);
    public Task SaveAsync(ReachAiCredentials credentials, CancellationToken ct) => Task.CompletedTask;
}
public sealed class FlakyStore : IReachAiCredentialStore
{
    public int SaveCalls { get; private set; }
    private ReachAiCredentials? saved;
    public Task<ReachAiCredentials?> LoadAsync(CancellationToken ct) => Task.FromResult(saved);
    public Task SaveAsync(ReachAiCredentials credentials, CancellationToken ct)
    {
        SaveCalls++;
        if (SaveCalls == 1) throw new IOException("simulated credential store failure");
        saved = credentials;
        return Task.CompletedTask;
    }
}
public sealed class EnrollmentHandler : HttpMessageHandler
{
    public bool LoseEnrollmentResponse { get; init; }
    public int EnrollmentCalls { get; private set; }
    public int SignedCalls { get; private set; }
    protected override Task<HttpResponseMessage> SendAsync(HttpRequestMessage request, CancellationToken ct)
    {
        if (request.Headers.Contains("X-ReachAI-Registry-Enrollment-Token"))
        {
            EnrollmentCalls++;
            if (LoseEnrollmentResponse) throw new HttpRequestException("simulated lost response");
            return Task.FromResult(new HttpResponseMessage(System.Net.HttpStatusCode.OK)
            { Content = new StringContent("{\"projectCode\":\"orders\",\"appKey\":\"key\",\"appSecret\":\"secret\"}") });
        }
        SignedCalls++;
        return Task.FromResult(new HttpResponseMessage(System.Net.HttpStatusCode.OK) { Content = new StringContent("{}") });
    }
}
public sealed class TestResolver
{
    [ReachBusinessIndexResolver]
    [ReachCapability("order.resolve", SideEffect = ReachSideEffect.Read, BusinessIdentityRequired = true)]
    public ReachBusinessIndexResolution Resolve([ReachParam("request")] ReachBusinessIndexResolverRequest request)
    {
        var scope = ReachBusinessIndexAccessScope.AuthorizeRow("tenant-a", "tenant-a", "alice", "orders", request.ResourceType, request.ResourceId, "A-100", true);
        return ReachBusinessIndexResolution.CreateAuthorized(scope, "orders", "v2", new Dictionary<string, object?> { ["status"] = "open" });
    }
}
public sealed class ResolverWithWrongRequestName
{
    [ReachBusinessIndexResolver]
    [ReachCapability("bad.resolve", SideEffect = ReachSideEffect.Read, BusinessIdentityRequired = true)]
    public ReachBusinessIndexResolution Resolve([ReachParam("input")] ReachBusinessIndexResolverRequest input)
        => throw new InvalidOperationException("must never be called");
}
public sealed class CaptureHandler : HttpMessageHandler
{
    public string? LastPath { get; private set; }
    public byte[]? LastBody { get; private set; }
    public bool BodySignatureValid { get; private set; }
    protected override async Task<HttpResponseMessage> SendAsync(HttpRequestMessage request, CancellationToken ct)
    {
        LastPath = request.RequestUri!.AbsolutePath;
        LastBody = await request.Content!.ReadAsByteArrayAsync(ct);
        if (LastPath.Contains("knowledge-ingress"))
        {
            var body = LastBody;
            var timestamp = request.Headers.GetValues("X-ReachAI-Timestamp").Single(); var nonce = request.Headers.GetValues("X-ReachAI-Nonce").Single();
            BodySignatureValid = request.Headers.GetValues("X-ReachAI-Signature").Single() == ReachAiProtocol.BodySignature("POST", LastPath, "orders", "key", timestamp, nonce, body, "secret");
        }
        if (LastPath.EndsWith("/capabilities/sync"))
        {
            using var sent = JsonDocument.Parse(LastBody);
            var count = sent.RootElement.GetProperty("capabilities").GetArrayLength();
            return new HttpResponseMessage(System.Net.HttpStatusCode.OK) { Content = new StringContent("{\"code\":200,\"data\":{\"projectCode\":\"orders\",\"syncId\":\"snapshot-1\",\"received\":" + count + "}}") };
        }
        return new HttpResponseMessage(System.Net.HttpStatusCode.OK) { Content = new StringContent("{}") };
    }
}
