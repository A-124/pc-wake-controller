using System.IO.Pipes;
using System.Security.AccessControl;
using System.Security.Principal;
using System.Text;
using System.Text.Json;
using PcWake.Agent.Core.Abstractions;
using PcWake.Agent.Core.Audit;
using PcWake.Agent.Core.Config;
using PcWake.Agent.Core.Pairing;
using PcWake.Agent.Core.Power;
using PcWake.Agent.Core.Protocol;
using PcWake.Agent.Core.Secrets;
using PcWake.Agent.Core.State;

namespace PcWake.Agent.Service.Ipc;

/// <summary>
/// Local control pipes. PcWakeAgent.Admin is SYSTEM+Administrators only and can
/// start pairing/revoke; PcWakeAgent.Status is available to Authenticated Users
/// and exposes only sanitized status plus the local cancel action.
/// </summary>
public sealed class AgentPipeHost : BackgroundService
{
    private const int MaxRequestChars = 64 * 1024;
    private static readonly TimeSpan ConnectionTimeout = TimeSpan.FromSeconds(10);

    private readonly PairingManager _pairing;
    private readonly DeviceStore _devices;
    private readonly IDeviceSecretStore _secrets;
    private readonly PowerCommandService _power;
    private readonly AgentRuntimeInfo _runtime;
    private readonly AgentConfig _config;
    private readonly AgentPaths _paths;
    private readonly IClock _clock;
    private readonly IAuditLog _audit;
    private readonly ILogger<AgentPipeHost> _logger;

    public AgentPipeHost(
        PairingManager pairing,
        DeviceStore devices,
        IDeviceSecretStore secrets,
        PowerCommandService power,
        AgentRuntimeInfo runtime,
        AgentConfig config,
        AgentPaths paths,
        IClock clock,
        IAuditLog audit,
        ILogger<AgentPipeHost> logger)
    {
        _pairing = pairing;
        _devices = devices;
        _secrets = secrets;
        _power = power;
        _runtime = runtime;
        _config = config;
        _paths = paths;
        _clock = clock;
        _audit = audit;
        _logger = logger;
    }

    protected override async Task ExecuteAsync(CancellationToken stoppingToken)
    {
        var adminLoop = AcceptLoopAsync(ProtocolConstants.AdminPipeName, isAdmin: true, stoppingToken);
        var statusLoop = AcceptLoopAsync(ProtocolConstants.StatusPipeName, isAdmin: false, stoppingToken);
        await Task.WhenAll(adminLoop, statusLoop).ConfigureAwait(false);
    }

    private async Task AcceptLoopAsync(string pipeName, bool isAdmin, CancellationToken stoppingToken)
    {
        var security = CreatePipeSecurity(isAdmin);

        while (!stoppingToken.IsCancellationRequested)
        {
            try
            {
                await using var pipe = NamedPipeServerStreamAcl.Create(
                    pipeName,
                    PipeDirection.InOut,
                    maxNumberOfServerInstances: 4,
                    PipeTransmissionMode.Byte,
                    PipeOptions.Asynchronous,
                    inBufferSize: 16 * 1024,
                    outBufferSize: 16 * 1024,
                    security);

                await pipe.WaitForConnectionAsync(stoppingToken).ConfigureAwait(false);
                using var timeout = CancellationTokenSource.CreateLinkedTokenSource(stoppingToken);
                timeout.CancelAfter(ConnectionTimeout);
                await HandleConnectionAsync(pipe, isAdmin, timeout.Token).ConfigureAwait(false);
            }
            catch (OperationCanceledException) when (stoppingToken.IsCancellationRequested)
            {
                break;
            }
            catch (IOException ex)
            {
                _logger.LogDebug(ex, "Named pipe {Pipe} connection ended.", pipeName);
            }
            catch (UnauthorizedAccessException ex)
            {
                _logger.LogWarning(ex, "Named pipe {Pipe} access failed.", pipeName);
            }
            catch (Exception ex)
            {
                _logger.LogError(ex, "Named pipe {Pipe} failed unexpectedly.", pipeName);
                try
                {
                    await Task.Delay(TimeSpan.FromSeconds(1), stoppingToken).ConfigureAwait(false);
                }
                catch (OperationCanceledException)
                {
                    break;
                }
            }
        }
    }

