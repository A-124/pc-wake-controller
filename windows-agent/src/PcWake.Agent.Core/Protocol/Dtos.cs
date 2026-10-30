using PcWake.Agent.Core.Network;
using PcWake.Agent.Core.Power;

namespace PcWake.Agent.Core.Protocol;

public sealed class ErrorEnvelope
{
    public bool Ok => false;
    public string Error { get; init; } = ErrorCodes.InternalError;

    public ErrorEnvelope()
    {
    }

    public ErrorEnvelope(string error) => Error = error;
}

public sealed class HealthResponse
{
    public bool Ok => true;
    public string Service => ProtocolConstants.AgentServiceId;
    public string Version => ProtocolConstants.AgentVersion;
    public string Kind => ProtocolConstants.AgentKind;
}

public sealed class AdapterDto
{
    public string Name { get; init; } = string.Empty;
    public string Mac { get; init; } = string.Empty;
    public List<string> Ipv4 { get; init; } = [];
    public List<string> Ipv6 { get; init; } = [];

    public static AdapterDto From(NetworkAdapterInfo adapter) => new()
    {
        Name = adapter.Name,
        Mac = adapter.Mac,
        Ipv4 = [.. adapter.Ipv4],
        Ipv6 = [.. adapter.Ipv6]
    };
}

public sealed class PairRequestDto
{
    public string? Code { get; init; }
    public string? DeviceName { get; init; }
    public string? DeviceModel { get; init; }
    public string? AppVersion { get; init; }
}

public sealed class ServerInfoDto
{
    public string Kind => ProtocolConstants.AgentKind;
    public string Name { get; init; } = string.Empty;
    public string Version => ProtocolConstants.AgentVersion;
    public string AgentVersion => ProtocolConstants.AgentVersion;
    public string HostName { get; init; } = string.Empty;
    public List<AdapterDto> Adapters { get; init; } = [];
    public object? Targets => null;
}

public sealed class PairResponseDto
{
    public bool Ok => true;
    public string DeviceId { get; init; } = string.Empty;
    public string Token { get; init; } = string.Empty;
    public ServerInfoDto Server { get; init; } = new();
}

public sealed class CommandDto
{
    public string Id { get; init; } = string.Empty;
    public string Action { get; init; } = string.Empty;
    public long RequestedAt { get; init; }
    public long ExecuteAt { get; init; }
    public string Status { get; init; } = "pending";
    public int GraceSeconds { get; init; }
    public string? Error { get; init; }

    public static CommandDto From(PowerCommand command) => new()
    {
        Id = command.Id,
        Action = command.Action.ToWireName(),
        RequestedAt = command.RequestedAt,
        ExecuteAt = command.ExecuteAt,
        Status = command.Status.ToWireName(),
        GraceSeconds = command.GraceSeconds,
        Error = command.Error
    };
}

public sealed class StatusResponse
{
    public bool Ok => true;
    public string Name { get; init; } = string.Empty;
    public string HostName { get; init; } = string.Empty;
    public string Os { get; init; } = string.Empty;
    public string AgentVersion => ProtocolConstants.AgentVersion;
    public bool Online => true;
    public long UptimeSeconds { get; init; }
    public List<AdapterDto> Adapters { get; init; } = [];
    public List<string> Capabilities { get; init; } = [.. ProtocolConstants.Capabilities];
    public CommandDto? PendingCommand { get; init; }
    public CommandDto? LastCommand { get; init; }
}

public sealed class PowerRequestDto
{
    public int? GraceSeconds { get; init; }
    public string? Reason { get; init; }
}

public sealed class PowerAcceptedResponse
{
    public bool Ok => true;
    public CommandDto Command { get; init; } = new();
}

public sealed class CancelResponse
{
    public bool Ok => true;
    public bool Cancelled { get; init; }
}

public sealed class RevokeResponse
{
    public bool Ok => true;
}

public sealed class DiscoveryResponse
{
    public int V => ProtocolConstants.Version;
    public string Kind => ProtocolConstants.AgentKind;
    public string Name { get; init; } = string.Empty;
    public int Port { get; init; }
    public bool Paired { get; init; }
}

/// <summary>Single-line JSON payload used for the pairing QR code.</summary>
public sealed class PairingPayload
{
    public int V => ProtocolConstants.Version;
    public string Kind => ProtocolConstants.AgentKind;
    public string Name { get; init; } = string.Empty;
    public string Host { get; init; } = string.Empty;
    public int Port { get; init; }
    public string Fingerprint { get; init; } = string.Empty;
    public string Code { get; init; } = string.Empty;

    public string ToJson() => System.Text.Json.JsonSerializer.Serialize(this, JsonDefaults.Protocol);
}
