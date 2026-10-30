using System.Globalization;
using Microsoft.Extensions.Logging;
using Microsoft.Extensions.Logging.Abstractions;
using PcWake.Agent.Core.Abstractions;
using PcWake.Agent.Core.Audit;
using PcWake.Agent.Core.Protocol;
using PcWake.Agent.Core.Secrets;
using PcWake.Agent.Core.State;

namespace PcWake.Agent.Core.Auth;

public sealed record AuthRequest(
    string Method,
    string PathWithQuery,
    string? DeviceId,
    string? Timestamp,
    string? Nonce,
    string? Signature,
    byte[] Body);

public enum AuthenticationOutcome
{
    Ok,
    Unauthorized,
    RateLimited
}

public sealed record AuthenticationResult(AuthenticationOutcome Outcome, DeviceRecord? Device)
{
    public bool Succeeded => Outcome == AuthenticationOutcome.Ok;
}

public sealed class RequestAuthenticator
{
    private readonly IDeviceStore _devices;
    private readonly IDeviceSecretStore _secrets;
    private readonly IClock _clock;
    private readonly NonceReplayCache _nonces;
    private readonly DeviceRateLimiter _deviceLimiter;
    private readonly AuthFailureRateLimiter _failureLimiter;
    private readonly IAuditLog _audit;
    private readonly ILogger _logger;

    public RequestAuthenticator(
        IDeviceStore devices,
        IDeviceSecretStore secrets,
        IClock clock,
        NonceReplayCache nonces,
        DeviceRateLimiter deviceLimiter,
        AuthFailureRateLimiter failureLimiter,
        IAuditLog audit,
        ILogger<RequestAuthenticator>? logger = null)
    {
        _devices = devices;
        _secrets = secrets;
        _clock = clock;
        _nonces = nonces;
        _deviceLimiter = deviceLimiter;
        _failureLimiter = failureLimiter;
        _audit = audit;
        _logger = logger ?? NullLogger<RequestAuthenticator>.Instance;
    }

