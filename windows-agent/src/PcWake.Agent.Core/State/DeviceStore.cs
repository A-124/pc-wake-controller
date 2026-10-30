using System.Text.Json;
using Microsoft.Extensions.Logging;
using Microsoft.Extensions.Logging.Abstractions;
using PcWake.Agent.Core.Abstractions;
using PcWake.Agent.Core.Config;
using PcWake.Agent.Core.Protocol;

namespace PcWake.Agent.Core.State;

/// <summary>
/// Device metadata (including only SHA-256 token hashes) persisted atomically in
/// state.json. Raw bearer tokens are kept separately by IDeviceSecretStore.
/// </summary>
public interface IDeviceStore
{
    IReadOnlyList<DeviceRecord> GetAll();
    DeviceRecord? GetById(string deviceId);
    int ActiveDeviceCount { get; }
    Task AddAsync(DeviceRecord device, CancellationToken cancellationToken = default);
    Task<bool> RevokeAsync(string deviceId, long revokedAt, CancellationToken cancellationToken = default);
    Task TouchAsync(string deviceId, long seenAt, CancellationToken cancellationToken = default);
    Task FlushAsync(CancellationToken cancellationToken = default);
}

public sealed class DeviceStore : IDeviceStore, IAsyncDisposable
{
    private static readonly TimeSpan PersistInterval = TimeSpan.FromSeconds(15);

    private readonly string _path;
    private readonly IClock _clock;
    private readonly bool _restrictAcl;
    private readonly ILogger _logger;
    private readonly SemaphoreSlim _gate = new(1, 1);
    private readonly Dictionary<string, DeviceRecord> _devices = new(StringComparer.Ordinal);
    private DateTimeOffset _lastPersist = DateTimeOffset.MinValue;
    private bool _dirty;
    private bool _disposed;

    public DeviceStore(
        AgentPaths paths,
        IClock clock,
        bool restrictAcl,
        ILogger<DeviceStore>? logger = null)
    {
        _path = paths.StateFile;
        _clock = clock;
        _restrictAcl = restrictAcl;
        _logger = logger ?? NullLogger<DeviceStore>.Instance;

        paths.EnsureDirectories();
        if (restrictAcl)
        {
            FileAcl.RestrictDirectory(paths.RootDirectory);
        }

        LoadOrInitialize();
    }

    public int ActiveDeviceCount
    {
        get
        {
            lock (_devices)
            {
                return _devices.Values.Count(d => !d.Revoked);
            }
        }
    }

    public IReadOnlyList<DeviceRecord> GetAll()
    {
        lock (_devices)
        {
            return _devices.Values
                .OrderByDescending(d => d.PairedAt)
                .Select(d => d.Clone())
                .ToList();
        }
    }

    public DeviceRecord? GetById(string deviceId)
    {
        if (!TokenGenerator.IsValidDeviceId(deviceId))
        {
            return null;
        }

        lock (_devices)
        {
            return _devices.TryGetValue(deviceId, out var device) ? device.Clone() : null;
        }
    }

    public async Task AddAsync(DeviceRecord device, CancellationToken cancellationToken = default)
    {
        AgentStateValidator.ThrowIfInvalid(new AgentState { Devices = [device] });
        if (!TokenGenerator.IsValidDeviceId(device.DeviceId))
        {
            throw new ArgumentException("Invalid device id.", nameof(device));
        }

        await _gate.WaitAsync(cancellationToken).ConfigureAwait(false);
        try
        {
            lock (_devices)
            {
                if (_devices.ContainsKey(device.DeviceId))
                {
                    throw new InvalidOperationException("Device already exists.");
                }
            }

            var next = Snapshot();
            next.Devices.Add(device.Clone());
            await PersistAsync(next, cancellationToken).ConfigureAwait(false);

            lock (_devices)
            {
                _devices[device.DeviceId] = device.Clone();
            }
        }
        finally
        {
            _gate.Release();
        }
    }

