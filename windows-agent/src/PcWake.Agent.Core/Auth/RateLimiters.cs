using PcWake.Agent.Core.Abstractions;
using PcWake.Agent.Core.Protocol;

namespace PcWake.Agent.Core.Auth;

/// <summary>60 authenticated requests per minute per device.</summary>
public sealed class DeviceRateLimiter
{
    private readonly SlidingWindowRateLimiter _inner;
    public DeviceRateLimiter(IClock clock, int limit = ProtocolConstants.DeviceRequestsPerMinute)
    {
        _inner = new SlidingWindowRateLimiter(clock);
        Limit = limit;
    }

    public int Limit { get; }
    public TimeSpan Window { get; } = TimeSpan.FromMinutes(1);

    public bool TryAcquire(string deviceId) =>
        _inner.TryAcquire("device:" + deviceId, Limit, Window);
}

/// <summary>10 authentication failures per minute per source IP, then HTTP 429.</summary>
public sealed class AuthFailureRateLimiter
{
    private readonly SlidingWindowRateLimiter _inner;
    public AuthFailureRateLimiter(IClock clock, int limit = ProtocolConstants.AuthFailuresPerMinute)
    {
        _inner = new SlidingWindowRateLimiter(clock);
        Limit = limit;
    }

    public int Limit { get; }
    public TimeSpan Window { get; } = TimeSpan.FromMinutes(1);

    public bool IsBlocked(string sourceIp) =>
        _inner.IsBlocked("failure:" + sourceIp, Limit, Window);

    public void RecordFailure(string sourceIp) =>
        _inner.Record("failure:" + sourceIp);
}

/// <summary>Rate limits unauthenticated pairing requests per source IP.</summary>
public sealed class PairingRateLimiter
{
    private readonly SlidingWindowRateLimiter _inner;
    public PairingRateLimiter(IClock clock, int limit = ProtocolConstants.PairingRequestsPerMinutePerIp)
    {
        _inner = new SlidingWindowRateLimiter(clock);
        Limit = limit;
    }

    public int Limit { get; }

    public bool TryAcquire(string sourceIp) =>
        _inner.TryAcquire("pair:" + sourceIp, Limit, TimeSpan.FromMinutes(1));
}
