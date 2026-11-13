using LibVLCSharp.Shared;

namespace Garuda.ControlRoom.Services;

public sealed class VlcHost : IDisposable
{
    public LibVLC Engine { get; }

    public VlcHost()
    {
        App.Logger.Info("Initializing VLC runtime.");
        Core.Initialize();
        App.Logger.Info("VLC native runtime initialized.");
        Engine = new LibVLC("--no-video-title-show", "--network-caching=250", "--live-caching=250");
        App.Logger.Info("VLC playback engine ready.");
    }

    public void Dispose() => Engine.Dispose();
}
