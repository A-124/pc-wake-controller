using System.Text.Json;
using Microsoft.AspNetCore.Http.Features;
using PcWake.Agent.Core.Protocol;

namespace PcWake.Agent.Service.Endpoints;

internal static class HttpItems
{
    public const string Body = "pcwake.body";
    public const string Device = "pcwake.device";
}

internal sealed class RequestBodyTooLargeException : Exception
{
    public RequestBodyTooLargeException() : base("Request body exceeds the configured limit.")
    {
    }
}

internal static class HttpHelpers
{
    public const string JsonContentType = "application/json; charset=utf-8";
    private const int MaxPairBodyBytes = 16 * 1024;

    public static IResult Json(object value, int statusCode = StatusCodes.Status200OK)
    {
        return Results.Json(value, JsonDefaults.Protocol, JsonContentType, statusCode);
    }

    public static Task WriteJsonAsync(
        HttpResponse response,
        object value,
        int statusCode,
        CancellationToken cancellationToken)
    {
        var json = JsonSerializer.Serialize(value, JsonDefaults.Protocol);
        response.StatusCode = statusCode;
        response.ContentType = JsonContentType;
        return response.WriteAsync(json, cancellationToken);
    }

    public static Task WriteErrorAsync(
        HttpResponse response,
        string error,
        int statusCode,
        CancellationToken cancellationToken)
    {
        return WriteJsonAsync(response, new ErrorEnvelope(error), statusCode, cancellationToken);
    }

    public static async Task<byte[]> ReadBodyAsync(
        HttpRequest request,
        int maxBytes,
        CancellationToken cancellationToken)
    {
        if (request.ContentLength is > 0 && request.ContentLength > maxBytes)
        {
            throw new RequestBodyTooLargeException();
        }

        using var buffer = new MemoryStream(capacity: 4096);
        var chunk = new byte[8192];
        while (true)
        {
            var read = await request.Body.ReadAsync(chunk, cancellationToken).ConfigureAwait(false);
            if (read == 0)
            {
                break;
            }

            if (buffer.Length + read > maxBytes)
            {
                throw new RequestBodyTooLargeException();
            }

            buffer.Write(chunk, 0, read);
        }

        return buffer.ToArray();
    }

    public static Task<byte[]> ReadPairBodyAsync(HttpRequest request, CancellationToken cancellationToken) =>
        ReadBodyAsync(request, MaxPairBodyBytes, cancellationToken);

    public static string? GetSingleHeader(HttpRequest request, string name)
    {
        if (!request.Headers.TryGetValue(name, out var values) || values.Count != 1)
        {
            return null;
        }

        var value = values[0];
        return string.IsNullOrWhiteSpace(value) ? null : value;
    }

    public static string GetRawTarget(HttpContext context)
    {
        var rawTarget = context.Features.Get<IHttpRequestFeature>()?.RawTarget;
        if (!string.IsNullOrEmpty(rawTarget) && rawTarget.StartsWith('/'))
        {
            return rawTarget;
        }

        return context.Request.PathBase + context.Request.Path + context.Request.QueryString;
    }

    public static string SourceIp(HttpContext context) =>
        context.Connection.RemoteIpAddress?.ToString() ?? "unknown";

    public static bool TryGetJsonBody<T>(HttpContext context, out T? value)
    {
        value = default;
        if (context.Items.TryGetValue(HttpItems.Body, out var raw) && raw is byte[] bytes && bytes.Length > 0)
        {
            try
            {
                value = JsonSerializer.Deserialize<T>(bytes, JsonDefaults.Protocol);
                return value is not null;
            }
            catch (JsonException)
            {
                return false;
            }
        }

        return true;
    }
}
