using System.Reflection;

namespace CarScannerSpeedLimitInjected;

public static class Bridge
{
    private static Assembly runtimeAssembly;
    private static Type runtimeType;

    private static Type GetRuntimeType()
    {
        if (runtimeType != null) return runtimeType;

        Assembly host = typeof(Bridge).Assembly;
        Stream stream = host.GetManifestResourceStream("CarScannerSpeedLimitRuntime.dll");
        if (stream == null) return null;

        MemoryStream memory = new MemoryStream();
        stream.CopyTo(memory);
        stream.Dispose();

        runtimeAssembly = Assembly.Load(memory.ToArray());
        memory.Dispose();

        runtimeType = runtimeAssembly.GetType("CarScannerSpeedLimitInjected.Runtime", false);
        return runtimeType;
    }

    private static void Call(string name, object arg)
    {
        try
        {
            Type type = GetRuntimeType();
            if (type == null) return;

            MethodInfo method = type.GetMethod(name, BindingFlags.Public | BindingFlags.Static);
            if (method == null) return;

            method.Invoke(null, new object[] { arg });
        }
        catch
        {
        }
    }

    public static void RegisterPid(object pid) => Call("RegisterPid", pid);
    public static void OnLocation(object location) => Call("OnLocation", location);
    public static void AddSettingsEntries(object page) => Call("AddSettingsEntries", page);
}
