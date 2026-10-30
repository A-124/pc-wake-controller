using System.Text;

namespace PcWake.Agent.Core.Protocol;

/// <summary>Base64url without padding, as required by PROTOCOL.md.</summary>
public static class Base64Url
{
    public static string Encode(ReadOnlySpan<byte> data)
    {
        return Convert.ToBase64String(data)
            .TrimEnd('=')
            .Replace('+', '-')
            .Replace('/', '_');
    }

    public static bool TryDecode(string? value, out byte[] bytes)
    {
        bytes = [];
        if (string.IsNullOrEmpty(value))
        {
            return false;
        }

        for (var i = 0; i < value.Length; i++)
        {
            var c = value[i];
            var valid = c is >= 'A' and <= 'Z' or >= 'a' and <= 'z' or >= '0' and <= '9' or '-' or '_';
            if (!valid)
            {
                return false;
            }
        }

        var padded = value.Replace('-', '+').Replace('_', '/');
        switch (padded.Length % 4)
        {
            case 1:
                return false;
            case 2:
                padded += "==";
                break;
            case 3:
                padded += "=";
                break;
        }

        try
        {
            bytes = Convert.FromBase64String(padded);
            return true;
        }
        catch (FormatException)
        {
            return false;
        }
    }

    public static byte[] Decode(string value)
    {
        if (!TryDecode(value, out var bytes))
        {
            throw new FormatException("Invalid base64url value.");
        }

        return bytes;
    }

    /// <summary>Validates a base64url header value against the protocol length bounds.</summary>
    public static bool IsWellFormed(string? value, int minChars, int maxChars)
    {
        if (value is null || value.Length < minChars || value.Length > maxChars)
        {
            return false;
        }

        return TryDecode(value, out _);
    }

    public static string EncodeUtf8(string value) => Encode(Encoding.UTF8.GetBytes(value));
}
