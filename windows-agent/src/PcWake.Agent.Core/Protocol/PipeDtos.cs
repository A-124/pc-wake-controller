using PcWake.Agent.Core.Power;

namespace PcWake.Agent.Core.Protocol;

public static class PipeCommands
{
    public const string PairStart = "pair-start";
    public const string PairStatus = "pair-status";
    public const string Devices = "devices";
    public const string Revoke = "revoke";
    public const string ServiceInfo = "service-info";
    public const string Status = "status";
    public const string Cancel = "cancel";
}

public sealed class PipeRequest
{
    public string Command { get; init; } = string.Empty;
    public string? DeviceId { get; init; }
}

public sealed class DeviceSummaryDto
{
    public string DeviceId { get; init; } = string.Empty;
    public string Name { get; init; } = string.Empty;
    public string Model { get; init; } = string.Empty;
    public string AppVersion { get; init; } = string.Empty;
    public long PairedAt { get; init; }
    public long LastSeenAt { get; init; }
    public bool Revoked { get; init; }
    public long? RevokedAt { get; init; }
}

/// <summary>
/// Response returned on both local pipes. The status pipe must never populate
/// <see cref="Code"/> or <see cref="Payload"/>; only the admin pipe may do so.
/// It never contains tokens or token hashes.
/// </summary>
public sealed class AgentStatusSnapshot
{
    public bool Ok { get; init; } = true;
    public string? Error { get; init; }
    public string ServiceName => ProtocolConstants.WindowsServiceName;
    public string ServiceId => ProtocolConstants.AgentServiceId;
    public string Version => ProtocolConstants.AgentVersion;
    public string Name { get; init; } = string.Empty;
    public string HostName { get; init; } = string.Empty;
    public string HostAddress { get; init; } = string.Empty;
    public int Port { get; init; }
    public string Fingerprint { get; init; } = string.Empty;
    public bool DiscoveryEnabled { get; init; }
    public int DiscoveryPort { get; init; }
    public long UptimeSeconds { get; init; }
    public bool PairingOpen { get; init; }
    public string PairingState { get; init; } = "closed";
    public long? PairingExpiresAt { get; init; }
    public long? PairingRemainingSeconds { get; init; }
    public string? PairedDeviceName { get; init; }
    public string? Code { get; init; }
    public string? Payload { get; init; }
    public List<DeviceSummaryDto> Devices { get; init; } = [];
    public CommandDto? PendingCommand { get; init; }
    public CommandDto? LastCommand { get; init; }
    public string? LogDirectory { get; init; }
    public string? ConfigPath { get; init; }
    public int ProcessId { get; init; }

    public AgentStatusSnapshot WithAdminPairing(string code, string payload) => new()
    {
        Ok = Ok,
        Error = Error,
        Name = Name,
        HostName = HostName,
        HostAddress = HostAddress,
        Port = Port,
        Fingerprint = Fingerprint,
        DiscoveryEnabled = DiscoveryEnabled,
        DiscoveryPort = DiscoveryPort,
        UptimeSeconds = UptimeSeconds,
        PairingOpen = PairingOpen,
        PairingState = PairingState,
        PairingExpiresAt = PairingExpiresAt,
        PairingRemainingSeconds = PairingRemainingSeconds,
        PairedDeviceName = PairedDeviceName,
        Code = code,
        Payload = payload,
        Devices = Devices,
        PendingCommand = PendingCommand,
        LastCommand = LastCommand,
        LogDirectory = LogDirectory,
        ConfigPath = ConfigPath,
        ProcessId = ProcessId
    };
}

public sealed class PipeCancelResult
{
    public bool Ok { get; init; } = true;
    public string? Error { get; init; }
    public bool Cancelled { get; init; }
    public string? Message { get; init; }
    public CommandDto? PendingCommand { get; init; }
}
