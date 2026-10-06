using System.Globalization;
using System.Text;

namespace CarScannerSpeedLimitInjected;

internal static partial class PbfExtractor
{
    private static void ParsePrimitiveBlock(
        byte[] b, bool wantWays, bool wantNodes,
        List<Way> ways, HashSet<long> needed,
        Dictionary<long, (int lat, int lon)>? coords)
    {
        var strings = new List<string>();
        var groups = new List<(int offset, int length)>();
        int granularity = 100;
        long latOffset = 0, lonOffset = 0;
        int p = 0;

        while (p < b.Length)
        {
            ulong key = ReadVar(b, ref p);
            int field = (int)(key >> 3);
            int wt = (int)(key & 7);

            if (wt == 2)
            {
                int len = checked((int)ReadVar(b, ref p));
                int offset = p;
                p += len;
                if (p > b.Length) throw new EndOfStreamException();

                if (field == 1) ParseStringTable(b, offset, len, strings);
                else if (field == 2) groups.Add((offset, len));
            }
            else if (field == 17 && wt == 0) granularity = checked((int)ReadVar(b, ref p));
            else if (field == 19 && wt == 0) latOffset = unchecked((long)ReadVar(b, ref p));
            else if (field == 20 && wt == 0) lonOffset = unchecked((long)ReadVar(b, ref p));
            else Skip(b, ref p, wt);
        }

        foreach (var group in groups)
            ParseGroup(b, group.offset, group.length, strings, granularity, latOffset, lonOffset,
                wantWays, wantNodes, ways, needed, coords);
    }

    private static void ParseStringTable(byte[] b, int offset, int length, List<string> strings)
    {
        int p = offset, end = offset + length;
        while (p < end)
        {
            ulong key = ReadVar(b, ref p);
            int field = (int)(key >> 3);
            int wt = (int)(key & 7);
            if (field == 1 && wt == 2) strings.Add(Encoding.UTF8.GetString(ReadBytes(b, ref p)));
            else Skip(b, ref p, wt);
        }
    }

    private static void ParseGroup(
        byte[] b, int offset, int length, List<string> strings,
        int granularity, long latOffset, long lonOffset,
        bool wantWays, bool wantNodes, List<Way> ways, HashSet<long> needed,
        Dictionary<long, (int lat, int lon)>? coords)
    {
        int p = offset, end = offset + length;
        while (p < end)
        {
            ulong key = ReadVar(b, ref p);
            int field = (int)(key >> 3);
            int wt = (int)(key & 7);

            if (wt != 2)
            {
                Skip(b, ref p, wt);
                continue;
            }

            int len = checked((int)ReadVar(b, ref p));
            int start = p;
            p += len;
            if (p > end) throw new EndOfStreamException();

            if (wantWays && field == 3)
                ParseWay(b, start, len, strings, ways, needed);
            else if (wantNodes && field == 1 && coords != null)
                ParseNode(b, start, len, granularity, latOffset, lonOffset, needed, coords);
            else if (wantNodes && field == 2 && coords != null)
                ParseDense(b, start, len, granularity, latOffset, lonOffset, needed, coords);
        }
    }

    private static void ParseWay(
        byte[] b, int offset, int length, List<string> strings,
        List<Way> ways, HashSet<long> needed)
    {
        var keys = new List<int>();
        var vals = new List<int>();
        var refs = new List<long>();
        int p = offset, end = offset + length;

        while (p < end)
        {
            ulong key = ReadVar(b, ref p);
            int field = (int)(key >> 3);
            int wt = (int)(key & 7);
            if (field == 2 && wt == 2) ReadPackedUInt(b, ref p, keys);
            else if (field == 3 && wt == 2) ReadPackedUInt(b, ref p, vals);
            else if (field == 8 && wt == 2) ReadPackedSInt64Delta(b, ref p, refs);
            else Skip(b, ref p, wt);
        }

        if (refs.Count < 2) return;

        string? highway = null, max = null, forward = null, backward = null, oneway = null, conditional = null;
        int count = Math.Min(keys.Count, vals.Count);

        for (int i = 0; i < count; i++)
        {
            if ((uint)keys[i] >= strings.Count || (uint)vals[i] >= strings.Count) continue;
            string k = strings[keys[i]], v = strings[vals[i]];
            switch (k)
            {
                case "highway": highway = v; break;
                case "maxspeed": max = v; break;
                case "maxspeed:forward": forward = v; break;
                case "maxspeed:backward": backward = v; break;
                case "oneway": oneway = v; break;
                case "maxspeed:conditional": conditional = v; break;
            }
        }

        if (string.IsNullOrEmpty(highway) || conditional != null) return;

        int baseline = ParseSpeed(max);
        int fwd = ParseSpeed(forward);
        int back = ParseSpeed(backward);
        if (fwd <= 0) fwd = baseline;
        if (back <= 0) back = baseline;

        string one = (oneway ?? "").Trim().ToLowerInvariant();
        if (one is "yes" or "1" or "true") back = 0;
        else if (one == "-1")
        {
            int reverseLimit = back > 0 ? back : fwd;
            fwd = 0;
            back = reverseLimit;
        }

        if (fwd <= 0 && back <= 0) return;

        long[] nodes = refs.ToArray();
        foreach (long id in nodes) needed.Add(id);
        ways.Add(new Way {
            Nodes = nodes,
            Fwd = (short)Math.Max(0, fwd),
            Back = (short)Math.Max(0, back),
            Rank = Rank(highway)
        });
    }

