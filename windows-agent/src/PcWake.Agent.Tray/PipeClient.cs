using System.IO.Pipes;
using System.Text;
using System.Text.Json;
using PcWake.Agent.Core.Protocol;

namespace PcWake.Agent.Tray;

internal static class PipeClient
{
    private static readonly TimeSpan Timeout = TimeSpan.FromSeconds(3);

    public static Task<AgentStatusSnapshot?> GetStatusAsync(CancellationToken cancellationToken = default) =>
        SendAsync<AgentStatusSnapshot>(
            ProtocolConstants.StatusPipeName,
            new PipeRequest { Command = PipeCommands.Status },
            cancellationToken);

    public static Task<AgentStatusSnapshot?> AdminAsync(
        string command,
        string? deviceId = null,
        CancellationToken cancellationToken = default) =>
        SendAsync<AgentStatusSnapshot>(
            ProtocolConstants.AdminPipeName,
            new PipeRequest { Command = command, DeviceId = deviceId },
            cancellationToken);

    public static Task<PipeCancelResult?> CancelAsync(CancellationToken cancellationToken = default) =>
        SendAsync<PipeCancelResult>(
            ProtocolConstants.StatusPipeName,
            new PipeRequest { Command = PipeCommands.Cancel },
            cancellationToken);

    private static async Task<T?> SendAsync<T>(
        string pipeName,
        PipeRequest request,
        CancellationToken cancellationToken)
    {
        using var timeout = CancellationTokenSource.CreateLinkedTokenSource(cancellationToken);
        timeout.CancelAfter(Timeout);

        try
        {
            await using var pipe = new NamedPipeClientStream(
                ".",
                pipeName,
                PipeDirection.InOut,
                PipeOptions.Asynchronous);

            await pipe.ConnectAsync(2000).WaitAsync(timeout.Token).ConfigureAwait(false);
            using var reader = new StreamReader(
                pipe,
                Encoding.UTF8,
                detectEncodingFromByteOrderMarks: false,
                bufferSize: 4096,
                leaveOpen: true);
            using var writer = new StreamWriter(pipe, new UTF8Encoding(false), bufferSize: 4096, leaveOpen: true)
            {
                AutoFlush = true
            };

            var payload = JsonSerializer.Serialize(request, JsonDefaults.Protocol);
            await writer.WriteLineAsync(payload.AsMemory(), timeout.Token).ConfigureAwait(false);
            await writer.FlushAsync(timeout.Token).ConfigureAwait(false);

            var line = await reader.ReadLineAsync().WaitAsync(timeout.Token).ConfigureAwait(false);
            if (string.IsNullOrWhiteSpace(line))
            {
                return default;
            }

            return JsonSerializer.Deserialize<T>(line, JsonDefaults.Protocol);
        }
        catch (Exception ex) when (
            ex is IOException or TimeoutException or OperationCanceledException or UnauthorizedAccessException or JsonException)
        {
            return default;
        }
    }
}
