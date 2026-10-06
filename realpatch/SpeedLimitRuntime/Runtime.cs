using System.Collections;
using System.Globalization;
using System.Net;
using System.Net.Http.Headers;
using System.Net.NetworkInformation;
using System.Reflection;
using System.Text.Json;

namespace CarScannerSpeedLimitInjected;

public static class Runtime
{
    private const string Region = "oklahoma";
    private const string GeofabrikUrl = "https://download.geofabrik.de/north-america/us/oklahoma-latest.osm.pbf";
    private const double MphToKmh = 1.609344;
    private const double KmhToMph = 0.621371192237334;

    private static readonly object Gate = new();
    private static object? _speedLimitPid;
    private static object? _settingsPage;
    private static RoadIndex? _roads;
    private static bool _autoUpdateStarted;
    private static bool _armed = true;
    private static int _overCount;
    private static DateTime _lastTone = DateTime.MinValue;

    private static string Root
    {
        get
        {
            string p = Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData);
            if (string.IsNullOrWhiteSpace(p)) p = Path.GetTempPath();
            p = Path.Combine(p, "speedlimits");
            Directory.CreateDirectory(p);
            return p;
        }
    }

    private static string DbPath => Path.Combine(Root, "roads.sldb");
    private static string SourcePath => Path.Combine(Root, Region + "-latest.osm.pbf");
    private static string ConfigPath => Path.Combine(Root, "config.json");

    public static void RegisterPid(object pid)
    {
        try
        {
            _speedLimitPid = pid;
            SetBool(pid, "IsAvailable", true);
            EnsureRoadsLoaded();
            StartAutoUpdateOnce();
        }
        catch
        {
        }
    }

    public static void OnLocation(object? location)
    {
        if (location == null) return;

        try
        {
            EnsureRoadsLoaded();
            RoadIndex? roads = _roads;
            if (roads == null || roads.Count == 0)
            {
                SetPidUnavailableValue();
                return;
            }

            double lat = GetDouble(location, "Latitude", double.NaN);
            double lon = GetDouble(location, "Longitude", double.NaN);
            if (double.IsNaN(lat) || double.IsNaN(lon)) return;

            double? accuracy = GetNullableDouble(location, "Accuracy");
            if (accuracy.HasValue && accuracy.Value > 60) return;

            double? course = GetNullableDouble(location, "Course");
            int mph = roads.FindLimit(lat, lon, course);

            if (mph <= 0)
            {
                SetPidUnavailableValue();
                _overCount = 0;
                return;
            }

            // Car Scanner's Speed unit uses km/h internally and applies the user's
            // selected display unit (mph/km/h) to dashboard widgets.
            SetPidValue(mph * MphToKmh);
            EvaluateWarning(mph, location);
        }
        catch
        {
        }
    }

    public static void AddSettingsEntries(object? page)
    {
        if (page == null) return;

        try
        {
            if (ReferenceEquals(_settingsPage, page)) return;
            _settingsPage = page;

            Type pageType = page.GetType();
            FieldInfo? field = pageType.GetField(
                "rootLayout",
                BindingFlags.Instance | BindingFlags.NonPublic | BindingFlags.Public);

            object? layout = field?.GetValue(page);
            if (layout == null) return;

            object? children = layout.GetType()
                .GetProperty("Children", BindingFlags.Instance | BindingFlags.Public)
                ?.GetValue(layout);

            if (children == null) return;

            AddAction(pageType.Assembly, children,
                "Offline speed limits",
                StatusText(),
                OnStatusTapped);

            AddAction(pageType.Assembly, children,
                "Import offline map (.osm.pbf)",
                "Select a state OpenStreetMap PBF and extract only explicit speed-limit road data.",
                OnImportTapped);

            AddAction(pageType.Assembly, children,
                "Download / update Oklahoma road data",
                "Downloads on Wi-Fi and extracts a compact offline speed-limit database.",
                OnDownloadTapped);

            Config cfg = LoadConfig();

            AddAction(pageType.Assembly, children,
                "Automatic Wi-Fi road-data updates",
                cfg.AutoUpdate
                    ? "ON — check for a newer Oklahoma extract when Car Scanner runs on Wi-Fi."
                    : "OFF — tap to enable.",
                OnToggleAutoUpdateTapped);

            AddAction(pageType.Assembly, children,
                "Delete PBF after successful extraction",
                cfg.DeleteSourceAfterExtract
                    ? "ON — automatically remove the large source file after the compact database is ready."
                    : "OFF — keep the source PBF after extraction.",
                OnToggleDeleteSourceTapped);

            AddAction(pageType.Assembly, children,
                "Speed warning",
                cfg.WarningEnabled
                    ? "ON — warning at more than " + cfg.WarningOffsetMph + " mph over the posted limit."
                    : "OFF — tap to enable.",
                OnToggleWarningTapped);

            AddAction(pageType.Assembly, children,
                "Delete offline speed-limit data",
                "Remove the compact database and any retained PBF source file.",
                OnDeleteTapped);

            EnsureRoadsLoaded();
            StartAutoUpdateOnce();
        }
        catch
        {
        }
    }

    private static void AddAction(
        Assembly asm,
        object children,
        string title,
        string description,
        EventHandler handler)
    {
        try
        {
            Type? t = asm.GetType("CarScannerMaui.Controls.SettingsAction", false);
            if (t == null) return;

            object? item = Activator.CreateInstance(t);
            if (item == null) return;

            SetString(item, "Title", title);
            SetString(item, "Description", description);

            EventInfo? ev = t.GetEvent("Tapped", BindingFlags.Instance | BindingFlags.Public);
            if (ev != null) ev.AddEventHandler(item, handler);

            MethodInfo? add = null;
            foreach (MethodInfo m in children.GetType().GetMethods(BindingFlags.Instance | BindingFlags.Public))
            {
                if (m.Name == "Add" && m.GetParameters().Length == 1)
                {
                    add = m;
                    break;
                }
            }

            if (add != null) add.Invoke(children, new[] { item });
        }
        catch
        {
        }
    }

    private static void OnStatusTapped(object? sender, EventArgs e)
    {
        TryAlert("Offline speed limits", StatusText());
    }

    private static async void OnImportTapped(object? sender, EventArgs e)
    {
        try
        {
            SetDescription(sender, "Choose an .osm.pbf file…");

            Type? filePickerType = Type.GetType(
                "Microsoft.Maui.Storage.FilePicker, Microsoft.Maui.Essentials");
            if (filePickerType == null)
            {
                SetDescription(sender, "File picker is unavailable.");
                return;
            }

            object? def = filePickerType
                .GetProperty("Default", BindingFlags.Static | BindingFlags.Public)
                ?.GetValue(null);

            MethodInfo? pick = filePickerType.GetMethods(BindingFlags.Instance | BindingFlags.Public)
                .FirstOrDefault(m => m.Name == "PickAsync");

            if (def == null || pick == null)
            {
                SetDescription(sender, "File picker is unavailable.");
                return;
            }

            object?[] pickArgs = pick.GetParameters().Length == 0
                ? Array.Empty<object?>()
                : new object?[] { null };

            object? taskObj = pick.Invoke(def, pickArgs);
            if (taskObj is not Task task) return;

            await task.ConfigureAwait(true);
            object? result = taskObj.GetType().GetProperty("Result")?.GetValue(taskObj);
            if (result == null)
            {
                SetDescription(sender, "Import canceled.");
                return;
            }

            MethodInfo? open = result.GetType().GetMethod("OpenReadAsync", Type.EmptyTypes)
                ?? result.GetType().BaseType?.GetMethod("OpenReadAsync", Type.EmptyTypes);

            object? streamTaskObj = open?.Invoke(result, null);
            if (streamTaskObj is not Task streamTask)
                throw new IOException("Unable to open selected PBF.");

            await streamTask.ConfigureAwait(false);
            Stream? stream = streamTaskObj.GetType().GetProperty("Result")?.GetValue(streamTaskObj) as Stream;
            if (stream == null)
                throw new IOException("Unable to read selected PBF.");

            SetDescription(sender, "Copying selected map…");

            using (stream)
            using (var output = new FileStream(
                SourcePath,
                FileMode.Create,
                FileAccess.Write,
                FileShare.None,
                1024 * 1024,
                true))
            {
                await stream.CopyToAsync(output).ConfigureAwait(false);
            }

            SetDescription(sender, "Extracting speed-limit roads… This can take several minutes.");
            await Task.Run(() => BuildDatabase(SourcePath, sender)).ConfigureAwait(false);
            SetDescription(sender, "Road data ready. " + StatusText());
        }
        catch (Exception ex)
        {
            SetDescription(sender, "Import failed: " + ShortMessage(ex));
        }
    }

    private static async void OnDownloadTapped(object? sender, EventArgs e)
    {
        try
        {
            if (!IsWifiAvailable())
            {
                SetDescription(sender, "Wi-Fi is required for map downloads.");
                return;
            }

            await DownloadAndBuildAsync(sender, false).ConfigureAwait(false);
            SetDescription(sender, "Road data ready. " + StatusText());
        }
        catch (Exception ex)
        {
            SetDescription(sender, "Update failed: " + ShortMessage(ex));
        }
    }

    private static void OnToggleAutoUpdateTapped(object? sender, EventArgs e)
    {
        try
        {
            Config c = LoadConfig();
            c.AutoUpdate = !c.AutoUpdate;
            SaveConfig(c);

            SetDescription(sender, c.AutoUpdate
                ? "ON — automatic Oklahoma road-data updates when Car Scanner runs on Wi-Fi."
                : "OFF — tap to enable.");

            if (c.AutoUpdate) StartAutoUpdateOnce(true);
        }
        catch
        {
        }
    }

    private static void OnToggleDeleteSourceTapped(object? sender, EventArgs e)
    {
        try
        {
            Config c = LoadConfig();
            c.DeleteSourceAfterExtract = !c.DeleteSourceAfterExtract;
            SaveConfig(c);

            SetDescription(sender, c.DeleteSourceAfterExtract
                ? "ON — automatically remove the large source file after the compact database is ready."
                : "OFF — keep the source PBF after extraction.");
        }
        catch
        {
        }
    }

    private static void OnToggleWarningTapped(object? sender, EventArgs e)
    {
        try
        {
            Config c = LoadConfig();
            c.WarningEnabled = !c.WarningEnabled;
            SaveConfig(c);
            _armed = true;
            _overCount = 0;

            SetDescription(sender, c.WarningEnabled
                ? "ON — warning at more than " + c.WarningOffsetMph + " mph over the posted limit."
                : "OFF — tap to enable.");
        }
        catch
        {
        }
    }

    private static void OnDeleteTapped(object? sender, EventArgs e)
    {
        try
        {
            lock (Gate) _roads = null;
            TryDelete(DbPath);
            TryDelete(SourcePath);
            SetPidUnavailableValue();
            SetDescription(sender, "Offline speed-limit database deleted.");
        }
        catch (Exception ex)
        {
            SetDescription(sender, "Delete failed: " + ShortMessage(ex));
        }
    }

    private static void StartAutoUpdateOnce(bool force = false)
    {
        lock (Gate)
        {
            if (_autoUpdateStarted && !force) return;
            _autoUpdateStarted = true;
        }

        _ = Task.Run(async () =>
        {
            try
            {
                Config c = LoadConfig();
                if (!c.AutoUpdate || !IsWifiAvailable()) return;

                if (!force
                    && c.LastUpdateUtc > DateTime.UtcNow.AddDays(-7)
                    && File.Exists(DbPath))
                    return;

                await DownloadAndBuildAsync(null, true).ConfigureAwait(false);
            }
            catch
            {
            }
        });
    }

    private static async Task DownloadAndBuildAsync(object? statusItem, bool automatic)
    {
        if (!IsWifiAvailable())
            throw new InvalidOperationException("Wi-Fi is required.");

        SetDescription(
            statusItem,
            automatic ? "Automatic road-data update…" : "Downloading Oklahoma OSM data…");

        using var client = new HttpClient { Timeout = TimeSpan.FromMinutes(30) };
        client.DefaultRequestHeaders.UserAgent.Add(
            new ProductInfoHeaderValue("CarScanner-SpeedLimit", "1.0"));

        Config cfg = LoadConfig();
        if (cfg.LastModifiedUtc > DateTime.MinValue)
            client.DefaultRequestHeaders.IfModifiedSince = cfg.LastModifiedUtc;

        using HttpResponseMessage response = await client.GetAsync(
            GeofabrikUrl,
            HttpCompletionOption.ResponseHeadersRead).ConfigureAwait(false);

        if (response.StatusCode == HttpStatusCode.NotModified && File.Exists(DbPath))
        {
            cfg.LastUpdateUtc = DateTime.UtcNow;
            SaveConfig(cfg);
            return;
        }

        response.EnsureSuccessStatusCode();

        await using (Stream input = await response.Content.ReadAsStreamAsync().ConfigureAwait(false))
        await using (var output = new FileStream(
            SourcePath,
            FileMode.Create,
            FileAccess.Write,
            FileShare.None,
            1024 * 1024,
            true))
        {
            await input.CopyToAsync(output).ConfigureAwait(false);
        }

        SetDescription(statusItem, "Extracting speed-limit roads… This can take several minutes.");
        await Task.Run(() => BuildDatabase(SourcePath, statusItem)).ConfigureAwait(false);

        cfg = LoadConfig();
        cfg.LastUpdateUtc = DateTime.UtcNow;
        cfg.LastModifiedUtc = response.Content.Headers.LastModified?.UtcDateTime ?? DateTime.UtcNow;
        SaveConfig(cfg);
    }

    private static void BuildDatabase(string pbfPath, object? statusItem)
    {
        string temp = DbPath + ".new";
        TryDelete(temp);

        try
        {
            PbfExtractor.Extract(
                pbfPath,
                temp,
                text => SetDescription(statusItem, text));

            RoadIndex test = RoadIndex.Load(temp);
            if (test.Count < 1)
                throw new InvalidDataException("No explicit speed-limit roads were found.");

            lock (Gate)
            {
                if (File.Exists(DbPath)) File.Delete(DbPath);
                File.Move(temp, DbPath);
                _roads = test;
            }

            Config cfg = LoadConfig();
            cfg.LastUpdateUtc = DateTime.UtcNow;
            SaveConfig(cfg);

            if (cfg.DeleteSourceAfterExtract)
                TryDelete(pbfPath);
        }
        finally
        {
            TryDelete(temp);
        }
    }

    private static void EnsureRoadsLoaded()
    {
        if (_roads != null) return;

        lock (Gate)
        {
            if (_roads != null) return;
            try
            {
                if (File.Exists(DbPath))
                    _roads = RoadIndex.Load(DbPath);
            }
            catch
            {
                _roads = null;
            }
        }
    }

    private static void SetPidValue(double internalKmh)
    {
        object? pid = _speedLimitPid;
        if (pid == null) return;

        try
        {
            SetBool(pid, "IsAvailable", true);

            MethodInfo? setValue = pid.GetType().GetMethod(
                "SetValue",
                BindingFlags.Instance | BindingFlags.Public | BindingFlags.NonPublic,
                null,
                new[] { typeof(double) },
                null)
                ?? pid.GetType().BaseType?.GetMethod(
                    "SetValue",
                    BindingFlags.Instance | BindingFlags.Public | BindingFlags.NonPublic,
                    null,
                    new[] { typeof(double) },
                    null);

            if (setValue != null)
            {
                setValue.Invoke(pid, new object[] { internalKmh });
            }
            else
            {
                pid.GetType()
                    .GetProperty("Value", BindingFlags.Instance | BindingFlags.Public)
                    ?.SetValue(pid, internalKmh);
            }
        }
        catch
        {
        }
    }

    private static void SetPidUnavailableValue()
    {
        object? pid = _speedLimitPid;
        if (pid == null) return;

        try
        {
            MethodInfo? sendNan = pid.GetType().GetMethod(
                "SendNaN",
                BindingFlags.Instance | BindingFlags.Public | BindingFlags.NonPublic)
                ?? pid.GetType().BaseType?.GetMethod(
                    "SendNaN",
                    BindingFlags.Instance | BindingFlags.Public | BindingFlags.NonPublic);

            if (sendNan != null) sendNan.Invoke(pid, null);
            else SetPidValue(double.NaN);
        }
        catch
        {
        }
    }

    private static void EvaluateWarning(int limitMph, object location)
    {
        try
        {
            Config cfg = LoadConfig();
            if (!cfg.WarningEnabled)
            {
                _armed = true;
                _overCount = 0;
                return;
            }

            double speedMph = ReadObdSpeedMph();

            if (speedMph < 0)
            {
                double? gps = GetNullableDouble(location, "Speed");
                if (gps.HasValue && gps.Value >= 0)
                    speedMph = gps.Value * 2.2369362920544;
            }

            if (speedMph < 0)
            {
                _overCount = 0;
                return;
            }

            int offset = Math.Clamp(cfg.WarningOffsetMph, 1, 20);
            double trigger = limitMph + offset;
            double rearm = limitMph + Math.Max(0, offset - 2);

            if (speedMph > trigger)
            {
                _overCount++;
                if (_armed && _overCount >= 2)
                {
                    _armed = false;
                    PlayWarningTone();
                }
            }
            else
            {
                _overCount = 0;
                if (speedMph <= rearm) _armed = true;
            }
        }
        catch
        {
        }
    }

    private static double ReadObdSpeedMph()
    {
        try
        {
            Assembly? asm = _speedLimitPid?.GetType().Assembly;
            Type? app = asm?.GetType("CarScannerMaui.App", false);
            object? reader = app?.GetMethod(
                "get_OBDReader",
                BindingFlags.Static | BindingFlags.Public)
                ?.Invoke(null, null);

            object? data = reader?.GetType()
                .GetProperty("CurrentCarData", BindingFlags.Instance | BindingFlags.Public)
                ?.GetValue(reader);

            IEnumerable? list = data?.GetType()
                .GetProperty("LiveDataPIDs", BindingFlags.Instance | BindingFlags.Public)
                ?.GetValue(data) as IEnumerable;

            if (list == null) return -1;

            foreach (object? p in list)
            {
                if (p == null) continue;

                string? cmd = p.GetType()
                    .GetProperty("Command", BindingFlags.Instance | BindingFlags.Public)
                    ?.GetValue(p) as string
                    ?? p.GetType().BaseType?
                        .GetProperty("Command", BindingFlags.Instance | BindingFlags.Public)
                        ?.GetValue(p) as string;

                if (!string.Equals(cmd, "010D", StringComparison.OrdinalIgnoreCase))
                    continue;

                PropertyInfo? vp = p.GetType().GetProperty(
                    "Value",
                    BindingFlags.Instance | BindingFlags.Public);

                if (vp == null)
                {
                    foreach (Type iface in p.GetType().GetInterfaces())
                    {
                        vp = iface.GetProperty("Value");
                        if (vp != null) break;
                    }
                }

                if (vp == null) continue;

                object? val = vp.GetValue(p);
                if (val is double kmh && !double.IsNaN(kmh) && kmh >= 0)
                    return kmh * KmhToMph;
            }
        }
        catch
        {
        }

        return -1;
    }

    private static void PlayWarningTone()
    {
        try
        {
            if ((DateTime.UtcNow - _lastTone).TotalSeconds < 3) return;
            _lastTone = DateTime.UtcNow;

            Type? tgType = Type.GetType("Android.Media.ToneGenerator, Mono.Android");
            Type? streamType = Type.GetType("Android.Media.Stream, Mono.Android");
            Type? toneType = Type.GetType("Android.Media.Tone, Mono.Android");

            if (tgType == null || streamType == null || toneType == null) return;

            object stream = Enum.ToObject(streamType, 3);
            object? tg = Activator.CreateInstance(tgType, stream, 90);
            if (tg == null) return;

            object tone = Enum.ToObject(toneType, 24);
            MethodInfo? start = tgType.GetMethods()
                .FirstOrDefault(m => m.Name == "StartTone" && m.GetParameters().Length == 2);

            start?.Invoke(tg, new[] { tone, (object)650 });

            _ = Task.Delay(900).ContinueWith(_ =>
            {
                try { (tg as IDisposable)?.Dispose(); }
                catch { }
            });
        }
        catch
        {
        }
    }

    private static bool IsWifiAvailable()
    {
        try
        {
            foreach (NetworkInterface n in NetworkInterface.GetAllNetworkInterfaces())
            {
                if (n.OperationalStatus == OperationalStatus.Up
                    && n.NetworkInterfaceType == NetworkInterfaceType.Wireless80211)
                    return true;
            }
        }
        catch
        {
        }

        try
        {
            Type? appType = Type.GetType("Android.App.Application, Mono.Android");
            object? context = appType?
                .GetProperty("Context", BindingFlags.Static | BindingFlags.Public)
                ?.GetValue(null);

            if (context == null) return false;

            MethodInfo? getSvc = context.GetType().GetMethod(
                "GetSystemService",
                new[] { typeof(string) });

            object? cm = getSvc?.Invoke(context, new object[] { "connectivity" });
            if (cm == null) return false;

            object? active = cm.GetType().GetProperty("ActiveNetwork")?.GetValue(cm);
            if (active == null) return false;

            object? caps = cm.GetType()
                .GetMethod("GetNetworkCapabilities")
                ?.Invoke(cm, new[] { active });

            if (caps == null) return false;

            MethodInfo? has = caps.GetType().GetMethods()
                .FirstOrDefault(m => m.Name == "HasTransport" && m.GetParameters().Length == 1);

            if (has == null) return false;

            Type pt = has.GetParameters()[0].ParameterType;
            object wifi = pt.IsEnum ? Enum.ToObject(pt, 1) : (object)1;

            return has.Invoke(caps, new[] { wifi }) as bool? == true;
        }
        catch
        {
            return false;
        }
    }

    private static string StatusText()
    {
        try
        {
            Config cfg = LoadConfig();

            if (!File.Exists(DbPath))
                return "Database not installed. Auto-update: "
                    + (cfg.AutoUpdate ? "ON" : "OFF")
                    + ".";

            long bytes = new FileInfo(DbPath).Length;
            string age = cfg.LastUpdateUtc > DateTime.MinValue
                ? cfg.LastUpdateUtc.ToLocalTime().ToString("g", CultureInfo.CurrentCulture)
                : "unknown";

            return "Database "
                + SizeText(bytes)
                + ", updated "
                + age
                + ". Auto-update: "
                + (cfg.AutoUpdate ? "ON" : "OFF")
                + ".";
        }
        catch
        {
            return "Status unavailable.";
        }
    }

    private static void TryAlert(string title, string message)
    {
        try
        {
            object? page = _settingsPage;
            if (page == null) return;

            MethodInfo? m = page.GetType().GetMethods(BindingFlags.Instance | BindingFlags.Public)
                .FirstOrDefault(x => x.Name == "DisplayAlert" && x.GetParameters().Length == 3)
                ?? page.GetType().GetMethods(BindingFlags.Instance | BindingFlags.Public)
                    .FirstOrDefault(x => x.Name == "DisplayAlert" && x.GetParameters().Length == 4);

            if (m == null) return;

            if (m.GetParameters().Length == 3)
                m.Invoke(page, new object?[] { title, message, "OK" });
            else
                m.Invoke(page, new object?[] { title, message, "OK", null });
        }
        catch
        {
        }
    }

    private static Config LoadConfig()
    {
        try
        {
            if (File.Exists(ConfigPath))
            {
                Config? x = JsonSerializer.Deserialize<Config>(File.ReadAllText(ConfigPath));
                if (x != null) return x;
            }
        }
        catch
        {
        }

        return new Config();
    }

    private static void SaveConfig(Config c)
    {
        try
        {
            File.WriteAllText(ConfigPath, JsonSerializer.Serialize(c));
        }
        catch
        {
        }
    }

    private static string SizeText(long b)
    {
        if (b < 1024) return b + " B";
        if (b < 1024L * 1024)
            return (b / 1024.0).ToString("0.0", CultureInfo.InvariantCulture) + " KB";

        return (b / (1024.0 * 1024)).ToString("0.0", CultureInfo.InvariantCulture) + " MB";
    }

    private static string ShortMessage(Exception e)
    {
        string s = e.GetBaseException().Message ?? e.Message;
        return s.Replace('\n', ' ').Replace('\r', ' ');
    }

    private static void TryDelete(string p)
    {
        try
        {
            if (File.Exists(p)) File.Delete(p);
        }
        catch
        {
        }
    }

    private static void SetDescription(object? o, string s)
    {
        if (o != null) SetString(o, "Description", s);
    }

    private static void SetString(object o, string p, string v)
    {
        try
        {
            o.GetType()
                .GetProperty(p, BindingFlags.Instance | BindingFlags.Public)
                ?.SetValue(o, v);
        }
        catch
        {
        }
    }

    private static void SetBool(object o, string p, bool v)
    {
        try
        {
            o.GetType()
                .GetProperty(p, BindingFlags.Instance | BindingFlags.Public)
                ?.SetValue(o, v);
        }
        catch
        {
        }
    }

    private static double GetDouble(object o, string p, double fallback)
    {
        try
        {
            object? v = o.GetType().GetProperty(p)?.GetValue(o);
            return v is double d ? d : fallback;
        }
        catch
        {
            return fallback;
        }
    }

    private static double? GetNullableDouble(object o, string p)
    {
        try
        {
            object? v = o.GetType().GetProperty(p)?.GetValue(o);
            if (v is double d) return d;
            return null;
        }
        catch
        {
            return null;
        }
    }

    private sealed class Config
    {
        public bool AutoUpdate { get; set; } = true;
        public bool DeleteSourceAfterExtract { get; set; } = true;
        public bool WarningEnabled { get; set; } = true;
        public int WarningOffsetMph { get; set; } = 5;
        public DateTime LastUpdateUtc { get; set; } = DateTime.MinValue;
        public DateTime LastModifiedUtc { get; set; } = DateTime.MinValue;
    }
}
