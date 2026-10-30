using PcWake.Agent.Core.Protocol;

namespace PcWake.Agent.Tray.Dialogs;

internal sealed class PendingCommandForm : Form
{
    private readonly Label _titleLabel;
    private readonly Label _detailLabel;
    private readonly Label _countdownLabel;
    private readonly Button _cancelButton;
    private readonly Button _dismissButton;
    private readonly System.Windows.Forms.Timer _countdownTimer;
    private readonly CancellationTokenSource _lifetime = new();
    private CommandDto _command;
    private bool _cancelling;

    public PendingCommandForm(CommandDto command)
    {
        _command = command;

        Text = "PC Wake Controller - remote power command";
        FormBorderStyle = FormBorderStyle.FixedToolWindow;
        StartPosition = FormStartPosition.Manual;
        ShowInTaskbar = false;
        TopMost = true;
        MinimizeBox = false;
        MaximizeBox = false;
        ClientSize = new Size(470, 205);
        BackColor = Color.FromArgb(250, 250, 250);
        Font = new Font("Segoe UI", 9f);

        _titleLabel = new Label
        {
            AutoSize = false,
            Location = new Point(18, 16),
            Size = new Size(434, 28),
            Font = new Font("Segoe UI", 12.5f, FontStyle.Bold),
            ForeColor = Color.FromArgb(160, 30, 30)
        };

        _detailLabel = new Label
        {
            AutoSize = false,
            Location = new Point(18, 52),
            Size = new Size(434, 70)
        };

        _countdownLabel = new Label
        {
            AutoSize = false,
            Location = new Point(18, 124),
            Size = new Size(434, 24),
            Font = new Font("Segoe UI", 9.5f, FontStyle.Bold)
        };

        _cancelButton = new Button
        {
            Text = "Cancel command",
            Location = new Point(236, 160),
            Size = new Size(130, 30)
        };
        _cancelButton.Click += async (_, _) => await CancelAsync().ConfigureAwait(true);

        _dismissButton = new Button
        {
            Text = "Dismiss",
            Location = new Point(374, 160),
            Size = new Size(78, 30),
            DialogResult = DialogResult.OK
        };
        _dismissButton.Click += (_, _) => Close();

        Controls.AddRange([_titleLabel, _detailLabel, _countdownLabel, _cancelButton, _dismissButton]);

        _countdownTimer = new System.Windows.Forms.Timer { Interval = 1000 };
        _countdownTimer.Tick += (_, _) => UpdateCountdown();
        _countdownTimer.Start();

        Shown += (_, _) => PositionBottomRight();
        FormClosed += (_, _) =>
        {
            _countdownTimer.Stop();
            _countdownTimer.Dispose();
            _lifetime.Cancel();
            _lifetime.Dispose();
        };

        UpdateCommand(command);
    }

    public void UpdateCommand(CommandDto command)
    {
        if (IsDisposed)
        {
            return;
        }

        if (InvokeRequired)
        {
            BeginInvoke(new Action(() => UpdateCommand(command)));
            return;
        }

        _command = command;
        var isSleep = string.Equals(command.Action, "sleep", StringComparison.OrdinalIgnoreCase);
        var isShutdown = string.Equals(command.Action, "shutdown", StringComparison.OrdinalIgnoreCase);
        var isRestart = string.Equals(command.Action, "restart", StringComparison.OrdinalIgnoreCase);

        _titleLabel.Text = isSleep
            ? "Remote sleep requested"
            : isRestart
                ? "Remote restart requested"
                : "Remote shutdown requested";

        _detailLabel.Text = isSleep
            ? "This computer will sleep in a few seconds. Sleep cannot be cancelled once the PC is suspended. Save your work now."
            : $"This computer will {(isRestart ? "restart" : "shut down")} automatically. Windows shows its own countdown. You can cancel the pending command below.";

        _cancelButton.Visible = !isSleep;
        _cancelButton.Text = isRestart ? "Cancel restart" : "Cancel shutdown";
        _countdownLabel.ForeColor = isSleep ? Color.FromArgb(120, 85, 0) : Color.FromArgb(160, 30, 30);
        UpdateCountdown();
    }

    private void UpdateCountdown()
    {
        if (IsDisposed || _command.Status != "pending")
        {
            return;
        }

        var remaining = _command.ExecuteAt - DateTimeOffset.UtcNow.ToUnixTimeSeconds();
        if (remaining < 0)
        {
            remaining = 0;
        }

        if (string.Equals(_command.Action, "sleep", StringComparison.OrdinalIgnoreCase))
        {
            _countdownLabel.Text = remaining > 0
                ? $"Sleep begins in {remaining} second{(remaining == 1 ? string.Empty : "s")}."
                : "The PC is about to sleep.";
            return;
        }

        _countdownLabel.Text = remaining > 0
            ? $"Command executes in {remaining} second{(remaining == 1 ? string.Empty : "s")}."
            : "The native shutdown countdown has started.";
    }

    private async Task CancelAsync()
    {
        if (_cancelling)
        {
            return;
        }

        _cancelling = true;
        _cancelButton.Enabled = false;
        try
        {
            var result = await PipeClient.CancelAsync(_lifetime.Token).ConfigureAwait(true);
            if (IsDisposed)
            {
                return;
            }

            if (result is null)
            {
                MessageBox.Show(
                    this,
                    "The agent did not respond. Use the Windows shutdown dialog to abort instead.",
                    "PC Wake Controller",
                    MessageBoxButtons.OK,
                    MessageBoxIcon.Warning);
            }
            else if (result.Cancelled)
            {
                _cancelButton.Visible = false;
                _countdownLabel.Text = "The power command was cancelled.";
                _countdownLabel.ForeColor = Color.FromArgb(20, 110, 50);
            }
            else
            {
                MessageBox.Show(
                    this,
                    result.Message == "sleep_cannot_be_cancelled"
                        ? "Sleep cannot be cancelled once it is pending."
                        : "There is no pending command to cancel. It may have already executed.",
                    "PC Wake Controller",
                    MessageBoxButtons.OK,
                    MessageBoxIcon.Information);
            }
        }
        catch (OperationCanceledException)
        {
        }
        finally
        {
            _cancelling = false;
            if (!IsDisposed)
            {
                _cancelButton.Enabled = true;
            }
        }
    }

    private void PositionBottomRight()
    {
        var screen = Screen.FromPoint(Cursor.Position);
        var area = screen.WorkingArea;
        Location = new Point(area.Right - Width - 18, area.Bottom - Height - 18);
    }
}
