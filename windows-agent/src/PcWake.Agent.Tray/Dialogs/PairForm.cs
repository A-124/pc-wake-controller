using PcWake.Agent.Core.Protocol;
using QRCoder;

namespace PcWake.Agent.Tray.Dialogs;

internal sealed class PairForm : Form
{
    private readonly Label _stateLabel;
    private readonly Label _hostLabel;
    private readonly TextBox _codeBox;
    private readonly TextBox _fingerprintBox;
    private readonly TextBox _payloadBox;
    private readonly PictureBox _qrBox;
    private readonly Button _copyButton;
    private readonly Button _closeButton;
    private readonly System.Windows.Forms.Timer _pollTimer;
    private readonly CancellationTokenSource _lifetime = new();
    private Image? _qrImage;
    private string _lastState = string.Empty;
    private bool _starting;
    private bool _polling;

    public PairForm()
    {
        Text = "PC Wake Controller - pair a device";
        StartPosition = FormStartPosition.CenterScreen;
        MinimumSize = new Size(840, 590);
        Size = new Size(880, 620);
        Font = new Font("Segoe UI", 9f);

        var root = new TableLayoutPanel
        {
            Dock = DockStyle.Fill,
            ColumnCount = 2,
            RowCount = 2,
            Padding = new Padding(16)
        };
        root.ColumnStyles.Add(new ColumnStyle(SizeType.Absolute, 330));
        root.ColumnStyles.Add(new ColumnStyle(SizeType.Percent, 100));
        root.RowStyles.Add(new RowStyle(SizeType.Percent, 100));
        root.RowStyles.Add(new RowStyle(SizeType.Absolute, 48));

        var left = new Panel { Dock = DockStyle.Fill };
        var qrCaption = new Label
        {
            Text = "Scan this code with the PC Wake Controller Android app",
            Dock = DockStyle.Top,
            Height = 44,
            Font = new Font("Segoe UI", 9.5f, FontStyle.Bold)
        };
        _qrBox = new PictureBox
        {
            Location = new Point(0, 48),
            Size = new Size(310, 310),
            SizeMode = PictureBoxSizeMode.Zoom,
            BorderStyle = BorderStyle.FixedSingle,
            BackColor = Color.White
        };
        left.Controls.Add(_qrBox);
        left.Controls.Add(qrCaption);

        var right = new TableLayoutPanel
        {
            Dock = DockStyle.Fill,
            ColumnCount = 1,
            RowCount = 8,
            Padding = new Padding(10, 0, 0, 0)
        };
        right.RowStyles.Add(new RowStyle(SizeType.Absolute, 30));
        right.RowStyles.Add(new RowStyle(SizeType.Absolute, 66));
        right.RowStyles.Add(new RowStyle(SizeType.Absolute, 44));
        right.RowStyles.Add(new RowStyle(SizeType.Absolute, 38));
        right.RowStyles.Add(new RowStyle(SizeType.Absolute, 38));
        right.RowStyles.Add(new RowStyle(SizeType.Absolute, 38));
        right.RowStyles.Add(new RowStyle(SizeType.Absolute, 74));
        right.RowStyles.Add(new RowStyle(SizeType.Percent, 100));

        right.Controls.Add(new Label
        {
            Text = "One-time pairing code",
            Dock = DockStyle.Fill,
            ForeColor = Color.FromArgb(80, 80, 80)
        }, 0, 0);

        _codeBox = new TextBox
        {
            Dock = DockStyle.Fill,
            ReadOnly = true,
            BorderStyle = BorderStyle.FixedSingle,
            Font = new Font("Consolas", 27f, FontStyle.Bold),
            TextAlign = HorizontalAlignment.Center,
            BackColor = Color.White
        };
        right.Controls.Add(_codeBox, 0, 1);

        _stateLabel = new Label
        {
            Text = "Starting pairing window...",
            Dock = DockStyle.Fill,
            Font = new Font("Segoe UI", 10.5f, FontStyle.Bold),
            ForeColor = Color.FromArgb(35, 80, 150),
            TextAlign = ContentAlignment.MiddleLeft
        };
        right.Controls.Add(_stateLabel, 0, 2);

        _hostLabel = new Label { Dock = DockStyle.Fill, TextAlign = ContentAlignment.MiddleLeft };
        right.Controls.Add(_hostLabel, 0, 3);

        var pinCaption = new Label
        {
            Text = "Certificate SHA-256 pin (required by the app)",
            Dock = DockStyle.Fill,
            ForeColor = Color.FromArgb(80, 80, 80),
            TextAlign = ContentAlignment.MiddleLeft
        };
        right.Controls.Add(pinCaption, 0, 4);

        _fingerprintBox = new TextBox
        {
            Dock = DockStyle.Fill,
            ReadOnly = true,
            BorderStyle = BorderStyle.FixedSingle,
            Font = new Font("Consolas", 8.5f),
            Margin = new Padding(0, 2, 0, 2)
        };
        right.Controls.Add(_fingerprintBox, 0, 5);

        var payloadPanel = new Panel { Dock = DockStyle.Fill };
        var payloadCaption = new Label
        {
            Text = "Pairing payload (paste manually if QR scanning is not possible)",
            Dock = DockStyle.Top,
            Height = 20,
            ForeColor = Color.FromArgb(80, 80, 80)
        };
        _payloadBox = new TextBox
        {
            Dock = DockStyle.Fill,
            ReadOnly = true,
            Multiline = true,
            ScrollBars = ScrollBars.Vertical,
            Font = new Font("Consolas", 8.5f)
        };
        payloadPanel.Controls.Add(_payloadBox);
        payloadPanel.Controls.Add(payloadCaption);
        _copyButton = new Button
        {
            Text = "Copy payload",
            Dock = DockStyle.Bottom,
            Height = 28,
            Enabled = false
        };
        _copyButton.Click += (_, _) => CopyPayload();
        payloadPanel.Controls.Add(_copyButton);
        right.Controls.Add(payloadPanel, 0, 6);

        right.Controls.Add(new Label
        {
            Text = "The code is single-use and expires after 5 minutes or after 5 failed attempts.",
            Dock = DockStyle.Fill,
            ForeColor = Color.FromArgb(90, 90, 90),
            TextAlign = ContentAlignment.TopLeft
        }, 0, 7);

        root.Controls.Add(left, 0, 0);
        root.Controls.Add(right, 1, 0);

        var buttons = new FlowLayoutPanel
        {
            Dock = DockStyle.Fill,
            FlowDirection = FlowDirection.RightToLeft,
            WrapContents = false,
            Padding = new Padding(0, 8, 0, 0)
        };
        _closeButton = new Button { Text = "Close", Size = new Size(90, 30) };
        _closeButton.Click += (_, _) => Close();
        buttons.Controls.Add(_closeButton);
        root.Controls.Add(buttons, 0, 1);
        root.SetColumnSpan(buttons, 2);

        Controls.Add(root);

        _pollTimer = new System.Windows.Forms.Timer { Interval = 1000 };
        _pollTimer.Tick += async (_, _) => await PollAsync().ConfigureAwait(true);
        Shown += async (_, _) =>
        {
            await StartPairingAsync().ConfigureAwait(true);
            _pollTimer.Start();
        };
        FormClosed += (_, _) =>
        {
            _pollTimer.Stop();
            _pollTimer.Dispose();
            _lifetime.Cancel();
            _lifetime.Dispose();
            _qrImage?.Dispose();
            _qrImage = null;
        };
    }

