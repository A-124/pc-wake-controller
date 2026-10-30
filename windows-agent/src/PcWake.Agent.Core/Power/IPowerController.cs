namespace PcWake.Agent.Core.Power;

public sealed record PowerNativeResult(bool Success, int? ExitCode, string? Error)
{
    public static PowerNativeResult Ok(int exitCode = 0) => new(true, exitCode, null);
    public static PowerNativeResult Fail(string error, int? exitCode = null) => new(false, exitCode, error);
}

/// <summary>
/// Only these fixed native operations are exposed. There is no method that takes
/// an executable path, a command line, or shell text from a request.
/// </summary>
public interface IPowerController
{
    Task<PowerNativeResult> ShutdownAsync(int graceSeconds, string reason, CancellationToken cancellationToken);
    Task<PowerNativeResult> RestartAsync(int graceSeconds, string reason, CancellationToken cancellationToken);
    Task<PowerNativeResult> SleepAsync(CancellationToken cancellationToken);
    Task<PowerNativeResult> CancelAsync(CancellationToken cancellationToken);
}
