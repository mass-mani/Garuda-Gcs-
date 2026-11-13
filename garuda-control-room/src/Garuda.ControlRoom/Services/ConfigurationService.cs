using System.Security.Cryptography;
using System.Text.Json;
using System.IO;
using Garuda.ControlRoom.Models;

namespace Garuda.ControlRoom.Services;

public sealed class ConfigurationService
{
    private static readonly byte[] Entropy = "Garuda.ControlRoom.settings.v1"u8.ToArray();
    private static readonly JsonSerializerOptions JsonOptions = new() { WriteIndented = true };
    private readonly string _path;

    public ConfigurationService() : this(Path.Combine(
        Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData),
        "Garuda Control Room", "settings.dat")) { }

    public ConfigurationService(string path) => _path = path;

    public AppConfig Load()
    {
        try
        {
            if (!File.Exists(_path)) return DefaultConfig();
            var clear = ProtectedData.Unprotect(File.ReadAllBytes(_path), Entropy, DataProtectionScope.CurrentUser);
            var config = JsonSerializer.Deserialize<AppConfig>(clear, JsonOptions) ?? DefaultConfig();
            config.Normalize();
            return config;
        }
        catch (Exception ex)
        {
            App.Logger.Warning($"Could not load saved configuration ({ex.GetType().Name}); using defaults.");
            return DefaultConfig();
        }
    }

    public void Save(AppConfig config)
    {
        config = config.Copy();
        config.Normalize();
        var folder = Path.GetDirectoryName(_path)!;
        Directory.CreateDirectory(folder);
        var clear = JsonSerializer.SerializeToUtf8Bytes(config, JsonOptions);
        var protectedBytes = ProtectedData.Protect(clear, Entropy, DataProtectionScope.CurrentUser);
        var temp = _path + ".tmp";
        File.WriteAllBytes(temp, protectedBytes);
        File.Move(temp, _path, overwrite: true);
    }

    public static AppConfig DefaultConfig() => new();
}