    private async Task HandleConnectionAsync(NamedPipeServerStream pipe, bool isAdmin, CancellationToken cancellationToken)
    {
        using var reader = new StreamReader(
            pipe,
            Encoding.UTF8,
            detectEncodingFromByteOrderMarks: false,
            bufferSize: 4096,
            leaveOpen: true);
        using var writer = new StreamWriter(pipe, new UTF8Encoding(false), bufferSize: 4096, leaveOpen: true)
        {
            AutoFlush = true
        };

        var line = await ReadLineLimitedAsync(reader, MaxRequestChars, cancellationToken).ConfigureAwait(false);
        if (line is null)
        {
            await WriteAsync(writer, new AgentStatusSnapshot { Ok = false, Error = ErrorCodes.InvalidRequest }, cancellationToken)
                .ConfigureAwait(false);
            return;
        }

        PipeRequest? request;
        try
        {
            request = JsonSerializer.Deserialize<PipeRequest>(line, JsonDefaults.Protocol);
        }
        catch (JsonException)
        {
            request = null;
        }

        if (request is null || string.IsNullOrWhiteSpace(request.Command))
        {
            await WriteAsync(writer, new AgentStatusSnapshot { Ok = false, Error = ErrorCodes.InvalidRequest }, cancellationToken)
                .ConfigureAwait(false);
            return;
        }

        if (isAdmin)
        {
            await HandleAdminAsync(writer, request, cancellationToken).ConfigureAwait(false);
        }
        else
        {
            await HandleStatusAsync(writer, request, cancellationToken).ConfigureAwait(false);
        }
    }

    private async Task HandleAdminAsync(
        StreamWriter writer,
        PipeRequest request,
        CancellationToken cancellationToken)
    {
        switch (request.Command)
        {
            case PipeCommands.PairStart:
            {
                var start = await _pairing.StartAsync(
                    new PairingContext(
                        _runtime.Name,
                        _runtime.HostAddress,
                        _runtime.Port,
                        _runtime.Fingerprint),
                    cancellationToken).ConfigureAwait(false);

                await _audit.LogAsync(
                    AuditEvents.LocalAdminAction,
                    new Dictionary<string, object?> { ["action"] = "pair-start", ["source"] = "admin_pipe" },
                    cancellationToken).ConfigureAwait(false);

                var snapshot = BuildSnapshot().WithAdminPairing(start.Code, start.Payload);
                await WriteAsync(writer, snapshot, cancellationToken).ConfigureAwait(false);
                return;
            }

            case PipeCommands.PairStatus:
            {
                var snapshot = BuildSnapshot();
                var material = _pairing.GetOpenPairingMaterial();
                if (material is not null)
                {
                    snapshot = snapshot.WithAdminPairing(material.Value.Code, material.Value.Payload);
                }

                await WriteAsync(writer, snapshot, cancellationToken).ConfigureAwait(false);
                return;
            }

            case PipeCommands.ServiceInfo:
            case PipeCommands.Devices:
                await WriteAsync(writer, BuildSnapshot(), cancellationToken).ConfigureAwait(false);
                return;

            case PipeCommands.Revoke:
            {
                if (string.IsNullOrWhiteSpace(request.DeviceId))
                {
                    await WriteAsync(writer, new AgentStatusSnapshot { Ok = false, Error = ErrorCodes.InvalidRequest }, cancellationToken)
                        .ConfigureAwait(false);
                    return;
                }

                var revoked = await _devices.RevokeAsync(
                    request.DeviceId,
                    _clock.UtcNow.ToUnixTimeSeconds(),
                    cancellationToken).ConfigureAwait(false);

                if (!revoked)
                {
                    await WriteAsync(writer, new AgentStatusSnapshot { Ok = false, Error = ErrorCodes.NotFound }, cancellationToken)
                        .ConfigureAwait(false);
                    return;
                }

                try
                {
                    await _secrets.RemoveTokenAsync(request.DeviceId, cancellationToken).ConfigureAwait(false);
                }
                catch (Exception ex) when (ex is IOException or UnauthorizedAccessException)
                {
                    _logger.LogWarning(ex, "Could not remove the revoked device secret.");
                }

                await _audit.LogAsync(
                    AuditEvents.DeviceRevoked,
                    new Dictionary<string, object?>
                    {
                        ["deviceId"] = request.DeviceId,
                        ["source"] = "admin_pipe",
                        ["ip"] = "local"
                    },
                    cancellationToken).ConfigureAwait(false);

                await WriteAsync(writer, BuildSnapshot(), cancellationToken).ConfigureAwait(false);
                return;
            }

            default:
                await WriteAsync(writer, new AgentStatusSnapshot { Ok = false, Error = ErrorCodes.NotFound }, cancellationToken)
                    .ConfigureAwait(false);
                return;
        }
    }

