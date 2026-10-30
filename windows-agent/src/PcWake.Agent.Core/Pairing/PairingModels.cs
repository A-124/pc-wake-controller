namespace PcWake.Agent.Core.Pairing;

public enum PairingState
{
    Closed,
    Open,
    Paired,
    Expired,
    Locked
}

public static class PairingStateExtensions
{
    public static string ToWireName(this PairingState state) => state switch
    {
        PairingState.Open => "open",
        PairingState.Paired => "paired",
        PairingState.Expired => "expired",
        PairingState.Locked => "locked",
        _ => "closed"
    };
}

public enum PairingError
{
    None,
    InvalidCode,
    Closed,
    TooManyAttempts,
    InternalError
}

public sealed record PairingContext(string Name, string Host, int Port, string Fingerprint);

public sealed record PairDeviceInfo(string DeviceName, string DeviceModel, string AppVersion);

public sealed record PairingCredentials(string DeviceId, string Token);

public sealed record PairingOutcome(PairingError Error, PairingCredentials? Credentials)
{
    public bool Succeeded => Error == PairingError.None && Credentials is not null;
}

public sealed record PairingStatusInfo(
    PairingState State,
    long? ExpiresAt,
    long? RemainingSeconds,
    string? PairedDeviceName);

public sealed record PairingStartInfo(
    PairingContext Context,
    string Code,
    string Payload,
    long ExpiresAt,
    long RemainingSeconds);
