using System.ComponentModel;
using System.Diagnostics;
using PcWake.Agent.Core.Protocol;
using PcWake.Agent.Tray.Dialogs;

namespace PcWake.Agent.Tray;

internal sealed class TrayApplicationContext : ApplicationContext
{
    private readonly NotifyIcon _notifyIcon;
    private readonly Icon _ownedIcon;
    private readonly System.Windows.Forms.Timer _pollTimer;
    private readonly CancellationTokenSource _lifetime = new();
    private StatusForm? _statusForm;
    private PendingCommandForm? _pendingForm;
    private string? _notifiedCommandId;
    private bool _polling;
    private bool _disposed;

    public TrayApplicationContext()
    {
        _ownedIcon = TrayIconFactory.Create();
        _notifyIcon = new NotifyIcon
        {
            Icon = _ownedIcon,
            Text = "PC Wake Controller Agent",
            Visible = true,
            ContextMenuStrip = BuildMenu()
        };
        _notifyIcon.BalloonTipClicked += (_, _) => ShowStatusWindow();

        _pollTimer = new System.Windows.Forms.Timer { Interval = 2000 };
        _pollTimer.Tick += async (_, _) => await PollAsync().ConfigureAwait(true);
        _pollTimer.Start();

        _ = PollAsync();
        Application.ApplicationExit += (_, _) => Cleanup();
    }

    private ContextMenuStrip BuildMenu()
    {
        var menu = new ContextMenuStrip();
        menu.Items.Add("Status", null, (_, _) => ShowStatusWindow());
        menu.Items.Add("Pair device", null, (_, _) => RunAdministrativeMode(
            elevatedArgument: "--pair",
            inProcess: () => new PairForm()));
        menu.Items.Add("Paired devices", null, (_, _) => RunAdministrativeMode(
            elevatedArgument: "--devices",
            inProcess: () => new DevicesForm()));
        menu.Items.Add(new ToolStripSeparator());
        menu.Items.Add("Open log folder", null, (_, _) => OpenLogFolder());
        menu.Items.Add(new ToolStripSeparator());
        menu.Items.Add("Exit", null, (_, _) =>
        {
            Cleanup();
            ExitThread();
        });
        return menu;
    }

    private void RunAdministrativeMode(string elevatedArgument, Func<Form> inProcess)
    {
        if (Program.IsElevated)
        {
            using var form = inProcess();
            form.ShowDialog();
            return;
        }

        try
        {
            var startInfo = new ProcessStartInfo
            {
                FileName = Application.ExecutablePath,
                Arguments = elevatedArgument,
                UseShellExecute = true,
                Verb = "runas"
            };
            Process.Start(startInfo);
        }
        catch (Win32Exception ex) when (ex.NativeErrorCode == 1223)
        {
            // User declined UAC. Nothing to do.
        }
        catch (Exception ex)
        {
            MessageBox.Show(
                "The administrator prompt could not be started: " + ex.Message,
                "PC Wake Controller",
                MessageBoxButtons.OK,
                MessageBoxIcon.Warning);
        }
    }

    private void ShowStatusWindow()
    {
        if (_statusForm is { IsDisposed: false })
        {
            _statusForm.Show();
            _statusForm.Activate();
            return;
        }

        _statusForm = new StatusForm();
        _statusForm.FormClosed += (_, _) => _statusForm = null;
        _statusForm.Show();
    }

    private async Task PollAsync()
    {
        if (_polling || _disposed)
        {
            return;
        }

        _polling = true;
        try
        {
            var status = await PipeClient.GetStatusAsync(_lifetime.Token).ConfigureAwait(true);
            if (_disposed)
            {
                return;
            }

            if (status is null)
            {
                _notifyIcon.Text = "PC Wake Controller Agent (service unavailable)";
                return;
            }

            var pending = status.PendingCommand;
            if (pending is not null && string.Equals(pending.Status, "pending", StringComparison.Ordinal))
            {
                _notifyIcon.Text = $"PC Wake Controller Agent - {pending.Action} pending";
                if (!string.Equals(_notifiedCommandId, pending.Id, StringComparison.Ordinal))
                {
                    _notifiedCommandId = pending.Id;
                    ShowPendingNotification(pending);
                }
                else if (_pendingForm is { IsDisposed: false })
                {
                    _pendingForm.UpdateCommand(pending);
                }
            }
            else
            {
                _notifyIcon.Text = "PC Wake Controller Agent - running";
                ClosePendingForm();
            }
        }
        catch (Exception ex) when (ex is IOException or TimeoutException or OperationCanceledException)
        {
            if (!_disposed)
            {
                _notifyIcon.Text = "PC Wake Controller Agent (service unavailable)";
            }
        }
        finally
        {
            _polling = false;
        }
    }

    private void ShowPendingNotification(CommandDto command)
    {
        var title = command.Action switch
        {
            "shutdown" => "Remote shutdown pending",
            "restart" => "Remote restart pending",
            "sleep" => "Remote sleep pending",
            _ => "Remote power command pending"
        };
        var text = command.Action == "sleep"
            ? "The PC will sleep in a few seconds. Sleep cannot be cancelled once suspended."
            : "A remote power command is pending. Open the notification to cancel it.";
        _notifyIcon.ShowBalloonTip(10_000, title, text, command.Action == "sleep" ? ToolTipIcon.Info : ToolTipIcon.Warning);

        if (_pendingForm is { IsDisposed: false })
        {
            _pendingForm.UpdateCommand(command);
            _pendingForm.Show();
            _pendingForm.BringToFront();
            return;
        }

        _pendingForm = new PendingCommandForm(command);
        _pendingForm.FormClosed += (_, _) => _pendingForm = null;
        _pendingForm.Show();
    }

    private void ClosePendingForm()
    {
        if (_pendingForm is { IsDisposed: false })
        {
            _pendingForm.Close();
        }

        _pendingForm = null;
    }

    private void OpenLogFolder()
    {
        var directory = Path.Combine(
            Environment.GetFolderPath(Environment.SpecialFolder.CommonApplicationData),
            "PcWakeAgent",
            "logs");
        try
        {
            Directory.CreateDirectory(directory);
            Process.Start(new ProcessStartInfo
            {
                FileName = "explorer.exe",
                Arguments = "\"" + directory + "\"",
                UseShellExecute = true
            });
        }
        catch (Exception ex)
        {
            MessageBox.Show(
                "Could not open the log folder:\n" + directory + "\n\n" + ex.Message +
                "\n\nAudit logs are restricted to SYSTEM and Administrators.",
                "PC Wake Controller",
                MessageBoxButtons.OK,
                MessageBoxIcon.Warning);
        }
    }

    private void Cleanup()
    {
        if (_disposed)
        {
            return;
        }

        _disposed = true;
        _pollTimer.Stop();
        _pollTimer.Dispose();
        _lifetime.Cancel();
        _notifyIcon.Visible = false;
        _notifyIcon.Dispose();
        _ownedIcon.Dispose();
        ClosePendingForm();
        if (_statusForm is { IsDisposed: false })
        {
            _statusForm.Close();
        }

        _lifetime.Dispose();
    }

    protected override void Dispose(bool disposing)
    {
        if (disposing)
        {
            Cleanup();
        }

        base.Dispose(disposing);
    }
}
