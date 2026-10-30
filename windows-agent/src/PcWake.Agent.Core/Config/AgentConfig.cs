using PcWake.Agent.Core.Protocol;

namespace PcWake.Agent.Core.Config;

public sealed class AgentConfig
{
    public int SchemaVersion { get; set; } = 1;
    public int HttpsPort { get; set; } = ProtocolConstants.DefaultHttpsPort;
    public bool DiscoveryEnabled { get; set; } = true;
    public int DiscoveryPort { get; set; } = ProtocolConstants.DefaultDiscoveryPort;

    /// <summary>Optional friendly name. Defaults to the Windows computer name.</summary>
    public string? DisplayName { get; set; }

    public string ResolvedDisplayName =>
        string.IsNullOrWhiteSpace(DisplayName) ? Environment.MachineName : DisplayName.Trim();

    public static IReadOnlyList<string> Validate(AgentConfig? config)
    {
        var errors = new List<string>();
        if (config is null)
        {
            errors.Add("Configuration is empty.");
            return errors;
        }

        if (config.SchemaVersion != 1)
        {
            errors.Add($"Unsupported schemaVersion {config.SchemaVersion}; expected 1.");
        }

        if (config.HttpsPort is < 1 or > 65535)
        {
            errors.Add("httpsPort must be between 1 and 65535.");
        }

        if (config.DiscoveryPort is < 1 or > 65535)
        {
            errors.Add("discoveryPort must be between 1 and 65535.");
        }

        if (config.HttpsPort == config.DiscoveryPort)
        {
            errors.Add("httpsPort and discoveryPort must differ.");
        }

        if (config.DisplayName is not null)
        {
            var name = config.DisplayName.Trim();
            if (name.Length > 64)
            {
                errors.Add("displayName must be at most 64 characters.");
            }

            if (name.Any(char.IsControl))
            {
                errors.Add("displayName must not contain control characters.");
            }
        }

        return errors;
    }

    public AgentConfig Clone() => new()
    {
        SchemaVersion = SchemaVersion,
        HttpsPort = HttpsPort,
        DiscoveryEnabled = DiscoveryEnabled,
        DiscoveryPort = DiscoveryPort,
        DisplayName = DisplayName
    };
}

public sealed class AgentConfigurationException : Exception
{
    public AgentConfigurationException(string message) : base(message)
    {
    }

    public AgentConfigurationException(string message, Exception inner) : base(message, inner)
    {
    }
}
