using System.Text.Json;
using PcWake.Agent.Core.Audit;
using PcWake.Agent.Core.Auth;
using PcWake.Agent.Core.Network;
using PcWake.Agent.Core.Pairing;
using PcWake.Agent.Core.Power;
using PcWake.Agent.Core.Protocol;
using PcWake.Agent.Core.Secrets;
using PcWake.Agent.Core.State;

namespace PcWake.Agent.Service.Endpoints;

internal static class AgentEndpoints
{
    public static void MapAgentEndpoints(this WebApplication app)
    {
        app.MapGet("/health", () => HttpHelpers.Json(new HealthResponse()));

        app.MapPost("/api/v1/pair", PairAsync);

        app.MapGet("/api/v1/status", (
            HttpContext context,
            AgentRuntimeInfo runtime,
            INetworkAdapterProvider adapters,
            PowerCommandService power) =>
        {
            var snapshot = power.GetSnapshot();
            var response = new StatusResponse
            {
                Name = runtime.Name,
                HostName = runtime.HostName,
                Os = runtime.OsDescription,
                UptimeSeconds = runtime.UptimeSeconds,
                Adapters = adapters.GetAdapters().Select(AdapterDto.From).ToList(),
                PendingCommand = snapshot.PendingCommand is null ? null : CommandDto.From(snapshot.PendingCommand),
                LastCommand = snapshot.LastCommand is null ? null : CommandDto.From(snapshot.LastCommand)
            };
            return HttpHelpers.Json(response);
        });

        app.MapPost("/api/v1/power/shutdown", (
                HttpContext context,
                PowerCommandService power,
                IAuditLog audit,
                CancellationToken cancellationToken) =>
            HandlePowerAsync(context, PowerAction.Shutdown, power, audit, cancellationToken));

        app.MapPost("/api/v1/power/restart", (
                HttpContext context,
                PowerCommandService power,
                IAuditLog audit,
                CancellationToken cancellationToken) =>
            HandlePowerAsync(context, PowerAction.Restart, power, audit, cancellationToken));

        app.MapPost("/api/v1/power/sleep", (
                HttpContext context,
                PowerCommandService power,
                IAuditLog audit,
                CancellationToken cancellationToken) =>
            HandlePowerAsync(context, PowerAction.Sleep, power, audit, cancellationToken));

        app.MapPost("/api/v1/power/cancel", (
            HttpContext context,
            PowerCommandService power,
            IAuditLog audit,
            CancellationToken cancellationToken) => CancelAsync(context, power, audit, cancellationToken));

        app.MapPost("/api/v1/revoke", (
            HttpContext context,
            DeviceStore devices,
            IDeviceSecretStore secrets,
            Core.Abstractions.IClock clock,
            IAuditLog audit,
            CancellationToken cancellationToken) =>
            RevokeAsync(context, devices, secrets, clock, audit, cancellationToken));
    }

    private static async Task<IResult> PairAsync(
        HttpContext context,
        PairingManager pairing,
        PairingRateLimiter pairLimiter,
        INetworkAdapterProvider adapters,
        AgentRuntimeInfo runtime,
        IAuditLog audit,
        CancellationToken cancellationToken)
    {
        var ip = HttpHelpers.SourceIp(context);
        if (!pairLimiter.TryAcquire(ip))
        {
            await audit.LogAsync(
                AuditEvents.AuthRateLimited,
                new Dictionary<string, object?> { ["ip"] = ip, ["scope"] = "pair" },
                cancellationToken).ConfigureAwait(false);
            return HttpHelpers.Json(new ErrorEnvelope(ErrorCodes.RateLimited), StatusCodes.Status429TooManyRequests);
        }

        byte[] body;
        try
        {
            body = await HttpHelpers.ReadPairBodyAsync(context.Request, cancellationToken).ConfigureAwait(false);
        }
        catch (RequestBodyTooLargeException)
        {
            return HttpHelpers.Json(new ErrorEnvelope(ErrorCodes.InvalidRequest), StatusCodes.Status400BadRequest);
        }

        PairRequestDto? request;
        try
        {
            request = JsonSerializer.Deserialize<PairRequestDto>(body, JsonDefaults.Protocol);
        }
        catch (JsonException)
        {
            return HttpHelpers.Json(new ErrorEnvelope(ErrorCodes.InvalidRequest), StatusCodes.Status400BadRequest);
        }

        if (request is null || string.IsNullOrWhiteSpace(request.Code))
        {
            return HttpHelpers.Json(new ErrorEnvelope(ErrorCodes.InvalidRequest), StatusCodes.Status400BadRequest);
        }

        var deviceName = TextSanitizer.SanitizeLine(request.DeviceName, 64);
        if (deviceName is null)
        {
            return HttpHelpers.Json(new ErrorEnvelope(ErrorCodes.InvalidRequest), StatusCodes.Status400BadRequest);
        }

        var deviceModel = TextSanitizer.SanitizeLine(request.DeviceModel, 100) ?? "Android device";
        var appVersion = TextSanitizer.SanitizeLine(request.AppVersion, 40) ?? "unknown";

        var outcome = await pairing.TryPairAsync(
            request.Code,
            new PairDeviceInfo(deviceName, deviceModel, appVersion),
            ip,
            cancellationToken).ConfigureAwait(false);

        switch (outcome.Error)
        {
            case PairingError.InvalidCode:
                return HttpHelpers.Json(new ErrorEnvelope(ErrorCodes.InvalidCode), StatusCodes.Status401Unauthorized);
            case PairingError.Closed:
                return HttpHelpers.Json(new ErrorEnvelope(ErrorCodes.PairingClosed), StatusCodes.Status409Conflict);
            case PairingError.TooManyAttempts:
                return HttpHelpers.Json(new ErrorEnvelope(ErrorCodes.TooManyAttempts), StatusCodes.Status429TooManyRequests);
            case PairingError.InternalError:
                return HttpHelpers.Json(new ErrorEnvelope(ErrorCodes.InternalError), StatusCodes.Status500InternalServerError);
        }

        var credentials = outcome.Credentials!;
        var response = new PairResponseDto
        {
            DeviceId = credentials.DeviceId,
            Token = credentials.Token,
            Server = new ServerInfoDto
            {
                Name = runtime.Name,
                HostName = runtime.HostName,
                Adapters = adapters.GetAdapters().Select(AdapterDto.From).ToList()
            }
        };
        return HttpHelpers.Json(response);
    }

