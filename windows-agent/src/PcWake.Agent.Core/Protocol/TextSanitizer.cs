using System.Text;

namespace PcWake.Agent.Core.Protocol;

public static class TextSanitizer
{
    /// <summary>Strips control characters, trims, and caps length. Returns null for empty input.</summary>
    public static string? SanitizeLine(string? value, int maxLength)
    {
        if (string.IsNullOrEmpty(value) || maxLength <= 0)
        {
            return null;
        }

        var builder = new StringBuilder(Math.Min(value.Length, maxLength));
        foreach (var c in value)
        {
            if (char.IsControl(c))
            {
                continue;
            }

            builder.Append(c);
            if (builder.Length >= maxLength)
            {
                break;
            }
        }

        var result = builder.ToString().Trim();
        return result.Length == 0 ? null : result;
    }
}
