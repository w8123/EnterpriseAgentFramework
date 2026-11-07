using System.Collections;
using System.Reflection;
using System.Text.Json;
using System.Text.Json.Serialization;
using System.Text.RegularExpressions;
using Microsoft.Extensions.DependencyInjection;

namespace ReachAI.AspNetCore;

internal sealed record CapabilityHandler(Type ServiceType, MethodInfo Method, ReachCapabilityAttribute Declaration)
{
    public bool IsBusinessIndexResolver => Method.GetCustomAttribute<ReachBusinessIndexResolverAttribute>() != null || UnwrappedResult == typeof(ReachBusinessIndexResolution) || Method.GetParameters().Any(p => p.ParameterType == typeof(ReachBusinessIndexResolverRequest));
    private Type UnwrappedResult => Method.ReturnType.IsGenericType && (Method.ReturnType.GetGenericTypeDefinition() == typeof(Task<>) || Method.ReturnType.GetGenericTypeDefinition() == typeof(ValueTask<>)) ? Method.ReturnType.GetGenericArguments()[0] : Method.ReturnType;
}
internal sealed class ReachAiCapabilityTypes { public List<Type> Types { get; } = []; }

internal sealed class CapabilityCatalog
{
    private static readonly NullabilityInfoContext Nullability = new();
    private readonly Dictionary<string, CapabilityHandler> handlers = new(StringComparer.OrdinalIgnoreCase);
    private readonly ReachAiOptions options;
    public bool HasConfiguredSources { get; }
    public CapabilityCatalog(ReachAiCapabilityTypes types, ReachAiOptions options)
    {
        this.options = options;
        HasConfiguredSources = types.Types.Count > 0;
        foreach (var type in types.Types.Distinct())
        foreach (var method in type.GetMethods(BindingFlags.Public | BindingFlags.Instance))
        {
            var capability = method.GetCustomAttribute<ReachCapabilityAttribute>();
            if (capability is null) continue;
            if (!Regex.IsMatch(capability.Name, "^[A-Za-z0-9_.-]{1,192}$") || !handlers.TryAdd(capability.Name, new(type, method, capability)))
                throw new InvalidOperationException($"Invalid or duplicate capability: {capability.Name}");
            if (method.ContainsGenericParameters || method.GetParameters().Any(p => p.ParameterType.IsByRef)) throw new InvalidOperationException("Generic and by-ref capability methods are unsupported");
            if (handlers[capability.Name].IsBusinessIndexResolver && (!capability.BusinessIdentityRequired || Unwrap(method.ReturnType) != typeof(ReachBusinessIndexResolution) || method.GetParameters().Count(p => p.ParameterType == typeof(ReachBusinessIndexResolverRequest)) != 1 || method.GetParameters().Count(p => p.ParameterType != typeof(CancellationToken)) != 1)) throw new InvalidOperationException("Business index resolver must require identity and declare the typed request and result");
            if (handlers[capability.Name].IsBusinessIndexResolver)
            {
                var request = method.GetParameters().Single(p => p.ParameterType == typeof(ReachBusinessIndexResolverRequest));
                if ((request.GetCustomAttribute<ReachParamAttribute>()?.Name ?? request.Name) != "request") throw new InvalidOperationException("Business index resolver parameter must be named request");
            }
            _ = Registration(handlers[capability.Name]); // Fail closed before any full inventory is sent.
        }
    }
    public CapabilityHandler? Find(string name) => handlers.GetValueOrDefault(name);
    public int Count => handlers.Count;
    public object[] Registrations() => handlers.Values.Select(Registration).ToArray();
    private object Registration(CapabilityHandler h)
    {
        var attr = h.Declaration;
        var returnType = Unwrap(h.Method.ReturnType);
        var parameters = h.Method.GetParameters().Where(p => p.ParameterType != typeof(CancellationToken)).Select(Parameter).ToArray();
        if (parameters.Select(p => (string)p["name"]!).Distinct(StringComparer.Ordinal).Count() != parameters.Length) throw new InvalidOperationException("Duplicate parameter names");
        var metadata = new Dictionary<string, object?> {
            ["assetType"] = "BUSINESS_METHOD", ["declared"] = true, ["source"] = "ReachCapability", ["requiredRoles"] = attr.RequiredRoles,
            ["businessIdentityRequired"] = attr.BusinessIdentityRequired, ["className"] = h.ServiceType.FullName,
            ["methodName"] = h.Method.Name, ["invokeProtocol"] = "REACHAI_CAPABILITY_HTTP",
            ["timeoutMs"] = 0, ["retryLimit"] = -1
        };
        if (h.IsBusinessIndexResolver) metadata["tags"] = new[] { "business-index-resolver" };
        if (returnType != typeof(void) && !h.IsBusinessIndexResolver)
        {
            var info = Nullability.Create(h.Method.ReturnParameter);
            if (h.Method.ReturnType.IsGenericType && (h.Method.ReturnType.GetGenericTypeDefinition() == typeof(Task<>) || h.Method.ReturnType.GetGenericTypeDefinition() == typeof(ValueTask<>))) info = info.GenericTypeArguments[0];
            metadata["resultSchema"] = Schema(returnType, new HashSet<Type>(), info);
        }
        return new { name = attr.Name, title = attr.Title, description = attr.Description, httpMethod = "POST", baseUrl = options.BaseUrl,
            contextPath = options.ContextPath, endpointPath = "/reachai/capabilities/" + Uri.EscapeDataString(attr.Name) + "/invoke",
            requestBodyType = "object", responseType = returnType == typeof(void) ? "void" : returnType.FullName,
            sideEffect = attr.SideEffect switch { ReachSideEffect.Read => "READ", ReachSideEffect.IdempotentWrite => "IDEMPOTENT_WRITE", ReachSideEffect.Irreversible => "IRREVERSIBLE", _ => "WRITE" },
            enabled = true, parameters, metadata };
    }
    private static Dictionary<string, object?> Parameter(ParameterInfo p)
    {
        var attr = p.GetCustomAttribute<ReachParamAttribute>();
        var name = attr?.Name ?? p.Name ?? throw new InvalidOperationException("Parameter name missing");
        var schema = Schema(p.ParameterType, new HashSet<Type>(), Nullability.Create(p), true);
        var required = attr?.Required ?? !p.HasDefaultValue;
        if (!required && schema["type"] is not string[] && (!p.HasDefaultValue || p.DefaultValue is null))
            throw new InvalidOperationException($"Optional non-null parameter requires a non-null default: {name}");
        return new() { ["name"] = name, ["type"] = BaseType(schema), ["description"] = attr?.Description, ["required"] = required,
            ["location"] = "BODY", ["children"] = Children(schema), ["metadata"] = ParameterMetadata(schema) };
    }
    private static object[] Children(Dictionary<string, object?> schema)
    {
        if (BaseType(schema) == "array" && schema.GetValueOrDefault("items") is Dictionary<string, object?> items) schema = items;
        if (schema.GetValueOrDefault("properties") is not Dictionary<string, object?> properties) return [];
        var required = schema.GetValueOrDefault("required") as string[] ?? [];
        return properties.Select(e => { var s = (Dictionary<string, object?>)e.Value!; return (object)new Dictionary<string, object?> { ["name"] = e.Key, ["type"] = BaseType(s), ["required"] = required.Contains(e.Key), ["location"] = "BODY", ["children"] = Children(s), ["metadata"] = ParameterMetadata(s) }; }).ToArray();
    }
    private static Dictionary<string, object?> ParameterMetadata(Dictionary<string, object?> schema)
    {
        var result = new Dictionary<string, object?> { ["nullable"] = schema["type"] is string[] };
        if (schema.TryGetValue("enum", out var values)) result["enum"] = values;
        if (schema.TryGetValue("format", out var format)) result["format"] = format;
        if (schema.TryGetValue("items", out var items) && items is Dictionary<string, object?> item) { result["itemsType"] = BaseType(item); result["itemsMetadata"] = ParameterMetadata(item); }
        return result;
    }
    private static string BaseType(Dictionary<string, object?> schema) => schema["type"] is string[] union ? union.First(x => x != "null") : (string)schema["type"]!;
    private static Type Unwrap(Type type) => type == typeof(Task) || type == typeof(ValueTask) ? typeof(void) : type.IsGenericType && (type.GetGenericTypeDefinition() == typeof(Task<>) || type.GetGenericTypeDefinition() == typeof(ValueTask<>)) ? type.GetGenericArguments()[0] : type;
    private static Dictionary<string, object?> Schema(Type source, HashSet<Type> stack, NullabilityInfo? info = null, bool input = false)
    {
        var type = Nullable.GetUnderlyingType(source) ?? source;
        var result = SchemaCore(type, stack, info, input);
        if (Nullable.GetUnderlyingType(source) != null || !type.IsValueType && info?.ReadState == NullabilityState.Nullable)
            result["type"] = new[] { (string)result["type"]!, "null" };
        return result;
    }
    private static Dictionary<string, object?> SchemaCore(Type type, HashSet<Type> stack, NullabilityInfo? info, bool input)
    {
        if (type.GetCustomAttribute<JsonConverterAttribute>() != null) throw new InvalidOperationException($"Custom JSON converters are unsupported: {type}");
        if (type == typeof(string) || type == typeof(char) || type == typeof(Guid) || type == typeof(DateTime) || type == typeof(DateTimeOffset)) return new() { ["type"] = "string" };
        if (type == typeof(bool)) return new() { ["type"] = "boolean" };
        if (type.IsEnum) return new() { ["type"] = "integer", ["enum"] = Enum.GetValues(type).Cast<object>().Select(Convert.ToInt64).ToArray() };
        if (type == typeof(byte) || type == typeof(short) || type == typeof(int) || type == typeof(long)) return new() { ["type"] = "integer" };
        if (type == typeof(float) || type == typeof(double) || type == typeof(decimal)) return new() { ["type"] = "number" };
        if (type.IsArray) return new() { ["type"] = "array", ["items"] = Schema(type.GetElementType()!, stack, info?.ElementType, input) };
        if (typeof(IDictionary).IsAssignableFrom(type) || type.GetInterfaces().Append(type).Any(t => t.IsGenericType &&
            (t.GetGenericTypeDefinition() == typeof(IDictionary<,>) || t.GetGenericTypeDefinition() == typeof(IReadOnlyDictionary<,>))))
            throw new InvalidOperationException($"Dictionary capability DTO types are unsupported: {type}");
        var enumerable = type.GetInterfaces().Append(type).FirstOrDefault(t => t.IsGenericType && t.GetGenericTypeDefinition() == typeof(IEnumerable<>));
        if (enumerable != null) return new() { ["type"] = "array", ["items"] = Schema(enumerable.GetGenericArguments()[0], stack, info?.GenericTypeArguments.FirstOrDefault(), input) };
        if (type == typeof(object) || typeof(IDictionary).IsAssignableFrom(type) || type.IsAbstract || type.IsInterface || !stack.Add(type)) throw new InvalidOperationException($"Unsupported capability DTO type: {type}");
        try
        {
            var properties = new Dictionary<string, object?>(); var required = new List<string>();
            foreach (var property in type.GetProperties(BindingFlags.Instance | BindingFlags.Public).Where(p => p.CanRead && p.GetIndexParameters().Length == 0))
            {
                if (property.GetCustomAttribute<JsonIgnoreAttribute>() is { Condition: JsonIgnoreCondition.Always }) continue;
                if (property.GetCustomAttribute<JsonConverterAttribute>() != null) throw new InvalidOperationException($"Custom JSON converters are unsupported: {property.Name}");
                var name = property.GetCustomAttribute<JsonPropertyNameAttribute>()?.Name ?? JsonNamingPolicy.CamelCase.ConvertName(property.Name);
                var propertyInfo = Nullability.Create(property);
                if (!properties.TryAdd(name, Schema(property.PropertyType, stack, propertyInfo, input))) throw new InvalidOperationException("Duplicate JSON property");
                var explicitParam = property.GetCustomAttribute<ReachParamAttribute>();
                if (input ? explicitParam?.Required ?? (property.GetCustomAttribute<System.ComponentModel.DataAnnotations.RequiredAttribute>() != null || property.GetCustomAttribute<System.Runtime.CompilerServices.RequiredMemberAttribute>() != null || propertyInfo.ReadState == NullabilityState.NotNull) : property.GetCustomAttribute<JsonIgnoreAttribute>() is not { Condition: JsonIgnoreCondition.WhenWritingNull or JsonIgnoreCondition.WhenWritingDefault }) required.Add(name);
            }
            return new() { ["type"] = "object", ["properties"] = properties, ["required"] = required.ToArray() };
        }
        finally { stack.Remove(type); }
    }
    public static async Task<object?> InvokeAsync(CapabilityHandler handler, IServiceProvider provider, JsonElement arguments, CancellationToken ct)
        => await InvokeBoundAsync(handler, provider, BindArguments(handler, arguments, ct));

