using System.Diagnostics;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Media;
using Garuda.ControlRoom.Models;
using Garuda.ControlRoom.Services;

namespace Garuda.ControlRoom.Views;

public partial class HostedPlayerTile : UserControl
{
    private readonly CameraConfig _camera;

    public HostedPlayerTile(CameraConfig camera)
    {
        InitializeComponent();
        _camera = camera.Copy();
        NameLabel.Text = _camera.Name;
        Loaded += Tile_Loaded;
    }

    private void Tile_Loaded(object sender, RoutedEventArgs e)
    {
        if (!StreamAddress.TryParse(_camera.Url, out var uri) || uri is null || StreamAddress.IsRtsp(uri))
        {
            ShowError("Enter a valid HTTP(S) hosted player URL in Settings.");
            return;
        }

        StatusLabel.Text = "READY";
        StatusLabel.Foreground = new SolidColorBrush(Color.FromRgb(70, 204, 137));
        StatusDot.Fill = StatusLabel.Foreground;
    }

    private void ShowError(string message)
    {
        StatusLabel.Text = "NO PLAYER";
        StatusLabel.Foreground = new SolidColorBrush(Color.FromRgb(241, 93, 104));
        StatusDot.Fill = StatusLabel.Foreground;
        DetailLabel.Text = message;
        DetailLabel.Visibility = Visibility.Visible;
    }

    private void OpenPlayer_Click(object sender, RoutedEventArgs e)
    {
        if (!StreamAddress.TryParse(_camera.Url, out var uri) || uri is null || StreamAddress.IsRtsp(uri))
        {
            ShowError("Enter a valid HTTP(S) hosted player URL in Settings.");
            return;
        }

        try
        {
            Process.Start(new ProcessStartInfo(uri.AbsoluteUri) { UseShellExecute = true });
            StatusLabel.Text = "OPENED";
        }
        catch (Exception ex)
        {
            ShowError($"Default browser could not open the page ({ex.GetType().Name}).");
        }
    }
}
