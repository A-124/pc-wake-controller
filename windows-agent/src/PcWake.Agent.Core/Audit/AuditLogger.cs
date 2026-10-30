using System.Text;
using System.Text.Json;
using Microsoft.Extensions.Logging;
using Microsoft.Extensions.Logging.Abstractions;
using PcWake.Agent.Core.Config;

namespace PcWake.Agent.Core.Audit;

public interface IAuditLog
{
    Task LogAsync(
        string eventName,
        IReadOnlyDictionary<string, object?>? fields = null,
        CancellationToken cancellationToken = default);
}

public sealed class NullAuditLog : IAuditLog
{
    public static readonly NullAuditLog Instance = new();

    public Task LogAsync(
        string eventName,
        IReadOnlyDictionary<string, object?>? fields = null,
        CancellationToken cancellationToken = default) => Task.CompletedTask;
}

/// <summary>
/// One-JSON-object-per-line audit log with size-based rotation. Values under keys
/// that look sensitive are redacted before serialization; callers must not pass
/// pairing codes or tokens in the first place.
/// </summary>
public sealed class AuditLogger : IAuditLog, IAsyncDisposable
{
    private static readonly string[] SensitiveFragments =
    [
        "token", "code", "signature", "secret", "password", "nonce", "authorization"
    ];

    private readonly string _directory;
    private readonly string _currentFile;
    private readonly long _maxBytes;
    private readonly int _maxFiles;
    private readonly SemaphoreSlim _gate = new(1, 1);
    private readonly ILogger _logger;
    private bool _disposed;

    public AuditLogger(
        AgentPaths paths,
        bool restrictAcl,
        long maxBytes = 2 * 1024 * 1024,
        int maxFiles = 5,
        ILogger<AuditLogger>? logger = null)
    {
        if (maxBytes <= 0)
        {
            throw new ArgumentOutOfRangeException(nameof(maxBytes));
        }

        if (maxFiles < 1)
        {
            throw new ArgumentOutOfRangeException(nameof(maxFiles));
        }

        _directory = paths.LogDirectory;
        _currentFile = Path.Combine(_directory, "audit.jsonl");
        _maxBytes = maxBytes;
        _maxFiles = maxFiles;
        _logger = logger ?? NullLogger<AuditLogger>.Instance;

        Directory.CreateDirectory(_directory);
        if (restrictAcl)
        {
            FileAcl.RestrictDirectory(paths.RootDirectory);
            FileAcl.RestrictDirectory(_directory);
        }
    }

    public string CurrentFile => _currentFile;

    public async Task LogAsync(
        string eventName,
        IReadOnlyDictionary<string, object?>? fields = null,
        CancellationToken cancellationToken = default)
    {
        if (string.IsNullOrWhiteSpace(eventName))
        {
            throw new ArgumentException("Audit event name is required.", nameof(eventName));
        }

        var record = new Dictionary<string, object?>
        {
            ["ts"] = DateTimeOffset.UtcNow.ToUnixTimeSeconds(),
            ["event"] = SanitizeValue(eventName, 80)
        };

        if (fields is not null)
        {
            foreach (var (key, value) in fields)
            {
                if (IsSensitiveKey(key))
                {
                    record[SanitizeKey(key)] = "[redacted]";
                    continue;
                }

                record[SanitizeKey(key)] = SanitizeValue(value, 256);
            }
        }

        var line = JsonSerializer.Serialize(record) + "\n";
        var bytes = Encoding.UTF8.GetBytes(line);

        await _gate.WaitAsync(cancellationToken).ConfigureAwait(false);
        try
        {
            if (_disposed)
            {
                return;
            }

            Directory.CreateDirectory(_directory);
            RotateIfNeeded();
            await using var stream = new FileStream(
                _currentFile,
                FileMode.Append,
                FileAccess.Write,
                FileShare.Read,
                bufferSize: 4096,
                FileOptions.Asynchronous);
            await stream.WriteAsync(bytes, cancellationToken).ConfigureAwait(false);
            await stream.FlushAsync(cancellationToken).ConfigureAwait(false);
        }
        catch (Exception ex) when (ex is IOException or UnauthorizedAccessException)
        {
            _logger.LogError(ex, "Audit log write failed.");
        }
        finally
        {
            _gate.Release();
        }
    }

    private void RotateIfNeeded()
    {
        var info = new FileInfo(_currentFile);
        if (!info.Exists || info.Length < _maxBytes)
        {
            return;
        }

        var last = Path.Combine(_directory, $"audit.{_maxFiles - 1}.jsonl");
        if (File.Exists(last))
        {
            File.Delete(last);
        }

        for (var i = _maxFiles - 2; i >= 1; i--)
        {
            var source = Path.Combine(_directory, $"audit.{i}.jsonl");
            if (File.Exists(source))
            {
                File.Move(source, Path.Combine(_directory, $"audit.{i + 1}.jsonl"), overwrite: true);
            }
        }

        File.Move(_currentFile, Path.Combine(_directory, "audit.1.jsonl"), overwrite: true);
    }

    private static bool IsSensitiveKey(string key) =>
        SensitiveFragments.Any(fragment => key.Contains(fragment, StringComparison.OrdinalIgnoreCase));

    private static string SanitizeKey(string key)
    {
        var builder = new StringBuilder(key.Length);
        foreach (var c in key)
        {
            builder.Append(char.IsLetterOrDigit(c) || c is '_' or '-' ? c : '_');
        }

        return builder.Length == 0 ? "field" : builder.ToString();
    }

    private static object? SanitizeValue(object? value, int maxLength)
    {
        if (value is null)
        {
            return null;
        }

        var text = value switch
        {
            string s => s,
            IFormattable formattable => formattable.ToString(null, System.Globalization.CultureInfo.InvariantCulture),
            _ => value.ToString()
        } ?? string.Empty;

        var builder = new StringBuilder(Math.Min(text.Length, maxLength));
        foreach (var c in text)
        {
            if (char.IsControl(c) && c is not '\t')
            {
                continue;
            }

            builder.Append(c);
            if (builder.Length >= maxLength)
            {
                break;
            }
        }

        return builder.ToString();
    }

    public async ValueTask DisposeAsync()
    {
        if (_disposed)
        {
            return;
        }

        await _gate.WaitAsync().ConfigureAwait(false);
        try
        {
            _disposed = true;
        }
        finally
        {
            _gate.Release();
        }
    }
}
