using System.Text;
using System.Text.Json;
using PcWake.Agent.Core.Audit;
using PcWake.Agent.Core.Pairing;
using PcWake.Agent.Core.Protocol;
using PcWake.Agent.Core.Secrets;
using PcWake.Agent.Core.State;

namespace PcWake.Agent.Tests;

public sealed class PairingTests
{
    private static PairingContext Context() => new(
        "DESKTOP-TEST",
        "192.168.1.50",
        47101,
        new string('a', 64));

    private sealed class Fixture : IDisposable
    {
        public Fixture()
        {
            Temp = new TempDirectory();
            Clock = new FakeClock();
            Store = new DeviceStore(Temp.Paths, Clock, restrictAcl: false);
            Secrets = new InMemoryDeviceSecretStore();
            Audit = new RecordingAuditLog();
            Manager = new PairingManager(Store, Secrets, Clock, Audit);
        }

        public TempDirectory Temp { get; }
        public FakeClock Clock { get; }
        public DeviceStore Store { get; }
        public InMemoryDeviceSecretStore Secrets { get; }
        public RecordingAuditLog Audit { get; }
        public PairingManager Manager { get; }

        public void Dispose() => Temp.Dispose();
    }

    [Fact]
    public async Task Start_CreatesValidPayloadAndNeverPersistsOrAuditsTheCode()
    {
        using var fixture = new Fixture();
        var start = await fixture.Manager.StartAsync(Context());

        Assert.Matches("^[0-9A-HJKMNP-TV-Z]{4}-[0-9A-HJKMNP-TV-Z]{4}$", start.Code);
        Assert.Equal(PairingState.Open, fixture.Manager.GetStatus().State);
        Assert.Equal(300L, fixture.Manager.GetStatus().RemainingSeconds);

        using var payload = JsonDocument.Parse(start.Payload);
        Assert.Equal(1, payload.RootElement.GetProperty("v").GetInt32());
        Assert.Equal("agent", payload.RootElement.GetProperty("kind").GetString());
        Assert.Equal("DESKTOP-TEST", payload.RootElement.GetProperty("name").GetString());
        Assert.Equal("192.168.1.50", payload.RootElement.GetProperty("host").GetString());
        Assert.Equal(47101, payload.RootElement.GetProperty("port").GetInt32());
        Assert.Equal(new string('a', 64), payload.RootElement.GetProperty("fingerprint").GetString());
        Assert.Equal(start.Code, payload.RootElement.GetProperty("code").GetString());

        var stateJson = File.ReadAllText(fixture.Temp.Paths.StateFile);
        Assert.DoesNotContain(start.Code, stateJson);
        Assert.DoesNotContain("code", stateJson, StringComparison.OrdinalIgnoreCase);
        Assert.All(
            fixture.Audit.Entries,
            entry => Assert.DoesNotContain(start.Code, JsonSerializer.Serialize(entry.Fields)));
    }

    [Fact]
    public async Task SuccessfulPairing_ReturnsTokenOnceAndPersistsOnlyHashInState()
    {
        using var fixture = new Fixture();
        var start = await fixture.Manager.StartAsync(Context());

        var outcome = await fixture.Manager.TryPairAsync(
            start.Code.ToLowerInvariant(),
            new PairDeviceInfo("Pixel 8", "Google Pixel 8", "1.0.0"),
            "192.168.1.60");

        Assert.True(outcome.Succeeded);
        var credentials = outcome.Credentials!;
        Assert.True(TokenGenerator.IsValidDeviceId(credentials.DeviceId));
        Assert.True(TokenGenerator.IsValidToken(credentials.Token));
        Assert.True(TokenGenerator.VerifyTokenHash(
            credentials.Token,
            fixture.Store.GetById(credentials.DeviceId)!.TokenHash));

        var stateJson = File.ReadAllText(fixture.Temp.Paths.StateFile);
        Assert.DoesNotContain(credentials.Token, stateJson);
        Assert.Contains("tokenHash", stateJson, StringComparison.OrdinalIgnoreCase);
        Assert.True(fixture.Secrets.TryGetToken(credentials.DeviceId, out var storedToken));
        Assert.Equal(credentials.Token, storedToken);

        Assert.Equal(PairingState.Paired, fixture.Manager.GetStatus().State);
        Assert.Equal("Pixel 8", fixture.Manager.GetStatus().PairedDeviceName);
        Assert.True(fixture.Audit.ContainsEvent(AuditEvents.PairingSucceeded));

        var second = await fixture.Manager.TryPairAsync(
            start.Code,
            new PairDeviceInfo("Second", "Model", "1.0.0"));
        Assert.Equal(PairingError.Closed, second.Error);
        Assert.Null(second.Credentials);
    }