    private async Task StartPairingAsync()
    {
        if (_starting)
        {
            return;
        }

        _starting = true;
        try
        {
            var status = await PipeClient.AdminAsync(PipeCommands.PairStart, cancellationToken: _lifetime.Token)
                .ConfigureAwait(true);
            if (IsDisposed)
            {
                return;
            }

            if (status is null || !status.Ok || string.IsNullOrEmpty(status.Code) || string.IsNullOrEmpty(status.Payload))
            {
                _stateLabel.Text = "Pairing could not be started. Run the tray elevated and check that the agent service is running.";
                _stateLabel.ForeColor = Color.FromArgb(160, 30, 30);
                return;
            }

            _codeBox.Text = status.Code;
            _fingerprintBox.Text = status.Fingerprint;
            _hostLabel.Text = $"Address: {status.HostAddress}:{status.Port}   ({status.Name})";
            _payloadBox.Text = status.Payload;
            _copyButton.Enabled = true;
            RenderQr(status.Payload);
            ApplyPairingState(status);
        }
        catch (OperationCanceledException)
        {
        }
        finally
        {
            _starting = false;
        }
    }

    private async Task PollAsync()
    {
        if (_polling || IsDisposed)
        {
            return;
        }

        _polling = true;
        try
        {
            var status = await PipeClient.AdminAsync(PipeCommands.PairStatus, cancellationToken: _lifetime.Token)
                .ConfigureAwait(true);
            if (IsDisposed)
            {
                return;
            }

            if (status is null)
            {
                _stateLabel.Text = "Agent service unavailable.";
                _stateLabel.ForeColor = Color.FromArgb(160, 30, 30);
                return;
            }

            ApplyPairingState(status);
            if (_lastState is "paired" or "expired" or "locked" or "closed")
            {
                _pollTimer.Stop();
                _copyButton.Enabled = false;
            }
        }
        catch (OperationCanceledException)
        {
        }
        finally
        {
            _polling = false;
        }
    }

