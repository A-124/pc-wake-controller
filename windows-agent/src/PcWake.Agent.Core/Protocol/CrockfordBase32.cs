using System.Security.Cryptography;
using System.Text;

namespace PcWake.Agent.Core.Protocol;

/// <summary>
/// Crockford base32 pairing codes: 8 symbols (40 bits), no I/L/O/U in the alphabet.
/// Normalization follows PROTOCOL.md section 9.
/// </summary>
public static class CrockfordBase32
{
    public const int CodeSymbols = 8;
    private const string Alphabet = "0123456789ABCDEFGHJKMNPQRSTVWXYZ";

    public static string GenerateNormalizedCode()
    {
        Span<char> symbols = stackalloc char[CodeSymbols];
        for (var i = 0; i < symbols.Length; i++)
        {
            symbols[i] = Alphabet[RandomNumberGenerator.GetInt32(Alphabet.Length)];
        }

        return new string(symbols);
    }

    public static string Format(string normalizedCode)
    {
        if (normalizedCode.Length != CodeSymbols)
        {
            throw new ArgumentException("A normalized pairing code has exactly 8 symbols.", nameof(normalizedCode));
        }

        return string.Concat(normalizedCode.AsSpan(0, 4), "-", normalizedCode.AsSpan(4, 4));
    }

    /// <summary>Uppercases, strips dashes/spaces and maps ambiguous characters I,L→1, O→0, U→V.</summary>
    public static string Normalize(string? input)
    {
        if (string.IsNullOrEmpty(input))
        {
            return string.Empty;
        }

        var builder = new StringBuilder(CodeSymbols);
        foreach (var raw in input)
        {
            if (raw is '-' or ' ')
            {
                continue;
            }

            var c = char.ToUpperInvariant(raw);
            switch (c)
            {
                case 'I':
                case 'L':
                    c = '1';
                    break;
                case 'O':
                    c = '0';
                    break;
                case 'U':
                    c = 'V';
                    break;
            }

            builder.Append(c);
        }

        return builder.ToString();
    }

    public static bool IsValidNormalized(string? value)
    {
        return value is { Length: CodeSymbols } && value.All(c => Alphabet.IndexOf(c) >= 0);
    }

    /// <summary>Constant-time comparison of already-normalized codes.</summary>
    public static bool FixedTimeEquals(string left, string right)
    {
        if (left.Length != right.Length)
        {
            return false;
        }

        return CryptographicOperations.FixedTimeEquals(
            Encoding.ASCII.GetBytes(left),
            Encoding.ASCII.GetBytes(right));
    }
}
