using System.Net;
using System.Net.Http.Headers;
using System.Text;
using System.Text.Json;
using Microsoft.AspNetCore.Builder;
using Microsoft.AspNetCore.Hosting.Server;
using Microsoft.AspNetCore.Hosting.Server.Features;
using Microsoft.Extensions.DependencyInjection;
using PcWake.Agent.Core.Abstractions;
using PcWake.Agent.Core.Network;
using PcWake.Agent.Core.Pairing;
using PcWake.Agent.Core.Power;
using PcWake.Agent.Core.Protocol;
using PcWake.Agent.Core.Secrets;
using PcWake.Agent.Service;

namespace PcWake.Agent.Tests;

public sealed class HttpIntegrationTests : IAsyncLifetime
{
    private readonly TempDirectory _temp = new();
    private readonly FakeClock _clock = new();
    private readonly FakePowerController _power = new();
    private readonly ManualPowerDelay _delay = new();
    private readonly InMemoryDeviceSecretStore _secrets = new();
    private WebApplication? _app;
    private HttpClient? _client;
    private ServerCertificate? _certificate;
    private string _fingerprint = string.Empty;
    private string? _deviceId;
    private string? _token;

    public async Task InitializeAsync()
    {
        _certificate = new ServerCertificate(
            CertificateFactory.CreateSelfSigned("DESKTOP-TEST"),
            string.Empty);
        _fingerprint = CertificateFactory.Fingerprint(_certificate.Certificate);

        var options = new AgentServerOptions
        {
            Paths = _temp.Paths,
            Config = new Core.Config.AgentConfig
            {
                HttpsPort = ProtocolConstants.DefaultHttpsPort,
                DiscoveryEnabled = false,
                DisplayName = "DESKTOP-TEST"
            },
            Certificate = _certificate.Certificate,
            ListenPortOverride = 0,
            EnableDiscovery = false,
            EnablePipes = false,
            RestrictDataAcl = false,
            UseWindowsService = false,
            Clock = _clock,
            PowerController = _power,
            PowerDelay = _delay,
            SecretStore = _secrets
        };

        _app = AgentWebApp.Build(options);
        await _app.StartAsync();

        var addresses = _app.Services.GetRequiredService<IServer>()
            .Features.Get<IServerAddressesFeature>()!
            .Addresses;
        var bound = addresses.Select(a => new Uri(a)).First();
        var baseAddress = new UriBuilder(Uri.UriSchemeHttps, "127.0.0.1", bound.Port).Uri;

        var handler = new HttpClientHandler
        {
            UseProxy = false,
            // This is a test-only trust override for the ephemeral self-signed cert.
            ServerCertificateCustomValidationCallback = (_, _, _, _) => true
        };
        _client = new HttpClient(handler) { BaseAddress = baseAddress, Timeout = TimeSpan.FromSeconds(15) };
    }

    [Fact]
    public async Task Health_IsUnauthenticatedAndLeaksNoState()
    {
        var response = await _client!.GetAsync("/health");
        var body = await response.Content.ReadAsStringAsync();

        Assert.Equal(HttpStatusCode.OK, response.StatusCode);
        Assert.StartsWith("application/json", response.Content.Headers.ContentType!.MediaType);
        using var document = JsonDocument.Parse(body);
        Assert.Equal(4, document.RootElement.EnumerateObject().Count());
        Assert.True(document.RootElement.GetProperty("ok").GetBoolean());
        Assert.Equal("pcwake-agent", document.RootElement.GetProperty("service").GetString());
        Assert.Equal("agent", document.RootElement.GetProperty("kind").GetString());
        Assert.DoesNotContain("DESKTOP-TEST", body);
        Assert.DoesNotContain(_fingerprint, body);
        Assert.DoesNotContain("paired", body, StringComparison.OrdinalIgnoreCase);
    }