    public async Task<AuthenticationResult> AuthenticateAsync(
        AuthRequest request,
        string sourceIp,
        CancellationToken cancellationToken = default)
    {
        var ip = string.IsNullOrWhiteSpace(sourceIp) ? "unknown" : sourceIp;

        if (_failureLimiter.IsBlocked(ip))
        {
            await _audit.LogAsync(
                AuditEvents.AuthRateLimited,
                new Dictionary<string, object?> { ["ip"] = ip },
                cancellationToken).ConfigureAwait(false);
            return new AuthenticationResult(AuthenticationOutcome.RateLimited, null);
        }

        if (string.IsNullOrEmpty(request.DeviceId) ||
            string.IsNullOrEmpty(request.Timestamp) ||
            string.IsNullOrEmpty(request.Nonce) ||
            string.IsNullOrEmpty(request.Signature))
        {
            return await FailAsync(ip, request.DeviceId, "missing_header", cancellationToken).ConfigureAwait(false);
        }

        if (!TokenGenerator.IsValidDeviceId(request.DeviceId))
        {
            return await FailAsync(ip, request.DeviceId, "malformed_device_id", cancellationToken).ConfigureAwait(false);
        }

        if (!long.TryParse(request.Timestamp, NumberStyles.None, CultureInfo.InvariantCulture, out var unixTimestamp))
        {
            return await FailAsync(ip, request.DeviceId, "malformed_timestamp", cancellationToken).ConfigureAwait(false);
        }

        var nowUnix = _clock.UtcNow.ToUnixTimeSeconds();
        if (unixTimestamp < nowUnix - ProtocolConstants.TimestampSkewSeconds ||
            unixTimestamp > nowUnix + ProtocolConstants.TimestampSkewSeconds)
        {
            return await FailAsync(ip, request.DeviceId, "timestamp_skew", cancellationToken).ConfigureAwait(false);
        }

        if (!Base64Url.IsWellFormed(request.Nonce, 16, 64) ||
            !Base64Url.TryDecode(request.Nonce, out var nonceBytes) ||
            nonceBytes.Length < 16)
        {
            return await FailAsync(ip, request.DeviceId, "malformed_nonce", cancellationToken).ConfigureAwait(false);
        }

        if (!Base64Url.TryDecode(request.Signature, out var signatureBytes) || signatureBytes.Length != 32)
        {
            return await FailAsync(ip, request.DeviceId, "malformed_signature", cancellationToken).ConfigureAwait(false);
        }

        var canonical = CanonicalRequest.Build(
            request.Method,
            request.PathWithQuery,
            request.Timestamp,
            request.Nonce,
            request.Body);

        var device = _devices.GetById(request.DeviceId);
        if (device is null)
        {
            CanonicalRequest.VerifyWithDummyKey(canonical, request.Signature);
            return await FailAsync(ip, request.DeviceId, "unknown_device", cancellationToken).ConfigureAwait(false);
        }

        if (device.Revoked)
        {
            CanonicalRequest.VerifyWithDummyKey(canonical, request.Signature);
            return await FailAsync(ip, request.DeviceId, "revoked_device", cancellationToken).ConfigureAwait(false);
        }

        if (!_secrets.TryGetToken(request.DeviceId, out var token) || string.IsNullOrEmpty(token))
        {
            CanonicalRequest.VerifyWithDummyKey(canonical, request.Signature);
            _logger.LogWarning("No signing secret is available for a paired device.");
            return await FailAsync(ip, request.DeviceId, "missing_secret", cancellationToken).ConfigureAwait(false);
        }

        if (!TokenGenerator.VerifyTokenHash(token, device.TokenHash))
        {
            CanonicalRequest.VerifyWithDummyKey(canonical, request.Signature);
            return await FailAsync(ip, request.DeviceId, "token_hash_mismatch", cancellationToken).ConfigureAwait(false);
        }

        if (!CanonicalRequest.VerifySignature(token, canonical, request.Signature))
        {
            return await FailAsync(ip, request.DeviceId, "bad_signature", cancellationToken).ConfigureAwait(false);
        }

        if (_nonces.Contains(request.DeviceId, request.Nonce))
        {
            return await FailAsync(ip, request.DeviceId, "replayed_nonce", cancellationToken).ConfigureAwait(false);
        }

        if (!_deviceLimiter.TryAcquire(request.DeviceId))
        {
            await _audit.LogAsync(
                AuditEvents.AuthRateLimited,
                new Dictionary<string, object?> { ["ip"] = ip, ["deviceId"] = request.DeviceId },
                cancellationToken).ConfigureAwait(false);
            return new AuthenticationResult(AuthenticationOutcome.RateLimited, null);
        }

        if (!_nonces.TryStore(request.DeviceId, request.Nonce, ProtocolConstants.NonceReplaySeconds))
        {
            return await FailAsync(ip, request.DeviceId, "replayed_nonce", cancellationToken).ConfigureAwait(false);
        }

        await _devices.TouchAsync(request.DeviceId, nowUnix, cancellationToken).ConfigureAwait(false);
        await _audit.LogAsync(
            AuditEvents.AuthOk,
            new Dictionary<string, object?>
            {
                ["deviceId"] = request.DeviceId,
                ["ip"] = ip,
                ["method"] = request.Method,
                ["path"] = request.PathWithQuery
            },
            cancellationToken).ConfigureAwait(false);

        return new AuthenticationResult(AuthenticationOutcome.Ok, device);
    }

    private async Task<AuthenticationResult> FailAsync(
        string ip,
        string? deviceId,
        string reason,
        CancellationToken cancellationToken)
    {
        _failureLimiter.RecordFailure(ip);
        try
        {
            await _audit.LogAsync(
                AuditEvents.AuthFailure,
                new Dictionary<string, object?>
                {
                    ["deviceId"] = deviceId,
                    ["ip"] = ip,
                    ["reason"] = reason
                },
                cancellationToken).ConfigureAwait(false);
        }
        catch (OperationCanceledException) when (cancellationToken.IsCancellationRequested)
        {
        }

        return new AuthenticationResult(AuthenticationOutcome.Unauthorized, null);
    }
}
