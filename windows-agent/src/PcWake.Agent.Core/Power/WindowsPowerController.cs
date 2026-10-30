using System.Runtime.InteropServices;

namespace PcWake.Agent.Core.Power;

/// <summary>
/// Windows implementation. shutdown.exe is invoked directly with the fixed
/// protocol arguments; sleep uses powrprof!SetSuspendState.
/// </summary>
public sealed class WindowsPowerController : IPowerController
{
    private static readonly TimeSpan ShutdownTimeout = TimeSpan.FromSeconds(20);
    private readonly IProcessRunner _runner;
    private readonly string? _systemDirectory;

    public WindowsPowerController(IProcessRunner runner, string? systemDirectory = null)
    {
        _runner = runner;
        _systemDirectory = systemDirectory;
    }

    public async Task<PowerNativeResult> ShutdownAsync(
        int graceSeconds,
        string reason,
        CancellationToken cancellationToken)
    {
        var invocation = FixedPowerCommand.Shutdown(graceSeconds, reason, _systemDirectory);
        return await RunAsync(invocation, cancellationToken).ConfigureAwait(false);
    }

    public async Task<PowerNativeResult> RestartAsync(
        int graceSeconds,
        string reason,
        CancellationToken cancellationToken)
    {
        var invocation = FixedPowerCommand.Restart(graceSeconds, reason, _systemDirectory);
        return await RunAsync(invocation, cancellationToken).ConfigureAwait(false);
    }

    public async Task<PowerNativeResult> CancelAsync(CancellationToken cancellationToken)
    {
        var invocation = FixedPowerCommand.Cancel(_systemDirectory);
        return await RunAsync(invocation, cancellationToken).ConfigureAwait(false);
    }

    public Task<PowerNativeResult> SleepAsync(CancellationToken cancellationToken)
    {
        cancellationToken.ThrowIfCancellationRequested();
        if (!OperatingSystem.IsWindows())
        {
            return Task.FromResult(PowerNativeResult.Fail("Sleep is only supported on Windows."));
        }

        var suspended = SetSuspendState(false, false, false);
        if (!suspended)
        {
            var error = Marshal.GetLastWin32Error();
            return Task.FromResult(PowerNativeResult.Fail($"SetSuspendState failed with Win32 error {error}."));
        }

        return Task.FromResult(PowerNativeResult.Ok());
    }

    private async Task<PowerNativeResult> RunAsync(ProcessInvocation invocation, CancellationToken cancellationToken)
    {
        ProcessRunResult result;
        try
        {
            result = await _runner.RunAsync(
                invocation.FileName,
                invocation.Arguments,
                ShutdownTimeout,
                cancellationToken).ConfigureAwait(false);
        }
        catch (OperationCanceledException)
        {
            throw;
        }

        if (result.TimedOut)
        {
            return PowerNativeResult.Fail("Native command timed out.");
        }

        if (result.ExitCode != 0)
        {
            return PowerNativeResult.Fail(
                $"Native command failed with exit code {result.ExitCode}.",
                result.ExitCode);
        }

        return PowerNativeResult.Ok(result.ExitCode);
    }

    [DllImport("powrprof.dll", SetLastError = true)]
    [return: MarshalAs(UnmanagedType.U1)]
    private static extern bool SetSuspendState(
        [MarshalAs(UnmanagedType.U1)] bool hibernate,
        [MarshalAs(UnmanagedType.U1)] bool forceCritical,
        [MarshalAs(UnmanagedType.U1)] bool disableWakeEvent);
}
