using System.Text;

namespace PcWake.Agent.Core.Config;

/// <summary>
/// Writes a temporary file in the target directory, flushes it to disk and then
/// renames it over the destination. A reader therefore sees either the old or
/// the new complete document.
/// </summary>
public static class AtomicFile
{
    public static async Task WriteAllBytesAsync(
        string path,
        ReadOnlyMemory<byte> contents,
        CancellationToken cancellationToken = default)
    {
        var directory = Path.GetDirectoryName(path)
            ?? throw new ArgumentException("Destination must have a directory.", nameof(path));
        Directory.CreateDirectory(directory);

        var temp = Path.Combine(directory, "." + Path.GetFileName(path) + "." + Guid.NewGuid().ToString("N") + ".tmp");
        try
        {
            await using (var stream = new FileStream(
                temp,
                FileMode.CreateNew,
                FileAccess.Write,
                FileShare.None,
                bufferSize: 4096,
                FileOptions.Asynchronous | FileOptions.WriteThrough))
            {
                await stream.WriteAsync(contents, cancellationToken).ConfigureAwait(false);
                await stream.FlushAsync(cancellationToken).ConfigureAwait(false);
                stream.Flush(flushToDisk: true);
            }

            File.Move(temp, path, overwrite: true);
        }
        catch
        {
            TryDelete(temp);
            throw;
        }
    }

    public static Task WriteAllTextAsync(
        string path,
        string contents,
        CancellationToken cancellationToken = default)
    {
        return WriteAllBytesAsync(path, Encoding.UTF8.GetBytes(contents), cancellationToken);
    }

    public static void WriteAllText(string path, string contents)
    {
        WriteAllBytes(path, Encoding.UTF8.GetBytes(contents));
    }

    public static void WriteAllBytes(string path, ReadOnlyMemory<byte> contents)
    {
        var directory = Path.GetDirectoryName(path)
            ?? throw new ArgumentException("Destination must have a directory.", nameof(path));
        Directory.CreateDirectory(directory);

        var temp = Path.Combine(directory, "." + Path.GetFileName(path) + "." + Guid.NewGuid().ToString("N") + ".tmp");
        try
        {
            using (var stream = new FileStream(
                temp,
                FileMode.CreateNew,
                FileAccess.Write,
                FileShare.None,
                bufferSize: 4096,
                FileOptions.WriteThrough))
            {
                stream.Write(contents.Span);
                stream.Flush(flushToDisk: true);
            }

            File.Move(temp, path, overwrite: true);
        }
        catch
        {
            TryDelete(temp);
            throw;
        }
    }

    private static void TryDelete(string path)
    {
        try
        {
            if (File.Exists(path))
            {
                File.Delete(path);
            }
        }
        catch (IOException)
        {
        }
        catch (UnauthorizedAccessException)
        {
        }
    }
}
