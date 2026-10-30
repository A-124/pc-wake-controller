using System.Security.Principal;
using PcWake.Agent.Tray.Dialogs;

namespace PcWake.Agent.Tray;

internal static class Program
{
    [STAThread]
    private static void Main(string[] args)
    {
        ApplicationConfiguration.Initialize();

        if (args.Any(a => string.Equals(a, "--pair", StringComparison.OrdinalIgnoreCase)))
        {
            Application.Run(new PairForm());
            return;
        }

        if (args.Any(a => string.Equals(a, "--devices", StringComparison.OrdinalIgnoreCase)))
        {
            Application.Run(new DevicesForm());
            return;
        }

        using var mutex = new Mutex(initiallyOwned: true, @"Local\PcWakeAgent.Tray", out var createdNew);
        if (!createdNew)
        {
            MessageBox.Show(
                "PC Wake Controller Agent is already running in the notification area.",
                "PC Wake Controller",
                MessageBoxButtons.OK,
                MessageBoxIcon.Information);
            return;
        }

        Application.Run(new TrayApplicationContext());
    }

    public static bool IsElevated
    {
        get
        {
            using var identity = WindowsIdentity.GetCurrent();
            return new WindowsPrincipal(identity).IsInRole(WindowsBuiltInRole.Administrator);
        }
    }
}