    public async Task<bool> RevokeAsync(
        string deviceId,
        long revokedAt,
        CancellationToken cancellationToken = default)
    {
        await _gate.WaitAsync(cancellationToken).ConfigureAwait(false);
        try
        {
            DeviceRecord? updated;
            lock (_devices)
            {
                if (!_devices.TryGetValue(deviceId, out var current))
                {
                    return false;
                }

                updated = current.Clone();
                updated.Revoked = true;
                updated.RevokedAt = revokedAt;
            }

            var next = Snapshot();
            var target = next.Devices.Single(d => d.DeviceId == deviceId);
            target.Revoked = true;
            target.RevokedAt = revokedAt;
            await PersistAsync(next, cancellationToken).ConfigureAwait(false);

            lock (_devices)
            {
                _devices[deviceId] = updated;
            }

            return true;
        }
        finally
        {
            _gate.Release();
        }
    }

    public async Task TouchAsync(string deviceId, long seenAt, CancellationToken cancellationToken = default)
    {
        var shouldPersist = false;

        lock (_devices)
        {
            if (!_devices.TryGetValue(deviceId, out var device))
            {
                return;
            }

            device.LastSeenAt = Math.Max(device.LastSeenAt, seenAt);
            _dirty = true;
            shouldPersist = _clock.UtcNow - _lastPersist >= PersistInterval;
        }

        if (!shouldPersist)
        {
            return;
        }

        try
        {
            await FlushAsync(cancellationToken).ConfigureAwait(false);
        }
        catch (IOException ex)
        {
            // lastSeenAt is not worth failing an otherwise authenticated request.
            _logger.LogWarning(ex, "Could not persist lastSeenAt.");
        }
        catch (UnauthorizedAccessException ex)
        {
            _logger.LogWarning(ex, "Could not persist lastSeenAt.");
        }
    }

    public async Task FlushAsync(CancellationToken cancellationToken = default)
    {
        await _gate.WaitAsync(cancellationToken).ConfigureAwait(false);
        try
        {
            if (!_dirty && File.Exists(_path))
            {
                return;
            }

            await PersistAsync(Snapshot(), cancellationToken).ConfigureAwait(false);
        }
        finally
        {
            _gate.Release();
        }
    }

    private async Task PersistAsync(AgentState state, CancellationToken cancellationToken)
    {
        AgentStateValidator.ThrowIfInvalid(state);
        var json = JsonSerializer.Serialize(state, JsonDefaults.Indented);
        await AtomicFile.WriteAllTextAsync(_path, json, cancellationToken).ConfigureAwait(false);
        if (_restrictAcl)
        {
            FileAcl.RestrictFile(_path);
        }

        _lastPersist = _clock.UtcNow;
        _dirty = false;
    }

    private AgentState Snapshot()
    {
        lock (_devices)
        {
            return new AgentState
            {
                SchemaVersion = 1,
                Devices = _devices.Values.Select(d => d.Clone()).ToList()
            };
        }
    }

    private void LoadOrInitialize()
    {
        if (!File.Exists(_path))
        {
            var initial = new AgentState();
            AgentStateValidator.ThrowIfInvalid(initial);
            var json = JsonSerializer.Serialize(initial, JsonDefaults.Indented);
            AtomicFile.WriteAllText(_path, json);
            if (_restrictAcl)
            {
                FileAcl.RestrictFile(_path);
            }

            _lastPersist = _clock.UtcNow;
            return;
        }

        AgentState? state;
        try
        {
            var json = File.ReadAllText(_path);
            state = JsonSerializer.Deserialize<AgentState>(json, JsonDefaults.Protocol);
        }
        catch (JsonException ex)
        {
            throw new InvalidDataException($"state.json is not valid JSON: {ex.Message}", ex);
        }

        AgentStateValidator.ThrowIfInvalid(state);
        foreach (var device in state!.Devices)
        {
            _devices[device.DeviceId] = device;
        }

        _lastPersist = _clock.UtcNow;
    }

    public async ValueTask DisposeAsync()
    {
        if (_disposed)
        {
            return;
        }

        _disposed = true;
        try
        {
            await FlushAsync(CancellationToken.None).ConfigureAwait(false);
        }
        catch (Exception ex) when (ex is IOException or UnauthorizedAccessException)
        {
            _logger.LogWarning(ex, "Could not flush device state during shutdown.");
        }
    }
}
