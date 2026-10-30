using System.Diagnostics;

namespace PcWake.Agent.Core.Power;

public sealed record ProcessRunResult(int ExitCode, bool TimedOut, string? StandardError);

public interface IProcessRunner
{
    Task<ProcessRunResult> RunAsync(
        string fileName,
        IReadOnlyList<string> arguments,
        TimeSpan timeout,
        CancellationToken cancellationToken);
}

/// <summary>Runs a fixed executable directly (never cmd.exe or a shell).</summary>
public sealed class WindowsProcessRunner : IProcessRunner
{
    public async Task<ProcessRunResult> RunAsync(
        string fileName,
        IReadOnlyList<string> arguments,
        TimeSpan timeout,
        CancellationToken cancellationToken)
    {
        if (string.IsNullOrWhiteSpace(fileName) || !Path.IsPathFullyQualified(fileName))
        {
            return new ProcessRunResult(-1, false, "Executable path must be absolute.");
        }

        if (!File.Exists(fileName))
        {
            return new ProcessRunResult(-1, false, "Executable not found.");
        }

        var startInfo = new ProcessStartInfo
        {
            FileName = fileName,
            UseShellExecute = false,
            CreateNoWindow = true,
            RedirectStandardOutput = true,
            RedirectStandardError = true,
            WindowStyle = ProcessWindowStyle.Hidden
        };

        foreach (var argument in arguments)
        {
            startInfo.ArgumentList.Add(argument);
        }

        using var process = new Process { StartInfo = startInfo };
        try
        {
            if (!process.Start())
            {
                return new ProcessRunResult(-1, false, "Process could not be started.");
            }

            var stderrTask = process.StandardError.ReadToEndAsync();
            _ = process.StandardOutput.ReadToEndAsync();

            using var timeoutCts = CancellationTokenSource.CreateLinkedTokenSource(cancellationToken);
            timeoutCts.CancelAfter(timeout);
            var timedOut = false;
            try
            {
                await process.WaitForExitAsync(timeoutCts.Token).ConfigureAwait(false);
            }
            catch (OperationCanceledException) when (!cancellationToken.IsCancellationRequested)
            {
                timedOut = true;
                TryKill(process);
            }
            catch (OperationCanceledException)
            {
                TryKill(process);
                throw;
            }

            var stderr = await stderrTask.ConfigureAwait(false);
            var exitCode = timedOut ? -1 : process.ExitCode;
            return new ProcessRunResult(exitCode, timedOut, Trim(stderr));
        }
        catch (Exception ex) when (ex is System.ComponentModel.Win32Exception or InvalidOperationException)
        {
            return new ProcessRunResult(-1, false, "Process could not be started.");
        }
    }

    private static void TryKill(Process process)
    {
        try
        {
            if (!process.HasExited)
            {
                process.Kill(entireProcessTree: true);
            }
        }
        catch (InvalidOperationException)
        {
        }
        catch (System.ComponentModel.Win32Exception)
        {
        }
    }

    private static string? Trim(string? value)
    {
        if (string.IsNullOrWhiteSpace(value))
        {
            return null;
        }

        var trimmed = value.Trim();
        return trimmed.Length <= 400 ? trimmed : trimmed[..400];
    }
}
