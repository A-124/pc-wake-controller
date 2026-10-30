using System.Collections.Concurrent;
using PcWake.Agent.Core.Abstractions;
using PcWake.Agent.Core.Audit;
using PcWake.Agent.Core.Config;
using PcWake.Agent.Core.Power;

namespace PcWake.Agent.Tests;

internal sealed class FakeClock : IClock
{
    private long _unixSeconds;

    public FakeClock(long unixSeconds = 1_720_000_000) => _unixSeconds = unixSeconds;

    public DateTimeOffset UtcNow => DateTimeOffset.FromUnixTimeSeconds(Interlocked.Read(ref _unixSeconds));

    public void Set(long unixSeconds) => Interlocked.Exchange(ref _unixSeconds, unixSeconds);

    public void Advance(TimeSpan delta) => Interlocked.Add(ref _unixSeconds, (long)delta.TotalSeconds);
}

internal sealed class RecordingAuditLog : IAuditLog
{
    private readonly ConcurrentQueue<(string EventName, IReadOnlyDictionary<string, object?> Fields)> _entries = new();

    public IReadOnlyList<(string EventName, IReadOnlyDictionary<string, object?> Fields)> Entries => _entries.ToList();

    public Task LogAsync(
        string eventName,
        IReadOnlyDictionary<string, object?>? fields = null,
        CancellationToken cancellationToken = default)
    {
        _entries.Enqueue((eventName, fields ?? new Dictionary<string, object?>()));
        return Task.CompletedTask;
    }

    public bool ContainsEvent(string eventName) => _entries.Any(e => e.EventName == eventName);
}

internal sealed class TempDirectory : IDisposable
{
    public TempDirectory()
    {
        Path = System.IO.Path.Combine(System.IO.Path.GetTempPath(), "pcwake-tests-" + Guid.NewGuid().ToString("N"));
        Directory.CreateDirectory(Path);
        Paths = new AgentPaths(Path);
    }

    public string Path { get; }
    public AgentPaths Paths { get; }

    public void Dispose()
    {
        for (var attempt = 0; attempt < 3; attempt++)
        {
            try
            {
                if (Directory.Exists(Path))
                {
                    Directory.Delete(Path, recursive: true);
                }

                return;
            }
            catch (IOException)
            {
                Thread.Sleep(50);
            }
            catch (UnauthorizedAccessException)
            {
                Thread.Sleep(50);
            }
        }
    }
}

internal sealed class ManualPowerDelay : IPowerDelay
{
    private sealed class Entry
    {
        public required TaskCompletionSource<bool> Completion { get; init; }
        public required CancellationTokenRegistration Registration { get; init; }
    }

    private readonly object _sync = new();
    private readonly List<Entry> _entries = [];
    private readonly List<TimeSpan> _requests = [];

    public IReadOnlyList<TimeSpan> Requests
    {
        get
        {
            lock (_sync)
            {
                return _requests.ToList();
            }
        }
    }

    public int PendingCount
    {
        get
        {
            lock (_sync)
            {
                return _entries.Count;
            }
        }
    }

    public Task DelayAsync(TimeSpan delay, CancellationToken cancellationToken)
    {
        var completion = new TaskCompletionSource<bool>(TaskCreationOptions.RunContinuationsAsynchronously);
        lock (_sync)
        {
            _requests.Add(delay);
            if (cancellationToken.IsCancellationRequested)
            {
                completion.TrySetCanceled(cancellationToken);
                return completion.Task;
            }

            var registration = cancellationToken.Register(() => completion.TrySetCanceled(cancellationToken));
            _entries.Add(new Entry { Completion = completion, Registration = registration });
        }

        return completion.Task;
    }

    public void ReleaseAll()
    {
        List<Entry> entries;
        lock (_sync)
        {
            entries = _entries.ToList();
            _entries.Clear();
        }

        foreach (var entry in entries)
        {
            entry.Registration.Dispose();
            entry.Completion.TrySetResult(true);
        }
    }

