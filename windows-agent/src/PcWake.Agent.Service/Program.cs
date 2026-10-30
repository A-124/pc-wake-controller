using PcWake.Agent.Core.Config;
using PcWake.Agent.Core.Network;
using PcWake.Agent.Service;

var consoleMode = args.Any(a => string.Equals(a, "--console", StringComparison.OrdinalIgnoreCase));

try
{
    var paths = AgentPaths.Default;
    var configStore = new AgentConfigStore(paths, restrictAcl: true);
    var config = configStore.LoadOrCreate();

    var adapters = new NetworkAdapterProvider();
    var certificateManager = new CertificateManager(
        paths,
        adapters,
        restrictAcl: true,
        warning: message => Console.Error.WriteLine(message));
    using var serverCertificate = certificateManager.GetOrCreate();

    var options = new AgentServerOptions
    {
        Paths = paths,
        Config = config,
        Certificate = serverCertificate.Certificate,
        EnableDiscovery = config.DiscoveryEnabled,
        EnablePipes = true,
        RestrictDataAcl = true,
        UseWindowsService = !consoleMode
    };

    var app = AgentWebApp.Build(options);
    await app.RunAsync().ConfigureAwait(false);
    return 0;
}
catch (Exception ex)
{
    Console.Error.WriteLine($"PcWakeAgent failed to start: {ex.Message}");
    return 1;
}
