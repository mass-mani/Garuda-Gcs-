using System.Windows.Controls;
using System.Windows.Input;
using Garuda.ControlRoom.Services;

namespace Garuda.ControlRoom.Views;

public partial class CameraTile : UserControl
{
    public event Action<CameraSession>? FullScreenRequested;

    public CameraTile(CameraSession session)
    {
        InitializeComponent();
        DataContext = session;
        Loaded += (_, _) =>
        {
            // The WPF VideoView needs its native window created before playback is attached.
            VideoSurface.MediaPlayer = session.Player;
            if (session.ShouldStart) session.Start();
        };
        Unloaded += (_, _) =>
        {
            // A tile can be moved into or out of full screen without restarting its RTSP session.
            if (ReferenceEquals(VideoSurface.MediaPlayer, session.Player))
                VideoSurface.MediaPlayer = null;
        };
    }

    private void FullScreen_Click(object sender, System.Windows.RoutedEventArgs e)
    {
        if (DataContext is CameraSession session) FullScreenRequested?.Invoke(session);
    }

    private void Tile_MouseLeftButtonDown(object sender, MouseButtonEventArgs e)
    {
        if (e.ClickCount == 2 && DataContext is CameraSession session)
            FullScreenRequested?.Invoke(session);
    }
}
