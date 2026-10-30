using PcWake.Agent.Core.Protocol;

namespace PcWake.Agent.Core.State;

public static class AgentStateValidator
{
    public static IReadOnlyList<string> Validate(AgentState? state)
    {
        var errors = new List<string>();
        if (state is null)
        {
            errors.Add("State is empty.");
            return errors;
        }

        if (state.SchemaVersion != 1)
        {
            errors.Add($"Unsupported schemaVersion {state.SchemaVersion}; expected 1.");
        }

        if (state.Devices is null)
        {
            errors.Add("devices is missing.");
            return errors;
        }

        var ids = new HashSet<string>(StringComparer.Ordinal);
        foreach (var device in state.Devices)
        {
            if (device is null)
            {
                errors.Add("A device entry is null.");
                continue;
            }

            if (!TokenGenerator.IsValidDeviceId(device.DeviceId))
            {
                errors.Add($"Invalid deviceId '{device.DeviceId}'.");
            }
            else if (!ids.Add(device.DeviceId))
            {
                errors.Add($"Duplicate deviceId '{device.DeviceId}'.");
            }

            if (!TokenGenerator.IsValidTokenHash(device.TokenHash))
            {
                errors.Add($"Invalid token hash for device '{device.DeviceId}'.");
            }

            if (string.IsNullOrWhiteSpace(device.Name) || device.Name.Length > 100)
            {
                errors.Add($"Invalid name for device '{device.DeviceId}'.");
            }

            if (device.Name.Any(char.IsControl) || device.Model.Any(char.IsControl) ||
                device.AppVersion.Any(char.IsControl))
            {
                errors.Add($"Control characters in device '{device.DeviceId}'.");
            }

            if (device.Model.Length > 100 || device.AppVersion.Length > 40)
            {
                errors.Add($"Oversized device metadata for '{device.DeviceId}'.");
            }

            if (device.PairedAt < 0 || device.LastSeenAt < 0)
            {
                errors.Add($"Negative timestamp for device '{device.DeviceId}'.");
            }

            if (device.Revoked && device.RevokedAt is null)
            {
                errors.Add($"Revoked device '{device.DeviceId}' has no revokedAt.");
            }
        }

        return errors;
    }

    public static void ThrowIfInvalid(AgentState? state)
    {
        var errors = Validate(state);
        if (errors.Count > 0)
        {
            throw new InvalidDataException("state.json is invalid: " + string.Join(" ", errors));
        }
    }
}
