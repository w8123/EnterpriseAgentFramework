using System.Security.Cryptography;
using System.Text;
using System.Text.Json;
using Microsoft.AspNetCore.DataProtection;

namespace ReachAI.AspNetCore;

public static class ReachAiProtocol
{
    public static string Hmac(string secret, string message) => Convert.ToHexStringLower(HMACSHA256.HashData(Encoding.UTF8.GetBytes(secret.Trim()), Encoding.UTF8.GetBytes(message)));
    public static string RegistrySignature(string projectCode, string timestamp, string nonce, string secret) => Hmac(secret, $"{projectCode.Trim()}\n{timestamp.Trim()}\n{nonce.Trim()}");
    public static string BodySignature(string method, string path, string projectCode, string appKey, string timestamp, string nonce, byte[] body, string secret)
    {
        if (!path.StartsWith('/') || path.Contains('?') || path.Contains('#') || path.Contains("..") || path.Contains('\n') || path.Contains('\r')) throw new ArgumentException("Invalid signed path");
        var hash = Convert.ToHexStringLower(SHA256.HashData(body));
        return Hmac(secret, $"REACHAI_PROJECT_REQUEST_V1\n{method.Trim().ToUpperInvariant()}\n{path.Trim()}\n{projectCode.Trim()}\n{appKey.Trim()}\n{timestamp.Trim()}\n{nonce.Trim()}\n{hash}");
    }
    public static ReachAiInvocationContext VerifyInvocation(string token, ReachAiCredentials credentials, string projectCode, string capabilityName, DateTimeOffset now)
    {
        if (string.IsNullOrEmpty(token) || token.Length > 16384) throw new UnauthorizedAccessException();
        var parts = token.Split('.');
        if (parts.Length != 3 || parts.Any(p => p.Length == 0 || p.IndexOfAny(['=', '+', '/']) >= 0)) throw new UnauthorizedAccessException();
        try
        {
            var signature = HMACSHA256.HashData(Encoding.UTF8.GetBytes(credentials.AppSecret.Trim()), Encoding.UTF8.GetBytes(parts[0] + "." + parts[1]));
            if (!CryptographicOperations.FixedTimeEquals(signature, Decode(parts[2]))) throw new UnauthorizedAccessException();
            using var header = JsonDocument.Parse(Decode(parts[0]));
            if (header.RootElement.GetProperty("alg").GetString() != "HS256") throw new UnauthorizedAccessException();
            using var doc = JsonDocument.Parse(Decode(parts[1]));
            var claims = doc.RootElement;
            var current = now.ToUnixTimeSeconds();
            if (claims.ValueKind != JsonValueKind.Object) throw new UnauthorizedAccessException();
            foreach (var optional in new[] { "iat", "nbf" })
                if (claims.TryGetProperty(optional, out var issued) && issued.GetInt64() > current) throw new UnauthorizedAccessException();
            if (claims.GetProperty("exp").GetInt64() < current || claims.GetProperty("projectCode").GetString() != projectCode || claims.GetProperty("capabilityName").GetString() != capabilityName || claims.GetProperty("appKey").GetString() != credentials.AppKey) throw new UnauthorizedAccessException();
            return new ReachAiInvocationContext(claims.Clone());
        }
        catch (UnauthorizedAccessException) { throw; }
        catch (Exception ex) when (ex is FormatException or JsonException or KeyNotFoundException or InvalidOperationException) { throw new UnauthorizedAccessException(); }
    }
    private static byte[] Decode(string text) { var s = text.Replace('-', '+').Replace('_', '/'); return Convert.FromBase64String(s.PadRight((s.Length + 3) / 4 * 4, '=')); }
}

public sealed class ProtectedFileCredentialStore(ReachAiOptions options, IDataProtectionProvider provider) : IReachAiCredentialStore
{
    private readonly IDataProtector protector = provider.CreateProtector("ReachAI.Registry.Credentials.v1", options.ProjectCode);
    public async Task<ReachAiCredentials?> LoadAsync(CancellationToken cancellationToken)
    {
        if (string.IsNullOrWhiteSpace(options.CredentialFilePath)) throw new InvalidOperationException("CredentialFilePath is required when enrollment is used");
        if (!File.Exists(options.CredentialFilePath)) return null;
        var encrypted = await File.ReadAllBytesAsync(options.CredentialFilePath, cancellationToken);
        return JsonSerializer.Deserialize<ReachAiCredentials>(protector.Unprotect(encrypted), Wire.Json) ?? throw new InvalidOperationException("Invalid credential store");
    }
    public async Task SaveAsync(ReachAiCredentials credentials, CancellationToken cancellationToken)
    {
        var path = Path.GetFullPath(options.CredentialFilePath);
        Directory.CreateDirectory(Path.GetDirectoryName(path)!);
        var temporary = path + "." + Guid.NewGuid().ToString("N") + ".tmp";
        try
        {
            var encrypted = protector.Protect(JsonSerializer.SerializeToUtf8Bytes(credentials, Wire.Json));
            await File.WriteAllBytesAsync(temporary, encrypted, cancellationToken);
            if (!OperatingSystem.IsWindows()) File.SetUnixFileMode(temporary, UnixFileMode.UserRead | UnixFileMode.UserWrite);
            File.Move(temporary, path, false);
        }
        finally { if (File.Exists(temporary)) File.Delete(temporary); }
    }
}
