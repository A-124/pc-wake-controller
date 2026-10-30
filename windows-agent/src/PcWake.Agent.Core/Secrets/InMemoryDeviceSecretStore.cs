using System.Collections.Concurrent;

namespace PcWake.Agent.Core.Secrets;

public sealed class InMemoryDeviceSecretStore : IDeviceSecretStore
{
    private readonly ConcurrentDictionary<string, string> _tokens = new(StringComparer.Ordinal);

    public bool TryGetToken(string deviceId, out string? token) =>
        _tokens.TryGetValue(deviceId, out token);

    public Task SetTokenAsync(string deviceId, string token, CancellationToken cancellationToken = default)
    {
        _tokens[deviceId] = token;
        return Task.CompletedTask;
    }

    public Task<bool> RemoveTokenAsync(string deviceId, CancellationToken cancellationToken = default) =>
        Task.FromResult(_tokens.TryRemove(deviceId, out _));
}
