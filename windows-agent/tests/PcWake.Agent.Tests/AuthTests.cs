using System.Text;
using PcWake.Agent.Core.Audit;
using PcWake.Agent.Core.Auth;
using PcWake.Agent.Core.Protocol;
using PcWake.Agent.Core.Secrets;
using PcWake.Agent.Core.State;

namespace PcWake.Agent.Tests;

public sealed class AuthTests
{
    private sealed class Fixture : IDisposable
    {
        public Fixture(int deviceLimit = 60, int failureLimit = 10)
        {
            Temp = new TempDirectory();
            Clock = new FakeClock();
            Devices = new DeviceStore(Temp.Paths, Clock, restrictAcl: false);
            Secrets = new InMemoryDeviceSecretStore();
            Audit = new RecordingAuditLog();
            DeviceId = TokenGenerator.CreateDeviceId();
            Token = TokenGenerator.CreateToken();
            Secrets.SetTokenAsync(DeviceId, Token).GetAwaiter().GetResult();
            Devices.AddAsync(new DeviceRecord
            {
                DeviceId = DeviceId,
                TokenHash = TokenGenerator.HashToken(Token),
                Name = "Pixel 8",
                Model = "Google Pixel 8",
                AppVersion = "1.0.0",
                PairedAt = Clock.UtcNow.ToUnixTimeSeconds(),
                LastSeenAt = Clock.UtcNow.ToUnixTimeSeconds()
            }).GetAwaiter().GetResult();

            var nonces = new NonceReplayCache(Clock);
            Authenticator = new RequestAuthenticator(
                Devices,
                Secrets,
                Clock,
                nonces,
                new DeviceRateLimiter(Clock, deviceLimit),
                new AuthFailureRateLimiter(Clock, failureLimit),
                Audit);
        }

        public TempDirectory Temp { get; }
        public FakeClock Clock { get; }
        public DeviceStore Devices { get; }
        public InMemoryDeviceSecretStore Secrets { get; }
        public RecordingAuditLog Audit { get; }
        public string DeviceId { get; }
        public string Token { get; }
        public RequestAuthenticator Authenticator { get; }

        public AuthRequest Build(
            string method,
            string path,
            string? body,
            long? timestamp = null,
            string? nonce = null,
            string? signatureOverride = null,
            string? deviceIdOverride = null)
        {
            var bytes = body is null ? [] : Encoding.UTF8.GetBytes(body);
            var signed = RequestSigner.Sign(
                Token,
                deviceIdOverride ?? DeviceId,
                method,
                path,
                bytes,
                timestamp ?? Clock.UtcNow.ToUnixTimeSeconds(),
                nonce);
            return new AuthRequest(
                method,
                path,
                signed.DeviceId,
                signed.Timestamp,
                signed.Nonce,
                signatureOverride ?? signed.Signature,
                bytes);
        }

        public void Dispose() => Temp.Dispose();
    }

    [Fact]
    public async Task ValidRequest_IsAcceptedAndUpdatesLastSeen()
    {
        using var fixture = new Fixture();
        var request = fixture.Build("GET", "/api/v1/status", null);

        var result = await fixture.Authenticator.AuthenticateAsync(request, "192.168.1.10");

        Assert.True(result.Succeeded);
        Assert.Equal(fixture.DeviceId, result.Device!.DeviceId);
        Assert.Equal(fixture.Clock.UtcNow.ToUnixTimeSeconds(), fixture.Devices.GetById(fixture.DeviceId)!.LastSeenAt);
        Assert.True(fixture.Audit.ContainsEvent(AuditEvents.AuthOk));
    }

    [Fact]
    public async Task BadSignature_IsUnauthorized()
    {
        using var fixture = new Fixture();
        var request = fixture.Build("POST", "/api/v1/power/shutdown", "{\"graceSeconds\":15}");
        var tampered = request.Signature![0] == 'A' ? "B" + request.Signature[1..] : "A" + request.Signature[1..];
        request = request with { Signature = tampered };

        var result = await fixture.Authenticator.AuthenticateAsync(request, "192.168.1.10");

        Assert.Equal(AuthenticationOutcome.Unauthorized, result.Outcome);
        Assert.True(fixture.Audit.ContainsEvent(AuditEvents.AuthFailure));
    }

    [Theory]
    [InlineData(-61)]
    [InlineData(61)]
    public async Task TimestampOutsideSkew_IsUnauthorized(int offset)
    {
        using var fixture = new Fixture();
        var timestamp = fixture.Clock.UtcNow.ToUnixTimeSeconds() + offset;
        var request = fixture.Build("GET", "/api/v1/status", null, timestamp);

        var result = await fixture.Authenticator.AuthenticateAsync(request, "192.168.1.10");

        Assert.Equal(AuthenticationOutcome.Unauthorized, result.Outcome);
    }