    private async Task HandleStatusAsync(
        StreamWriter writer,
        PipeRequest request,
        CancellationToken cancellationToken)
    {
        switch (request.Command)
        {
            case PipeCommands.Status:
                await WriteAsync(writer, BuildSnapshot(), cancellationToken).ConfigureAwait(false);
                return;

            case PipeCommands.Cancel:
            {
                var result = await _power.CancelAsync(cancellationToken).ConfigureAwait(false);
                var snapshot = BuildSnapshot();
                var response = new PipeCancelResult
                {
                    Cancelled = result.Cancelled,
                    PendingCommand = snapshot.PendingCommand,
                    Message = result.Cancelled
                        ? "cancelled"
                        : snapshot.PendingCommand?.Action == "sleep"
                            ? "sleep_cannot_be_cancelled"
                            : "no_pending_command"
                };

                await _audit.LogAsync(
                    AuditEvents.CommandCancelResult,
                    new Dictionary<string, object?>
                    {
                        ["cancelled"] = result.Cancelled,
                        ["source"] = "status_pipe",
                        ["ip"] = "local"
                    },
                    cancellationToken).ConfigureAwait(false);

                await WriteAsync(writer, response, cancellationToken).ConfigureAwait(false);
                return;
            }

            default:
                await WriteAsync(writer, new AgentStatusSnapshot { Ok = false, Error = ErrorCodes.NotFound }, cancellationToken)
                    .ConfigureAwait(false);
                return;
        }
    }

    private AgentStatusSnapshot BuildSnapshot()
    {
        var pairing = _pairing.GetStatus();
        var power = _power.GetSnapshot();
        return new AgentStatusSnapshot
        {
            Name = _runtime.Name,
            HostName = _runtime.HostName,
            HostAddress = _runtime.HostAddress,
            Port = _runtime.Port,
            Fingerprint = _runtime.Fingerprint,
            DiscoveryEnabled = _config.DiscoveryEnabled,
            DiscoveryPort = _config.DiscoveryPort,
            UptimeSeconds = _runtime.UptimeSeconds,
            PairingOpen = pairing.State == PairingState.Open,
            PairingState = pairing.State.ToWireName(),
            PairingExpiresAt = pairing.ExpiresAt,
            PairingRemainingSeconds = pairing.RemainingSeconds,
            PairedDeviceName = pairing.PairedDeviceName,
            Devices = _devices.GetAll().Select(d => new DeviceSummaryDto
            {
                DeviceId = d.DeviceId,
                Name = d.Name,
                Model = d.Model,
                AppVersion = d.AppVersion,
                PairedAt = d.PairedAt,
                LastSeenAt = d.LastSeenAt,
                Revoked = d.Revoked,
                RevokedAt = d.RevokedAt
            }).ToList(),
            PendingCommand = power.PendingCommand is null ? null : CommandDto.From(power.PendingCommand),
            LastCommand = power.LastCommand is null ? null : CommandDto.From(power.LastCommand),
            LogDirectory = _paths.LogDirectory,
            ConfigPath = _paths.ConfigFile,
            ProcessId = Environment.ProcessId
        };
    }

    private static PipeSecurity CreatePipeSecurity(bool isAdmin)
    {
        var security = new PipeSecurity();
        security.SetAccessRuleProtection(true, false);

        var system = new SecurityIdentifier(WellKnownSidType.LocalSystemSid, null);
        security.AddAccessRule(new PipeAccessRule(system, PipeAccessRights.FullControl, AccessControlType.Allow));

        if (isAdmin)
        {
            var admins = new SecurityIdentifier(WellKnownSidType.BuiltinAdministratorsSid, null);
            security.AddAccessRule(new PipeAccessRule(admins, PipeAccessRights.FullControl, AccessControlType.Allow));
        }
        else
        {
            var authenticated = new SecurityIdentifier(WellKnownSidType.AuthenticatedUserSid, null);
            security.AddAccessRule(new PipeAccessRule(
                authenticated,
                PipeAccessRights.ReadWrite,
                AccessControlType.Allow));
        }

        return security;
    }

    private static async Task WriteAsync(StreamWriter writer, object value, CancellationToken cancellationToken)
    {
        var json = JsonSerializer.Serialize(value, JsonDefaults.Protocol);
        await writer.WriteLineAsync(json.AsMemory(), cancellationToken).ConfigureAwait(false);
        await writer.FlushAsync(cancellationToken).ConfigureAwait(false);
    }

    private static async Task<string?> ReadLineLimitedAsync(
        StreamReader reader,
        int maxChars,
        CancellationToken cancellationToken)
    {
        var builder = new StringBuilder(256);
        var buffer = new char[512];
        while (true)
        {
            var read = await reader.ReadAsync(buffer.AsMemory(), cancellationToken).ConfigureAwait(false);
            if (read == 0)
            {
                return builder.Length == 0 ? null : builder.ToString();
            }

            for (var i = 0; i < read; i++)
            {
                var c = buffer[i];
                if (c == '\n')
                {
                    return builder.ToString().TrimEnd('\r');
                }

                if (builder.Length >= maxChars)
                {
                    return null;
                }

                builder.Append(c);
            }
        }
    }
}
