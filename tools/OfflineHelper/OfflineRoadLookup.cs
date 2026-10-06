using System.Reflection;

namespace CarScannerOffline;

public static class OfflineRoadLookup
{
    private const string ResourceName = "CarScannerOffline.offline_roads.bin";
    private static readonly object Sync = new();
    private static byte[]? Data;
    private static Dictionary<long, long>? Buckets;
    private static int CellE7;
    private static int RecordBase;
    private static bool Loaded;

    public static double GetLimitKph(double latitude, double longitude)
    {
        try
        {
            EnsureLoaded();
            if (Data == null || Buckets == null || CellE7 <= 0) return -1;

            int latE7 = (int)Math.Round(latitude * 10000000.0);
            int lonE7 = (int)Math.Round(longitude * 10000000.0);
            int cellLat = (int)Math.Floor((double)latE7 / CellE7);
            int cellLon = (int)Math.Floor((double)lonE7 / CellE7);

            double bestScore = double.MaxValue;
            int bestLimit = -1;

            for (int dy = -1; dy <= 1; dy++)
            {
                for (int dx = -1; dx <= 1; dx++)
                {
                    long key = MakeKey(cellLat + dy, cellLon + dx);
                    if (!Buckets.TryGetValue(key, out long packed)) continue;

                    int start = (int)(packed >> 32);
                    int count = (int)(packed & 0xffffffffL);
                    for (int i = 0; i < count; i++)
                    {
                        int p = RecordBase + (start + i) * 20;
                        int aLat = ReadInt32(Data, p);
                        int aLon = ReadInt32(Data, p + 4);
                        int bLat = ReadInt32(Data, p + 8);
                        int bLon = ReadInt32(Data, p + 12);
                        int fwd = Data[p + 16];
                        int back = Data[p + 17];
                        int rank = Data[p + 18];

                        int limit;
                        if (fwd > 0 && back > 0) limit = Math.Min(fwd, back);
                        else limit = fwd > 0 ? fwd : back;
                        if (limit <= 0) continue;

                        double dist = PointSegmentMeters(
                            latitude, longitude,
                            aLat / 10000000.0, aLon / 10000000.0,
                            bLat / 10000000.0, bLon / 10000000.0);
                        if (dist > 90.0) continue;

                        double score = dist - Math.Min(6, rank);
                        if (score < bestScore)
                        {
                            bestScore = score;
                            bestLimit = limit;
                        }
                    }
                }
            }

            return bestLimit > 0 ? bestLimit * 1.609344 : -1;
        }
        catch
        {
            return -1;
        }
    }

    private static void EnsureLoaded()
    {
        if (Loaded) return;
        lock (Sync)
        {
            if (Loaded) return;

            using Stream? stream = Assembly.GetExecutingAssembly().GetManifestResourceStream(ResourceName);
            if (stream == null)
            {
                Loaded = true;
                return;
            }

            using var ms = new MemoryStream();
            stream.CopyTo(ms);
            byte[] data = ms.ToArray();
            if (data.Length < 20 ||
                data[0] != (byte)'C' || data[1] != (byte)'S' ||
                data[2] != (byte)'R' || data[3] != (byte)'O' ||
                data[4] != (byte)'A' || data[5] != (byte)'D' ||
                data[6] != (byte)'1')
            {
                Loaded = true;
                return;
            }

            int cell = ReadInt32(data, 8);
            int bucketCount = ReadInt32(data, 12);
            int recordCount = ReadInt32(data, 16);
            int recordBase = 20 + bucketCount * 16;
            if (cell <= 0 || bucketCount < 0 || recordCount < 0 ||
                recordBase < 20 || recordBase + (long)recordCount * 20L > data.Length)
            {
                Loaded = true;
                return;
            }

            var buckets = new Dictionary<long, long>(bucketCount);
            int p = 20;
            for (int i = 0; i < bucketCount; i++, p += 16)
            {
                int clat = ReadInt32(data, p);
                int clon = ReadInt32(data, p + 4);
                int start = ReadInt32(data, p + 8);
                int count = ReadInt32(data, p + 12);
                if (start < 0 || count < 0 || (long)start + count > recordCount) continue;
                buckets[MakeKey(clat, clon)] = ((long)start << 32) | (uint)count;
            }

            Data = data;
            Buckets = buckets;
            CellE7 = cell;
            RecordBase = recordBase;
            Loaded = true;
        }
    }

    private static long MakeKey(int latCell, int lonCell)
        => ((long)latCell << 32) ^ (uint)lonCell;

    private static int ReadInt32(byte[] data, int p)
        => data[p] | (data[p + 1] << 8) | (data[p + 2] << 16) | (data[p + 3] << 24);

    private static double PointSegmentMeters(
        double pLat, double pLon,
        double aLat, double aLon,
        double bLat, double bLon)
    {
        double scale = Math.Cos(pLat * Math.PI / 180.0);
        double px = pLon * 111320.0 * scale;
        double py = pLat * 110540.0;
        double ax = aLon * 111320.0 * scale;
        double ay = aLat * 110540.0;
        double bx = bLon * 111320.0 * scale;
        double by = bLat * 110540.0;

        double dx = bx - ax;
        double dy = by - ay;
        double len2 = dx * dx + dy * dy;
        double t = len2 == 0.0 ? 0.0 : ((px - ax) * dx + (py - ay) * dy) / len2;
        if (t < 0.0) t = 0.0;
        else if (t > 1.0) t = 1.0;

        double x = ax + t * dx;
        double y = ay + t * dy;
        double ex = px - x;
        double ey = py - y;
        return Math.Sqrt(ex * ex + ey * ey);
    }
}
