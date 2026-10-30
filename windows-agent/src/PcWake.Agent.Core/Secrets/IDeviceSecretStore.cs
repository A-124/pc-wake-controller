namespace PcWake.Agent.Core.Secrets;

/// <summary>
/// Storage for the HMAC keys required to verify request signatures. Stateless
/// hash-only authentication of HMAC is impossible, so raw tokens are held in a
/// separate DPAPI-protected vault; state.json itself contains only SHA-256 hashes.
/// </summary>
public interface IDeviceSecretStore
{
    bool TryGetToken(string deviceId, out string? token);
    Task SetTokenAsync(string deviceId, string token, CancellationToken cancellationToken = default);
    Task<bool> RemoveTokenAsync(string deviceId, CancellationToken cancellationToken = default);
}
