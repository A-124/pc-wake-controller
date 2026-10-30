using System.Net;
using System.Net.Sockets;
using System.Text;
using System.Text.Json;
using PcWake.Agent.Core.Abstractions;
using PcWake.Agent.Core.Audit;
using PcWake.Agent.Core.Config;
using PcWake.Agent.Core.Protocol;
using PcWake.Agent.Core.State;

namespace PcWake.Agent.Service;

/// <summary>
/// UDP LAN discovery on port 47100 (configurable). It answers the exact probe
/// with one unicast JSON datagram at most once per second per source address.
/// </summary>
public sealed class DiscoveryResponder : BackgroundService
{
    private static readonly byte[] ProbeBytes = Encoding.ASCII.GetBytes(ProtocolConstants.DiscoveryProbe);

    private readonly AgentConfig _config;
    private readonly AgentRuntimeInfo _runtime;
    private readonly DeviceStore _devices;
    private readonly IClock _clock;
    private readonly IAuditLog _audit;
    private readonly ILogger<DiscoveryResponder> _logger;
    private readonly Dictionary<string, long> _lastReplyBySource = new(StringComparer.Ordinal);

    public DiscoveryResponder(
        AgentConfig config,
        AgentRuntimeInfo runtime,
        DeviceStore devices,
        IClock clock,
        IAuditLog audit,
        ILogger<DiscoveryResponder> logger)
    {
        _config = config;
        _runtime = runtime;
        _devices = devices;
        _clock = clock;
        _audit = audit;
        _logger = logger;
    }

    protected override async Task ExecuteAsync(CancellationToken stoppingToken)
    {
        if (!_config.DiscoveryEnabled)
        {
            return;
        }

        using var udp = new UdpClient(AddressFamily.InterNetwork);
        try
        {
            udp.Client.SetSocketOption(SocketOptionLevel.Socket, SocketOptionName.ReuseAddress, false);
            udp.Client.Bind(new IPEndPoint(IPAddress.Any, _config.DiscoveryPort));
        }
        catch (SocketException ex)
        {
            _logger.LogWarning(ex, "UDP discovery could not bind to port {Port}.", _config.DiscoveryPort);
            await _audit.LogAsync(
                AuditEvents.DiscoveryError,
                new Dictionary<string, object?> { ["reason"] = "bind_failed", ["port"] = _config.DiscoveryPort },
                CancellationToken.None).ConfigureAwait(false);
            return;
        }

        _logger.LogInformation("Discovery responder listening on UDP {Port}.", _config.DiscoveryPort);

        while (!stoppingToken.IsCancellationRequested)
        {
            UdpReceiveResult datagram;
            try
            {
                datagram = await udp.ReceiveAsync(stoppingToken).ConfigureAwait(false);
            }
            catch (OperationCanceledException) when (stoppingToken.IsCancellationRequested)
            {
                break;
            }
            catch (SocketException ex)
            {
                _logger.LogWarning(ex, "UDP discovery receive failed.");
                try
                {
                    await Task.Delay(TimeSpan.FromSeconds(1), stoppingToken).ConfigureAwait(false);
                }
                catch (OperationCanceledException)
                {
                    break;
                }

                continue;
            }
            catch (ObjectDisposedException)
            {
                break;
            }

            if (!datagram.Buffer.AsSpan().SequenceEqual(ProbeBytes))
            {
                continue;
            }

            var sourceKey = datagram.RemoteEndPoint.Address.ToString();
            var now = _clock.UtcNow.ToUnixTimeMilliseconds();
            if (_lastReplyBySource.TryGetValue(sourceKey, out var lastReply) && now - lastReply < 1000)
            {
                continue;
            }

            _lastReplyBySource[sourceKey] = now;
            if (_lastReplyBySource.Count > 1024)
            {
                var cutoff = now - 60_000;
                foreach (var stale in _lastReplyBySource.Where(pair => pair.Value < cutoff).Select(pair => pair.Key).ToList())
                {
                    _lastReplyBySource.Remove(stale);
                }
            }

            var response = new DiscoveryResponse
            {
                Name = _runtime.Name,
                Port = _runtime.Port,
                Paired = _devices.ActiveDeviceCount > 0
            };

            try
            {
                var payload = JsonSerializer.SerializeToUtf8Bytes(response, JsonDefaults.Protocol);
                await udp.SendAsync(payload, datagram.RemoteEndPoint, stoppingToken).ConfigureAwait(false);
            }
            catch (SocketException ex)
            {
                _logger.LogDebug(ex, "UDP discovery reply failed.");
            }
        }
    }
}