    [Fact]
    public async Task PairingCode_ExpiresAfter300SecondsWithFakeClock()
    {
        using var fixture = new Fixture();
        var start = await fixture.Manager.StartAsync(Context());
        fixture.Clock.Advance(TimeSpan.FromSeconds(301));

        var outcome = await fixture.Manager.TryPairAsync(
            start.Code,
            new PairDeviceInfo("Pixel", "Pixel", "1.0.0"));

        Assert.Equal(PairingError.InvalidCode, outcome.Error);
        Assert.Equal(PairingState.Expired, fixture.Manager.GetStatus().State);
        Assert.Empty(fixture.Store.GetAll());
    }

    [Fact]
    public async Task WrongCode_LocksAfterFiveFailedAttempts()
    {
        using var fixture = new Fixture();
        var start = await fixture.Manager.StartAsync(Context());
        var wrong = start.Code[..4] + "-ZZZZ";

        for (var attempt = 1; attempt <= 5; attempt++)
        {
            var outcome = await fixture.Manager.TryPairAsync(
                wrong,
                new PairDeviceInfo("Pixel", "Pixel", "1.0.0"));
            Assert.Equal(PairingError.InvalidCode, outcome.Error);
        }

        Assert.Equal(PairingState.Locked, fixture.Manager.GetStatus().State);
        var sixth = await fixture.Manager.TryPairAsync(
            wrong,
            new PairDeviceInfo("Pixel", "Pixel", "1.0.0"));
        Assert.Equal(PairingError.TooManyAttempts, sixth.Error);
        Assert.True(fixture.Audit.ContainsEvent(AuditEvents.PairingLocked));
    }

    [Fact]
    public async Task WrongCode_TypoDoesNotConsumeTheWindow()
    {
        using var fixture = new Fixture();
        var start = await fixture.Manager.StartAsync(Context());
        await fixture.Manager.TryPairAsync(
            "XXXX-XXXX",
            new PairDeviceInfo("Pixel", "Pixel", "1.0.0"));

        var outcome = await fixture.Manager.TryPairAsync(
            start.Code,
            new PairDeviceInfo("Pixel", "Pixel", "1.0.0"));

        Assert.True(outcome.Succeeded);
        Assert.Single(fixture.Store.GetAll());
    }

    [Fact]
    public async Task PairingWithoutAnOpenWindow_IsClosed()
    {
        using var fixture = new Fixture();
        var outcome = await fixture.Manager.TryPairAsync(
            "ABCD-EFGH",
            new PairDeviceInfo("Pixel", "Pixel", "1.0.0"));

        Assert.Equal(PairingError.Closed, outcome.Error);
        Assert.Equal(PairingState.Closed, fixture.Manager.GetStatus().State);
    }

    [Fact]
    public async Task PairingCodeNormalization_AcceptsAmbiguousCharacters()
    {
        using var fixture = new Fixture();
        var start = await fixture.Manager.StartAsync(Context());

        // Format with spaces, lowercase and ambiguity substitutions that normalize
        // to the generated code.
        var normalized = CrockfordBase32.Normalize(start.Code);
        var ambiguous = new StringBuilder();
        foreach (var c in normalized)
        {
            ambiguous.Append(c switch
            {
                '1' => 'I',
                '0' => 'O',
                'V' => 'U',
                _ => char.ToLowerInvariant(c)
            });
        }

        ambiguous.Insert(4, ' ');
        var outcome = await fixture.Manager.TryPairAsync(
            ambiguous.ToString(),
            new PairDeviceInfo("Pixel", "Pixel", "1.0.0"));

        Assert.True(outcome.Succeeded);
    }

    [Fact]
    public async Task DpapiSecretVault_DoesNotStoreTokenPlaintextAndSurvivesReload()
    {
        using var temp = new TempDirectory();
        var deviceId = TokenGenerator.CreateDeviceId();
        var token = TokenGenerator.CreateToken();

        var first = new DpapiDeviceSecretStore(temp.Paths, restrictAcl: false);
        await first.SetTokenAsync(deviceId, token);
        var bytes = File.ReadAllBytes(temp.Paths.SecretFile);
        Assert.DoesNotContain(token, Encoding.UTF8.GetString(bytes));

        var second = new DpapiDeviceSecretStore(temp.Paths, restrictAcl: false);
        Assert.True(second.TryGetToken(deviceId, out var loaded));
        Assert.Equal(token, loaded);

        await second.DisposeAsync();
        await first.DisposeAsync();
    }
}
