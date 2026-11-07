using System.Text.Json;
using System.Text.Json.Serialization;
using Microsoft.AspNetCore.Http;

namespace ReachAI.AspNetCore;

public sealed class ReachAiOptions
{
    public string RegistryUrl { get; set; } = "";
    public string ProjectCode { get; set; } = "";
    public string ProjectName { get; set; } = "";
    public string BaseUrl { get; set; } = "";
    public string ContextPath { get; set; } = "";
    public string Environment { get; set; } = "default";
    public string InstanceId { get; set; } = "";
    public string EnrollmentToken { get; set; } = "";
    public string AppKey { get; set; } = "";
    public string AppSecret { get; set; } = "";
    public string CredentialFilePath { get; set; } = "";
    public int HeartbeatSeconds { get; set; } = 30;
}

public enum ReachSideEffect { Read, Write, IdempotentWrite, Irreversible }

[AttributeUsage(AttributeTargets.Method)]
public sealed class ReachCapabilityAttribute(string name) : Attribute
{
    public string Name { get; } = name;
    public string Title { get; set; } = "";
    public string Description { get; set; } = "";
    public ReachSideEffect SideEffect { get; set; } = ReachSideEffect.Write;
    public bool BusinessIdentityRequired { get; set; }
    public string[] RequiredRoles { get; set; } = [];
}

[AttributeUsage(AttributeTargets.Parameter | AttributeTargets.Property)]
public sealed class ReachParamAttribute(string name) : Attribute
{
    public string Name { get; } = name;
    public string Description { get; set; } = "";
    public bool Required { get; set; } = true;
}

public sealed record ReachAiCredentials(string AppKey, string AppSecret);
public interface IReachAiCredentialStore
{
    Task<ReachAiCredentials?> LoadAsync(CancellationToken cancellationToken);
    Task SaveAsync(ReachAiCredentials credentials, CancellationToken cancellationToken);
}

public sealed record ReachAiInvocationContext(JsonElement Claims)
{
    public string? ExternalUserId => Text("externalUserId");
    public string? GlobalUserId => Text("globalUserId");
    public string? TenantId => Text("tenantId");
    public string? TraceId => Text("traceId");
    private string? Text(string name) => Claims.TryGetProperty(name, out var value) && value.ValueKind == JsonValueKind.String ? value.GetString() : null;
}

public sealed class ReachAiInvocationContextAccessor
{
    public ReachAiInvocationContext? Current { get; internal set; }
}

public interface IReachAiSecurityContextBridge
{
    Task<object?> InvokeAsync(ReachAiInvocationContext context, Func<Task<object?>> next, CancellationToken cancellationToken);
}

public sealed record ReachAiEmbedPrincipal(string ExternalUserId, string? GlobalUserId = null,
    string? UserName = null, string[]? Roles = null, Dictionary<string, object?>? Attributes = null,
    string? BusinessSessionId = null, long? BusinessSessionExpiresAt = null);
public interface IReachAiEmbedPrincipalResolver
{
    Task<ReachAiEmbedPrincipal?> ResolveAsync(HttpContext context, string agentId, CancellationToken cancellationToken);
}
public sealed record ReachAiEmbedRequest(string AgentId, string? PageKey, string PageInstanceId, string? Route, string? Origin);

internal static class Wire
{
    public static readonly JsonSerializerOptions Json = new(JsonSerializerDefaults.Web)
    { DefaultIgnoreCondition = JsonIgnoreCondition.Never, NumberHandling = JsonNumberHandling.AllowReadingFromString, Converters = { new BooleanConverter(), new NumericEnumConverterFactory() } };
    private sealed class NumericEnumConverterFactory : JsonConverterFactory
    {
        public override bool CanConvert(Type typeToConvert) => typeToConvert.IsEnum;
        public override JsonConverter CreateConverter(Type typeToConvert, JsonSerializerOptions options) => (JsonConverter)Activator.CreateInstance(typeof(NumericEnumConverter<>).MakeGenericType(typeToConvert))!;
    }
    private sealed class NumericEnumConverter<T> : JsonConverter<T> where T : struct, Enum
    {
        public override T Read(ref Utf8JsonReader reader, Type typeToConvert, JsonSerializerOptions options)
        {
            long value;
            if (reader.TokenType == JsonTokenType.Number && reader.TryGetInt64(out value) || reader.TokenType == JsonTokenType.String && long.TryParse(reader.GetString(), System.Globalization.NumberStyles.AllowLeadingSign, System.Globalization.CultureInfo.InvariantCulture, out value)) return (T)Enum.ToObject(typeof(T), value);
            throw new JsonException("Invalid enum");
        }
        public override void Write(Utf8JsonWriter writer, T value, JsonSerializerOptions options) => writer.WriteNumberValue(Convert.ToInt64(value));
    }
    private sealed class BooleanConverter : JsonConverter<bool>
    {
        public override bool Read(ref Utf8JsonReader reader, Type typeToConvert, JsonSerializerOptions options)
        {
            if (reader.TokenType == JsonTokenType.True) return true;
            if (reader.TokenType == JsonTokenType.False) return false;
            if (reader.TokenType == JsonTokenType.String && bool.TryParse(reader.GetString(), out var value)) return value;
            throw new JsonException("Invalid boolean");
        }
        public override void Write(Utf8JsonWriter writer, bool value, JsonSerializerOptions options) => writer.WriteBooleanValue(value);
    }
}
