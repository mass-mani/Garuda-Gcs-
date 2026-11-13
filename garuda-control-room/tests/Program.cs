using Garuda.ControlRoom.Models;
using Garuda.ControlRoom.Services;

if (args.Length == 2 && args[0].Equals("--rtsp", StringComparison.OrdinalIgnoreCase))
{
    using var host = new VlcHost();
    var result = await StreamTester.TestAsync(host.Engine, new CameraConfig { Name = "RTSP diagnostic", Url = args[1] });
    Console.WriteLine(result);
    return result.StartsWith("Connected", StringComparison.Ordinal) ? 0 : 1;
}

var assertions = 0;
void Check(bool condition, string description)
{
    assertions++;
    if (!condition) throw new InvalidOperationException($"FAIL: {description}");
    Console.WriteLine($"PASS: {description}");
}

var config = new AppConfig { Rows = 3, Columns = 2 };
config.Normalize();
Check(config.Cameras.Count == 6, "3 by 2 layout creates six inputs");
Check(config.Cameras[5].Name == "Camera 6", "generated cameras are named in order");

Check(StreamAddress.TryParse("rtsp://192.0.2.10:8555/live/drone", out var rtsp) && StreamAddress.IsRtsp(rtsp!), "RTSP viewer URL is supported");
Check(StreamAddress.TryParse("https://stream.example/live/camera/index.m3u8", out var hls) && !StreamAddress.IsRtsp(hls!), "HTTPS HLS playback URL is supported");
Check(StreamAddress.TryParse("https://stream.example/embed/camera", out _), "HTTPS hosted player URL is accepted");
Check(!StreamAddress.TryParse("rtmp://stream.example/live/key", out _), "RTMP publishing URL is rejected as a viewer URL");
Check(!StreamAddress.TryParse("file:///C:/video.mp4", out _), "local files are not accepted as remote stream URLs");
config.Rows = 2;
config.Columns = 2;
config.Normalize();
Check(config.Cameras.Count == 4, "shrinking layout removes extra inputs");
config.Cameras[0].Url = "rtsp://192.168.137.17:8555/live/drone";
config.Cameras[0].Username = "operator";
config.Cameras[0].Password = "secret-value";
var copy = config.Copy();
Check(copy.Cameras[0].Url == config.Cameras[0].Url && copy.Cameras[0].Password == "secret-value", "configuration copy preserves stream fields");
copy.Cameras[0].UseWebPlayer = true;
Check(copy.Cameras[0].Copy().UseWebPlayer, "hosted player mode survives configuration copying");
var sanitized = AppLogger.Redact("failed rtsp://operator:secret-value@192.168.1.10/live?password=secret-value");
Check(!sanitized.Contains("secret-value", StringComparison.Ordinal), "log sanitizer removes URL credentials and password parameters");
Check(AppLogger.Redact("camera offline").Contains("camera offline", StringComparison.Ordinal), "log sanitizer preserves harmless diagnostic text");
config.Rows = 0;
config.Columns = 9;
config.Normalize();
Check(config.Rows == 1 && config.Columns == 4 && config.Cameras.Count == 4, "layout dimensions clamp to the supported 1–4 range");

var temporaryFolder = Path.Combine(Path.GetTempPath(), $"GarudaControlRoomSmoke-{Guid.NewGuid():N}");
var settingsPath = Path.Combine(temporaryFolder, "settings.dat");
try
{
    var settings = new ConfigurationService(settingsPath);
    var saved = new AppConfig
    {
        Rows = 1,
        Columns = 1,
        Cameras = [new CameraConfig { Name = "Private camera", Url = "rtsp://192.0.2.10/live", Username = "operator", Password = "smoke-secret", UseWebPlayer = true }]
    };
    settings.Save(saved);
    var bytes = File.ReadAllBytes(settingsPath);
    Check(!System.Text.Encoding.UTF8.GetString(bytes).Contains("smoke-secret", StringComparison.Ordinal), "saved credentials are encrypted at rest");
    var restored = settings.Load();
    Check(restored.Cameras[0].Password == "smoke-secret" && restored.Cameras[0].Url == saved.Cameras[0].Url && restored.Cameras[0].UseWebPlayer, "DPAPI settings round-trip restores credentials and camera settings");
}
finally
{
    if (Directory.Exists(temporaryFolder)) Directory.Delete(temporaryFolder, recursive: true);
}
Console.WriteLine($"All {assertions} smoke checks passed.");
return 0;
