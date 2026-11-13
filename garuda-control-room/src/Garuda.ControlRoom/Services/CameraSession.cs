using System.ComponentModel;
using System.IO;
using System.Runtime.CompilerServices;
using System.Windows;
using System.Windows.Media;
using Garuda.ControlRoom.Models;
using LibVLCSharp.Shared;

namespace Garuda.ControlRoom.Services;

public sealed class CameraSession : INotifyPropertyChanged, IAsyncDisposable
{
    private readonly LibVLC _libVlc;
    private readonly CameraConfig _config;
    private readonly CancellationTokenSource _stop = new();
    private Task? _runTask;
    private TaskCompletionSource<bool>? _attemptConnected;
    private TaskCompletionSource<string>? _attemptEnded;
    private Media? _activeMedia;
    private string _status = "DISCONNECTED";
    private string _detail = "NO SIGNAL";
    private Brush _statusBrush = new SolidColorBrush(Color.FromRgb(241, 93, 104));
    private bool _connected;
    private bool _disposed;

    public CameraSession(LibVLC libVlc, CameraConfig config)
    {
        _libVlc = libVlc;
        _config = config.Copy();
        Player = new LibVLCSharp.Shared.MediaPlayer(_libVlc);
        // Keep multi-camera monitoring quiet by default; users can enable one feed's audio in Settings.
        Player.Mute = !_config.AudioEnabled;
        Player.Playing += OnPlaying;
        Player.EncounteredError += OnError;
        Player.Stopped += OnStopped;
        if (!_config.Enabled)
            SetState("DISABLED", "Stream disabled in Settings", 113, 134, 154);
        else if (string.IsNullOrWhiteSpace(_config.Url))
            SetState("NOT CONFIGURED", "Add a viewer URL in Settings", 113, 134, 154);
    }

    public LibVLCSharp.Shared.MediaPlayer Player { get; }
    public string Name => _config.Name;
    public bool ShouldStart => _config.Enabled && !_config.UseWebPlayer && !string.IsNullOrWhiteSpace(_config.Url);
    public string Status { get => _status; private set => Set(ref _status, value); }
    public string Detail { get => _detail; private set => Set(ref _detail, value); }
    public Brush StatusBrush { get => _statusBrush; private set => Set(ref _statusBrush, value); }
    public bool IsConnected { get => _connected; private set => Set(ref _connected, value); }

    public void Start()
    {
        if (_runTask is not null) return;
        _runTask = Task.Run(RunAsync);
    }

    public event PropertyChangedEventHandler? PropertyChanged;

    private async Task RunAsync()
    {
        var delaySeconds = 1;
        while (!_stop.IsCancellationRequested)
        {
            try
            {
                UpdateState("CONNECTING", "Opening video stream…", 241, 186, 83);
                App.Logger.Info($"Connecting stream '{Name}'.");
                _attemptConnected = new TaskCompletionSource<bool>(TaskCreationOptions.RunContinuationsAsynchronously);
                _attemptEnded = new TaskCompletionSource<string>(TaskCreationOptions.RunContinuationsAsynchronously);
                UpdateConnection(false);

                if (!StreamAddress.TryParse(_config.Url, out var uri))
                    throw new InvalidOperationException($"Enter a valid {StreamAddress.ExpectedSchemes} viewer address in Settings.");

                _activeMedia = new Media(_libVlc, _config.Url.Trim(), FromType.FromLocation);
                if (StreamAddress.IsRtsp(uri!) && _config.UseTcp) _activeMedia.AddOption(":rtsp-tcp");
                _activeMedia.AddOption(":network-caching=250");
                _activeMedia.AddOption(":live-caching=250");
                if (StreamAddress.IsRtsp(uri!))
                {
                    if (!string.IsNullOrEmpty(_config.Username)) _activeMedia.AddOption($":rtsp-user={_config.Username}");
                    if (!string.IsNullOrEmpty(_config.Password)) _activeMedia.AddOption($":rtsp-pwd={_config.Password}");
                }

                if (!Player.Play(_activeMedia)) throw new IOException("VLC could not start this media stream.");

                var firstResult = await Task.WhenAny(_attemptConnected.Task, _attemptEnded.Task, Task.Delay(TimeSpan.FromSeconds(15), _stop.Token));
                if (_stop.IsCancellationRequested) break;
                if (firstResult == _attemptEnded.Task)
                    throw new IOException(await _attemptEnded.Task);
                if (firstResult != _attemptConnected.Task)
                    throw new TimeoutException("Timed out waiting for the first video stream connection.");

                var frameDeadline = DateTime.UtcNow.AddSeconds(15);
                while (_activeMedia.Statistics.DecodedVideo == 0 && DateTime.UtcNow < frameDeadline)
                {
                    var frameOrEnd = await Task.WhenAny(_attemptEnded.Task, Task.Delay(500, _stop.Token));
                    if (_stop.IsCancellationRequested) break;
                    if (frameOrEnd == _attemptEnded.Task)
                        throw new IOException(await _attemptEnded.Task);
                }
                if (_stop.IsCancellationRequested) break;
                if (_activeMedia.Statistics.DecodedVideo == 0)
                    throw new TimeoutException("The stream connected but no video frames were received.");

                UpdateConnection(true);
                delaySeconds = 1;
                UpdateState("LIVE", "VIDEO RECEIVED", 70, 204, 137);
                await MonitorVideoAsync();
                break;
            }
            catch (OperationCanceledException) when (_stop.IsCancellationRequested)
            {
                break;
            }
            catch (Exception ex)
            {
                if (_stop.IsCancellationRequested) break;
                UpdateConnection(false);
                App.Logger.Warning($"Stream '{Name}' disconnected ({ex.GetType().Name}).");
                UpdateState("RECONNECTING", $"NO SIGNAL · retry in {delaySeconds}s", 241, 186, 83);
            }
            finally
            {
                try { Player.Stop(); } catch { }
                _activeMedia?.Dispose();
                _activeMedia = null;
            }

            try { await Task.Delay(TimeSpan.FromSeconds(delaySeconds), _stop.Token); }
            catch (OperationCanceledException) { break; }
            delaySeconds = Math.Min(delaySeconds * 2, 15);
        }
        UpdateConnection(false);
        UpdateState("DISCONNECTED", "NO SIGNAL", 241, 93, 104);
    }

