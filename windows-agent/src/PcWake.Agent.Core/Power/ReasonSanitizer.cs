using System.Text;
using PcWake.Agent.Core.Protocol;

namespace PcWake.Agent.Core.Power;

public static class ReasonSanitizer
{
    /// <summary>
    /// Strips control characters and single/double quotes, trims, truncates to
    /// 200 characters and substitutes a fixed fallback when nothing remains.
    /// Shell metacharacters are harmless because no shell is ever used.
    /// </summary>
    public static string Sanitize(string? reason, string fallback)
    {
        if (string.IsNullOrEmpty(reason))
        {
            return fallback;
        }

        var builder = new StringBuilder(Math.Min(reason.Length, ProtocolConstants.MaxReasonLength));
        foreach (var c in reason)
        {
            if (char.IsControl(c) || c is '"' or '\'')
            {
                continue;
            }

            builder.Append(c);
            if (builder.Length >= ProtocolConstants.MaxReasonLength)
            {
                break;
            }
        }

        var cleaned = builder.ToString().Trim();
        return string.IsNullOrWhiteSpace(cleaned) ? fallback : cleaned;
    }

    public static string DefaultFor(PowerAction action) => action switch
    {
        PowerAction.Shutdown => ProtocolConstants.DefaultShutdownReason,
        PowerAction.Restart => ProtocolConstants.DefaultRestartReason,
        _ => ProtocolConstants.DefaultSleepReason
    };
}