    [Fact]
    public async Task UnauthenticatedStatus_ReturnsJson401()
    {
        var response = await _client!.GetAsync("/api/v1/status");
        var body = await response.Content.ReadAsStringAsync();

        Assert.Equal(HttpStatusCode.Unauthorized, response.StatusCode);
        using var document = JsonDocument.Parse(body);
        Assert.False(document.RootElement.GetProperty("ok").GetBoolean());
        Assert.Equal("unauthorized", document.RootElement.GetProperty("error").GetString());
    }

    [Fact]
    public async Task PairSignedStatusShutdownCancelAndRevoke_WorkEndToEnd()
    {
        await PairAsync();

        var status = await SendSignedAsync(
            _token!, _deviceId!, HttpMethod.Get, "/api/v1/status", body: null);
        var statusBody = await status.Content.ReadAsStringAsync();
        Assert.Equal(HttpStatusCode.OK, status.StatusCode);
        using (var document = JsonDocument.Parse(statusBody))
        {
            Assert.True(document.RootElement.GetProperty("ok").GetBoolean());
            Assert.Equal("DESKTOP-TEST", document.RootElement.GetProperty("name").GetString());
            Assert.Contains(
                document.RootElement.GetProperty("capabilities").EnumerateArray().Select(e => e.GetString()),
                capability => capability == "shutdown");
            Assert.Equal(JsonValueKind.Null, document.RootElement.GetProperty("pendingCommand").ValueKind);
        }

        var badSignature = await SendSignedAsync(
            _token!,
            _deviceId!,
            HttpMethod.Get,
            "/api/v1/status",
            body: null,
            signatureOverride: "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA");
        Assert.Equal(HttpStatusCode.Unauthorized, badSignature.StatusCode);

        const string shutdownBody = "{\"graceSeconds\":15}";
        var shutdown = await SendSignedAsync(
            _token!, _deviceId!, HttpMethod.Post, "/api/v1/power/shutdown", shutdownBody);
        var shutdownJson = await shutdown.Content.ReadAsStringAsync();
        Assert.Equal(HttpStatusCode.Accepted, shutdown.StatusCode);
        using (var document = JsonDocument.Parse(shutdownJson))
        {
            var command = document.RootElement.GetProperty("command");
            Assert.Equal("shutdown", command.GetProperty("action").GetString());
            Assert.Equal("pending", command.GetProperty("status").GetString());
            Assert.Equal(15, command.GetProperty("graceSeconds").GetInt32());
            Assert.False(string.IsNullOrEmpty(command.GetProperty("id").GetString()));
        }

        Assert.Single(_power.ShutdownCalls);
        await WaitHelpers.WaitUntilAsync(() => _delay.Requests.Count == 1);

        var pendingStatus = await SendSignedAsync(
            _token!, _deviceId!, HttpMethod.Get, "/api/v1/status", body: null);
        using (var document = JsonDocument.Parse(await pendingStatus.Content.ReadAsStringAsync()))
        {
            Assert.Equal("shutdown", document.RootElement.GetProperty("pendingCommand").GetProperty("action").GetString());
        }

        var cancel = await SendSignedAsync(
            _token!, _deviceId!, HttpMethod.Post, "/api/v1/power/cancel", "{}");
        Assert.Equal(HttpStatusCode.OK, cancel.StatusCode);
        using (var document = JsonDocument.Parse(await cancel.Content.ReadAsStringAsync()))
        {
            Assert.True(document.RootElement.GetProperty("ok").GetBoolean());
            Assert.True(document.RootElement.GetProperty("cancelled").GetBoolean());
        }

        var revoke = await SendSignedAsync(
            _token!, _deviceId!, HttpMethod.Post, "/api/v1/revoke", "{}");
        Assert.Equal(HttpStatusCode.OK, revoke.StatusCode);

        var afterRevoke = await SendSignedAsync(
            _token!, _deviceId!, HttpMethod.Get, "/api/v1/status", body: null);
        Assert.Equal(HttpStatusCode.Unauthorized, afterRevoke.StatusCode);
    }

