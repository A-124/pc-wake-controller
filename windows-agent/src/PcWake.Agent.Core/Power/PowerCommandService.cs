using Microsoft.Extensions.Logging;
using Microsoft.Extensions.Logging.Abstractions;
using PcWake.Agent.Core.Abstractions;
using PcWake.Agent.Core.Audit;
using PcWake.Agent.Core.Protocol;

namespace PcWake.Agent.Core.Power;

/// <summary>
/// Owns the single pending power command. Shutdown/restart return immediately,
/// invoke shutdown.exe and remain "pending" for the requested grace period so the
/// tray/login user can cancel. The agent never asserts that the PC powered off.
/// </summary>
public sealed class PowerCommandService : IAsyncDisposable
{
    private readonly IPowerController _controller;
    private readonly IClock _clock;
    private readonly IPowerDelay _delay;
    private readonly IAuditLog _audit;
    private readonly ILogger _logger;
    private readonly object _sync = new();
    private readonly CancellationTokenSource _lifetime = new();
    private PowerCommand? _pending;
    private PowerCommand? _last;
    private Task? _worker;
    private bool _disposed;

    public PowerCommandService(
        IPowerController controller,
        IClock clock,
        IPowerDelay delay,
        IAuditLog audit,
        ILogger<PowerCommandService>? logger = null)
    {
        _controller = controller;
        _clock = clock;
        _delay = delay;
        _audit = audit;
        _logger = logger ?? NullLogger<PowerCommandService>.Instance;
    }

    public PowerSnapshot GetSnapshot()
    {
        lock (_sync)
        {
            return new PowerSnapshot(_pending?.Clone(), _last?.Clone());
        }
    }

    public async Task<PowerStartResult> StartAsync(
        PowerAction action,
        int? requestedGraceSeconds,
        string? requestedReason,
        CancellationToken cancellationToken = default)
    {
        PowerCommand command;
        PowerCommand snapshot;
        string reason;

        lock (_sync)
        {
            if (_disposed)
            {
                return new PowerStartResult(null, PowerStartError.Unavailable);
            }

            if (_pending is not null)
            {
                return new PowerStartResult(null, PowerStartError.CommandPending);
            }

            var grace = action == PowerAction.Sleep
                ? ProtocolConstants.SleepDelaySeconds
                : Math.Clamp(
                    requestedGraceSeconds ?? ProtocolConstants.DefaultShutdownGraceSeconds,
                    ProtocolConstants.GraceMinSeconds,
                    ProtocolConstants.GraceMaxSeconds);

            var now = _clock.UtcNow.ToUnixTimeSeconds();
            reason = ReasonSanitizer.Sanitize(requestedReason, ReasonSanitizer.DefaultFor(action));
            command = new PowerCommand
            {
                Id = TokenGenerator.CreateCommandId(),
                Action = action,
                RequestedAt = now,
                ExecuteAt = now + grace,
                Status = PowerCommandStatus.Pending,
                GraceSeconds = grace,
                Error = null
            };
            snapshot = command.Clone();
            _pending = command;
            _last = command;
        }

        await _audit.LogAsync(
            AuditEvents.CommandAccepted,
            new Dictionary<string, object?>
            {
                ["commandId"] = command.Id,
                ["action"] = command.Action.ToWireName(),
                ["graceSeconds"] = command.GraceSeconds
            },
            cancellationToken).ConfigureAwait(false);

        _worker = Task.Run(() => ExecuteAsync(command, reason), CancellationToken.None);
        return new PowerStartResult(snapshot, PowerStartError.None);
    }

    public async Task<PowerCancelResult> CancelAsync(CancellationToken cancellationToken = default)
    {
        PowerCommand? target;
        lock (_sync)
        {
            target = _pending;
            if (target is null || target.Action == PowerAction.Sleep)
            {
                return new PowerCancelResult(false, target?.Clone());
            }
        }

        PowerNativeResult result;
        try
        {
            result = await _controller.CancelAsync(cancellationToken).ConfigureAwait(false);
        }
        catch (OperationCanceledException)
        {
            throw;
        }
        catch (Exception ex)
        {
            _logger.LogError(ex, "Native cancel invocation failed.");
            result = PowerNativeResult.Fail("Native cancel invocation failed.");
        }

        if (!result.Success)
        {
            await _audit.LogAsync(
                AuditEvents.CommandCancelResult,
                new Dictionary<string, object?>
                {
                    ["commandId"] = target.Id,
                    ["cancelled"] = false,
                    ["exitCode"] = result.ExitCode
                },
                cancellationToken).ConfigureAwait(false);
            return new PowerCancelResult(false, target.Clone());
        }

        PowerCommand snapshot;
        lock (_sync)
        {
            if (_pending?.Id == target.Id)
            {
                _pending = null;
            }

            if (_last is not null &&
                _last.Id == target.Id &&
                _last.Status == PowerCommandStatus.Pending)
            {
                _last.Status = PowerCommandStatus.Cancelled;
                _last.Error = null;
            }

            snapshot = _last?.Clone() ?? target.Clone();
        }

        await _audit.LogAsync(
            AuditEvents.CommandCancelled,
            new Dictionary<string, object?>
            {
                ["commandId"] = target.Id,
                ["action"] = target.Action.ToWireName()
            },
            cancellationToken).ConfigureAwait(false);

        return new PowerCancelResult(true, snapshot);
    }

