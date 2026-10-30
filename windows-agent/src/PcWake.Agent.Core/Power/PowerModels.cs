namespace PcWake.Agent.Core.Power;

public enum PowerAction
{
    Shutdown,
    Restart,
    Sleep
}

public enum PowerCommandStatus
{
    Pending,
    Executed,
    Failed,
    Cancelled
}

public static class PowerActionExtensions
{
    public static string ToWireName(this PowerAction action) => action switch
    {
        PowerAction.Shutdown => "shutdown",
        PowerAction.Restart => "restart",
        PowerAction.Sleep => "sleep",
        _ => "unknown"
    };

    public static string ToWireName(this PowerCommandStatus status) => status switch
    {
        PowerCommandStatus.Pending => "pending",
        PowerCommandStatus.Executed => "executed",
        PowerCommandStatus.Failed => "failed",
        PowerCommandStatus.Cancelled => "cancelled",
        _ => "unknown"
    };

    public static bool TryParseWire(string? value, out PowerAction action)
    {
        switch (value)
        {
            case "shutdown":
                action = PowerAction.Shutdown;
                return true;
            case "restart":
                action = PowerAction.Restart;
                return true;
            case "sleep":
                action = PowerAction.Sleep;
                return true;
            default:
                action = default;
                return false;
        }
    }
}

public sealed class PowerCommand
{
    public string Id { get; init; } = string.Empty;
    public PowerAction Action { get; init; }
    public long RequestedAt { get; init; }
    public long ExecuteAt { get; init; }
    public PowerCommandStatus Status { get; set; }
    public int GraceSeconds { get; init; }
    public string? Error { get; set; }

    public PowerCommand Clone() => new()
    {
        Id = Id,
        Action = Action,
        RequestedAt = RequestedAt,
        ExecuteAt = ExecuteAt,
        Status = Status,
        GraceSeconds = GraceSeconds,
        Error = Error
    };
}

public sealed record PowerSnapshot(PowerCommand? PendingCommand, PowerCommand? LastCommand);

public enum PowerStartError
{
    None,
    CommandPending,
    Unavailable
}

public sealed record PowerStartResult(PowerCommand? Command, PowerStartError Error);

public sealed record PowerCancelResult(bool Cancelled, PowerCommand? Command);
