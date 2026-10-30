using System.Globalization;
using System.Security.Cryptography;

namespace PcWake.Agent.Core.Protocol;

public sealed record SignedRequestHeaders(
    string DeviceId,
    string Timestamp,
    string Nonce,
    string Signature,
    string Canonical);

/// <summary>Client-side helper for tests and diagnostics; the Android app implements the same construction.</summary>
public static class RequestSigner
{
    public static string CreateNonce() => Base64Url.Encode(RandomNumberGenerator.GetBytes(16));

    public static SignedRequestHeaders Sign(
        string token,
        string deviceId,
        string method,
        string pathWithQuery,
        ReadOnlySpan<byte> body,
        long? unixTimestamp = null,
        string? nonce = null)
    {
        var timestamp = (unixTimestamp ?? DateTimeOffset.UtcNow.ToUnixTimeSeconds())
            .ToString(CultureInfo.InvariantCulture);
        var actualNonce = nonce ?? CreateNonce();
        var canonical = CanonicalRequest.Build(method, pathWithQuery, timestamp, actualNonce, body);
        var signature = CanonicalRequest.ComputeSignature(token, canonical);
        return new SignedRequestHeaders(deviceId, timestamp, actualNonce, signature, canonical);
    }

    public static SignedRequestHeaders Sign(
        string token,
        string deviceId,
        string method,
        string pathWithQuery,
        string? body = null,
        long? unixTimestamp = null,
        string? nonce = null)
    {
        var bytes = body is null ? [] : System.Text.Encoding.UTF8.GetBytes(body);
        return Sign(token, deviceId, method, pathWithQuery, bytes, unixTimestamp, nonce);
    }
}
