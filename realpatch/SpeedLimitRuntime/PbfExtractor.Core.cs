using System.IO.Compression;
using System.Net;
using System.Text;

namespace CarScannerSpeedLimitInjected;

internal static partial class PbfExtractor
{
    private sealed class Way
    {
        public long[] Nodes = Array.Empty<long>();
        public short Fwd;
        public short Back;
        public byte Rank;
    }

    public static void Extract(string source, string output, Action<string>? progress)
    {
        var ways = new List<Way>();
        var needed = new HashSet<long>();

        progress?.Invoke("Pass 1 of 3: selecting explicit speed-limit roads…");
        ReadPbf(source, block => ParsePrimitiveBlock(block, true, false, ways, needed, null));
        if (ways.Count == 0) throw new InvalidDataException("No explicit numeric maxspeed roads found in PBF.");

        progress?.Invoke("Pass 2 of 3: extracting required coordinates…");
        var coords = new Dictionary<long, (int lat, int lon)>(needed.Count);
        ReadPbf(source, block => ParsePrimitiveBlock(block, false, true, ways, needed, coords));

        progress?.Invoke("Pass 3 of 3: building compact offline road database…");
        int count = 0;
        using (var fs = new FileStream(output, FileMode.Create, FileAccess.Write, FileShare.None, 1024 * 1024))
        using (var bw = new BinaryWriter(fs, Encoding.UTF8, true))
        {
            bw.Write(Encoding.ASCII.GetBytes("SLDB1\0"));
            bw.Write(0);

            foreach (Way w in ways)
            {
                for (int i = 1; i < w.Nodes.Length; i++)
                {
                    if (!coords.TryGetValue(w.Nodes[i - 1], out var a) ||
                        !coords.TryGetValue(w.Nodes[i], out var b)) continue;

                    bw.Write(a.lat); bw.Write(a.lon); bw.Write(b.lat); bw.Write(b.lon);
                    bw.Write(w.Fwd); bw.Write(w.Back); bw.Write(w.Rank);
                    bw.Write(new byte[3]);
                    count++;
                }
            }

            bw.Flush();
            fs.Position = 6;
            bw.Write(count);
            bw.Flush();
        }

        if (count == 0) throw new InvalidDataException("No usable speed-limit road segments were produced.");
        progress?.Invoke($"Road data ready: {count:N0} segments.");
    }

    private static void ReadPbf(string path, Action<byte[]> dataBlock)
    {
        using var fs = new FileStream(path, FileMode.Open, FileAccess.Read, FileShare.Read, 1024 * 1024, FileOptions.SequentialScan);
        using var br = new BinaryReader(fs, Encoding.UTF8, true);

        while (fs.Position < fs.Length)
        {
            if (fs.Length - fs.Position < 4) break;
            int headerLen = IPAddress.NetworkToHostOrder(br.ReadInt32());
            if (headerLen <= 0 || headerLen > 64 * 1024) throw new InvalidDataException("Invalid PBF blob header.");

            byte[] header = br.ReadBytes(headerLen);
            if (header.Length != headerLen) throw new EndOfStreamException();

            ParseBlobHeader(header, out string type, out int dataSize);
            if (dataSize < 0 || dataSize > 64 * 1024 * 1024) throw new InvalidDataException("Invalid PBF blob size.");

            byte[] blob = br.ReadBytes(dataSize);
            if (blob.Length != dataSize) throw new EndOfStreamException();
            if (type == "OSMData") dataBlock(UnpackBlob(blob));
        }
    }

    private static void ParseBlobHeader(byte[] b, out string type, out int size)
    {
        type = "";
        size = -1;
        int p = 0;
        while (p < b.Length)
        {
            ulong key = ReadVar(b, ref p);
            int field = (int)(key >> 3);
            int wt = (int)(key & 7);
            if (field == 1 && wt == 2) type = Encoding.UTF8.GetString(ReadBytes(b, ref p));
            else if (field == 3 && wt == 0) size = checked((int)ReadVar(b, ref p));
            else Skip(b, ref p, wt);
        }
    }

    private static byte[] UnpackBlob(byte[] b)
    {
        byte[]? raw = null;
        byte[]? zlib = null;
        int rawSize = -1;
        int p = 0;

        while (p < b.Length)
        {
            ulong key = ReadVar(b, ref p);
            int field = (int)(key >> 3);
            int wt = (int)(key & 7);

            if (field == 1 && wt == 2) raw = ReadBytes(b, ref p);
            else if (field == 2 && wt == 0) rawSize = checked((int)ReadVar(b, ref p));
            else if (field == 3 && wt == 2) zlib = ReadBytes(b, ref p);
            else Skip(b, ref p, wt);
        }

        if (raw != null) return raw;
        if (zlib == null) throw new InvalidDataException("Unsupported PBF compression.");

        using var input = new MemoryStream(zlib, false);
        using var zs = new ZLibStream(input, CompressionMode.Decompress);
        using var output = rawSize > 0 ? new MemoryStream(rawSize) : new MemoryStream();
        zs.CopyTo(output);
        return output.ToArray();
    }

    private static byte[] ReadBytes(byte[] b, ref int p)
    {
        int n = checked((int)ReadVar(b, ref p));
        if (n < 0 || p + n > b.Length) throw new EndOfStreamException();
        byte[] x = new byte[n];
        Buffer.BlockCopy(b, p, x, 0, n);
        p += n;
        return x;
    }

    private static ulong ReadVar(byte[] b, ref int p)
    {
        ulong value = 0;
        int shift = 0;
        while (p < b.Length && shift < 70)
        {
            byte x = b[p++];
            value |= (ulong)(x & 0x7f) << shift;
            if ((x & 0x80) == 0) return value;
            shift += 7;
        }
        throw new InvalidDataException("Invalid protobuf varint.");
    }

    private static long Zig(ulong x) => unchecked((long)((x >> 1) ^ (ulong)-(long)(x & 1)));

    private static void Skip(byte[] b, ref int p, int wireType)
    {
        switch (wireType)
        {
            case 0: ReadVar(b, ref p); break;
            case 1: p += 8; break;
            case 2: p += checked((int)ReadVar(b, ref p)); break;
            case 5: p += 4; break;
            default: throw new InvalidDataException("Unsupported protobuf wire type " + wireType);
        }
        if (p > b.Length) throw new EndOfStreamException();
    }
}
