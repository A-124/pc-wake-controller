using System.Net;
using System.Runtime.Versioning;
using System.Security.Cryptography;
using System.Security.Cryptography.X509Certificates;
using System.Text;
using PcWake.Agent.Core.Config;
using PcWake.Agent.Core.Network;
using PcWake.Agent.Core.Protocol;

namespace PcWake.Agent.Service;

public sealed record ServerCertificate(X509Certificate2 Certificate, string Fingerprint) : IDisposable
{
    public void Dispose() => Certificate.Dispose();
}

/// <summary>
/// Generates and persists the pinned server certificate. The PFX lives under
/// ProgramData\PcWakeAgent\certs with a SYSTEM+Administrators ACL; its random
/// password is protected with DPAPI machine scope.
/// </summary>
[SupportedOSPlatform("windows")]
public sealed class CertificateManager
{
    private static readonly byte[] Entropy = Encoding.UTF8.GetBytes("PcWakeAgent.PfxPassword.v1");
    private static readonly TimeSpan RenewBefore = TimeSpan.FromDays(30);

    private readonly AgentPaths _paths;
    private readonly INetworkAdapterProvider _adapters;
    private readonly bool _restrictAcl;
    private readonly Action<string>? _warning;
    private readonly string _hostName;

    public CertificateManager(
        AgentPaths paths,
        INetworkAdapterProvider adapters,
        bool restrictAcl = true,
        Action<string>? warning = null,
        string? hostName = null)
    {
        _paths = paths;
        _adapters = adapters;
        _restrictAcl = restrictAcl;
        _warning = warning;
        _hostName = string.IsNullOrWhiteSpace(hostName) ? Environment.MachineName : hostName;
    }

    public ServerCertificate GetOrCreate()
    {
        _paths.EnsureDirectories();
        if (_restrictAcl)
        {
            FileAcl.RestrictDirectory(_paths.RootDirectory);
            FileAcl.RestrictDirectory(_paths.CertificateDirectory);
        }

        if (File.Exists(_paths.PfxFile) && File.Exists(_paths.PfxPasswordFile))
        {
            try
            {
                var loaded = Load();
                if (loaded.HasPrivateKey && loaded.NotAfter.ToUniversalTime() > DateTime.UtcNow.Add(RenewBefore))
                {
                    return new ServerCertificate(loaded, CertificateFactory.Fingerprint(loaded));
                }

                loaded.Dispose();
                _warning?.Invoke("Stored agent certificate is expired or close to expiry; generating a new one.");
            }
            catch (Exception ex) when (ex is CryptographicException or IOException or UnauthorizedAccessException or InvalidDataException)
            {
                _warning?.Invoke("Stored agent certificate could not be loaded; generating a new one.");
            }
        }

        return GenerateAndStore();
    }

    private ServerCertificate GenerateAndStore()
    {
        var lanAddresses = GetLanAddresses();
        using var generated = CertificateFactory.CreateSelfSigned(_hostName, lanAddresses);
        var password = Base64Url.Encode(RandomNumberGenerator.GetBytes(32));
        var pfx = generated.Export(X509ContentType.Pkcs12, password);
        byte[] passwordBytes = Encoding.UTF8.GetBytes(password);
        byte[] protectedPassword;
        try
        {
            protectedPassword = ProtectedData.Protect(passwordBytes, Entropy, DataProtectionScope.LocalMachine);
        }
        finally
        {
            CryptographicOperations.ZeroMemory(passwordBytes);
        }

        try
        {
            // Password first; a crash between the two writes is detected on the next
            // start and results in regeneration.
            AtomicFile.WriteAllBytes(_paths.PfxPasswordFile, protectedPassword);
            if (_restrictAcl)
            {
                FileAcl.RestrictFile(_paths.PfxPasswordFile);
            }

            AtomicFile.WriteAllBytes(_paths.PfxFile, pfx);
            if (_restrictAcl)
            {
                FileAcl.RestrictFile(_paths.PfxFile);
            }

            var loaded = Load();
            return new ServerCertificate(loaded, CertificateFactory.Fingerprint(loaded));
        }
        finally
        {
            CryptographicOperations.ZeroMemory(pfx);
            CryptographicOperations.ZeroMemory(protectedPassword);
        }
    }

    private X509Certificate2 Load()
    {
        var protectedPassword = File.ReadAllBytes(_paths.PfxPasswordFile);
        var passwordBytes = ProtectedData.Unprotect(protectedPassword, Entropy, DataProtectionScope.LocalMachine);
        string password;
        try
        {
            password = Encoding.UTF8.GetString(passwordBytes);
        }
        finally
        {
            CryptographicOperations.ZeroMemory(passwordBytes);
        }

        var pfx = File.ReadAllBytes(_paths.PfxFile);
        try
        {
            var certificate = new X509Certificate2(
                pfx,
                password,
                X509KeyStorageFlags.MachineKeySet | X509KeyStorageFlags.Exportable);
            if (!certificate.HasPrivateKey)
            {
                certificate.Dispose();
                throw new InvalidDataException("The agent certificate has no private key.");
            }

            return certificate;
        }
        finally
        {
            CryptographicOperations.ZeroMemory(pfx);
        }
    }

    private IReadOnlyList<IPAddress> GetLanAddresses()
    {
        var addresses = new List<IPAddress>();
        foreach (var adapter in _adapters.GetAdapters())
        {
            foreach (var text in adapter.Ipv4.Concat(adapter.Ipv6))
            {
                if (IPAddress.TryParse(text, out var address) && !addresses.Contains(address))
                {
                    addresses.Add(address);
                }
            }
        }

        return addresses;
    }
}
