using System.Windows;
using System.Windows.Controls.Primitives;
using System.Windows.Input;
using Garuda.ControlRoom.Models;
using Garuda.ControlRoom.Services;

namespace Garuda.ControlRoom.Views;

public partial class MainWindow : Window
{
    private readonly ConfigurationService _configuration = new();
    private VlcHost? _vlcHost;
    private AppConfig _config = ConfigurationService.DefaultConfig();
    private readonly List<CameraSession> _sessions = [];
    private CameraSession? _fullScreenSession;
    private bool _closing;
    private bool _allowClose;

    public MainWindow()
    {
        InitializeComponent();
        Loaded += MainWindow_Loaded;
    }

    private async void MainWindow_Loaded(object sender, RoutedEventArgs e)
    {
        try
        {
            StreamSummary.Text = "Initializing video engine…";
            var host = await Task.Run(() => new VlcHost());
            if (_closing)
            {
                host.Dispose();
                return;
            }
            _vlcHost = host;
            SettingsButton.IsEnabled = true;
            ReconnectButton.IsEnabled = true;
            _config = _configuration.Load();
            await ApplyConfigAsync(_config, "Loaded saved layout.");
        }
        catch (Exception ex)
        {
            App.Logger.Error($"Main screen setup failed ({ex.GetType().Name}): {AppLogger.Redact(ex.Message)}");
            StreamSummary.Text = "Startup error · See local log";
            MessageBox.Show($"The video monitor could not finish starting.\n\n{ex.Message}", "Garuda Live Viewer", MessageBoxButton.OK, MessageBoxImage.Error, MessageBoxResult.OK);
        }
    }

    private void Settings_Click(object sender, RoutedEventArgs e)
    {
        App.Logger.Info("Settings opened.");
        var host = _vlcHost;
        if (host is null) return;
        var original = _config.Copy();
        try
        {
            var settings = new SettingsWindow(original, host.Engine, config => ApplyConfigAsync(config), SaveConfig);
            settings.Owner = this;
            App.Logger.Info("Displaying settings dialog.");
            settings.ShowDialog();
            App.Logger.Info("Settings closed.");
        }
        catch (Exception ex)
        {
            App.Logger.Error($"Settings window failed ({ex.GetType().Name}): {AppLogger.Redact(ex.Message)}");
            MessageBox.Show($"Settings could not be opened.\n\n{ex.Message}", "Garuda Live Viewer", MessageBoxButton.OK, MessageBoxImage.Error);
        }
    }

    private async void Reconnect_Click(object sender, RoutedEventArgs e)
    {
        if (_vlcHost is null || _closing) return;
        ReconnectButton.IsEnabled = false;
        try
        {
            await ApplyConfigAsync(_config, "All configured video inputs were manually reconnected.");
        }
        catch (Exception ex)
        {
            App.Logger.Error($"Manual reconnect failed ({ex.GetType().Name}): {AppLogger.Redact(ex.Message)}");
            StreamSummary.Text = "Reconnect failed · Check the local log";
        }
        finally
        {
            ReconnectButton.IsEnabled = _vlcHost is not null && !_closing;
        }
    }

    private async Task ApplyConfigAsync(AppConfig next, string logMessage = "Configuration applied.")
    {
        next = next.Copy();
        next.Normalize();
        _fullScreenSession = null;
        // Detach VLC's WPF surfaces before stopping or replacing their media sessions.
        VideoGrid.Children.Clear();
        foreach (var session in _sessions.ToArray())
            await session.DisposeAsync();
        _sessions.Clear();
        _config = next;
        Topmost = _config.AlwaysOnTop;
        RenderGrid();
        App.Logger.Info(logMessage);
    }

    private void SaveConfig(AppConfig config)
    {
        _configuration.Save(config);
        App.Logger.Info("Configuration saved locally.");
    }

    private void RenderGrid()
    {
        var engine = _vlcHost?.Engine;
        if (engine is null) return;
        VideoGrid.Children.Clear();
        LayoutLabel.Text = $"{_config.Rows} × {_config.Columns}  ·  {_config.Cameras.Count} INPUTS";
        var grid = new UniformGrid { Rows = _fullScreenSession is null ? _config.Rows : 1, Columns = _fullScreenSession is null ? _config.Columns : 1 };
        IEnumerable<CameraConfig> cameras = _fullScreenSession is null
            ? _config.Cameras
            : _config.Cameras.Where(camera => camera.Name == _fullScreenSession.Name).Take(1);
        if (_fullScreenSession is not null)
        {
            var fullscreenTile = new CameraTile(_fullScreenSession) { Margin = new Thickness(2) };
            fullscreenTile.FullScreenRequested += ToggleFullScreen;
            grid.Children.Add(fullscreenTile);
        }
        else
        {
            var sessionIndex = 0;
            foreach (var camera in cameras)
            {
                if (camera.UseWebPlayer)
                {
                    grid.Children.Add(new HostedPlayerTile(camera) { Margin = new Thickness(2) });
                    continue;
                }

                CameraSession session;
                if (sessionIndex < _sessions.Count)
                {
                    session = _sessions[sessionIndex];
                }
                else
                {
                    session = new CameraSession(engine, camera);
                    _sessions.Add(session);
                }
                var tile = new CameraTile(session) { Margin = new Thickness(2) };
                tile.FullScreenRequested += ToggleFullScreen;
                grid.Children.Add(tile);
                sessionIndex++;
            }
        }
        VideoGrid.Children.Add(grid);
        var enabledCount = _config.Cameras.Count(camera => camera.Enabled && !string.IsNullOrWhiteSpace(camera.Url));
        StreamSummary.Text = $"{enabledCount} CONFIGURED STREAM{(enabledCount == 1 ? "" : "S")}  ·  ESC TO EXIT FULL SCREEN";
    }

    private void ToggleFullScreen(CameraSession session)
    {
        if (_fullScreenSession == session)
        {
            _fullScreenSession = null;
        }
        else
        {
            _fullScreenSession = session;
        }
        RenderGrid();
    }

    private void Window_KeyDown(object sender, KeyEventArgs e)
    {
        if (e.Key == Key.Escape && _fullScreenSession is not null)
        {
            _fullScreenSession = null;
            RenderGrid();
            e.Handled = true;
        }
    }

    private async void MainWindow_Closing(object? sender, System.ComponentModel.CancelEventArgs e)
    {
        if (_allowClose) return;
        e.Cancel = true;
        if (_closing) return;
        _closing = true;
        SettingsButton.IsEnabled = false;
        ReconnectButton.IsEnabled = false;
        foreach (var session in _sessions.ToArray()) await session.DisposeAsync();
        _sessions.Clear();
        _vlcHost?.Dispose();
        _allowClose = true;
        Close();
    }
}
