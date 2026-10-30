using PcWake.Agent.Core.Protocol;

namespace PcWake.Agent.Tray.Dialogs;

internal sealed class StatusForm : Form
{
    private readonly Label _serviceLabel;
    private readonly Label _endpointLabel;
    private readonly TextBox _fingerprintBox;
    private readonly Label _pairingLabel;
    private readonly Label _discoveryLabel;
    private readonly Label _pendingLabel;
    private readonly Button _cancelButton;
    private readonly ListView _deviceList;
    private readonly Button _refreshButton;
    private readonly Button _closeButton;
    private readonly System.Windows.Forms.Timer _timer;
    private readonly CancellationTokenSource _lifetime = new();
    private bool _loading;
    private string _deviceSignature = string.Empty;

    public StatusForm()
    {
        Text = "PC Wake Controller - agent status";
        StartPosition = FormStartPosition.CenterScreen;
        MinimumSize = new Size(760, 600);
        Size = new Size(820, 650);
        Font = new Font("Segoe UI", 9f);

        var root = new TableLayoutPanel
        {
            Dock = DockStyle.Fill,
            ColumnCount = 1,
            RowCount = 5,
            Padding = new Padding(14)
        };
        root.RowStyles.Add(new RowStyle(SizeType.Absolute, 46));
        root.RowStyles.Add(new RowStyle(SizeType.Absolute, 168));
        root.RowStyles.Add(new RowStyle(SizeType.Absolute, 102));
        root.RowStyles.Add(new RowStyle(SizeType.Percent, 100));
        root.RowStyles.Add(new RowStyle(SizeType.Absolute, 46));

        _serviceLabel = new Label
        {
            Dock = DockStyle.Fill,
            Font = new Font("Segoe UI", 13f, FontStyle.Bold),
            Text = "Checking agent status...",
            TextAlign = ContentAlignment.MiddleLeft
        };
        root.Controls.Add(_serviceLabel, 0, 0);

        var info = new TableLayoutPanel
        {
            Dock = DockStyle.Fill,
            ColumnCount = 2,
            RowCount = 4,
            Padding = new Padding(0, 4, 0, 4)
        };
        info.ColumnStyles.Add(new ColumnStyle(SizeType.Absolute, 112));
        info.ColumnStyles.Add(new ColumnStyle(SizeType.Percent, 100));
        for (var i = 0; i < 4; i++)
        {
            info.RowStyles.Add(new RowStyle(SizeType.Absolute, 34));
        }

        info.Controls.Add(MakeCaption("Endpoint"), 0, 0);
        _endpointLabel = new Label { Dock = DockStyle.Fill, TextAlign = ContentAlignment.MiddleLeft };
        info.Controls.Add(_endpointLabel, 1, 0);

        info.Controls.Add(MakeCaption("Cert SHA-256 pin"), 0, 1);
        _fingerprintBox = new TextBox
        {
            Dock = DockStyle.Fill,
            ReadOnly = true,
            BorderStyle = BorderStyle.FixedSingle,
            Font = new Font("Consolas", 9f),
            Margin = new Padding(0, 4, 0, 4)
        };
        info.Controls.Add(_fingerprintBox, 1, 1);

        info.Controls.Add(MakeCaption("Pairing"), 0, 2);
        _pairingLabel = new Label { Dock = DockStyle.Fill, TextAlign = ContentAlignment.MiddleLeft };
        info.Controls.Add(_pairingLabel, 1, 2);

        info.Controls.Add(MakeCaption("Discovery"), 0, 3);
        _discoveryLabel = new Label { Dock = DockStyle.Fill, TextAlign = ContentAlignment.MiddleLeft };
        info.Controls.Add(_discoveryLabel, 1, 3);

        root.Controls.Add(info, 0, 1);

        var pendingGroup = new GroupBox { Text = "Pending command", Dock = DockStyle.Fill };
        _pendingLabel = new Label
        {
            Location = new Point(14, 26),
            Size = new Size(610, 60),
            Anchor = AnchorStyles.Top | AnchorStyles.Left | AnchorStyles.Right
        };
        _cancelButton = new Button
        {
            Text = "Cancel",
            Size = new Size(125, 32),
            Location = new Point(640, 24),
            Anchor = AnchorStyles.Top | AnchorStyles.Right,
            Enabled = false
        };
        _cancelButton.Click += async (_, _) => await CancelPendingAsync().ConfigureAwait(true);
        pendingGroup.Controls.Add(_pendingLabel);
        pendingGroup.Controls.Add(_cancelButton);
        root.Controls.Add(pendingGroup, 0, 2);

        var devicesGroup = new GroupBox { Text = "Paired devices", Dock = DockStyle.Fill };
        _deviceList = new ListView
        {
            Dock = DockStyle.Fill,
            View = View.Details,
            FullRowSelect = true,
            GridLines = true,
            MultiSelect = false
        };
        _deviceList.Columns.Add("Name", 170);
        _deviceList.Columns.Add("Device ID", 280);
        _deviceList.Columns.Add("Model", 150);
        _deviceList.Columns.Add("Paired", 130);
        _deviceList.Columns.Add("Last seen", 130);
        _deviceList.Columns.Add("Status", 80);
        devicesGroup.Controls.Add(_deviceList);
        root.Controls.Add(devicesGroup, 0, 3);

        var buttons = new FlowLayoutPanel
        {
            Dock = DockStyle.Fill,
            FlowDirection = FlowDirection.RightToLeft,
            WrapContents = false,
            Padding = new Padding(0, 8, 0, 0)
        };
        _closeButton = new Button { Text = "Close", Size = new Size(90, 30) };
        _closeButton.Click += (_, _) => Close();
        _refreshButton = new Button { Text = "Refresh", Size = new Size(90, 30) };
        _refreshButton.Click += async (_, _) => await LoadStatusAsync().ConfigureAwait(true);
        buttons.Controls.Add(_closeButton);
        buttons.Controls.Add(_refreshButton);
        root.Controls.Add(buttons, 0, 4);

        Controls.Add(root);

        _timer = new System.Windows.Forms.Timer { Interval = 2000 };
        _timer.Tick += async (_, _) => await LoadStatusAsync().ConfigureAwait(true);
        Shown += async (_, _) =>
        {
            _timer.Start();
            await LoadStatusAsync().ConfigureAwait(true);
        };
        FormClosed += (_, _) =>
        {
            _timer.Stop();
            _timer.Dispose();
            _lifetime.Cancel();
            _lifetime.Dispose();
        };
    }

