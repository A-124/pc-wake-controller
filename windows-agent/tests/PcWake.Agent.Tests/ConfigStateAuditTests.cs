using System.Text.Json;
using PcWake.Agent.Core.Audit;
using PcWake.Agent.Core.Config;
using PcWake.Agent.Core.Protocol;
using PcWake.Agent.Core.State;

namespace PcWake.Agent.Tests;

public sealed class ConfigStateAuditTests
{
    private static DeviceRecord ValidDevice(string? id = null) => new()
    {
        DeviceId = id ?? TokenGenerator.CreateDeviceId(),
        TokenHash = TokenGenerator.HashToken(TokenGenerator.CreateToken()),
        Name = "Pixel",
        Model = "Pixel",
        AppVersion = "1.0.0",
        PairedAt = 1_720_000_000,
        LastSeenAt = 1_720_000_000
    };

    [Fact]
    public void ConfigValidation_RejectsInvalidPortsAndNames()
    {
        var config = new AgentConfig { HttpsPort = 0 };
        Assert.Contains(AgentConfig.Validate(config), e => e.Contains("httpsPort"));

        config = new AgentConfig { HttpsPort = 47100, DiscoveryPort = 47100 };
        Assert.Contains(AgentConfig.Validate(config), e => e.Contains("differ"));

        config = new AgentConfig { DisplayName = "bad\nname" };
        Assert.Contains(AgentConfig.Validate(config), e => e.Contains("control"));

        config = new AgentConfig { DisplayName = new string('a', 65) };
        Assert.Contains(AgentConfig.Validate(config), e => e.Contains("64"));

        Assert.Empty(AgentConfig.Validate(new AgentConfig()));
    }

    [Fact]
    public async Task ConfigStore_CreatesReloadsAndValidates()
    {
        using var temp = new TempDirectory();
        var store = new AgentConfigStore(temp.Paths, restrictAcl: false);
        var created = store.LoadOrCreate();

        Assert.True(File.Exists(temp.Paths.ConfigFile));
        Assert.Equal(47101, created.HttpsPort);
        Assert.True(created.DiscoveryEnabled);

        created.HttpsPort = 47102;
        await store.SaveAsync(created);
        var reloaded = store.LoadOrCreate();
        Assert.Equal(47102, reloaded.HttpsPort);

        File.WriteAllText(temp.Paths.ConfigFile, "{ this is not json");
        Assert.Throws<AgentConfigurationException>(() => store.LoadOrCreate());

        File.WriteAllText(temp.Paths.ConfigFile, JsonSerializer.Serialize(new AgentConfig { HttpsPort = 70000 }));
        Assert.Throws<AgentConfigurationException>(() => store.LoadOrCreate());
    }

    [Fact]
    public void StateValidation_RejectsDangerousOrMalformedRecords()
    {
        var valid = new AgentState { Devices = [ValidDevice()] };
        Assert.Empty(AgentStateValidator.Validate(valid));

        var duplicateId = TokenGenerator.CreateDeviceId();
        var duplicate = new AgentState
        {
            Devices = [ValidDevice(duplicateId), ValidDevice(duplicateId)]
        };
        Assert.Contains(AgentStateValidator.Validate(duplicate), e => e.Contains("Duplicate"));

        var uppercase = new AgentState
        {
            Devices = [new DeviceRecord
            {
                DeviceId = TokenGenerator.CreateDeviceId().ToUpperInvariant(),
                TokenHash = TokenGenerator.HashToken("x"),
                Name = "phone"
            }]
        };
        Assert.Contains(AgentStateValidator.Validate(uppercase), e => e.Contains("Invalid deviceId"));

        var badHash = ValidDevice();
        badHash.TokenHash = "abc";
        Assert.Contains(AgentStateValidator.Validate(new AgentState { Devices = [badHash] }),
            e => e.Contains("Invalid token hash"));

        var revoked = ValidDevice();
        revoked.Revoked = true;
        Assert.Contains(AgentStateValidator.Validate(new AgentState { Devices = [revoked] }),
            e => e.Contains("revokedAt"));

        Assert.Throws<InvalidDataException>(() =>
            AgentStateValidator.ThrowIfInvalid(new AgentState { SchemaVersion = 99 }));
    }