    /// <summary>Validates and deserializes the request before any business code runs, so bad input is never reported as an execution failure.</summary>
    public static object?[] BindArguments(CapabilityHandler handler, JsonElement arguments, CancellationToken ct)
    {
        if (arguments.ValueKind != JsonValueKind.Object) throw new ArgumentException("Arguments must be an object");
        var inputParameters = handler.Method.GetParameters().Where(p => p.ParameterType != typeof(CancellationToken)).ToArray();
        ParameterInfo? dto = null; Dictionary<string, object?>? dtoSchema = null;
        if (inputParameters.Length == 1)
        {
            var candidate = inputParameters[0]; var candidateSchema = Schema(candidate.ParameterType, new HashSet<Type>(), Nullability.Create(candidate), true);
            if (BaseType(candidateSchema) == "object" && candidateSchema.GetValueOrDefault("properties") is Dictionary<string, object?> properties && properties.Count > 0) { dto = candidate; dtoSchema = candidateSchema; }
        }
        var dtoName = dto?.GetCustomAttribute<ReachParamAttribute>()?.Name ?? dto?.Name;
        var wrapped = dto != null && arguments.TryGetProperty(dtoName!, out _);
        if (dto != null && !wrapped) ValidateValue(arguments, dtoSchema!);
        else
        {
            var names = inputParameters.Select(p => p.GetCustomAttribute<ReachParamAttribute>()?.Name ?? p.Name!).ToHashSet(StringComparer.Ordinal);
            foreach (var field in arguments.EnumerateObject()) if (!names.Contains(field.Name)) throw new ArgumentException("Undeclared argument");
        }
        var args = handler.Method.GetParameters().Select(p => {
            if (p.ParameterType == typeof(CancellationToken)) return (object?)ct;
            var name = p.GetCustomAttribute<ReachParamAttribute>()?.Name ?? p.Name!;
            var present = arguments.TryGetProperty(name, out var value);
            if (!present && p == dto) { value = arguments; present = true; }
            if (present)
            {
                ValidateValue(value, Schema(p.ParameterType, new HashSet<Type>(), Nullability.Create(p), true));
                return JsonSerializer.Deserialize(value, p.ParameterType, Wire.Json);
            }
            if (p.GetCustomAttribute<ReachParamAttribute>() is { Required: true }) throw new ArgumentException($"Missing argument: {name}");
            if (p.HasDefaultValue) return p.DefaultValue;
            if (p.GetCustomAttribute<ReachParamAttribute>() is { Required: false } && (!p.ParameterType.IsValueType || Nullable.GetUnderlyingType(p.ParameterType) != null)) return null;
            throw new ArgumentException($"Missing argument: {name}");
        }).ToArray();
        return args;
    }

