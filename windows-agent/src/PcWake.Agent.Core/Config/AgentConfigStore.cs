using System.Text.Json;
using PcWake.Agent.Core.Protocol;

namespace PcWake.Agent.Core.Config;

public sealed class AgentConfigStore
{
    private readonly AgentPaths _paths;
    private readonly bool _restrictAcl;
    private readonly SemaphoreSlim _gate = new(1, 1);

    public AgentConfigStore(AgentPaths paths, bool restrictAcl)
    {
        _paths = paths;
        _restrictAcl = restrictAcl;
    }

    public AgentConfig LoadOrCreate()
    {
        _paths.EnsureDirectories();
        if (_restrictAcl)
        {
            FileAcl.RestrictDirectory(_paths.RootDirectory);
        }

        if (!File.Exists(_paths.ConfigFile))
        {
            var defaults = new AgentConfig();
            WriteDefaults(defaults);
            return defaults;
        }

        AgentConfig? config;
        try
        {
            var json = File.ReadAllText(_paths.ConfigFile);
            config = JsonSerializer.Deserialize<AgentConfig>(json, JsonDefaults.Protocol);
        }
        catch (JsonException ex)
        {
            throw new AgentConfigurationException($"config.json is not valid JSON: {ex.Message}", ex);
        }
        catch (IOException ex)
        {
            throw new AgentConfigurationException($"config.json could not be read: {ex.Message}", ex);
        }

        var errors = AgentConfig.Validate(config);
        if (errors.Count > 0)
        {
            throw new AgentConfigurationException("config.json is invalid: " + string.Join(" ", errors));
        }

        return config!;
    }

    public async Task SaveAsync(AgentConfig config, CancellationToken cancellationToken = default)
    {
        var errors = AgentConfig.Validate(config);
        if (errors.Count > 0)
        {
            throw new AgentConfigurationException("Refusing to save invalid configuration: " + string.Join(" ", errors));
        }

        await _gate.WaitAsync(cancellationToken).ConfigureAwait(false);
        try
        {
            _paths.EnsureDirectories();
            if (_restrictAcl)
            {
                FileAcl.RestrictDirectory(_paths.RootDirectory);
            }

            var json = JsonSerializer.Serialize(config, JsonDefaults.Indented);
            await AtomicFile.WriteAllTextAsync(_paths.ConfigFile, json, cancellationToken).ConfigureAwait(false);
            if (_restrictAcl)
            {
                FileAcl.RestrictFile(_paths.ConfigFile);
            }
        }
        finally
        {
            _gate.Release();
        }
    }

    private void WriteDefaults(AgentConfig defaults)
    {
        var json = JsonSerializer.Serialize(defaults, JsonDefaults.Indented);
        AtomicFile.WriteAllText(_paths.ConfigFile, json);
        if (_restrictAcl)
        {
            FileAcl.RestrictFile(_paths.ConfigFile);
        }
    }
}
