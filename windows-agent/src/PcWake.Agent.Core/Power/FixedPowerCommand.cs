using PcWake.Agent.Core.Protocol;

namespace PcWake.Agent.Core.Power;

public sealed record ProcessInvocation(string FileName, IReadOnlyList<string> Arguments);

/// <summary>
/// The complete, closed set of shutdown.exe invocations. Arguments are passed via
/// ProcessStartInfo.ArgumentList; the reason is still sanitized before use.
/// </summary>
public static class FixedPowerCommand
{
    public static string ResolveShutdownExe(string? systemDirectory = null)
    {
        var directory = string.IsNullOrWhiteSpace(systemDirectory)
            ? Environment.SystemDirectory
            : systemDirectory;
        return Path.Combine(directory, "shutdown.exe");
    }

    public static ProcessInvocation Shutdown(int graceSeconds, string reason, string? systemDirectory = null)
    {
        var clamped = Math.Clamp(graceSeconds, ProtocolConstants.GraceMinSeconds, ProtocolConstants.GraceMaxSeconds);
        return new ProcessInvocation(
            ResolveShutdownExe(systemDirectory),
            [
                "/s",
                "/t",
                clamped.ToString(System.Globalization.CultureInfo.InvariantCulture),
                "/d",
                "p:4:1",
                "/c",
                ReasonSanitizer.Sanitize(reason, ProtocolConstants.DefaultShutdownReason)
            ]);
    }

    public static ProcessInvocation Restart(int graceSeconds, string reason, string? systemDirectory = null)
    {
        var clamped = Math.Clamp(graceSeconds, ProtocolConstants.GraceMinSeconds, ProtocolConstants.GraceMaxSeconds);
        return new ProcessInvocation(
            ResolveShutdownExe(systemDirectory),
            [
                "/r",
                "/t",
                clamped.ToString(System.Globalization.CultureInfo.InvariantCulture),
                "/d",
                "p:4:1",
                "/c",
                ReasonSanitizer.Sanitize(reason, ProtocolConstants.DefaultRestartReason)
            ]);
    }

    public static ProcessInvocation Cancel(string? systemDirectory = null) =>
        new(ResolveShutdownExe(systemDirectory), ["/a"]);
}