    private static async Task<IResult> HandlePowerAsync(
        HttpContext context,
        PowerAction action,
        PowerCommandService power,
        IAuditLog audit,
        CancellationToken cancellationToken)
    {
        PowerRequestDto? request;
        if (!HttpHelpers.TryGetJsonBody(context, out request))
        {
            return HttpHelpers.Json(new ErrorEnvelope(ErrorCodes.InvalidRequest), StatusCodes.Status400BadRequest);
        }

        request ??= new PowerRequestDto();
        var device = context.Items[HttpItems.Device] as DeviceRecord;
        var result = await power.StartAsync(
            action,
            request.GraceSeconds,
            request.Reason,
            cancellationToken).ConfigureAwait(false);

        if (result.Error == PowerStartError.CommandPending)
        {
            await audit.LogAsync(
                AuditEvents.CommandRejected,
                new Dictionary<string, object?>
                {
                    ["deviceId"] = device?.DeviceId,
                    ["action"] = action.ToWireName(),
                    ["reason"] = ErrorCodes.CommandPending,
                    ["ip"] = HttpHelpers.SourceIp(context)
                },
                cancellationToken).ConfigureAwait(false);
            return HttpHelpers.Json(new ErrorEnvelope(ErrorCodes.CommandPending), StatusCodes.Status409Conflict);
        }

        if (result.Error == PowerStartError.Unavailable || result.Command is null)
        {
            return HttpHelpers.Json(new ErrorEnvelope(ErrorCodes.InternalError), StatusCodes.Status503ServiceUnavailable);
        }

        await audit.LogAsync(
            AuditEvents.CommandRequested,
            new Dictionary<string, object?>
            {
                ["deviceId"] = device?.DeviceId,
                ["commandId"] = result.Command.Id,
                ["action"] = action.ToWireName(),
                ["graceSeconds"] = result.Command.GraceSeconds,
                ["ip"] = HttpHelpers.SourceIp(context)
            },
            cancellationToken).ConfigureAwait(false);

        return HttpHelpers.Json(
            new PowerAcceptedResponse { Command = CommandDto.From(result.Command) },
            StatusCodes.Status202Accepted);
    }

    private static async Task<IResult> CancelAsync(
        HttpContext context,
        PowerCommandService power,
        IAuditLog audit,
        CancellationToken cancellationToken)
    {
        var result = await power.CancelAsync(cancellationToken).ConfigureAwait(false);
        var device = context.Items[HttpItems.Device] as DeviceRecord;

        await audit.LogAsync(
            AuditEvents.CommandCancelResult,
            new Dictionary<string, object?>
            {
                ["deviceId"] = device?.DeviceId,
                ["cancelled"] = result.Cancelled,
                ["ip"] = HttpHelpers.SourceIp(context)
            },
            cancellationToken).ConfigureAwait(false);

        return HttpHelpers.Json(new CancelResponse { Cancelled = result.Cancelled });
    }

    private static async Task<IResult> RevokeAsync(
        HttpContext context,
        DeviceStore devices,
        IDeviceSecretStore secrets,
        Core.Abstractions.IClock clock,
        IAuditLog audit,
        CancellationToken cancellationToken)
    {
        var device = context.Items[HttpItems.Device] as DeviceRecord;
        if (device is null)
        {
            return HttpHelpers.Json(new ErrorEnvelope(ErrorCodes.Unauthorized), StatusCodes.Status401Unauthorized);
        }

        var revoked = await devices.RevokeAsync(
            device.DeviceId,
            clock.UtcNow.ToUnixTimeSeconds(),
            cancellationToken).ConfigureAwait(false);

        if (!revoked)
        {
            return HttpHelpers.Json(new ErrorEnvelope(ErrorCodes.InternalError), StatusCodes.Status500InternalServerError);
        }

        try
        {
            await secrets.RemoveTokenAsync(device.DeviceId, cancellationToken).ConfigureAwait(false);
        }
        catch (Exception ex) when (ex is IOException or UnauthorizedAccessException)
        {
            // The device is already revoked in state.json, so authentication is denied
            // regardless of whether vault cleanup succeeded.
        }

        await audit.LogAsync(
            AuditEvents.DeviceRevoked,
            new Dictionary<string, object?>
            {
                ["deviceId"] = device.DeviceId,
                ["deviceName"] = device.Name,
                ["source"] = "http",
                ["ip"] = HttpHelpers.SourceIp(context)
            },
            cancellationToken).ConfigureAwait(false);

        return HttpHelpers.Json(new RevokeResponse());
    }
}
