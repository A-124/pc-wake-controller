using System.Text.Json;
using System.Text.Json.Serialization;

namespace PcWake.Agent.Core.Protocol;

public static class JsonDefaults
{
    public static readonly JsonSerializerOptions Protocol = Create(false);
    public static readonly JsonSerializerOptions Indented = Create(true);

    private static JsonSerializerOptions Create(bool indented)
    {
        var options = new JsonSerializerOptions(JsonSerializerDefaults.Web)
        {
            PropertyNamingPolicy = JsonNamingPolicy.CamelCase,
            PropertyNameCaseInsensitive = true,
            WriteIndented = indented,
            DefaultIgnoreCondition = JsonIgnoreCondition.Never
        };
        options.Converters.Add(new JsonStringEnumConverter(JsonNamingPolicy.CamelCase));
        return options;
    }
}
