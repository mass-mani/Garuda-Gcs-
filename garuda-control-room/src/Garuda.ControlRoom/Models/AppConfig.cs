namespace Garuda.ControlRoom.Models;

public sealed class AppConfig
{
    public int Rows { get; set; } = 1;
    public int Columns { get; set; } = 1;
    public bool AlwaysOnTop { get; set; }
    public List<CameraConfig> Cameras { get; set; } = [new CameraConfig { Name = "Camera 1" }];

    public AppConfig Copy() => new()
    {
        Rows = Rows,
        Columns = Columns,
        AlwaysOnTop = AlwaysOnTop,
        Cameras = Cameras.Select(camera => camera.Copy()).ToList()
    };

    public void Normalize()
    {
        Rows = Math.Clamp(Rows, 1, 4);
        Columns = Math.Clamp(Columns, 1, 4);
        Cameras ??= [];
        Cameras = Cameras.Take(Rows * Columns).ToList();
        while (Cameras.Count < Rows * Columns)
            Cameras.Add(new CameraConfig { Name = $"Camera {Cameras.Count + 1}" });

        for (var i = 0; i < Cameras.Count; i++)
        {
            if (string.IsNullOrWhiteSpace(Cameras[i].Name))
                Cameras[i].Name = $"Camera {i + 1}";
            Cameras[i].Url ??= "";
            Cameras[i].Username ??= "";
            Cameras[i].Password ??= "";
        }
    }
}
