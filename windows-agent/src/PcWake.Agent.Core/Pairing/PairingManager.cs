using Microsoft.Extensions.Logging;
using Microsoft.Extensions.Logging.Abstractions;
using PcWake.Agent.Core.Abstractions;
using PcWake.Agent.Core.Audit;
using PcWake.Agent.Core.Protocol;
using PcWake.Agent.Core.Secrets;
using PcWake.Agent.Core.State;

namespace PcWake.Agent.Core.Pairing;

/// <summary>
/// One-time, 5-minute pairing window. Codes and failure counters live in memory
/// only; none of them is persisted or written to the audit log.
/// </summary>
public sealed class PairingManager
{
    private readonly IDeviceStore _devices;
    private readonly IDeviceSecretStore _secrets;
    private readonly IClock _clock;
    private readonly IAuditLog _audit;
    private readonly ILogger _logger;
    private readonly SemaphoreSlim _gate = new(1, 1);
    private readonly object _sync = new();
    private Window? _window;

    public PairingManager(
        IDeviceStore devices,
        IDeviceSecretStore secrets,
        IClock clock,
        IAuditLog audit,
        ILogger<PairingManager>? logger = null)
    {
        _devices = devices;
        _secrets = secrets;
        _clock = clock;
        _audit = audit;
        _logger = logger ?? NullLogger<PairingManager>.Instance;
    }

    public async Task<PairingStartInfo> StartAsync(
        PairingContext context,
        CancellationToken cancellationToken = default)
    {
        ValidateContext(context);
        var code = CrockfordBase32.GenerateNormalizedCode();
        var now = _clock.UtcNow.ToUnixTimeSeconds();
        var expiresAt = now + ProtocolConstants.PairingWindowSeconds;

        await _gate.WaitAsync(cancellationToken).ConfigureAwait(false);
        try
        {
            lock (_sync)
            {
                _window = new Window
                {
                    Context = context,
                    NormalizedCode = code,
                    FormattedCode = CrockfordBase32.Format(code),
                    Payload = new PairingPayload
                    {
                        Name = context.Name,
                        Host = context.Host,
                        Port = context.Port,
                        Fingerprint = context.Fingerprint,
                        Code = CrockfordBase32.Format(code)
                    }.ToJson(),
                    StartedAt = now,
                    ExpiresAt = expiresAt,
                    State = PairingState.Open
                };
            }

            await _audit.LogAsync(
                AuditEvents.PairingWindowStarted,
                new Dictionary<string, object?>
                {
                    ["name"] = context.Name,
                    ["host"] = context.Host,
                    ["port"] = context.Port,
                    ["expiresAt"] = expiresAt
                },
                cancellationToken).ConfigureAwait(false);
        }
        finally
        {
            _gate.Release();
        }

        var payload = new PairingPayload
        {
            Name = context.Name,
            Host = context.Host,
            Port = context.Port,
            Fingerprint = context.Fingerprint,
            Code = CrockfordBase32.Format(code)
        }.ToJson();

        return new PairingStartInfo(
            context,
            CrockfordBase32.Format(code),
            payload,
            expiresAt,
            ProtocolConstants.PairingWindowSeconds);
    }

    public PairingStatusInfo GetStatus()
    {
        lock (_sync)
        {
            if (_window is null)
            {
                return new PairingStatusInfo(PairingState.Closed, null, null, null);
            }

            var now = _clock.UtcNow.ToUnixTimeSeconds();
            if (_window.State == PairingState.Open && now >= _window.ExpiresAt)
            {
                _window.State = PairingState.Expired;
            }

            var remaining = _window.State == PairingState.Open
                ? Math.Max(0, _window.ExpiresAt - now)
                : 0;

            return new PairingStatusInfo(
                _window.State,
                _window.ExpiresAt,
                _window.State == PairingState.Open ? remaining : null,
                _window.PairedDeviceName);
        }
    }

    public bool IsOpen => GetStatus().State == PairingState.Open;