    private static void ParseNode(
        byte[] b, int offset, int length, int granularity,
        long latOffset, long lonOffset, HashSet<long> needed,
        Dictionary<long, (int lat, int lon)> coords)
    {
        long id = 0, lat = 0, lon = 0;
        int p = offset, end = offset + length;

        while (p < end)
        {
            ulong key = ReadVar(b, ref p);
            int field = (int)(key >> 3);
            int wt = (int)(key & 7);
            if (field == 1 && wt == 0) id = Zig(ReadVar(b, ref p));
            else if (field == 8 && wt == 0) lat = Zig(ReadVar(b, ref p));
            else if (field == 9 && wt == 0) lon = Zig(ReadVar(b, ref p));
            else Skip(b, ref p, wt);
        }

        if (needed.Contains(id))
            coords[id] = (ToE7(latOffset, granularity, lat), ToE7(lonOffset, granularity, lon));
    }

    private static void ParseDense(
        byte[] b, int offset, int length, int granularity,
        long latOffset, long lonOffset, HashSet<long> needed,
        Dictionary<long, (int lat, int lon)> coords)
    {
        byte[]? ids = null, lats = null, lons = null;
        int p = offset, end = offset + length;

        while (p < end)
        {
            ulong key = ReadVar(b, ref p);
            int field = (int)(key >> 3);
            int wt = (int)(key & 7);

            if (wt == 2 && (field == 1 || field == 8 || field == 9))
            {
                byte[] x = ReadBytes(b, ref p);
                if (field == 1) ids = x;
                else if (field == 8) lats = x;
                else lons = x;
            }
            else Skip(b, ref p, wt);
        }

        if (ids == null || lats == null || lons == null) return;

        int pi = 0, pa = 0, po = 0;
        long id = 0, lat = 0, lon = 0;

        while (pi < ids.Length && pa < lats.Length && po < lons.Length)
        {
            id += Zig(ReadVar(ids, ref pi));
            lat += Zig(ReadVar(lats, ref pa));
            lon += Zig(ReadVar(lons, ref po));

            if (needed.Contains(id))
                coords[id] = (ToE7(latOffset, granularity, lat), ToE7(lonOffset, granularity, lon));
        }
    }

    private static int ParseSpeed(string? value)
    {
        if (string.IsNullOrWhiteSpace(value)) return -1;
        string s = value.Trim().ToLowerInvariant();
        if (s.Contains(';') || s.Contains("signals") || s.Contains("variable") || s.Contains("none") || s.Contains("walk"))
            return -1;

        int i = 0;
        while (i < s.Length && !char.IsDigit(s[i])) i++;
        if (i == s.Length) return -1;
        int j = i;
        while (j < s.Length && (char.IsDigit(s[j]) || s[j] == '.')) j++;

        if (!double.TryParse(s[i..j], NumberStyles.Float, CultureInfo.InvariantCulture, out double speed))
            return -1;

        if (!s.Contains("mph")) speed *= 0.621371192237334;
        int mph = (int)Math.Round(speed);
        return mph is >= 5 and <= 120 ? mph : -1;
    }

    private static byte Rank(string highway) => highway switch
    {
        "motorway" => 9,
        "trunk" => 8,
        "primary" => 7,
        "secondary" => 6,
        "tertiary" => 5,
        "unclassified" => 4,
        "residential" => 3,
        "living_street" => 2,
        "service" => 1,
        _ => 0
    };

    private static int ToE7(long offset, int granularity, long raw) =>
        (int)Math.Round((offset + (double)granularity * raw) / 100.0);

    private static void ReadPackedUInt(byte[] b, ref int p, List<int> output)
    {
        int n = checked((int)ReadVar(b, ref p));
        int end = p + n;
        if (end > b.Length) throw new EndOfStreamException();
        while (p < end) output.Add(checked((int)ReadVar(b, ref p)));
    }

    private static void ReadPackedSInt64Delta(byte[] b, ref int p, List<long> output)
    {
        int n = checked((int)ReadVar(b, ref p));
        int end = p + n;
        if (end > b.Length) throw new EndOfStreamException();

        long acc = 0;
        while (p < end)
        {
            acc += Zig(ReadVar(b, ref p));
            output.Add(acc);
        }
    }
}
