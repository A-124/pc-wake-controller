using System.Security.Cryptography;
using System.Text;

namespace PcWake.Agent.Core.Protocol;

/// <summary>
/// The exact canonical request and HMAC construction from PROTOCOL.md section 4.1.
/// No field is optional and no normalization is applied to the request target.
/// </summary>
public static class CanonicalRequest
{
    public static string ComputeBodyHash(ReadOnlySpan<byte> bodyBytes)
    {
        return Convert.ToHexString(SHA256.HashData(bodyBytes)).ToLowerInvariant();
    }

    public static string Build(
        string method,
        string pathWithQuery,
        string timestamp,
        string nonce,
        ReadOnlySpan<byte> bodyBytes)
    {
        ArgumentException.ThrowIfNullOrEmpty(method);
        ArgumentException.ThrowIfNullOrEmpty(pathWithQuery);
        ArgumentException.ThrowIfNullOrEmpty(timestamp);
        ArgumentException.ThrowIfNullOrEmpty(nonce);

        return string.Concat(
            method.ToUpperInvariant(), "\n",
            pathWithQuery, "\n",
            timestamp, "\n",
            nonce, "\n",
            ComputeBodyHash(bodyBytes));
    }

    public static string ComputeSignature(string token, string canonical)
    {
        ArgumentException.ThrowIfNullOrEmpty(token);
        ArgumentException.ThrowIfNullOrEmpty(canonical);

        var key = Encoding.UTF8.GetBytes(token);
        var message = Encoding.UTF8.GetBytes(canonical);
        try
        {
            return Base64Url.Encode(HMACSHA256.HashData(key, message));
        }
        finally
        {
            CryptographicOperations.ZeroMemory(key);
        }
    }

    public static bool VerifySignature(string token, string canonical, string signature)
    {
        if (string.IsNullOrEmpty(token) || string.IsNullOrEmpty(canonical) || string.IsNullOrEmpty(signature))
        {
            return false;
        }

        if (!Base64Url.TryDecode(signature, out var supplied) || supplied.Length != 32)
        {
            return false;
        }

        var key = Encoding.UTF8.GetBytes(token);
        var message = Encoding.UTF8.GetBytes(canonical);
        try
        {
            var expected = HMACSHA256.HashData(key, message);
            return CryptographicOperations.FixedTimeEquals(expected, supplied);
        }
        finally
        {
            CryptographicOperations.ZeroMemory(key);
            CryptographicOperations.ZeroMemory(supplied);
        }
    }

    /// <summary>Used while validating unknown devices so failure paths do similar work.</summary>
    public static bool VerifyWithDummyKey(string canonical, string signature)
    {
        Span<byte> key = stackalloc byte[32];
        key.Fill(0x5A);
        if (!Base64Url.TryDecode(signature, out var supplied) || supplied.Length != 32)
        {
            return false;
        }

        var message = Encoding.UTF8.GetBytes(canonical);
        var expected = HMACSHA256.HashData(key, message);
        var result = CryptographicOperations.FixedTimeEquals(expected, supplied);
        CryptographicOperations.ZeroMemory(supplied);
        return result;
    }
}
