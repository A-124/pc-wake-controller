using System.Collections.Concurrent;
using System.Runtime.Versioning;
using System.Security.Cryptography;
using System.Text;
using System.Text.Json;
using PcWake.Agent.Core.Config;
using PcWake.Agent.Core.Protocol;

namespace PcWake.Agent.Core.Secrets;

/// <summary>
/// Encrypts the HMAC keys at rest with DPAPI machine scope. The file and the
/// containing directory are ACL-restricted to SYSTEM and Administrators.
/// </summary>
[SupportedOSPlatform("windows")]
public sealed class DpapiDeviceSecretStore : IDeviceSecretStore, IAsyncDisposable
{
    private static readonly byte[] Entropy = Encoding.UTF8.GetBytes("PcWakeAgent.DeviceSecrets.v1");

    private readonly string _path;
    private readonly bool _restrictAcl;
    private readonly ConcurrentDictionary<string, string> _tokens = new(StringComparer.Ordinal);
    private readonly SemaphoreSlim _gate = new(1, 1);

    public DpapiDeviceSecretStore(AgentPaths paths, bool restrictAcl)
    {
        if (!OperatingSystem.IsWindows())
        {
            throw new PlatformNotSupportedException("DPAPI is only available on Windows.");
        }

        _path = paths.SecretFile;
        _restrictAcl = restrictAcl;
        paths.EnsureDirectories();
        if (restrictAcl)
        {
            FileAcl.RestrictDirectory(paths.RootDirectory);
        }

        Load();
    }

    public string FilePath => _path;

    public bool TryGetToken(string deviceId, out string? token) =>
        _tokens.TryGetValue(deviceId, out token);

    public async Task SetTokenAsync(string deviceId, string token, CancellationToken cancellationToken = default)
    {
        if (!TokenGenerator.IsValidDeviceId(deviceId) || !TokenGenerator.IsValidToken(token))
        {
            throw new ArgumentException("Invalid device id or token.");
        }

        await _gate.WaitAsync(cancellationToken).ConfigureAwait(false);
        try
        {
            var next = new Dictionary<string, string>(_tokens, StringComparer.Ordinal)
            {
                [deviceId] = token
            };
            await PersistAsync(next, cancellationToken).ConfigureAwait(false);
            _tokens[deviceId] = token;
        }
        finally
        {
            _gate.Release();
        }
    }

    public async Task<bool> RemoveTokenAsync(string deviceId, CancellationToken cancellationToken = default)
    {
        await _gate.WaitAsync(cancellationToken).ConfigureAwait(false);
        try
        {
            if (!_tokens.ContainsKey(deviceId))
            {
                return false;
            }

            var next = new Dictionary<string, string>(_tokens, StringComparer.Ordinal);
            next.Remove(deviceId);
            await PersistAsync(next, cancellationToken).ConfigureAwait(false);
            _tokens.TryRemove(deviceId, out _);
            return true;
        }
        finally
        {
            _gate.Release();
        }
    }

    private void Load()
    {
        if (!File.Exists(_path))
        {
            return;
        }

        try
        {
            var protectedBytes = File.ReadAllBytes(_path);
            var plain = ProtectedData.Unprotect(protectedBytes, Entropy, DataProtectionScope.LocalMachine);
            try
            {
                var document = JsonSerializer.Deserialize<SecretDocument>(plain, JsonDefaults.Protocol);
                if (document is null || document.SchemaVersion != 1 || document.Tokens is null)
                {
                    throw new InvalidDataException("Device secret vault has an unsupported format.");
                }

                foreach (var (deviceId, token) in document.Tokens)
                {
                    if (!TokenGenerator.IsValidDeviceId(deviceId) || !TokenGenerator.IsValidToken(token))
                    {
                        throw new InvalidDataException("Device secret vault contains an invalid entry.");
                    }

                    _tokens[deviceId] = token;
                }
            }
            finally
            {
                CryptographicOperations.ZeroMemory(plain);
            }
        }
        catch (CryptographicException ex)
        {
            throw new InvalidDataException("Device secret vault could not be decrypted.", ex);
        }
        catch (JsonException ex)
        {
            throw new InvalidDataException("Device secret vault is not valid JSON.", ex);
        }
    }

    private async Task PersistAsync(Dictionary<string, string> tokens, CancellationToken cancellationToken)
    {
        var document = new SecretDocument { SchemaVersion = 1, Tokens = tokens };
        var plain = JsonSerializer.SerializeToUtf8Bytes(document, JsonDefaults.Protocol);
        try
        {
            var protectedBytes = ProtectedData.Protect(plain, Entropy, DataProtectionScope.LocalMachine);
            await AtomicFile.WriteAllBytesAsync(_path, protectedBytes, cancellationToken).ConfigureAwait(false);
            if (_restrictAcl)
            {
                FileAcl.RestrictFile(_path);
            }
        }
        finally
        {
            CryptographicOperations.ZeroMemory(plain);
        }
    }

    public ValueTask DisposeAsync()
    {
        _gate.Dispose();
        return ValueTask.CompletedTask;
    }

    private sealed class SecretDocument
    {
        public int SchemaVersion { get; set; } = 1;
        public Dictionary<string, string>? Tokens { get; set; }
    }
}
