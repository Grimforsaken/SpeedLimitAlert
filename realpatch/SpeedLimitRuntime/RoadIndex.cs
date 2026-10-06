using System.Text;

namespace CarScannerSpeedLimitInjected;

internal sealed class RoadIndex
{
    internal readonly struct Segment
    {
        public readonly int ALa;
        public readonly int ALo;
        public readonly int BLa;
        public readonly int BLo;
        public readonly short FwdMph;
        public readonly short BackMph;
        public readonly byte Rank;

        public Segment(int ala, int alo, int bla, int blo, short f, short b, byte r)
        {
            ALa = ala;
            ALo = alo;
            BLa = bla;
            BLo = blo;
            FwdMph = f;
            BackMph = b;
            Rank = r;
        }
    }

    private readonly Segment[] _segments;
    private readonly Dictionary<long, int[]> _grid;

    public int Count => _segments.Length;

    private RoadIndex(Segment[] segments, Dictionary<long, int[]> grid)
    {
        _segments = segments;
        _grid = grid;
    }

    public static RoadIndex Load(string path)
    {
        using var fs = File.OpenRead(path);
        using var br = new BinaryReader(fs, Encoding.UTF8, false);

        byte[] magic = br.ReadBytes(6);
        if (Encoding.ASCII.GetString(magic) != "SLDB1\0")
            throw new InvalidDataException("Invalid road database.");

        int n = br.ReadInt32();
        if (n < 0 || n > 10_000_000)
            throw new InvalidDataException("Invalid road segment count.");

        var segments = new Segment[n];
        var temp = new Dictionary<long, List<int>>();

        for (int i = 0; i < n; i++)
        {
            int ala = br.ReadInt32();
            int alo = br.ReadInt32();
            int bla = br.ReadInt32();
            int blo = br.ReadInt32();
            short f = br.ReadInt16();
            short b = br.ReadInt16();
            byte rank = br.ReadByte();
            br.ReadBytes(3);

            segments[i] = new Segment(ala, alo, bla, blo, f, b, rank);

            int minLat = Math.Min(ala, bla);
            int maxLat = Math.Max(ala, bla);
            int minLon = Math.Min(alo, blo);
            int maxLon = Math.Max(alo, blo);

            int y0 = Cell(minLat);
            int y1 = Cell(maxLat);
            int x0 = Cell(minLon);
            int x1 = Cell(maxLon);

            if (y1 - y0 > 20 || x1 - x0 > 20) continue;

            for (int y = y0; y <= y1; y++)
            {
                for (int x = x0; x <= x1; x++)
                {
                    long key = Key(y, x);
                    if (!temp.TryGetValue(key, out List<int>? list))
                    {
                        list = new List<int>();
                        temp[key] = list;
                    }

                    list.Add(i);
                }
            }
        }

        return new RoadIndex(
            segments,
            temp.ToDictionary(k => k.Key, v => v.Value.ToArray()));
    }

    public int FindLimit(double lat, double lon, double? bearing)
    {
        int la = (int)Math.Round(lat * 1e7);
        int lo = (int)Math.Round(lon * 1e7);
        int cy = Cell(la);
        int cx = Cell(lo);

        double bestScore = double.MaxValue;
        int best = -1;
        var seen = new HashSet<int>();

        for (int dy = -1; dy <= 1; dy++)
        {
            for (int dx = -1; dx <= 1; dx++)
            {
                if (!_grid.TryGetValue(Key(cy + dy, cx + dx), out int[]? idx))
                    continue;

                foreach (int i in idx)
                {
                    if (!seen.Add(i)) continue;

                    Segment s = _segments[i];
                    double aLat = s.ALa / 1e7;
                    double aLon = s.ALo / 1e7;
                    double bLat = s.BLa / 1e7;
                    double bLon = s.BLo / 1e7;

                    double dist = PointSegmentMeters(
                        lat, lon, aLat, aLon, bLat, bLon);

                    if (dist > 90) continue;

                    bool forward = true;
                    double headingPenalty = 0;

                    if (bearing.HasValue && !double.IsNaN(bearing.Value))
                    {
                        double seg = Bearing(aLat, aLon, bLat, bLon);
                        double df = AngleDiff(bearing.Value, seg);
                        double db = AngleDiff(bearing.Value, (seg + 180) % 360);

                        forward = df <= db;
                        headingPenalty = Math.Min(df, db) * 0.45;
                    }

                    int limit = forward ? s.FwdMph : s.BackMph;
                    if (limit <= 0)
                        limit = forward ? s.BackMph : s.FwdMph;

                    if (limit <= 0) continue;

                    double score = dist + headingPenalty - Math.Min(6, s.Rank);
                    if (score < bestScore)
                    {
                        bestScore = score;
                        best = limit;
                    }
                }
            }
        }

        return best;
    }

    private static int Cell(int e7)
    {
        return (int)Math.Floor(e7 / 100000.0);
    }

    private static long Key(int y, int x)
    {
        return ((long)y << 32) ^ (uint)x;
    }

    private static double PointSegmentMeters(
        double pLat,
        double pLon,
        double aLat,
        double aLon,
        double bLat,
        double bLon)
    {
        double scale = Math.Cos(pLat * Math.PI / 180.0);

        double px = pLon * 111320 * scale;
        double py = pLat * 110540;
        double ax = aLon * 111320 * scale;
        double ay = aLat * 110540;
        double bx = bLon * 111320 * scale;
        double by = bLat * 110540;

        double dx = bx - ax;
        double dy = by - ay;
        double len2 = dx * dx + dy * dy;

        double t = len2 == 0
            ? 0
            : ((px - ax) * dx + (py - ay) * dy) / len2;

        t = Math.Max(0, Math.Min(1, t));

        return Math.Sqrt(
            Math.Pow(px - (ax + t * dx), 2)
            + Math.Pow(py - (ay + t * dy), 2));
    }

    private static double Bearing(
        double aLat,
        double aLon,
        double bLat,
        double bLon)
    {
        double dLon = (bLon - aLon) * Math.PI / 180.0;
        double ar = aLat * Math.PI / 180.0;
        double br = bLat * Math.PI / 180.0;

        double y = Math.Sin(dLon) * Math.Cos(br);
        double x = Math.Cos(ar) * Math.Sin(br)
            - Math.Sin(ar) * Math.Cos(br) * Math.Cos(dLon);

        return (Math.Atan2(y, x) * 180.0 / Math.PI + 360) % 360;
    }

    private static double AngleDiff(double a, double b)
    {
        double d = Math.Abs(a - b) % 360;
        return d > 180 ? 360 - d : d;
    }
}