    private static Label MakeCaption(string text) => new()
    {
        Text = text,
        Dock = DockStyle.Fill,
        TextAlign = ContentAlignment.MiddleLeft,
        ForeColor = Color.FromArgb(80, 80, 80)
    };

    private async Task LoadStatusAsync()
    {
        if (_loading || IsDisposed)
        {
            return;
        }

        _loading = true;
        try
        {
            var status = await PipeClient.GetStatusAsync(_lifetime.Token).ConfigureAwait(true);
            if (IsDisposed)
            {
                return;
            }

            if (status is null)
            {
                _serviceLabel.Text = "Agent service unavailable";
                _serviceLabel.ForeColor = Color.FromArgb(160, 30, 30);
                _endpointLabel.Text = string.Empty;
                _fingerprintBox.Text = string.Empty;
                _pairingLabel.Text = string.Empty;
                _discoveryLabel.Text = string.Empty;
                _pendingLabel.Text = "The service is not running, or the status pipe is not reachable.";
                _cancelButton.Enabled = false;
                return;
            }

            _serviceLabel.Text = $"PcWake Agent {status.Version} - running (PID {status.ProcessId})";
            _serviceLabel.ForeColor = Color.FromArgb(20, 110, 50);
            _endpointLabel.Text = $"{status.Name} ({status.HostName})   https://{status.HostAddress}:{status.Port}";
            _fingerprintBox.Text = status.Fingerprint;
            _pairingLabel.Text = FormatPairing(status);
            _discoveryLabel.Text = status.DiscoveryEnabled
                ? $"Enabled on UDP {status.DiscoveryPort}"
                : "Disabled";
            UpdatePending(status);
            UpdateDevices(status.Devices);
        }
        catch (OperationCanceledException)
        {
        }
        finally
        {
            _loading = false;
        }
    }

