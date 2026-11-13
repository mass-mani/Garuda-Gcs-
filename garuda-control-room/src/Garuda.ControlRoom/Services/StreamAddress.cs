namespace Garuda.ControlRoom.Services;

public static class StreamAddress
{
    private static readonly HashSet<string> SupportedSchemes = new(StringComparer.OrdinalIgnoreCase)
    {
        "rtsp",
        "rtsps",
        "http",
        "https"
    };

    public static bool TryParse(string? value, out Uri? uri)
    {
        uri = null;
        if (!Uri.TryCreate(value?.Trim(), UriKind.Absolute, out var parsed) ||
            string.IsNullOrWhiteSpace(parsed.Host) ||
            !SupportedSchemes.Contains(parsed.Scheme))
            return false;

        uri = parsed;
        return true;
    }

    public static bool IsRtsp(Uri uri) =>
        uri.Scheme.Equals("rtsp", StringComparison.OrdinalIgnoreCase) ||
        uri.Scheme.Equals("rtsps", StringComparison.OrdinalIgnoreCase);

    public static string ExpectedSchemes => "rtsp://, rtsps://, http://, or https://";
}