    [Fact]
    public async Task PairingSuccess_ResponseContainsServerInfoAndTokenOnce()
    {
        await PairAsync();

        using var stateDocument = JsonDocument.Parse(await File.ReadAllTextAsync(_temp.Paths.StateFile));
        var stateText = stateDocument.RootElement.GetRawText();
        Assert.DoesNotContain(_token!, stateText);
        var device = stateDocument.RootElement.GetProperty("devices")[0];
        Assert.Equal(_deviceId, device.GetProperty("deviceId").GetString());
        Assert.Equal(64, device.GetProperty("tokenHash").GetString()!.Length);
        Assert.True(_secrets.TryGetToken(_deviceId!, out var stored));
        Assert.Equal(_token, stored);
    }

    private async Task PairAsync()
    {
        var runtime = _app!.Services.GetRequiredService<AgentRuntimeInfo>();
        var pairing = _app.Services.GetRequiredService<PairingManager>();
        var start = await pairing.StartAsync(new PairingContext(
            runtime.Name,
            runtime.HostAddress,
            _client!.BaseAddress!.Port,
            _fingerprint));

        var requestJson = JsonSerializer.Serialize(new PairRequestDto
        {
            Code = start.Code,
            DeviceName = "Pixel 8",
            DeviceModel = "Google Pixel 8",
            AppVersion = "1.0.0"
        }, JsonDefaults.Protocol);

        using var content = new StringContent(requestJson, Encoding.UTF8, "application/json");
        var response = await _client!.PostAsync("/api/v1/pair", content);
        var body = await response.Content.ReadAsStringAsync();
        Assert.Equal(HttpStatusCode.OK, response.StatusCode);
        using var document = JsonDocument.Parse(body);
        _deviceId = document.RootElement.GetProperty("deviceId").GetString();
        _token = document.RootElement.GetProperty("token").GetString();
        Assert.NotNull(_deviceId);
        Assert.NotNull(_token);
        Assert.True(TokenGenerator.IsValidDeviceId(_deviceId));
        Assert.True(TokenGenerator.IsValidToken(_token));
        var server = document.RootElement.GetProperty("server");
        Assert.Equal("agent", server.GetProperty("kind").GetString());
        Assert.Equal("DESKTOP-TEST", server.GetProperty("name").GetString());
        Assert.Equal(JsonValueKind.Null, server.GetProperty("targets").ValueKind);
        Assert.True(server.GetProperty("adapters").GetArrayLength() >= 0);
    }

    private async Task<HttpResponseMessage> SendSignedAsync(
        string token,
        string deviceId,
        HttpMethod method,
        string path,
        string? body,
        string? signatureOverride = null)
    {
        var bytes = body is null ? [] : Encoding.UTF8.GetBytes(body);
        var signed = RequestSigner.Sign(
            token,
            deviceId,
            method.Method,
            path,
            bytes,
            _clock.UtcNow.ToUnixTimeSeconds());

        using var request = new HttpRequestMessage(method, path);
        request.Headers.Add(HeaderNames.Device, deviceId);
        request.Headers.Add(HeaderNames.Timestamp, signed.Timestamp);
        request.Headers.Add(HeaderNames.Nonce, signed.Nonce);
        request.Headers.Add(HeaderNames.Signature, signatureOverride ?? signed.Signature);
        if (body is not null)
        {
            var content = new ByteArrayContent(bytes);
            content.Headers.ContentType = new MediaTypeHeaderValue("application/json") { CharSet = "utf-8" };
            request.Content = content;
        }

        return await _client!.SendAsync(request);
    }

    public async Task DisposeAsync()
    {
        _client?.Dispose();
        if (_app is not null)
        {
            await _app.StopAsync();
            await _app.DisposeAsync();
        }

        _certificate?.Dispose();
        _temp.Dispose();
    }
}
