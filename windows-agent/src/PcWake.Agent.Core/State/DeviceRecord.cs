namespace PcWake.Agent.Core.State;

public sealed class DeviceRecord
{
    public string DeviceId { get; set; } = string.Empty;
    public string TokenHash { get; set; } = string.Empty;
    public string Name { get; set; } = string.Empty;
    public string Model { get; set; } = string.Empty;
    public string AppVersion { get; set; } = string.Empty;
    public long PairedAt { get; set; }
    public long LastSeenAt { get; set; }
    public bool Revoked { get; set; }
    public long? RevokedAt { get; set; }

    public DeviceRecord Clone() => new()
    {
        DeviceId = DeviceId,
        TokenHash = TokenHash,
        Name = Name,
        Model = Model,
        AppVersion = AppVersion,
        PairedAt = PairedAt,
        LastSeenAt = LastSeenAt,
        Revoked = Revoked,
        RevokedAt = RevokedAt
    };
}

public sealed class AgentState
{
    public int SchemaVersion { get; set; } = 1;
    public List<DeviceRecord> Devices { get; set; } = [];
}
