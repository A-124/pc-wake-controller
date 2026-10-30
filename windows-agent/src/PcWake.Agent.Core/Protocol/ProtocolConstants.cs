namespace PcWake.Agent.Core.Protocol;

/// <summary>Values fixed by documentation/PROTOCOL.md and the Windows agent defaults.</summary>
public static class ProtocolConstants
{
    public const int Version = 1;
    public const string AgentServiceId = "pcwake-agent";
    public const string AgentKind = "agent";
    public const string AgentVersion = "1.0.0";
    public const string ProductName = "PC Wake Controller";
    public const string WindowsServiceName = "PcWakeAgent";
    public const string AdminPipeName = "PcWakeAgent.Admin";
    public const string StatusPipeName = "PcWakeAgent.Status";

    public const int DefaultHttpsPort = 47101;
    public const int DefaultDiscoveryPort = 47100;
    public const string DiscoveryProbe = "PCWAKE_DISCOVER_V1";

    public const int PairingWindowSeconds = 300;
    public const int PairingMaxAttempts = 5;
    public const int TimestampSkewSeconds = 60;
    public const int NonceReplaySeconds = 180;
    public const int DeviceRequestsPerMinute = 60;
    public const int AuthFailuresPerMinute = 10;
    public const int PairingRequestsPerMinutePerIp = 10;

    public const int GraceMinSeconds = 0;
    public const int GraceMaxSeconds = 60;
    public const int DefaultShutdownGraceSeconds = 15;
    public const int SleepDelaySeconds = 3;
    public const int MaxReasonLength = 200;
    public const int MaxBodyBytes = 1024 * 1024;

    public const string DefaultShutdownReason = "PC Wake Controller remote shutdown";
    public const string DefaultRestartReason = "PC Wake Controller remote restart";
    public const string DefaultSleepReason = "PC Wake Controller remote sleep";

    public static readonly string[] Capabilities = ["shutdown", "restart", "sleep", "cancel"];
}
