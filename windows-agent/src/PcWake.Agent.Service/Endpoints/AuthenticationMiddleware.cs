using PcWake.Agent.Core.Auth;
using PcWake.Agent.Core.Protocol;

namespace PcWake.Agent.Service.Endpoints;

internal static class AuthenticationMiddleware
{
    public static IApplicationBuilder UseAgentAuthentication(this IApplicationBuilder app)
    {
        return app.Use(async (context, next) =>
        {
            var request = context.Request;
            var isApiV1 = request.Path.StartsWithSegments("/api/v1");
            var isPair = string.Equals(request.Path.Value, "/api/v1/pair", StringComparison.OrdinalIgnoreCase);

            if (!isApiV1 || isPair)
            {
                await next(context).ConfigureAwait(false);
                return;
            }

            byte[] body;
            try
            {
                body = await HttpHelpers.ReadBodyAsync(
                    request,
                    ProtocolConstants.MaxBodyBytes,
                    context.RequestAborted).ConfigureAwait(false);
            }
            catch (RequestBodyTooLargeException)
            {
                await HttpHelpers.WriteErrorAsync(
                    context.Response,
                    ErrorCodes.InvalidRequest,
                    StatusCodes.Status400BadRequest,
                    context.RequestAborted).ConfigureAwait(false);
                return;
            }

            var authenticator = context.RequestServices.GetRequiredService<RequestAuthenticator>();
            var authRequest = new AuthRequest(
                request.Method.ToUpperInvariant(),
                HttpHelpers.GetRawTarget(context),
                HttpHelpers.GetSingleHeader(request, HeaderNames.Device),
                HttpHelpers.GetSingleHeader(request, HeaderNames.Timestamp),
                HttpHelpers.GetSingleHeader(request, HeaderNames.Nonce),
                HttpHelpers.GetSingleHeader(request, HeaderNames.Signature),
                body);

            var result = await authenticator.AuthenticateAsync(
                authRequest,
                HttpHelpers.SourceIp(context),
                context.RequestAborted).ConfigureAwait(false);

            if (result.Outcome == AuthenticationOutcome.RateLimited)
            {
                await HttpHelpers.WriteErrorAsync(
                    context.Response,
                    ErrorCodes.RateLimited,
                    StatusCodes.Status429TooManyRequests,
                    context.RequestAborted).ConfigureAwait(false);
                return;
            }

            if (!result.Succeeded)
            {
                await HttpHelpers.WriteErrorAsync(
                    context.Response,
                    ErrorCodes.Unauthorized,
                    StatusCodes.Status401Unauthorized,
                    context.RequestAborted).ConfigureAwait(false);
                return;
            }

            context.Items[HttpItems.Body] = body;
            context.Items[HttpItems.Device] = result.Device;
            await next(context).ConfigureAwait(false);
        });
    }
}
