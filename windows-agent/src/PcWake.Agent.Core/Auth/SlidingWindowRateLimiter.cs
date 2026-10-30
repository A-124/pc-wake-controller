using PcWake.Agent.Core.Abstractions;

namespace PcWake.Agent.Core.Auth;

/// <summary>In-memory sliding-window counters used by the protocol rate limits.</summary>
public sealed class SlidingWindowRateLimiter
{
    private readonly IClock _clock;
    private readonly object _sync = new();
    private readonly Dictionary<string, Queue<long>> _events = new(StringComparer.Ordinal);

    public SlidingWindowRateLimiter(IClock clock) => _clock = clock;

    public bool TryAcquire(string key, int limit, TimeSpan window)
    {
        if (limit <= 0)
        {
            return false;
        }

        lock (_sync)
        {
            var queue = Prune(key, window);
            if (queue.Count >= limit)
            {
                return false;
            }

            queue.Enqueue(_clock.UtcNow.ToUnixTimeMilliseconds());
            PruneDictionaryIfLarge();
            return true;
        }
    }

    public void Record(string key)
    {
        lock (_sync)
        {
            var queue = Prune(key, TimeSpan.FromMinutes(2));
            queue.Enqueue(_clock.UtcNow.ToUnixTimeMilliseconds());
        }
    }

    public bool IsBlocked(string key, int limit, TimeSpan window)
    {
        lock (_sync)
        {
            return Prune(key, window).Count >= limit;
        }
    }

    public int Count(string key, TimeSpan window)
    {
        lock (_sync)
        {
            return Prune(key, window).Count;
        }
    }

    private Queue<long> Prune(string key, TimeSpan window)
    {
        var cutoff = _clock.UtcNow.ToUnixTimeMilliseconds() - (long)window.TotalMilliseconds;
        if (!_events.TryGetValue(key, out var queue))
        {
            queue = new Queue<long>();
            _events[key] = queue;
            return queue;
        }

        while (queue.Count > 0 && queue.Peek() < cutoff)
        {
            queue.Dequeue();
        }

        return queue;
    }

    private void PruneDictionaryIfLarge()
    {
        if (_events.Count < 4096)
        {
            return;
        }

        foreach (var key in _events.Where(pair => pair.Value.Count == 0).Select(pair => pair.Key).ToList())
        {
            _events.Remove(key);
        }
    }
}