    [Fact]
    public async Task ReplayedNonce_IsRejectedButNonceSurvivesFailedSignature()
    {
        using var fixture = new Fixture();
        var nonce = RequestSigner.CreateNonce();
        var first = fixture.Build("GET", "/api/v1/status", null, nonce: nonce);

        var accepted = await fixture.Authenticator.AuthenticateAsync(first, "192.168.1.10");
        Assert.True(accepted.Succeeded);

        var replay = await fixture.Authenticator.AuthenticateAsync(first, "192.168.1.10");
        Assert.Equal(AuthenticationOutcome.Unauthorized, replay.Outcome);

        // A failed signature does not consume the nonce.
        var secondNonce = RequestSigner.CreateNonce();
        var bad = fixture.Build("GET", "/api/v1/status", null, nonce: secondNonce, signatureOverride: "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA");
        var badResult = await fixture.Authenticator.AuthenticateAsync(bad, "192.168.1.11");
        Assert.Equal(AuthenticationOutcome.Unauthorized, badResult.Outcome);
        var good = fixture.Build("GET", "/api/v1/status", null, nonce: secondNonce);
        var goodResult = await fixture.Authenticator.AuthenticateAsync(good, "192.168.1.11");
        Assert.True(goodResult.Succeeded);
    }

    [Fact]
    public async Task RevokedDevice_IsUnauthorized()
    {
        using var fixture = new Fixture();
        await fixture.Devices.RevokeAsync(
            fixture.DeviceId,
            fixture.Clock.UtcNow.ToUnixTimeSeconds());

        var result = await fixture.Authenticator.AuthenticateAsync(
            fixture.Build("GET", "/api/v1/status", null),
            "192.168.1.10");

        Assert.Equal(AuthenticationOutcome.Unauthorized, result.Outcome);
    }

    [Fact]
    public async Task DeviceRateLimit_BlocksTheSixtyFirstRequestPerMinute()
    {
        using var fixture = new Fixture(deviceLimit: 3);
        AuthenticationResult? last = null;
        for (var i = 0; i < 4; i++)
        {
            last = await fixture.Authenticator.AuthenticateAsync(
                fixture.Build("GET", "/api/v1/status", null, nonce: RequestSigner.CreateNonce()),
                "192.168.1.10");
        }

        Assert.Equal(AuthenticationOutcome.RateLimited, last!.Outcome);
    }

    [Fact]
    public async Task AuthFailureRateLimit_Returns429AfterConfiguredFailures()
    {
        using var fixture = new Fixture(failureLimit: 2);
        for (var i = 0; i < 2; i++)
        {
            var bad = fixture.Build(
                "GET",
                "/api/v1/status",
                null,
                nonce: RequestSigner.CreateNonce(),
                signatureOverride: "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA");
            var result = await fixture.Authenticator.AuthenticateAsync(bad, "192.168.2.20");
            Assert.Equal(AuthenticationOutcome.Unauthorized, result.Outcome);
        }

        var third = await fixture.Authenticator.AuthenticateAsync(
            fixture.Build("GET", "/api/v1/status", null, nonce: RequestSigner.CreateNonce()),
            "192.168.2.20");

        Assert.Equal(AuthenticationOutcome.RateLimited, third.Outcome);
    }

    [Fact]
    public async Task DifferentSourceIps_HaveIndependentFailureBudgets()
    {
        using var fixture = new Fixture(failureLimit: 1);
        var bad = fixture.Build(
            "GET",
            "/api/v1/status",
            null,
            nonce: RequestSigner.CreateNonce(),
            signatureOverride: "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA");
        await fixture.Authenticator.AuthenticateAsync(bad, "10.0.0.1");

        var otherIp = await fixture.Authenticator.AuthenticateAsync(
            fixture.Build("GET", "/api/v1/status", null, nonce: RequestSigner.CreateNonce()),
            "10.0.0.2");
        Assert.True(otherIp.Succeeded);
    }

    [Fact]
    public async Task MissingHeaders_AreUnauthorized()
    {
        using var fixture = new Fixture();
        var request = new AuthRequest("GET", "/api/v1/status", null, null, null, null, []);

        var result = await fixture.Authenticator.AuthenticateAsync(request, "192.168.1.10");

        Assert.Equal(AuthenticationOutcome.Unauthorized, result.Outcome);
    }
}
