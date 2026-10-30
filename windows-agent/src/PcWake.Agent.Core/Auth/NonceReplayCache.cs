using PcWake.Agent.Core.Abstractions;

namespace PcWake.Agent.Core.Auth;

/// <summary>
/// Per-device nonce replay cache. Entries live for 180 seconds and are added only
/// after the signature has been verified.
/// </summary>
public sealed class NonceReplayCache
{
    private readonly IClock _clock;
    private readonly object _sync = new();
    private readonly Dictionary<string, long> _expiresAtUnixSeconds = new(StringComparer.Ordinal);
    private int _operations;

    public NonceReplayCache(IClock clock) => _clock = clock;

    public bool Contains(string deviceId, string nonce)
    {
        var key = deviceId + ":" + nonce;
        lock (_sync)
        {
            if (!_expiresAtUnixSeconds.TryGetValue(key, out var expires))
            {
                return false;
            }

            if (expires >= _clock.UtcNow.ToUnixTimeSeconds())
            {
                return true;
            }

            _expiresAtUnixSeconds.Remove(key);
            return false;
        }
    }

    public bool TryStore(string deviceId, string nonce, int ttlSeconds = 180)
    {
        var key = deviceId + ":" + nonce;
        var now = _clock.UtcNow.ToUnixTimeSeconds();
        lock (_sync)
        {
            if (_expiresAtUnixSeconds.TryGetValue(key, out var expires) && expires >= now)
            {
                return false;
            }

            _expiresAtUnixSeconds[key] = now + ttlSeconds;
            if (++_operations % 256 == 0)
            {
                foreach (var expired in _expiresAtUnixSeconds.Where(p => p.Value < now).Select(p => p.Key).ToList())
                {
                    _expiresAtUnixSeconds.Remove(expired);
                }
            }

            return true;
        }
    }

    public int Count
    {
        get
        {
            lock (_sync)
            {
                return _expiresAtUnixSeconds.Count;
            }
        }
    }
}
