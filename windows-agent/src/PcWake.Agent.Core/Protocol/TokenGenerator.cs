using System.Security.Cryptography;
using System.Text;

namespace PcWake.Agent.Core.Protocol;

/// <summary>Device identifiers and bearer tokens. Tokens are never persisted in plaintext.</summary>
public static class TokenGenerator
{
    public static string CreateDeviceId()
    {
        return Convert.ToHexString(RandomNumberGenerator.GetBytes(16)).ToLowerInvariant();
    }

    /// <summary>32 random bytes encoded as unpadded base64url (43 characters).</summary>
    public static string CreateToken()
    {
        return Base64Url.Encode(RandomNumberGenerator.GetBytes(32));
    }

    public static string CreateCommandId()
    {
        return Convert.ToHexString(RandomNumberGenerator.GetBytes(16)).ToLowerInvariant();
    }

    public static string HashToken(string token)
    {
        ArgumentNullException.ThrowIfNull(token);
        return Convert.ToHexString(SHA256.HashData(Encoding.UTF8.GetBytes(token))).ToLowerInvariant();
    }

    public static bool VerifyTokenHash(string token, string expectedHash)
    {
        if (string.IsNullOrEmpty(token) || string.IsNullOrEmpty(expectedHash))
        {
            return false;
        }

        var computed = Encoding.ASCII.GetBytes(HashToken(token));
        var expected = Encoding.ASCII.GetBytes(expectedHash);
        return computed.Length == expected.Length &&
               CryptographicOperations.FixedTimeEquals(computed, expected);
    }

    public static bool IsValidDeviceId(string? value)
    {
        return value is { Length: 32 } && value.All(c => c is >= '0' and <= '9' or >= 'a' and <= 'f');
    }

    public static bool IsValidTokenHash(string? value)
    {
        return value is { Length: 64 } && value.All(c => c is >= '0' and <= '9' or >= 'a' and <= 'f');
    }

    public static bool IsValidToken(string? value)
    {
        return value is { Length: 43 } && Base64Url.TryDecode(value, out var bytes) && bytes.Length == 32;
    }
}
