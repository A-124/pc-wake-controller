namespace PcWake.Agent.Core.Audit;

public static class AuditEvents
{
    public const string AuthOk = "auth_ok";
    public const string AuthFailure = "auth_failure";
    public const string AuthRateLimited = "auth_rate_limited";
    public const string PairingWindowStarted = "pairing_window_started";
    public const string PairingFailed = "pairing_failed";
    public const string PairingSucceeded = "pairing_succeeded";
    public const string PairingLocked = "pairing_locked";
    public const string DeviceRevoked = "device_revoked";
    public const string DeviceRevokeFailed = "device_revoke_failed";
    public const string CommandRequested = "command_requested";
    public const string CommandAccepted = "command_accepted";
    public const string CommandRejected = "command_rejected";
    public const string CommandExecuted = "command_executed";
    public const string CommandFailed = "command_failed";
    public const string CommandCancelled = "command_cancelled";
    public const string CommandCancelResult = "command_cancel_result";
    public const string LocalAdminAction = "local_admin_action";
    public const string ServiceStarted = "service_started";
    public const string ServiceStopped = "service_stopped";
    public const string DiscoveryError = "discovery_error";
}
