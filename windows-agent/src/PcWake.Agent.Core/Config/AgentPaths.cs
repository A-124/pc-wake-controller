namespace PcWake.Agent.Core.Config;

/// <summary>All mutable agent data lives below one protected directory.</summary>
public sealed class AgentPaths
{
    public AgentPaths(string rootDirectory)
    {
        if (string.IsNullOrWhiteSpace(rootDirectory))
        {
            throw new ArgumentException("Root directory is required.", nameof(rootDirectory));
        }

        RootDirectory = Path.GetFullPath(rootDirectory);
    }

    public string RootDirectory { get; }
    public string ConfigFile => Path.Combine(RootDirectory, "config.json");
    public string StateFile => Path.Combine(RootDirectory, "state.json");
    public string SecretFile => Path.Combine(RootDirectory, "device-secrets.dat");
    public string LogDirectory => Path.Combine(RootDirectory, "logs");
    public string CertificateDirectory => Path.Combine(RootDirectory, "certs");
    public string PfxFile => Path.Combine(CertificateDirectory, "agent.pfx");
    public string PfxPasswordFile => Path.Combine(CertificateDirectory, "agent.pfx.pass");

    public static AgentPaths Default => new(
        Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.CommonApplicationData), "PcWakeAgent"));

    public void EnsureDirectories()
    {
        Directory.CreateDirectory(RootDirectory);
        Directory.CreateDirectory(LogDirectory);
        Directory.CreateDirectory(CertificateDirectory);
    }
}
