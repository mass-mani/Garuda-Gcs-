using Garuda.ControlRoom.Models;
using LibVLCSharp.Shared;

namespace Garuda.ControlRoom.Services;

public static class StreamTester
{
    public static async Task<string> TestAsync(LibVLC libVlc, CameraConfig camera, CancellationToken cancellationToken = default)
    {
        if (!StreamAddress.TryParse(camera.Url, out var uri))
            return $"Enter a valid {StreamAddress.ExpectedSchemes} viewer URL.";

        using var player = new MediaPlayer(libVlc);
        player.Mute = true;
        using var media = new Media(libVlc, camera.Url.Trim(), FromType.FromLocation);
        if (StreamAddress.IsRtsp(uri!) && camera.UseTcp) media.AddOption(":rtsp-tcp");
        media.AddOption(":network-caching=250");
        if (StreamAddress.IsRtsp(uri!))
        {
            if (!string.IsNullOrWhiteSpace(camera.Username)) media.AddOption($":rtsp-user={camera.Username}");
            if (!string.IsNullOrEmpty(camera.Password)) media.AddOption($":rtsp-pwd={camera.Password}");
        }

        var connected = new TaskCompletionSource(TaskCreationOptions.RunContinuationsAsynchronously);
        var failed = new TaskCompletionSource<string>(TaskCreationOptions.RunContinuationsAsynchronously);
        player.Playing += (_, _) => connected.TrySetResult();
        player.EncounteredError += (_, _) => failed.TrySetResult("Connection failed. Check the URL, credentials, and network.");
        player.Stopped += (_, _) => failed.TrySetResult("Stream stopped before video arrived.");
        if (!player.Play(media)) return "VLC could not open the stream.";

        var deadline = DateTime.UtcNow.AddSeconds(10);
        while (DateTime.UtcNow < deadline)
        {
            if (failed.Task.IsCompleted) return await failed.Task;
            if (connected.Task.IsCompleted && media.Statistics.DecodedVideo > 0) return "Connected · video received";
            await Task.Delay(250, cancellationToken);
        }
        if (cancellationToken.IsCancellationRequested) return "Test canceled.";
        if (connected.Task.IsCompleted) return "Connected, but no video frames arrived.";
        return "Timed out. Check that the stream URL and network are reachable.";
    }
}
