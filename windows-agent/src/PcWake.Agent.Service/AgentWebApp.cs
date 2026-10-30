using Microsoft.AspNetCore.Server.Kestrel.Core;
using Microsoft.Extensions.Hosting.WindowsServices;
using PcWake.Agent.Core.Abstractions;
using PcWake.Agent.Core.Audit;
using PcWake.Agent.Core.Auth;
using PcWake.Agent.Core.Config;
using PcWake.Agent.Core.Network;
using PcWake.Agent.Core.Pairing;
using PcWake.Agent.Core.Power;
using PcWake.Agent.Core.Protocol;
using PcWake.Agent.Core.Secrets;
using PcWake.Agent.Core.State;
using PcWake.Agent.Service.Endpoints;
using PcWake.Agent.Service.Ipc;

namespace PcWake.Agent.Service;

public static class AgentWebApp
{
    public static WebApplication Build(AgentServerOptions options)
    {
        var clock = options.Clock ?? SystemClock.Instance;
        var port = options.ListenPortOverride ?? options.Config.HttpsPort;
        var adapters = options.AdapterProvider ?? new NetworkAdapterProvider();
        var runtime = new AgentRuntimeInfo(
            options.Config,
            adapters.GetPrimaryIPv4(),
            port,
            CertificateFactory.Fingerprint(options.Certificate),
            clock);

        var builder = WebApplication.CreateBuilder(new WebApplicationOptions
        {
            ContentRootPath = AppContext.BaseDirectory,
            Args = []
        });

        if (options.UseWindowsService)
        {
            builder.Host.UseWindowsService(serviceOptions =>
            {
                serviceOptions.ServiceName = ProtocolConstants.WindowsServiceName;
            });
        }

        builder.Logging.AddFilter("Microsoft.AspNetCore", LogLevel.Warning);
        builder.Services.Configure<HostOptions>(hostOptions =>
        {
            hostOptions.ShutdownTimeout = TimeSpan.FromSeconds(15);
        });

        builder.WebHost.ConfigureKestrel(kestrel =>
        {
            kestrel.AddServerHeader = false;
            kestrel.Limits.MaxRequestBodySize = ProtocolConstants.MaxBodyBytes;
            kestrel.ListenAnyIP(port, listen =>
            {
                listen.UseHttps(options.Certificate);
            });
        });

        builder.Services.AddSingleton(options.Paths);
        builder.Services.AddSingleton(options.Config);
        builder.Services.AddSingleton<IClock>(clock);
        builder.Services.AddSingleton<INetworkAdapterProvider>(adapters);
        builder.Services.AddSingleton(runtime);
        builder.Services.AddSingleton<AuditLogger>(sp => new AuditLogger(
            options.Paths,
            options.RestrictDataAcl,
            logger: sp.GetRequiredService<ILogger<AuditLogger>>()));
        builder.Services.AddSingleton<IAuditLog>(sp => sp.GetRequiredService<AuditLogger>());
        builder.Services.AddSingleton<DeviceStore>(sp => new DeviceStore(
            options.Paths,
            clock,
            options.RestrictDataAcl,
            sp.GetRequiredService<ILogger<DeviceStore>>()));
        builder.Services.AddSingleton<IDeviceStore>(sp => sp.GetRequiredService<DeviceStore>());

        if (options.SecretStore is not null)
        {
            builder.Services.AddSingleton(options.SecretStore);
        }
        else
        {
            builder.Services.AddSingleton<IDeviceSecretStore>(sp => new DpapiDeviceSecretStore(
                options.Paths,
                options.RestrictDataAcl));
        }

        builder.Services.AddSingleton<NonceReplayCache>();
        builder.Services.AddSingleton<DeviceRateLimiter>();
        builder.Services.AddSingleton<AuthFailureRateLimiter>();
        builder.Services.AddSingleton<PairingRateLimiter>();
        builder.Services.AddSingleton<RequestAuthenticator>();
        builder.Services.AddSingleton<PairingManager>();
        builder.Services.AddSingleton<IPowerController>(
            options.PowerController ?? new WindowsPowerController(new WindowsProcessRunner()));
        builder.Services.AddSingleton<IPowerDelay>(options.PowerDelay ?? SystemPowerDelay.Instance);
        builder.Services.AddSingleton<PowerCommandService>();

        if (options.EnableDiscovery && options.Config.DiscoveryEnabled)
        {
            builder.Services.AddHostedService<DiscoveryResponder>();
        }

        if (options.EnablePipes)
        {
            builder.Services.AddHostedService<AgentPipeHost>();
        }

        var app = builder.Build();

        // Eagerly load persisted state and the secret vault so corrupt data fails
        // service startup rather than every request.
        _ = app.Services.GetRequiredService<DeviceStore>();
        _ = app.Services.GetRequiredService<IDeviceSecretStore>();

        var audit = app.Services.GetRequiredService<IAuditLog>();
        app.Lifetime.ApplicationStarted.Register(() =>
        {
            _ = audit.LogAsync(
                AuditEvents.ServiceStarted,
                new Dictionary<string, object?>
                {
                    ["version"] = ProtocolConstants.AgentVersion,
                    ["port"] = port,
                    ["pid"] = Environment.ProcessId
                });
        });
        app.Lifetime.ApplicationStopping.Register(() =>
        {
            _ = audit.LogAsync(
                AuditEvents.ServiceStopped,
                new Dictionary<string, object?>
                {
                    ["version"] = ProtocolConstants.AgentVersion,
                    ["pid"] = Environment.ProcessId
                });
        });

        app.UseExceptionHandler(errorApp =>
        {
            errorApp.Run(async context =>
            {
                var feature = context.Features.Get<Microsoft.AspNetCore.Diagnostics.IExceptionHandlerFeature>();
                if (feature?.Error is not null)
                {
                    context.RequestServices
                        .GetRequiredService<ILoggerFactory>()
                        .CreateLogger("PcWakeAgent")
                        .LogError(feature.Error, "Unhandled request failure.");
                }

                if (!context.Response.HasStarted)
                {
                    await HttpHelpers.WriteErrorAsync(
                        context.Response,
                        ErrorCodes.InternalError,
                        StatusCodes.Status500InternalServerError,
                        CancellationToken.None).ConfigureAwait(false);
                }
            });
        });

        app.Use(async (context, next) =>
        {
            context.Response.Headers.CacheControl = "no-store";
            context.Response.Headers.XContentTypeOptions = "nosniff";
            await next(context).ConfigureAwait(false);
        });

        app.UseAgentAuthentication();
        app.MapAgentEndpoints();

        return app;
    }
}
