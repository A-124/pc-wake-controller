using System.Security.Cryptography.X509Certificates;
using PcWake.Agent.Core.Abstractions;
using PcWake.Agent.Core.Config;
using PcWake.Agent.Core.Network;
using PcWake.Agent.Core.Power;
using PcWake.Agent.Core.Secrets;

namespace PcWake.Agent.Service;

public sealed class AgentServerOptions
{
    public required AgentPaths Paths { get; init; }
    public required AgentConfig Config { get; init; }
    public required X509Certificate2 Certificate { get; init; }

    /// <summary>Overrides config.httpsPort. 0 binds an ephemeral port (tests).</summary>
    public int? ListenPortOverride { get; init; }

    public bool EnableDiscovery { get; init; } = true;
    public bool EnablePipes { get; init; } = true;
    public bool RestrictDataAcl { get; init; } = true;
    public bool UseWindowsService { get; init; } = true;

    public IClock? Clock { get; init; }
    public INetworkAdapterProvider? AdapterProvider { get; init; }
    public IPowerController? PowerController { get; init; }
    public IPowerDelay? PowerDelay { get; init; }
    public IDeviceSecretStore? SecretStore { get; init; }
}