    /// <summary>
    /// Admin-pipe-only material. Returns null unless the window is still open, so
    /// code/QR data disappears from every status response after pairing closes.
    /// </summary>
    public (string Code, string Payload)? GetOpenPairingMaterial()
    {
        lock (_sync)
        {
            if (_window is null ||
                _window.State != PairingState.Open ||
                _clock.UtcNow.ToUnixTimeSeconds() >= _window.ExpiresAt)
            {
                return null;
            }

            return (_window.FormattedCode, _window.Payload);
        }
    }

    public async Task<PairingOutcome> TryPairAsync(
        string? suppliedCode,
        PairDeviceInfo info,
        string? sourceIp = null,
        CancellationToken cancellationToken = default)
    {
        var normalized = CrockfordBase32.Normalize(suppliedCode);

        await _gate.WaitAsync(cancellationToken).ConfigureAwait(false);
        try
        {
            Window? window;
            PairingState precheck;
            int attempts;
            lock (_sync)
            {
                window = _window;
                if (window is null || window.State == PairingState.Closed || window.State == PairingState.Paired)
                {
                    precheck = PairingState.Closed;
                    attempts = 0;
                }
                else if (window.State == PairingState.Locked)
                {
                    precheck = PairingState.Locked;
                    attempts = window.FailedAttempts;
                }
                else if (window.State == PairingState.Expired || _clock.UtcNow.ToUnixTimeSeconds() >= window.ExpiresAt)
                {
                    window.State = PairingState.Expired;
                    precheck = PairingState.Expired;
                    attempts = window.FailedAttempts;
                }
                else
                {
                    precheck = PairingState.Open;
                    attempts = window.FailedAttempts;
                }
            }

            if (precheck == PairingState.Closed)
            {
                await LogFailureAsync("pairing_closed", null, sourceIp, attempts, cancellationToken).ConfigureAwait(false);
                return new PairingOutcome(PairingError.Closed, null);
            }

            if (precheck == PairingState.Locked)
            {
                await LogFailureAsync("too_many_attempts", null, sourceIp, attempts, cancellationToken).ConfigureAwait(false);
                return new PairingOutcome(PairingError.TooManyAttempts, null);
            }

            if (precheck == PairingState.Expired)
            {
                await LogFailureAsync("expired_code", null, sourceIp, attempts, cancellationToken).ConfigureAwait(false);
                return new PairingOutcome(PairingError.InvalidCode, null);
            }

            var codeMatches = CrockfordBase32.IsValidNormalized(normalized) &&
                              CrockfordBase32.FixedTimeEquals(window!.NormalizedCode, normalized);
            if (!codeMatches)
            {
                int failedAttempts;
                var locked = false;
                lock (_sync)
                {
                    window!.FailedAttempts++;
                    failedAttempts = window.FailedAttempts;
                    if (failedAttempts >= ProtocolConstants.PairingMaxAttempts)
                    {
                        window.State = PairingState.Locked;
                        locked = true;
                    }
                }

                await LogFailureAsync(
                    locked ? "attempt_limit_reached" : "invalid_code",
                    null,
                    sourceIp,
                    failedAttempts,
                    cancellationToken).ConfigureAwait(false);
                if (locked)
                {
                    await _audit.LogAsync(
                        AuditEvents.PairingLocked,
                        new Dictionary<string, object?> { ["attempts"] = failedAttempts, ["ip"] = sourceIp },
                        cancellationToken).ConfigureAwait(false);
                }

                return new PairingOutcome(PairingError.InvalidCode, null);
            }

            if (string.IsNullOrWhiteSpace(info.DeviceName))
            {
                await LogFailureAsync("missing_device_name", null, sourceIp, attempts, cancellationToken).ConfigureAwait(false);
                return new PairingOutcome(PairingError.InvalidCode, null);
            }

            var deviceId = TokenGenerator.CreateDeviceId();
            var token = TokenGenerator.CreateToken();
            var tokenHash = TokenGenerator.HashToken(token);
            var now = _clock.UtcNow.ToUnixTimeSeconds();
            var record = new DeviceRecord
            {
                DeviceId = deviceId,
                TokenHash = tokenHash,
                Name = info.DeviceName,
                Model = info.DeviceModel,
                AppVersion = info.AppVersion,
                PairedAt = now,
                LastSeenAt = now,
                Revoked = false
            };

            var secretStored = false;
            try
            {
                await _secrets.SetTokenAsync(deviceId, token, cancellationToken).ConfigureAwait(false);
                secretStored = true;
                await _devices.AddAsync(record, cancellationToken).ConfigureAwait(false);
            }
            catch (Exception ex)
            {
                _logger.LogError(ex, "Failed to persist a newly paired device.");
                if (secretStored)
                {
                    try
                    {
                        await _secrets.RemoveTokenAsync(deviceId, cancellationToken).ConfigureAwait(false);
                    }
                    catch (Exception cleanup) when (cleanup is IOException or UnauthorizedAccessException)
                    {
                        _logger.LogWarning(cleanup, "Failed to clean up the device secret after a pairing failure.");
                    }
                }

                await LogFailureAsync("persistence_error", deviceId, sourceIp, attempts, cancellationToken).ConfigureAwait(false);
                return new PairingOutcome(PairingError.InternalError, null);
            }

            bool paired;
            lock (_sync)
            {
                // Re-check under the gate; only this method can consume an open window.
                paired = window!.State == PairingState.Open;
                if (paired)
                {
                    window.State = PairingState.Paired;
                    window.PairedDeviceId = deviceId;
                    window.PairedDeviceName = info.DeviceName;
                }
            }

            if (!paired)
            {
                // Extremely defensive: persistence succeeded but another path closed the window.
                await _devices.RevokeAsync(deviceId, now, cancellationToken).ConfigureAwait(false);
                await _secrets.RemoveTokenAsync(deviceId, cancellationToken).ConfigureAwait(false);
                return new PairingOutcome(PairingError.Closed, null);
            }

            await _audit.LogAsync(
                AuditEvents.PairingSucceeded,
                new Dictionary<string, object?>
                {
                    ["deviceId"] = deviceId,
                    ["deviceName"] = info.DeviceName,
                    ["ip"] = sourceIp
                },
                cancellationToken).ConfigureAwait(false);

            return new PairingOutcome(PairingError.None, new PairingCredentials(deviceId, token));
        }
        finally
        {
            _gate.Release();
        }
    }

