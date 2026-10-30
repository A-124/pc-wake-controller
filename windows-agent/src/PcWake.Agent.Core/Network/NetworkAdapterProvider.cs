using System.Net;
using System.Net.NetworkInformation;
using System.Net.Sockets;

namespace PcWake.Agent.Core.Network;

public sealed record NetworkAdapterInfo(
    string Name,
    string Mac,
    IReadOnlyList<string> Ipv4,
    IReadOnlyList<string> Ipv6);

public interface INetworkAdapterProvider
{
    IReadOnlyList<NetworkAdapterInfo> GetAdapters();

    /// <summary>Best LAN IPv4 address, falling back to 127.0.0.1 when no LAN interface exists.</summary>
    string GetPrimaryIPv4();
}

public sealed class NetworkAdapterProvider : INetworkAdapterProvider
{
    private static readonly string[] PrivatePrefixes = ["10.", "192.168."];

    public IReadOnlyList<NetworkAdapterInfo> GetAdapters()
    {
        var result = new List<NetworkAdapterInfo>();

        foreach (var nic in NetworkInterface.GetAllNetworkInterfaces())
        {
            if (nic.NetworkInterfaceType == NetworkInterfaceType.Loopback ||
                nic.OperationalStatus != OperationalStatus.Up)
            {
                continue;
            }

            var macBytes = nic.GetPhysicalAddress().GetAddressBytes();
            if (macBytes.Length == 0)
            {
                continue;
            }

            var ipv4 = new List<string>();
            var ipv6 = new List<string>();
            try
            {
                foreach (var address in nic.GetIPProperties().UnicastAddresses)
                {
                    var ip = address.Address;
                    if (ip.AddressFamily == AddressFamily.InterNetwork && !IPAddress.IsLoopback(ip))
                    {
                        ipv4.Add(ip.ToString());
                    }
                    else if (ip.AddressFamily == AddressFamily.InterNetworkV6 &&
                             !IPAddress.IsLoopback(ip) &&
                             !ip.IsIPv6LinkLocal &&
                             !ip.IsIPv6Multicast)
                    {
                        ipv6.Add(ip.ToString());
                    }
                }
            }
            catch (NetworkInformationException)
            {
                // An adapter can disappear while it is being enumerated.
                continue;
            }

            var mac = string.Join(":", macBytes.Select(b => b.ToString("X2")));
            result.Add(new NetworkAdapterInfo(nic.Name, mac, ipv4, ipv6));
        }

        return result
            .OrderBy(a => a.Name, StringComparer.OrdinalIgnoreCase)
            .ToList();
    }

    public string GetPrimaryIPv4()
    {
        var addresses = GetAdapters()
            .SelectMany(a => a.Ipv4)
            .Where(a => IPAddress.TryParse(a, out _))
            .ToList();

        var preferred = addresses.FirstOrDefault(IsPrivateAddress);
        return preferred ?? addresses.FirstOrDefault() ?? IPAddress.Loopback.ToString();
    }

    private static bool IsPrivateAddress(string address)
    {
        if (PrivatePrefixes.Any(address.StartsWith))
        {
            return true;
        }

        return address.StartsWith("172.", StringComparison.Ordinal) &&
               IPAddress.TryParse(address, out var ip) &&
               ip.GetAddressBytes() is [_, var second, _, _] && second is >= 16 and <= 31;
    }
}