    private void UpdatePending(AgentStatusSnapshot status)
    {
        var pending = status.PendingCommand;
        if (pending is null || !string.Equals(pending.Status, "pending", StringComparison.Ordinal))
        {
            _pendingLabel.Text = status.LastCommand is null
                ? "No pending command."
                : $"No pending command. Last: {Describe(status.LastCommand)}";
            _cancelButton.Enabled = false;
            return;
        }

        var remaining = Math.Max(0, pending.ExecuteAt - DateTimeOffset.UtcNow.ToUnixTimeSeconds());
        var canCancel = !string.Equals(pending.Action, "sleep", StringComparison.OrdinalIgnoreCase);
        _pendingLabel.Text =
            $"Remote {pending.Action} pending - executes in about {remaining} second{(remaining == 1 ? string.Empty : "s")}." +
            (canCancel
                ? " You can cancel it below."
                : " Sleep cannot be cancelled once it is pending.");
        _cancelButton.Enabled = canCancel;
        _cancelButton.Text = string.Equals(pending.Action, "restart", StringComparison.OrdinalIgnoreCase)
            ? "Cancel restart"
            : "Cancel shutdown";
    }

    private void UpdateDevices(List<DeviceSummaryDto> devices)
    {
        var signature = string.Join(
            "|",
            devices.Select(d => $"{d.DeviceId}:{d.Revoked}:{d.LastSeenAt}:{d.PairedAt}:{d.Name}"));
        if (string.Equals(signature, _deviceSignature, StringComparison.Ordinal))
        {
            return;
        }

        _deviceSignature = signature;
        _deviceList.BeginUpdate();
        try
        {
            _deviceList.Items.Clear();
            foreach (var device in devices.OrderByDescending(d => d.PairedAt))
            {
                var item = new ListViewItem(device.Name);
                item.SubItems.Add(device.DeviceId);
                item.SubItems.Add(device.Model);
                item.SubItems.Add(FormatUnix(device.PairedAt));
                item.SubItems.Add(FormatUnix(device.LastSeenAt));
                item.SubItems.Add(device.Revoked ? "Revoked" : "Active");
                if (device.Revoked)
                {
                    item.ForeColor = Color.Gray;
                }

                _deviceList.Items.Add(item);
            }
        }
        finally
        {
            _deviceList.EndUpdate();
        }
    }

    private async Task CancelPendingAsync()
    {
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
                    "The agent did not respond to the cancel request.",
                    "PC Wake Controller",
                    MessageBoxButtons.OK,
                    MessageBoxIcon.Warning);
            }
            else if (!result.Cancelled)
            {
                MessageBox.Show(
                    this,
                    result.Message == "sleep_cannot_be_cancelled"
                        ? "Sleep cannot be cancelled once it is pending."
                        : "There is no pending command to cancel.",
                    "PC Wake Controller",
                    MessageBoxButtons.OK,
                    MessageBoxIcon.Information);
            }

            await LoadStatusAsync().ConfigureAwait(true);
        }
        finally
        {
            if (!IsDisposed)
            {
                _cancelButton.Enabled = false;
            }
        }
    }

    private static string FormatPairing(AgentStatusSnapshot status)
    {
        if (status.PairingOpen)
        {
            return $"Window open - {status.PairingRemainingSeconds ?? 0} seconds remaining";
        }

        return status.PairingState switch
        {
            "paired" => $"Last pairing succeeded with {status.PairedDeviceName ?? "a device"}",
            "expired" => "Last pairing window expired",
            "locked" => "Last pairing window locked after too many attempts",
            _ => "Closed"
        };
    }

    private static string Describe(CommandDto command) =>
        $"{command.Action} {command.Status} at {FormatUnix(command.ExecuteAt)}";

    private static string FormatUnix(long value) =>
        value <= 0 ? "never" : DateTimeOffset.FromUnixTimeSeconds(value).ToLocalTime().ToString("g");
}
