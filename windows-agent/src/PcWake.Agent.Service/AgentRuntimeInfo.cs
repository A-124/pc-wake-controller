using System.Runtime.InteropServices;
using PcWake.Agent.Core.Abstractions;
using PcWake.Agent.Core.Config;
using PcWake.Agent.Core.Protocol;

namespace PcWake.Agent.Service;

public sealed class AgentRuntimeInfo
{
    private readonly IClock _clock;

    public AgentRuntimeInfo(
        AgentConfig config,
        string hostAddress,
        int port,
        string fingerprint,
        IClock clock)
    {
        Name = config.ResolvedDisplayName;
        HostName = Environment.MachineName;
        HostAddress = hostAddress;
        Port = port;
        Fingerprint = fingerprint;
        StartedAt = clock.UtcNow;
        _clock = clock;
    }

    public string Name { get; }
    public string HostName { get; }
    public string HostAddress { get; }
    public int Port { get; }
    public string Fingerprint { get; }
    public string Version => ProtocolConstants.AgentVersion;
    public DateTimeOffset StartedAt { get; }
    public string OsDescription => RuntimeInformation.OSDescription;

    public long UptimeSeconds
    {
        get
        {
            var seconds = (_clock.UtcNow - StartedAt).TotalSeconds;
            return seconds <= 0 ? 0 : (long)seconds;
        }
    }
}