    private void OnPlaying(object? sender, EventArgs e)
    {
        if (_stop.IsCancellationRequested) return;
        _attemptConnected?.TrySetResult(true);
        UpdateState("CONNECTING", "Stream connected · waiting for video", 241, 186, 83);
        App.Logger.Info($"Video session opened for '{Name}'; waiting for video frames.");
    }

    private void OnError(object? sender, EventArgs e)
    {
        if (_stop.IsCancellationRequested) return;
        App.Logger.Warning($"Stream '{Name}' reported a playback error.");
        _attemptEnded?.TrySetResult("The stream returned a playback error.");
    }

    private void OnStopped(object? sender, EventArgs e)
    {
        if (!_stop.IsCancellationRequested && !_disposed)
            _attemptEnded?.TrySetResult("The stream stopped.");
    }

    private async Task MonitorVideoAsync()
    {
        var lastVideoCount = _activeMedia?.Statistics.DecodedVideo ?? 0;
        var lastFrameAt = DateTime.UtcNow;
        while (!_stop.IsCancellationRequested)
        {
            var endedOrCheck = await Task.WhenAny(_attemptEnded!.Task, Task.Delay(TimeSpan.FromSeconds(3), _stop.Token));
            if (_stop.IsCancellationRequested) return;
            if (endedOrCheck == _attemptEnded.Task)
                throw new IOException(await _attemptEnded.Task);

            var currentVideoCount = _activeMedia?.Statistics.DecodedVideo ?? 0;
            if (currentVideoCount > lastVideoCount)
            {
                lastVideoCount = currentVideoCount;
                lastFrameAt = DateTime.UtcNow;
            }
            else if (DateTime.UtcNow - lastFrameAt > TimeSpan.FromSeconds(20))
            {
                throw new TimeoutException("The video stream stopped delivering frames.");
            }
        }
    }

    private void UpdateState(string status, string detail, byte red, byte green, byte blue)
    {
        var dispatcher = Application.Current?.Dispatcher;
        if (dispatcher is null || dispatcher.CheckAccess())
        {
            SetState(status, detail, red, green, blue);
            return;
        }
        _ = dispatcher.BeginInvoke(() => SetState(status, detail, red, green, blue));
    }

    private void UpdateConnection(bool connected)
    {
        var dispatcher = Application.Current?.Dispatcher;
        if (dispatcher is null || dispatcher.CheckAccess())
        {
            IsConnected = connected;
            return;
        }
        _ = dispatcher.BeginInvoke(() => IsConnected = connected);
    }

    private void SetState(string status, string detail, byte red, byte green, byte blue)
    {
        Status = status;
        Detail = detail;
        StatusBrush = new SolidColorBrush(Color.FromRgb(red, green, blue));
    }

    private void Set<T>(ref T field, T value, [CallerMemberName] string? name = null)
    {
        if (EqualityComparer<T>.Default.Equals(field, value)) return;
        field = value;
        PropertyChanged?.Invoke(this, new PropertyChangedEventArgs(name));
    }

    public async ValueTask DisposeAsync()
    {
        if (_disposed) return;
        _disposed = true;
        _stop.Cancel();
        try { Player.Stop(); } catch { }
        if (_runTask is not null)
        {
            try { await _runTask.WaitAsync(TimeSpan.FromSeconds(3)); } catch { }
        }
        Player.Playing -= OnPlaying;
        Player.EncounteredError -= OnError;
        Player.Stopped -= OnStopped;
        _activeMedia?.Dispose();
        Player.Dispose();
        _stop.Dispose();
    }
}
