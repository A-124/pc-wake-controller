using System.Drawing.Drawing2D;

namespace PcWake.Agent.Tray;

internal static class TrayIconFactory
{
    public static Icon Create()
    {
        using var bitmap = new Bitmap(32, 32);
        using var graphics = Graphics.FromImage(bitmap);
        graphics.SmoothingMode = SmoothingMode.AntiAlias;
        graphics.Clear(Color.Transparent);

        using var background = new SolidBrush(Color.FromArgb(18, 38, 70));
        graphics.FillEllipse(background, 1, 1, 30, 30);

        using var pen = new Pen(Color.FromArgb(90, 200, 250), 3.2f)
        {
            StartCap = LineCap.Round,
            EndCap = LineCap.Round
        };
        graphics.DrawArc(pen, 9, 8, 15, 15, -55, 290);
        graphics.DrawLine(pen, 16.5f, 5.5f, 16.5f, 14.5f);

        var handle = bitmap.GetHicon();
        try
        {
            using var temporary = Icon.FromHandle(handle);
            return (Icon)temporary.Clone();
        }
        finally
        {
            NativeMethods.DestroyIcon(handle);
        }
    }
}
