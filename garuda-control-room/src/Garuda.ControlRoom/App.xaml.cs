using System.Windows;
using Garuda.ControlRoom.Services;
using Garuda.ControlRoom.Views;

namespace Garuda.ControlRoom;

public partial class App : Application
{
    public static AppLogger Logger { get; } = new();

    protected override void OnStartup(StartupEventArgs e)
    {
        base.OnStartup(e);
        Logger.Info("Application started.");
        if (e.Args.Contains("--generate-vlc-cache", StringComparer.OrdinalIgnoreCase))
        {
            try
            {
                using var cacheBuilder = new LibVLCSharp.Shared.LibVLC("--reset-plugins-cache");
                Logger.Info("VLC plugin cache generation completed.");
                Shutdown(0);
            }
            catch (Exception ex)
            {
                Logger.Error($"VLC plugin cache generation failed ({ex.GetType().Name}): {AppLogger.Redact(ex.Message)}");
                Shutdown(-1);
            }
            return;
        }
        if (e.Args.Contains("--vlc-smoke", StringComparer.OrdinalIgnoreCase))
        {
            try
            {
                using var host = new VlcHost();
                Logger.Info("VLC runtime smoke check passed.");
                Shutdown(0);
            }
            catch (Exception ex)
            {
                Logger.Error($"VLC runtime smoke check failed ({ex.GetType().Name}): {AppLogger.Redact(ex.Message)}");
                Shutdown(-1);
            }
            return;
        }
        if (e.Args.Contains("--xaml-smoke", StringComparer.OrdinalIgnoreCase))
        {
            try
            {
                _ = new MainWindow();
                Logger.Info("Main window XAML smoke check passed.");
                Shutdown(0);
            }
            catch (Exception ex)
            {
                Logger.Error($"Main window XAML smoke check failed: {AppLogger.Redact(ex.ToString())}");
                Shutdown(-1);
            }
            return;
        }
        try
        {
            var window = new MainWindow();
            MainWindow = window;
            window.Show();
        }
        catch (Exception ex)
        {
            Logger.Error($"The main window could not start ({ex.GetType().Name}): {AppLogger.Redact(ex.ToString())}");
            MessageBox.Show($"Garuda Live Viewer could not start.\n\n{ex.Message}", "Garuda Live Viewer", MessageBoxButton.OK, MessageBoxImage.Error);
            Shutdown(-1);
        }
    }

    protected override void OnExit(ExitEventArgs e)
    {
        Logger.Info("Application closed.");
        base.OnExit(e);
    }
}