    private async Task ExecuteAsync(PowerCommand command, string reason)
    {
        try
        {
            if (command.Action == PowerAction.Sleep)
            {
                await _delay.DelayAsync(
                    TimeSpan.FromSeconds(ProtocolConstants.SleepDelaySeconds),
                    _lifetime.Token).ConfigureAwait(false);

                if (IsNoLongerPending(command))
                {
                    return;
                }

                var sleepResult = await _controller.SleepAsync(_lifetime.Token).ConfigureAwait(false);
                if (sleepResult.Success)
                {
                    await TransitionAsync(command, PowerCommandStatus.Executed, null).ConfigureAwait(false);
                }
                else
                {
                    await TransitionAsync(command, PowerCommandStatus.Failed, sleepResult.Error ?? "sleep_failed")
                        .ConfigureAwait(false);
                }

                return;
            }

            var nativeResult = command.Action == PowerAction.Shutdown
                ? await _controller.ShutdownAsync(command.GraceSeconds, reason, _lifetime.Token).ConfigureAwait(false)
                : await _controller.RestartAsync(command.GraceSeconds, reason, _lifetime.Token).ConfigureAwait(false);

            if (!nativeResult.Success)
            {
                await TransitionAsync(
                    command,
                    PowerCommandStatus.Failed,
                    nativeResult.Error ?? "native_command_failed").ConfigureAwait(false);
                return;
            }

            // The OS is now counting down. Keep the command pending until executeAt so
            // the tray can display it and a local user can cancel it via /a.
            var remaining = command.ExecuteAt - _clock.UtcNow.ToUnixTimeSeconds();
            if (remaining > 0)
            {
                await _delay.DelayAsync(TimeSpan.FromSeconds(remaining), _lifetime.Token).ConfigureAwait(false);
            }

            await TransitionAsync(command, PowerCommandStatus.Executed, null).ConfigureAwait(false);
        }
        catch (OperationCanceledException) when (_lifetime.IsCancellationRequested)
        {
            _logger.LogInformation("Power command worker stopped because the agent is shutting down.");
        }
        catch (Exception ex)
        {
            _logger.LogError(ex, "Power command execution failed.");
            await TransitionAsync(command, PowerCommandStatus.Failed, "power_command_error").ConfigureAwait(false);
        }
    }

    private bool IsNoLongerPending(PowerCommand command)
    {
        lock (_sync)
        {
            return command.Status != PowerCommandStatus.Pending;
        }
    }

    private async Task TransitionAsync(PowerCommand command, PowerCommandStatus status, string? error)
    {
        var changed = false;
        lock (_sync)
        {
            if (command.Status == PowerCommandStatus.Pending)
            {
                command.Status = status;
                command.Error = error;
                if (_pending?.Id == command.Id)
                {
                    _pending = null;
                }

                _last = command;
                changed = true;
            }
        }

        if (!changed)
        {
            return;
        }

        await _audit.LogAsync(
            status == PowerCommandStatus.Executed ? AuditEvents.CommandExecuted : AuditEvents.CommandFailed,
            new Dictionary<string, object?>
            {
                ["commandId"] = command.Id,
                ["action"] = command.Action.ToWireName(),
                ["status"] = status.ToWireName()
            },
            CancellationToken.None).ConfigureAwait(false);
    }

    public async ValueTask DisposeAsync()
    {
        lock (_sync)
        {
            if (_disposed)
            {
                return;
            }

            _disposed = true;
        }

        _lifetime.Cancel();
        var worker = _worker;
        if (worker is not null)
        {
            try
            {
                await worker.ConfigureAwait(false);
            }
            catch (OperationCanceledException)
            {
            }
        }

        _lifetime.Dispose();
    }
}
