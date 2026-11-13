using System.Text.RegularExpressions;
using System.IO;

namespace Garuda.ControlRoom.Services;

public sealed class AppLogger
{
    private static readonly Regex UserInfo = new(@"(?i)(rtsp(?:s)?://)[^/@\s]+@", RegexOptions.Compiled);
    private static readonly Regex PasswordParameter = new(@"(?i)(password|passwd|pwd)=([^&\s]+)", RegexOptions.Compiled);
    private readonly object _gate = new();
    private readonly string _path;

    public AppLogger()
    {
        var folder = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "Garuda Control Room", "Logs");
        Directory.CreateDirectory(folder);
        _path = Path.Combine(folder, $"control-room-{DateTime.Now:yyyy-MM-dd}.log");
    }

    public void Info(string message) => Write("INFO", message);
    public void Warning(string message) => Write("WARN", message);
    public void Error(string message) => Write("ERROR", message);

    public static string Redact(string message)
    {
        var safe = UserInfo.Replace(message, "$1[credentials]@");
        return PasswordParameter.Replace(safe, "$1=[redacted]");
    }

    private void Write(string level, string message)
    {
        try
        {
            var line = $"{DateTimeOffset.Now:O} [{level}] {Redact(message)}{Environment.NewLine}";
            lock (_gate) File.AppendAllText(_path, line);
        }
        catch
        {
            // Logging must never take down the video monitor.
        }
    }
}