    public void ReleaseNext()
    {
        Entry? entry = null;
        lock (_sync)
        {
            if (_entries.Count > 0)
            {
                entry = _entries[0];
                _entries.RemoveAt(0);
            }
        }

        if (entry is not null)
        {
            entry.Registration.Dispose();
            entry.Completion.TrySetResult(true);
        }
    }
}

internal sealed class ImmediatePowerDelay : IPowerDelay
{
    public Task DelayAsync(TimeSpan delay, CancellationToken cancellationToken) => Task.CompletedTask;
}

internal sealed class FakePowerController : IPowerController
{
    private readonly object _sync = new();
    private readonly List<(int GraceSeconds, string Reason)> _shutdownCalls = [];
    private readonly List<(int GraceSeconds, string Reason)> _restartCalls = [];
    private int _sleepCalls;
    private int _cancelCalls;

    public PowerNativeResult ShutdownResult { get; set; } = PowerNativeResult.Ok();
    public PowerNativeResult RestartResult { get; set; } = PowerNativeResult.Ok();
    public PowerNativeResult SleepResult { get; set; } = PowerNativeResult.Ok();
    public PowerNativeResult CancelResult { get; set; } = PowerNativeResult.Ok();

    public IReadOnlyList<(int GraceSeconds, string Reason)> ShutdownCalls
    {
        get { lock (_sync) { return _shutdownCalls.ToList(); } }
    }

    public IReadOnlyList<(int GraceSeconds, string Reason)> RestartCalls
    {
        get { lock (_sync) { return _restartCalls.ToList(); } }
    }

    public int SleepCalls => Volatile.Read(ref _sleepCalls);
    public int CancelCalls => Volatile.Read(ref _cancelCalls);

    public Task<PowerNativeResult> ShutdownAsync(int graceSeconds, string reason, CancellationToken cancellationToken)
    {
        lock (_sync)
        {
            _shutdownCalls.Add((graceSeconds, reason));
        }

        return Task.FromResult(ShutdownResult);
    }

    public Task<PowerNativeResult> RestartAsync(int graceSeconds, string reason, CancellationToken cancellationToken)
    {
        lock (_sync)
        {
            _restartCalls.Add((graceSeconds, reason));
        }

        return Task.FromResult(RestartResult);
    }

    public Task<PowerNativeResult> SleepAsync(CancellationToken cancellationToken)
    {
        Interlocked.Increment(ref _sleepCalls);
        return Task.FromResult(SleepResult);
    }

    public Task<PowerNativeResult> CancelAsync(CancellationToken cancellationToken)
    {
        Interlocked.Increment(ref _cancelCalls);
        return Task.FromResult(CancelResult);
    }
}

internal sealed class FakeProcessRunner : IProcessRunner
{
    private readonly object _sync = new();
    private readonly List<(string FileName, IReadOnlyList<string> Arguments)> _calls = [];

    public ProcessRunResult Result { get; set; } = new(0, false, null);

    public IReadOnlyList<(string FileName, IReadOnlyList<string> Arguments)> Calls
    {
        get { lock (_sync) { return _calls.ToList(); } }
    }

    public Task<ProcessRunResult> RunAsync(
        string fileName,
        IReadOnlyList<string> arguments,
        TimeSpan timeout,
        CancellationToken cancellationToken)
    {
        lock (_sync)
        {
            _calls.Add((fileName, arguments.ToList()));
        }

        return Task.FromResult(Result);
    }
}

internal static class WaitHelpers
{
    public static async Task WaitUntilAsync(Func<bool> condition, int timeoutMs = 5000, string? message = null)
    {
        var deadline = Environment.TickCount64 + timeoutMs;
        while (Environment.TickCount64 < deadline)
        {
            if (condition())
            {
                return;
            }

            await Task.Delay(20).ConfigureAwait(false);
        }

        throw new TimeoutException(message ?? "Condition was not satisfied in time.");
    }
}
