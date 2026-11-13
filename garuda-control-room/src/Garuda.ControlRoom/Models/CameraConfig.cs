using System.ComponentModel;
using System.Runtime.CompilerServices;

namespace Garuda.ControlRoom.Models;

public sealed class CameraConfig : INotifyPropertyChanged
{
    private string _name = "Camera";
    private string _url = "";
    private string _username = "";
    private string _password = "";
    private bool _enabled = true;
    private bool _useTcp = true;
    private bool _audioEnabled;
    private bool _useWebPlayer;

    public string Name { get => _name; set => Set(ref _name, value); }
    public string Url { get => _url; set => Set(ref _url, value); }
    public string Username { get => _username; set => Set(ref _username, value); }
    public string Password { get => _password; set => Set(ref _password, value); }
    public bool Enabled { get => _enabled; set => Set(ref _enabled, value); }
    public bool UseTcp { get => _useTcp; set => Set(ref _useTcp, value); }
    public bool AudioEnabled { get => _audioEnabled; set => Set(ref _audioEnabled, value); }
    public bool UseWebPlayer { get => _useWebPlayer; set => Set(ref _useWebPlayer, value); }

    public event PropertyChangedEventHandler? PropertyChanged;

    public CameraConfig Copy() => new()
    {
        Name = Name,
        Url = Url,
        Username = Username,
        Password = Password,
        Enabled = Enabled,
        UseTcp = UseTcp,
        AudioEnabled = AudioEnabled,
        UseWebPlayer = UseWebPlayer
    };

    private void Set<T>(ref T field, T value, [CallerMemberName] string? propertyName = null)
    {
        if (EqualityComparer<T>.Default.Equals(field, value)) return;
        field = value;
        PropertyChanged?.Invoke(this, new PropertyChangedEventArgs(propertyName));
    }
}
