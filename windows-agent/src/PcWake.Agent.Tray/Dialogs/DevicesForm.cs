using PcWake.Agent.Core.Protocol;

namespace PcWake.Agent.Tray.Dialogs;

internal sealed class DevicesForm : Form
{
    private readonly ListView _deviceList;
    private readonly Button _refreshButton;
    private readonly Button _revokeButton;
    private readonly Button _closeButton;
    private readonly Label _statusLabel;
    private readonly CancellationTokenSource _lifetime = new();
    private bool _loading;

    public DevicesForm()
    {
        Text = "PC Wake Controller - paired devices";
        StartPosition = FormStartPosition.CenterScreen;
        MinimumSize = new Size(760, 480);
        Size = new Size(830, 530);
        Font = new Font("Segoe UI", 9f);

        var root = new TableLayoutPanel
        {
            Dock = DockStyle.Fill,
            ColumnCount = 1,
            RowCount = 3,
            Padding = new Padding(14)
        };
        root.RowStyles.Add(new RowStyle(SizeType.Percent, 100));
        root.RowStyles.Add(new RowStyle(SizeType.Absolute, 34));
        root.RowStyles.Add(new RowStyle(SizeType.Absolute, 48));

        _deviceList = new ListView
        {
            Dock = DockStyle.Fill,
            View = View.Details,
            FullRowSelect = true,
            GridLines = true,
            MultiSelect = false
        };
        _deviceList.Columns.Add("Name", 160);
        _deviceList.Columns.Add("Device ID", 290);
        _deviceList.Columns.Add("Model", 150);
        _deviceList.Columns.Add("Paired", 135);
        _deviceList.Columns.Add("Last seen", 135);
        _deviceList.Columns.Add("Status", 80);
        _deviceList.SelectedIndexChanged += (_, _) =>
        {
            var selected = _deviceList.SelectedItems.Count > 0 ? _deviceList.SelectedItems[0] : null;
            _revokeButton!.Enabled = selected?.Tag is DeviceSummaryDto { Revoked: false };
        };
        root.Controls.Add(_deviceList, 0, 0);

        _statusLabel = new Label
        {
            Dock = DockStyle.Fill,
            TextAlign = ContentAlignment.MiddleLeft,
            ForeColor = Color.FromArgb(80, 80, 80)
        };
        root.Controls.Add(_statusLabel, 0, 1);

        var buttons = new FlowLayoutPanel
        {
            Dock = DockStyle.Fill,
            FlowDirection = FlowDirection.RightToLeft,
            WrapContents = false,
            Padding = new Padding(0, 8, 0, 0)
        };
        _closeButton = new Button { Text = "Close", Size = new Size(90, 30) };
        _closeButton.Click += (_, _) => Close();
        _revokeButton = new Button
        {
            Text = "Revoke device",
            Size = new Size(120, 30),
            Enabled = false,
            ForeColor = Color.FromArgb(140, 20, 20)
        };
        _revokeButton.Click += async (_, _) => await RevokeSelectedAsync().ConfigureAwait(true);
        _refreshButton = new Button { Text = "Refresh", Size = new Size(90, 30) };
        _refreshButton.Click += async (_, _) => await LoadDevicesAsync().ConfigureAwait(true);
        buttons.Controls.Add(_closeButton);
        buttons.Controls.Add(_revokeButton);
        buttons.Controls.Add(_refreshButton);
        root.Controls.Add(buttons, 0, 2);

        Controls.Add(root);
        Shown += async (_, _) => await LoadDevicesAsync().ConfigureAwait(true);
        FormClosed += (_, _) =>
        {
            _lifetime.Cancel();
            _lifetime.Dispose();
        };
    }

    private async Task LoadDevicesAsync()
    {
        if (_loading)
        {
            return;
        }

        _loading = true;
        _refreshButton.Enabled = false;
        try
        {
            var status = await PipeClient.AdminAsync(PipeCommands.Devices, cancellationToken: _lifetime.Token)
                .ConfigureAwait(true);
            if (IsDisposed)
            {
                return;
            }

            if (status is null)
            {
                _statusLabel.Text = "Cannot reach PcWakeAgent.Admin. Start the tray elevated (Run as administrator).";
                _statusLabel.ForeColor = Color.FromArgb(160, 30, 30);
                return;
            }

            if (!status.Ok)
            {
                _statusLabel.Text = "Service error: " + (status.Error ?? "unknown");
                _statusLabel.ForeColor = Color.FromArgb(160, 30, 30);
                return;
            }

            _deviceList.BeginUpdate();
            try
            {
                _deviceList.Items.Clear();
                foreach (var device in status.Devices.OrderByDescending(d => d.PairedAt))
                {
                    var item = new ListViewItem(device.Name) { Tag = device };
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

            _statusLabel.ForeColor = Color.FromArgb(80, 80, 80);
            _statusLabel.Text = $"{status.Devices.Count(d => !d.Revoked)} active device(s). Revocation is permanent; the phone must pair again.";
        }
        catch (OperationCanceledException)
        {
        }
        finally
        {
            _loading = false;
            _refreshButton.Enabled = true;
        }
    }

    private async Task RevokeSelectedAsync()
    {
        if (_deviceList.SelectedItems.Count == 0 ||
            _deviceList.SelectedItems[0].Tag is not DeviceSummaryDto device)
        {
            return;
        }

        var confirm = MessageBox.Show(
            this,
            $"Revoke '{device.Name}'?\n\nThe phone will immediately receive 401 errors and must pair again.",
            "PC Wake Controller",
            MessageBoxButtons.YesNo,
            MessageBoxIcon.Warning,
            MessageBoxDefaultButton.Button2);
        if (confirm != DialogResult.Yes)
        {
            return;
        }

        try
        {
            var status = await PipeClient.AdminAsync(PipeCommands.Revoke, device.DeviceId, _lifetime.Token)
                .ConfigureAwait(true);
            if (status is null || !status.Ok)
            {
                MessageBox.Show(
                    this,
                    "Revocation failed: " + (status?.Error ?? "agent unreachable"),
                    "PC Wake Controller",
                    MessageBoxButtons.OK,
                    MessageBoxIcon.Error);
                return;
            }

            await LoadDevicesAsync().ConfigureAwait(true);
        }
        catch (OperationCanceledException)
        {
        }
    }

    private static string FormatUnix(long value) =>
        value <= 0 ? "never" : DateTimeOffset.FromUnixTimeSeconds(value).ToLocalTime().ToString("g");
}