    private async Task LogFailureAsync(
        string reason,
        string? deviceId,
        string? sourceIp,
        int attempts,
        CancellationToken cancellationToken)
    {
        await _audit.LogAsync(
            AuditEvents.PairingFailed,
            new Dictionary<string, object?>
            {
                ["reason"] = reason,
                ["deviceId"] = deviceId,
                ["ip"] = sourceIp,
                ["attempts"] = attempts
            },
            cancellationToken).ConfigureAwait(false);
    }

    private static void ValidateContext(PairingContext context)
    {
        if (string.IsNullOrWhiteSpace(context.Name) || context.Name.Length > 64)
        {
            throw new ArgumentException("Pairing display name is invalid.", nameof(context));
        }

        if (string.IsNullOrWhiteSpace(context.Host))
        {
            throw new ArgumentException("Pairing host is invalid.", nameof(context));
        }

        if (context.Port is < 1 or > 65535)
        {
            throw new ArgumentException("Pairing port is invalid.", nameof(context));
        }

        if (context.Fingerprint.Length != 64 ||
            context.Fingerprint.Any(c => c is not (>= '0' and <= '9' or >= 'a' and <= 'f')))
        {
            throw new ArgumentException("Pairing fingerprint must be lowercase SHA-256 hex.", nameof(context));
        }
    }

    private sealed class Window
    {
        public PairingContext Context { get; init; } = null!;
        public string NormalizedCode { get; init; } = string.Empty;
        public string FormattedCode { get; init; } = string.Empty;
        public string Payload { get; init; } = string.Empty;
        public long StartedAt { get; init; }
        public long ExpiresAt { get; init; }
        public int FailedAttempts { get; set; }
        public PairingState State { get; set; }
        public string? PairedDeviceId { get; set; }
        public string? PairedDeviceName { get; set; }
    }
}
