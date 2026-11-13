using System.Windows;
using System.Windows.Controls;
using System.Windows.Data;
using System.Windows.Media;
using Garuda.ControlRoom.Models;
using Garuda.ControlRoom.Services;
using LibVLCSharp.Shared;

namespace Garuda.ControlRoom.Views;

public partial class SettingsWindow : Window
{
    private readonly AppConfig _original;
    private readonly AppConfig _draft;
    private readonly LibVLC _libVlc;
    private readonly Func<AppConfig, Task> _apply;
    private readonly Action<AppConfig> _save;
    private bool _initializing = true;
    private bool _hasApplied;

    public SettingsWindow(AppConfig original, LibVLC libVlc, Func<AppConfig, Task> apply, Action<AppConfig> save)
    {
        InitializeComponent();
        App.Logger.Info("Settings editor initialized.");
        Loaded += (_, _) => App.Logger.Info("Settings window loaded.");
        ContentRendered += (_, _) => App.Logger.Info("Settings window rendered.");
        _original = original.Copy();
        _draft = original.Copy();
        _libVlc = libVlc;
        _apply = apply;
        _save = save;
        RowsCombo.ItemsSource = Enumerable.Range(1, 4).ToArray();
        ColumnsCombo.ItemsSource = Enumerable.Range(1, 4).ToArray();
        RowsCombo.SelectedItem = _draft.Rows;
        ColumnsCombo.SelectedItem = _draft.Columns;
        AlwaysOnTopCheckBox.IsChecked = _draft.AlwaysOnTop;
        _initializing = false;
        ResizeCameras();
    }

    private void Layout_SelectionChanged(object sender, SelectionChangedEventArgs e)
    {
        if (_initializing || RowsCombo.SelectedItem is not int rows || ColumnsCombo.SelectedItem is not int columns) return;
        _draft.Rows = rows;
        _draft.Columns = columns;
        ResizeCameras();
    }

    private void ResizeCameras()
    {
        var count = _draft.Rows * _draft.Columns;
        while (_draft.Cameras.Count < count)
            _draft.Cameras.Add(new CameraConfig { Name = $"Camera {_draft.Cameras.Count + 1}" });
        if (_draft.Cameras.Count > count)
            _draft.Cameras.RemoveRange(count, _draft.Cameras.Count - count);
        TotalInputs.Text = $"{_draft.Cameras.Count} video input{(_draft.Cameras.Count == 1 ? "" : "s")}";
        RebuildCameraRows();
    }

    private void RebuildCameraRows()
    {
        CameraRowsPanel.Children.Clear();
        for (var index = 0; index < _draft.Cameras.Count; index++)
            CameraRowsPanel.Children.Add(BuildCameraEditor(_draft.Cameras[index], index));
    }

