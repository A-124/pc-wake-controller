using System.Net;
using System.Security.Cryptography;
using System.Security.Cryptography.X509Certificates;

namespace PcWake.Agent.Service;

public static class CertificateFactory
{
    /// <summary>
    /// ECDSA P-256 self-signed server certificate with SANs for the computer name,
    /// localhost and every supplied LAN address.
    /// </summary>
    public static X509Certificate2 CreateSelfSigned(
        string hostName,
        IEnumerable<IPAddress>? lanAddresses = null,
        DateTimeOffset? notBefore = null,
        DateTimeOffset? notAfter = null)
    {
        ArgumentException.ThrowIfNullOrWhiteSpace(hostName);

        using var key = ECDsa.Create(ECCurve.NamedCurves.nistP256);
        var subject = new X500DistinguishedName("CN=" + hostName.Replace(",", " ", StringComparison.Ordinal));
        var request = new CertificateRequest(subject, key, HashAlgorithmName.SHA256);

        request.CertificateExtensions.Add(new X509BasicConstraintsExtension(false, false, 0, false));
        request.CertificateExtensions.Add(new X509KeyUsageExtension(
            X509KeyUsageFlags.DigitalSignature,
            critical: false));

        var enhancedKeyUsage = new OidCollection
        {
            new("1.3.6.1.5.5.7.3.1") // serverAuth
        };
        request.CertificateExtensions.Add(new X509EnhancedKeyUsageExtension(enhancedKeyUsage, critical: false));

        var san = new SubjectAlternativeNameBuilder();
        san.AddDnsName(hostName);
        san.AddDnsName("localhost");
        san.AddIpAddress(IPAddress.Loopback);
        san.AddIpAddress(IPAddress.IPv6Loopback);

        if (lanAddresses is not null)
        {
            foreach (var address in lanAddresses.Distinct())
            {
                if (address.AddressFamily == System.Net.Sockets.AddressFamily.InterNetwork ||
                    address.AddressFamily == System.Net.Sockets.AddressFamily.InterNetworkV6)
                {
                    san.AddIpAddress(address);
                }
            }
        }

        request.CertificateExtensions.Add(san.Build());

        var start = notBefore ?? DateTimeOffset.UtcNow.AddDays(-1);
        var end = notAfter ?? DateTimeOffset.UtcNow.AddYears(5);
        using var generated = request.CreateSelfSigned(start, end);

        // Export/reimport so the returned certificate is a normal persisted-key
        // certificate independent of the disposed ECDsa object.
        var pfx = generated.Export(X509ContentType.Pkcs12);
        try
        {
            return X509CertificateLoaderCompat.LoadPkcs12(pfx, null);
        }
        finally
        {
            CryptographicOperations.ZeroMemory(pfx);
        }
    }

    public static string Fingerprint(X509Certificate2 certificate)
    {
        return Convert.ToHexString(SHA256.HashData(certificate.RawData)).ToLowerInvariant();
    }
}

internal static class X509CertificateLoaderCompat
{
    public static X509Certificate2 LoadPkcs12(byte[] pfx, string? password)
    {
        // .NET 8 uses the X509Certificate2 constructor; X509CertificateLoader is .NET 9+.
        return new X509Certificate2(pfx, password, X509KeyStorageFlags.UserKeySet | X509KeyStorageFlags.Exportable);
    }
}