    private void ApplyPairingState(AgentStatusSnapshot status)
    {
        _lastState = status.PairingState;
        switch (status.PairingState)
        {
            case "open":
                _stateLabel.Text = $"Waiting for a phone... {status.PairingRemainingSeconds ?? 0}s remaining";
                _stateLabel.ForeColor = Color.FromArgb(35, 80, 150);
                break;
            case "paired":
                _stateLabel.Text = $"Paired with {status.PairedDeviceName ?? "device"}";
                _stateLabel.ForeColor = Color.FromArgb(20, 110, 50);
                _codeBox.Text = "PAIRED";
                break;
            case "expired":
                _stateLabel.Text = "The pairing code expired. Start pairing again from the tray.";
                _stateLabel.ForeColor = Color.FromArgb(160, 30, 30);
                break;
            case "locked":
                _stateLabel.Text = "Too many failed attempts. The code was invalidated.";
                _stateLabel.ForeColor = Color.FromArgb(160, 30, 30);
                break;
            default:
                _stateLabel.Text = "Pairing window closed.";
                _stateLabel.ForeColor = Color.FromArgb(100, 100, 100);
                break;
        }
    }

    private void RenderQr(string payload)
    {
        try
        {
            using var generator = new QRCodeGenerator();
            using var data = generator.CreateQrCode(payload, QRCodeGenerator.ECCLevel.M);
            var png = new PngByteQRCode(data).GetGraphic(6);
            using var stream = new MemoryStream(png);
            using var image = Image.FromStream(stream);
            var bitmap = new Bitmap(image);
            _qrImage?.Dispose();
            _qrImage = bitmap;
            _qrBox.Image = bitmap;
        }
        catch (Exception ex)
        {
            _stateLabel.Text = "QR generation failed: " + ex.Message;
            _stateLabel.ForeColor = Color.FromArgb(160, 30, 30);
        }
    }

    private void CopyPayload()
    {
        try
        {
            if (!string.IsNullOrEmpty(_payloadBox.Text))
            {
                Clipboard.SetText(_payloadBox.Text);
            }
        }
        catch (Exception ex)
        {
            MessageBox.Show(this, "Could not copy to the clipboard: " + ex.Message, "PC Wake Controller",
                MessageBoxButtons.OK, MessageBoxIcon.Warning);
        }
    }
}
