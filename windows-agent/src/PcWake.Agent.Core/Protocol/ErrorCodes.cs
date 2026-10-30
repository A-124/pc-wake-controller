namespace PcWake.Agent.Core.Protocol;

public static class ErrorCodes
{
    public const string Unauthorized = "unauthorized";
    public const string RateLimited = "rate_limited";
    public const string InvalidCode = "invalid_code";
    public const string PairingClosed = "pairing_closed";
    public const string TooManyAttempts = "too_many_attempts";
    public const string InvalidRequest = "invalid_request";
    public const string CommandPending = "command_pending";
    public const string NotFound = "not_found";
    public const string InternalError = "internal_error";
}