    private Border BuildCameraEditor(CameraConfig camera, int index)
    {
        var border = new Border
        {
            Background = (Brush)FindResource("PanelBrush"),
            BorderBrush = (Brush)FindResource("BorderBrushDark"),
            BorderThickness = new Thickness(1),
            CornerRadius = new CornerRadius(7),
            Padding = new Thickness(14),
            Margin = new Thickness(0, 0, 0, 10)
        };
        var content = new Grid();
        content.RowDefinitions.Add(new RowDefinition { Height = GridLength.Auto });
        content.RowDefinitions.Add(new RowDefinition { Height = GridLength.Auto });
        content.RowDefinitions.Add(new RowDefinition { Height = GridLength.Auto });
        content.RowDefinitions.Add(new RowDefinition { Height = GridLength.Auto });

        var heading = new DockPanel { LastChildFill = false, Margin = new Thickness(0, 0, 0, 10) };
        var enabled = new CheckBox { Content = "Enabled", IsChecked = camera.Enabled, VerticalAlignment = VerticalAlignment.Center };
        enabled.Checked += (_, _) => camera.Enabled = true;
        enabled.Unchecked += (_, _) => camera.Enabled = false;
        DockPanel.SetDock(enabled, Dock.Right);
        heading.Children.Add(enabled);
        heading.Children.Add(new TextBlock
        {
            Text = $"INPUT {index + 1:00}", FontSize = 10, FontWeight = FontWeights.Bold,
            Foreground = (Brush)FindResource("AccentBrush"), VerticalAlignment = VerticalAlignment.Center
        });
        Grid.SetRow(heading, 0);
        content.Children.Add(heading);

        var nameAndUrl = new Grid { Margin = new Thickness(0, 0, 0, 10) };
        nameAndUrl.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(0.34, GridUnitType.Star) });
        nameAndUrl.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(0.66, GridUnitType.Star) });
        nameAndUrl.Children.Add(LabeledControl("Camera / Stream Name", CreateTextBox(camera, nameof(camera.Name))));
        var urlBox = CreateTextBox(camera, nameof(camera.Url));
        urlBox.ToolTip = "Enter an RTSP camera URL, an HLS .m3u8 playback URL, or an HTTP/HTTPS video or hosted player URL. For example: https://provider.example/embed/your-stream. RTSP inline credentials are moved to the separate hidden fields when you leave this box.";
        urlBox.LostFocus += (_, _) => NormalizeCredentials(camera, urlBox);
        var urlField = LabeledControl("Viewer URL", urlBox);
        Grid.SetColumn(urlField, 1);
        nameAndUrl.Children.Add(urlField);
        Grid.SetRow(nameAndUrl, 1);
        content.Children.Add(nameAndUrl);

        var credentials = new Grid { Margin = new Thickness(0, 0, 0, 10) };
        credentials.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(0.3, GridUnitType.Star) });
        credentials.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(0.43, GridUnitType.Star) });
        credentials.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(0.27, GridUnitType.Star) });
        credentials.Children.Add(LabeledControl("Username (optional)", CreateTextBox(camera, nameof(camera.Username))));
        var passwordBox = new PasswordBox { Password = camera.Password, Margin = new Thickness(0, 4, 7, 0), Height = 33 };
        var visiblePassword = new TextBox { Text = camera.Password, Margin = new Thickness(0, 4, 7, 0), Height = 33, Visibility = Visibility.Collapsed };
        passwordBox.PasswordChanged += (_, _) => camera.Password = passwordBox.Password;
        visiblePassword.TextChanged += (_, _) => camera.Password = visiblePassword.Text;
        var show = new CheckBox { Content = "Show", Margin = new Thickness(0, 8, 0, 0), VerticalAlignment = VerticalAlignment.Top };
        show.Checked += (_, _) => { visiblePassword.Text = passwordBox.Password; passwordBox.Visibility = Visibility.Collapsed; visiblePassword.Visibility = Visibility.Visible; };
        show.Unchecked += (_, _) => { passwordBox.Password = visiblePassword.Text; visiblePassword.Visibility = Visibility.Collapsed; passwordBox.Visibility = Visibility.Visible; };
        var passwordControl = new Grid();
        passwordControl.ColumnDefinitions.Add(new ColumnDefinition());
        passwordControl.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
        passwordControl.Children.Add(passwordBox);
        passwordControl.Children.Add(visiblePassword);
        Grid.SetColumn(show, 1);
        passwordControl.Children.Add(show);
        var passwordField = LabeledControl("Password (optional)", passwordControl);
        Grid.SetColumn(passwordField, 1);
        credentials.Children.Add(passwordField);
        var transport = new ComboBox { ItemsSource = new[] { "RTSP over TCP", "RTSP over UDP" }, SelectedIndex = camera.UseTcp ? 0 : 1, Margin = new Thickness(0, 4, 0, 0), Height = 33 };
        transport.SelectionChanged += (_, _) => camera.UseTcp = transport.SelectedIndex != 1;
        var playbackOptions = new StackPanel();
        playbackOptions.Children.Add(transport);
        var audio = new CheckBox
        {
            Content = "Enable audio",
            IsChecked = camera.AudioEnabled,
            Margin = new Thickness(0, 7, 0, 0),
            ToolTip = "Audio is muted by default. Enable it for this feed; use one audio feed to avoid mixing cameras."
        };
        audio.Checked += (_, _) => camera.AudioEnabled = true;
        audio.Unchecked += (_, _) => camera.AudioEnabled = false;
        playbackOptions.Children.Add(audio);
        var hostedPlayer = new CheckBox
        {
            Content = "Hosted player page (open in browser)",
            IsChecked = camera.UseWebPlayer,
            Margin = new Thickness(0, 7, 0, 0),
            ToolTip = "Select this for a provider's HTTPS embed player page. The page opens in your default browser. Leave off for RTSP, HLS .m3u8, or direct HTTP video URLs."
        };
        hostedPlayer.Checked += (_, _) => camera.UseWebPlayer = true;
        hostedPlayer.Unchecked += (_, _) => camera.UseWebPlayer = false;
        playbackOptions.Children.Add(hostedPlayer);
        var transportField = LabeledControl("Transport / audio", playbackOptions);
        Grid.SetColumn(transportField, 2);
        credentials.Children.Add(transportField);
        Grid.SetRow(credentials, 2);
        content.Children.Add(credentials);

        var actions = new DockPanel { LastChildFill = true };
        var result = new TextBlock { Text = "Enter a URL, then test the feed", Foreground = (Brush)FindResource("MutedBrush"), VerticalAlignment = VerticalAlignment.Center, FontSize = 11 };
        var testButton = new Button { Content = "Test URL", MinWidth = 100, Padding = new Thickness(10, 6, 10, 6), ToolTip = "Connect to the camera and confirm that video frames arrive." };
        testButton.Click += async (_, _) =>
        {
            NormalizeCredentials(camera, urlBox);
            testButton.IsEnabled = false;
            result.Text = "Connecting…";
            result.Foreground = new SolidColorBrush(Color.FromRgb(241, 186, 83));
            App.Logger.Info($"Testing viewer input '{camera.Name}'.");
            try
            {
                var outcome = await StreamTester.TestAsync(_libVlc, camera);
                result.Text = outcome;
                result.Foreground = outcome.StartsWith("Connected", StringComparison.Ordinal) ? new SolidColorBrush(Color.FromRgb(70, 204, 137)) : new SolidColorBrush(Color.FromRgb(241, 93, 104));
            }
            catch (Exception ex)
            {
                result.Text = $"Test failed ({ex.GetType().Name}).";
                result.Foreground = new SolidColorBrush(Color.FromRgb(241, 93, 104));
            }
            finally { testButton.IsEnabled = true; }
        };
        DockPanel.SetDock(testButton, Dock.Right);
        actions.Children.Add(testButton);
        actions.Children.Add(result);
        Grid.SetRow(actions, 3);
        content.Children.Add(actions);
        border.Child = content;
        return border;
    }

    private static TextBox CreateTextBox(CameraConfig config, string propertyName)
    {
        var box = new TextBox { Margin = new Thickness(0, 4, 7, 0), Height = 33 };
        box.SetBinding(TextBox.TextProperty, new Binding(propertyName) { Source = config, Mode = BindingMode.TwoWay, UpdateSourceTrigger = UpdateSourceTrigger.PropertyChanged });
        return box;
    }

    private StackPanel LabeledControl(string label, UIElement control)
    {
        var panel = new StackPanel { Margin = new Thickness(0, 0, 7, 0) };
        panel.Children.Add(new TextBlock { Text = label, Foreground = (Brush)FindResource("MutedBrush"), FontSize = 10 });
        panel.Children.Add(control);
        return panel;
    }

    private static void NormalizeCredentials(CameraConfig camera, TextBox urlBox)
    {
        if (!Uri.TryCreate(camera.Url.Trim(), UriKind.Absolute, out var uri) || string.IsNullOrWhiteSpace(uri.UserInfo)) return;
        var credentials = Uri.UnescapeDataString(uri.UserInfo);
        var separator = credentials.IndexOf(':');
        if (string.IsNullOrEmpty(camera.Username))
            camera.Username = separator < 0 ? credentials : credentials[..separator];
        if (string.IsNullOrEmpty(camera.Password) && separator >= 0)
            camera.Password = credentials[(separator + 1)..];
        var safeUri = new UriBuilder(uri) { UserName = "", Password = "" }.Uri.ToString();
        camera.Url = safeUri;
        urlBox.Text = safeUri;
    }

    private async void Apply_Click(object sender, RoutedEventArgs e)
    {
        foreach (var camera in _draft.Cameras)
            NormalizeCredentials(camera, FindUrlBox(camera));
        _draft.AlwaysOnTop = AlwaysOnTopCheckBox.IsChecked == true;
        _draft.Normalize();
        await _apply(_draft.Copy());
        _hasApplied = true;
    }

    private async void Save_Click(object sender, RoutedEventArgs e)
    {
        foreach (var camera in _draft.Cameras)
            NormalizeCredentials(camera, FindUrlBox(camera));
        _draft.AlwaysOnTop = AlwaysOnTopCheckBox.IsChecked == true;
        _draft.Normalize();
        await _apply(_draft.Copy());
        _save(_draft);
        DialogResult = true;
        Close();
    }

    private async void Cancel_Click(object sender, RoutedEventArgs e)
    {
        if (_hasApplied) await _apply(_original.Copy());
        DialogResult = false;
        Close();
    }

    private TextBox FindUrlBox(CameraConfig camera)
    {
        // The settings window already synchronizes URL text on edits; URLs with inline
        // credentials are normalized when the URL field loses focus or is tested.
        return new TextBox { Text = camera.Url };
    }
}
