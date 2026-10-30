using System.Text;
using PcWake.Agent.Core.Protocol;

namespace PcWake.Agent.Tests;

public sealed class ProtocolVectorTests
{
    private const string Token = "test-token-0123456789";
    private const string Timestamp = "1720000000";
    private const string Nonce = "AAECAwQFBgcICQoLDA0ODw";
    private const string BodyA = "{\"graceSeconds\":15}";
    private const string BodyAHash = "41d72c15ac4723b83324e5ff7a275bc1a6bbe34fa3de3a7c27d249c1b97eacb5";
    private const string EmptyHash = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855";
    private const string ShutdownSignature = "qEPDkh0eZIiTzT1ZGTXR4z398xh3XNG3iL6buuhenkQ";
    private const string StatusSignature = "6S_CySELEsBTeWGBaBgzKQqWjAkz3CMQTwxPMNKyVUI";

    [Fact]
    public void NormativeVectorA_ProducesExactCanonicalHashAndSignature()
    {
        var body = Encoding.UTF8.GetBytes(BodyA);
        var hash = CanonicalRequest.ComputeBodyHash(body);
        var canonical = CanonicalRequest.Build(
            "POST", "/api/v1/power/shutdown", Timestamp, Nonce, body);
        var signature = CanonicalRequest.ComputeSignature(Token, canonical);

        Assert.Equal(BodyAHash, hash);
        Assert.Equal(
            "POST\n/api/v1/power/shutdown\n1720000000\nAAECAwQFBgcICQoLDA0ODw\n" + BodyAHash,
            canonical);
        Assert.Equal(ShutdownSignature, signature);
        Assert.True(CanonicalRequest.VerifySignature(Token, canonical, ShutdownSignature));
    }

    [Fact]
    public void NormativeVectorB_ProducesExactEmptyHashAndSignature()
    {
        var canonical = CanonicalRequest.Build(
            "GET", "/api/v1/status", Timestamp, Nonce, ReadOnlySpan<byte>.Empty);
        var signature = CanonicalRequest.ComputeSignature(Token, canonical);

        Assert.Equal(EmptyHash, CanonicalRequest.ComputeBodyHash([]));
        Assert.Equal(
            "GET\n/api/v1/status\n1720000000\nAAECAwQFBgcICQoLDA0ODw\n" + EmptyHash,
            canonical);
        Assert.Equal(StatusSignature, signature);
        Assert.True(CanonicalRequest.VerifySignature(Token, canonical, StatusSignature));
    }

    [Fact]
    public void RequestSigner_ProducesTheNormativeHeaders()
    {
        var headers = RequestSigner.Sign(
            Token,
            "0123456789abcdef0123456789abcdef",
            "POST",
            "/api/v1/power/shutdown",
            BodyA,
            unixTimestamp: 1_720_000_000,
            nonce: Nonce);

        Assert.Equal(Timestamp, headers.Timestamp);
        Assert.Equal(Nonce, headers.Nonce);
        Assert.Equal(ShutdownSignature, headers.Signature);
    }

    [Theory]
    [InlineData("PATCH", "/api/v1/power/shutdown", BodyA)]
    [InlineData("POST", "/api/v1/power/restart", BodyA)]
    [InlineData("POST", "/api/v1/power/shutdown", "{\"graceSeconds\":16}")]
    [InlineData("POST", "/api/v1/power/shutdown?x=1", BodyA)]
    public void TamperedCanonical_IsRejected(string method, string path, string body)
    {
        var canonical = CanonicalRequest.Build(
            method, path, Timestamp, Nonce, Encoding.UTF8.GetBytes(body));
        Assert.False(CanonicalRequest.VerifySignature(Token, canonical, ShutdownSignature));
    }

    [Fact]
    public void TamperedTimestampOrNonce_IsRejected()
    {
        var body = Encoding.UTF8.GetBytes(BodyA);
        var timestampCanonical = CanonicalRequest.Build(
            "POST", "/api/v1/power/shutdown", "1720000001", Nonce, body);
        var nonceCanonical = CanonicalRequest.Build(
            "POST", "/api/v1/power/shutdown", Timestamp, Nonce + "A", body);

        Assert.False(CanonicalRequest.VerifySignature(Token, timestampCanonical, ShutdownSignature));
        Assert.False(CanonicalRequest.VerifySignature(Token, nonceCanonical, ShutdownSignature));
    }

    [Fact]
    public void Base64Url_IsUnpaddedAndStrict()
    {
        var token = TokenGenerator.CreateToken();
        Assert.Equal(43, token.Length);
        Assert.DoesNotContain('=', token);
        Assert.True(TokenGenerator.IsValidToken(token));
        Assert.False(Base64Url.TryDecode("AAAA=", out _));
        Assert.False(TokenGenerator.IsValidToken("short"));
        Assert.Equal(32, TokenGenerator.CreateDeviceId().Length);
    }

    [Fact]
    public void CrockfordCode_GenerationNormalizationAndFormatting()
    {
        var code = CrockfordBase32.GenerateNormalizedCode();
        Assert.Equal(8, code.Length);
        Assert.True(CrockfordBase32.IsValidNormalized(code));
        Assert.DoesNotContain('I', code);
        Assert.DoesNotContain('L', code);
        Assert.DoesNotContain('O', code);
        Assert.DoesNotContain('U', code);

        var formatted = CrockfordBase32.Format(code);
        Assert.Equal(9, formatted.Length);
        Assert.Equal('-', formatted[4]);
        Assert.Equal(code, CrockfordBase32.Normalize(formatted));
        Assert.Equal("11045678", CrockfordBase32.Normalize("il0-4567 8"));
        Assert.Equal("V", CrockfordBase32.Normalize("u"));
        Assert.False(CrockfordBase32.IsValidNormalized("1234567"));
    }

    [Fact]
    public void CrockfordNormalization_MapsAmbiguousCharacters()
    {
        var normalized = CrockfordBase32.Normalize("ILOU-1234");
        Assert.Equal("110V1234", normalized);
        Assert.True(CrockfordBase32.FixedTimeEquals(normalized, "110V1234"));
        Assert.False(CrockfordBase32.FixedTimeEquals(normalized, "110V1235"));
    }
}