    [Fact]
    public async Task DeviceStore_PersistsAtomicallyAndNeverWritesTokenHashesAsTokens()
    {
        using var temp = new TempDirectory();
        var clock = new FakeClock();
        var store = new DeviceStore(temp.Paths, clock, restrictAcl: false);
        var device = ValidDevice();
        await store.AddAsync(device);

        var json = await File.ReadAllTextAsync(temp.Paths.StateFile);
        using var document = JsonDocument.Parse(json);
        var stored = document.RootElement.GetProperty("devices")[0];
        Assert.Equal(device.DeviceId, stored.GetProperty("deviceId").GetString());
        Assert.Equal(device.TokenHash, stored.GetProperty("tokenHash").GetString());
        Assert.False(stored.TryGetProperty("token", out _));
        Assert.Empty(Directory.GetFiles(temp.Paths.RootDirectory, "*.tmp", SearchOption.AllDirectories));

        await store.RevokeAsync(device.DeviceId, clock.UtcNow.ToUnixTimeSeconds());
        await store.FlushAsync();
        var reloaded = new DeviceStore(temp.Paths, clock, restrictAcl: false);
        Assert.True(reloaded.GetById(device.DeviceId)!.Revoked);
    }

    [Fact]
    public void DeviceStore_RejectsCorruptState()
    {
        using var temp = new TempDirectory();
        File.WriteAllText(temp.Paths.StateFile, "{ definitely not json");
        Assert.Throws<InvalidDataException>(() =>
            new DeviceStore(temp.Paths, new FakeClock(), restrictAcl: false));
    }

    [Fact]
    public async Task AuditLog_RedactsSensitiveFieldNames()
    {
        using var temp = new TempDirectory();
        await using var audit = new AuditLogger(temp.Paths, restrictAcl: false);

        await audit.LogAsync("audit_test", new Dictionary<string, object?>
        {
            ["token"] = "SUPER_SECRET_TOKEN_VALUE",
            ["pairingCode"] = "K7Q2-M4XP",
            ["signature"] = "SUPER_SECRET_SIGNATURE",
            ["password"] = "SUPER_SECRET_PASSWORD",
            ["deviceId"] = "0123456789abcdef0123456789abcdef",
            ["ip"] = "192.168.1.9"
        });

        var contents = File.ReadAllText(audit.CurrentFile);
        Assert.DoesNotContain("SUPER_SECRET_TOKEN_VALUE", contents);
        Assert.DoesNotContain("K7Q2-M4XP", contents);
        Assert.DoesNotContain("SUPER_SECRET_SIGNATURE", contents);
        Assert.DoesNotContain("SUPER_SECRET_PASSWORD", contents);
        Assert.Contains("[redacted]", contents);
        Assert.Contains("0123456789abcdef0123456789abcdef", contents);
        Assert.Contains("\"event\":\"audit_test\"", contents);
    }

    [Fact]
    public async Task AuditLog_RotatesAndBoundsTheNumberOfFiles()
    {
        using var temp = new TempDirectory();
        await using var audit = new AuditLogger(temp.Paths, restrictAcl: false, maxBytes: 200, maxFiles: 3);

        for (var i = 0; i < 12; i++)
        {
            await audit.LogAsync("rotation_test", new Dictionary<string, object?>
            {
                ["sequence"] = i,
                ["detail"] = new string('z', 120)
            });
        }

        var files = Directory.GetFiles(temp.Paths.LogDirectory, "audit*.jsonl");
        Assert.True(files.Length <= 3, $"Expected at most 3 audit files, found {files.Length}.");
        Assert.Contains(files, f => Path.GetFileName(f) == "audit.1.jsonl");
        Assert.True(File.Exists(audit.CurrentFile));
        Assert.All(files, f => Assert.Contains("rotation_test", File.ReadAllText(f)));
    }
}