    public static async Task<object?> InvokeBoundAsync(CapabilityHandler handler, IServiceProvider provider, object?[] args)
    {
        var instance = provider.GetRequiredService(handler.ServiceType);
        object? result;
        try { result = handler.Method.Invoke(instance, args); }
        catch (TargetInvocationException ex) when (ex.InnerException != null) { System.Runtime.ExceptionServices.ExceptionDispatchInfo.Capture(ex.InnerException).Throw(); throw; }
        if (result is Task task) { await task; return handler.Method.ReturnType == typeof(Task) ? null : task.GetType().GetProperty("Result")!.GetValue(task); }
        if (result is ValueTask valueTask) { await valueTask; return null; }
        if (result != null && result.GetType().IsGenericType && result.GetType().GetGenericTypeDefinition() == typeof(ValueTask<>))
        { var convertedTask = (Task)result.GetType().GetMethod("AsTask")!.Invoke(result, null)!; await convertedTask; return convertedTask.GetType().GetProperty("Result")!.GetValue(convertedTask); }
        return result;
    }
    private static void ValidateValue(JsonElement value, Dictionary<string, object?> schema)
    {
        if (value.ValueKind == JsonValueKind.Null) { if (schema["type"] is not string[]) throw new ArgumentException("Null is not allowed"); return; }
        if (BaseType(schema) == "object")
        {
            if (value.ValueKind != JsonValueKind.Object || schema.GetValueOrDefault("properties") is not Dictionary<string, object?> properties) throw new ArgumentException("Object expected");
            foreach (var field in value.EnumerateObject()) { if (!properties.TryGetValue(field.Name, out var nested)) throw new ArgumentException("Undeclared DTO field"); ValidateValue(field.Value, (Dictionary<string, object?>)nested!); }
            foreach (var required in (string[])(schema.GetValueOrDefault("required") ?? Array.Empty<string>())) if (!value.TryGetProperty(required, out _)) throw new ArgumentException("Required DTO field missing");
        }
        else if (BaseType(schema) == "array")
        {
            if (value.ValueKind != JsonValueKind.Array) throw new ArgumentException("Array expected");
            foreach (var item in value.EnumerateArray()) ValidateValue(item, (Dictionary<string, object?>)schema["items"]!);
        }
    }
}
