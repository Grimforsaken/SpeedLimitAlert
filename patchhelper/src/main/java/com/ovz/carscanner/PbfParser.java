package com.ovz.carscanner;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.zip.InflaterInputStream;

public final class PbfParser {
    public interface BlockHandler{void onBlock(PrimitiveBlock b)throws Exception;}
    public interface WayHandler{void onWay(WayData w)throws Exception;}
    public interface NodeHandler{void onNode(NodeData n)throws Exception;}
    public static final class PrimitiveBlock{
        public final List<String> strings; public final List<byte[]> groups; public final int granularity; public final long latOffset,lonOffset;
        PrimitiveBlock(List<String>s,List<byte[]>g,int gr,long la,long lo){strings=s;groups=g;granularity=gr;latOffset=la;lonOffset=lo;}
    }
    public static final class WayData{public long id;public int[]keys=new int[0],vals=new int[0];public long[]refs=new long[0];}
    public static final class NodeData{public long id,rawLat,rawLon;}
    private PbfParser(){}

    public static void read(File file,BlockHandler handler)throws Exception{
        try(DataInputStream in=new DataInputStream(new BufferedInputStream(new FileInputStream(file),1024*1024))){
            while(true){
                int headerLen;try{headerLen=in.readInt();}catch(EOFException e){break;}
                if(headerLen<=0||headerLen>65536)throw new IOException("Invalid PBF block header");
                byte[]header=new byte[headerLen];in.readFully(header);
                Proto hp=new Proto(header);String type="";int dataSize=-1;
                while(hp.has()){int tag=hp.tag(),field=tag>>>3,wire=tag&7;
                    if(field==1&&wire==2)type=new String(hp.bytes(),StandardCharsets.UTF_8);
                    else if(field==3&&wire==0)dataSize=(int)hp.varint();else hp.skip(wire);}
                if(dataSize<0||dataSize>64*1024*1024)throw new IOException("Invalid PBF blob size");
                byte[]blob=new byte[dataSize];in.readFully(blob);if(!"OSMData".equals(type))continue;
                handler.onBlock(parsePrimitiveBlock(unpackBlob(blob)));
            }
        }
    }
    private static byte[]unpackBlob(byte[]blob)throws Exception{
        Proto p=new Proto(blob);byte[]raw=null,zlib=null;int rawSize=-1;
        while(p.has()){int tag=p.tag(),f=tag>>>3,w=tag&7;
            if(f==1&&w==2)raw=p.bytes();else if(f==2&&w==0)rawSize=(int)p.varint();else if(f==3&&w==2)zlib=p.bytes();else p.skip(w);}
        if(raw!=null)return raw;
        if(zlib!=null){
            try(InflaterInputStream zin=new InflaterInputStream(new ByteArrayInputStream(zlib));ByteArrayOutputStream out=new ByteArrayOutputStream(rawSize>0?rawSize:65536)){
                byte[]buf=new byte[65536];int n;while((n=zin.read(buf))!=-1)out.write(buf,0,n);return out.toByteArray();
            }
        }
        throw new IOException("Unsupported PBF compression");
    }
    private static PrimitiveBlock parsePrimitiveBlock(byte[]raw)throws Exception{
        Proto p=new Proto(raw);List<String>s=new ArrayList<>();List<byte[]>g=new ArrayList<>();int gran=100;long lat=0,lon=0;
        while(p.has()){int tag=p.tag(),f=tag>>>3,w=tag&7;
            if(f==1&&w==2)s=parseStringTable(p.bytes());else if(f==2&&w==2)g.add(p.bytes());
            else if(f==17&&w==0)gran=(int)p.varint();else if(f==19&&w==0)lat=p.varint();else if(f==20&&w==0)lon=p.varint();else p.skip(w);}
        return new PrimitiveBlock(s,g,gran,lat,lon);
    }
    private static List<String>parseStringTable(byte[]b)throws Exception{
        Proto p=new Proto(b);List<String>o=new ArrayList<>();while(p.has()){int t=p.tag(),f=t>>>3,w=t&7;
            if(f==1&&w==2)o.add(new String(p.bytes(),StandardCharsets.UTF_8));else p.skip(w);}return o;
    }
    public static void ways(PrimitiveBlock b,WayHandler h)throws Exception{
        for(byte[]g:b.groups){Proto p=new Proto(g);while(p.has()){int t=p.tag(),f=t>>>3,w=t&7;if(f==3&&w==2)h.onWay(parseWay(p.bytes()));else p.skip(w);}}
    }
    private static WayData parseWay(byte[]b)throws Exception{
        Proto p=new Proto(b);WayData x=new WayData();IntList k=new IntList(),v=new IntList();LongList r=new LongList();
        while(p.has()){int t=p.tag(),f=t>>>3,w=t&7;if(f==1&&w==0)x.id=p.varint();else if(f==2)readUInt32(p,w,k);else if(f==3)readUInt32(p,w,v);else if(f==8)readSInt64(p,w,r);else p.skip(w);}
        long acc=0;long[]rr=r.toArray();for(int i=0;i<rr.length;i++){acc+=rr[i];rr[i]=acc;}x.keys=k.toArray();x.vals=v.toArray();x.refs=rr;return x;
    }
    public static void nodes(PrimitiveBlock b,NodeHandler h)throws Exception{
        for(byte[]g:b.groups){Proto p=new Proto(g);while(p.has()){int t=p.tag(),f=t>>>3,w=t&7;if(f==1&&w==2)h.onNode(parseNode(p.bytes()));else if(f==2&&w==2)parseDense(p.bytes(),h);else p.skip(w);}}
    }
    private static NodeData parseNode(byte[]b)throws Exception{
        Proto p=new Proto(b);NodeData n=new NodeData();while(p.has()){int t=p.tag(),f=t>>>3,w=t&7;if(f==1&&w==0)n.id=p.sint64();else if(f==8&&w==0)n.rawLat=p.sint64();else if(f==9&&w==0)n.rawLon=p.sint64();else p.skip(w);}return n;
    }
    private static void parseDense(byte[]b,NodeHandler h)throws Exception{
        Proto p=new Proto(b);long[]ids=new long[0],lats=new long[0],lons=new long[0];
        while(p.has()){int t=p.tag(),f=t>>>3,w=t&7;if((f==1||f==8||f==9)&&w==2){Proto q=new Proto(p.bytes());LongList a=new LongList();while(q.has())a.add(q.sint64());if(f==1)ids=a.toArray();else if(f==8)lats=a.toArray();else lons=a.toArray();}else p.skip(w);}
        int n=Math.min(ids.length,Math.min(lats.length,lons.length));long id=0,lat=0,lon=0;
        for(int i=0;i<n;i++){id+=ids[i];lat+=lats[i];lon+=lons[i];NodeData x=new NodeData();x.id=id;x.rawLat=lat;x.rawLon=lon;h.onNode(x);}
    }
    private static void readUInt32(Proto p,int w,IntList o)throws Exception{if(w==0)o.add((int)p.varint());else if(w==2){Proto q=new Proto(p.bytes());while(q.has())o.add((int)q.varint());}else p.skip(w);}
    private static void readSInt64(Proto p,int w,LongList o)throws Exception{if(w==0)o.add(p.sint64());else if(w==2){Proto q=new Proto(p.bytes());while(q.has())o.add(q.sint64());}else p.skip(w);}
    private static final class Proto{
        final byte[]d;int p;Proto(byte[]d){this.d=d;}boolean has(){return p<d.length;}int tag()throws IOException{return(int)varint();}
        long varint()throws IOException{long v=0;int s=0;while(s<64&&p<d.length){int b=d[p++]&255;v|=(long)(b&127)<<s;if((b&128)==0)return v;s+=7;}throw new IOException("Bad protobuf varint");}
        long sint64()throws IOException{long v=varint();return(v>>>1)^-(v&1);}
        byte[]bytes()throws IOException{int n=(int)varint();if(n<0||p+n>d.length)throw new IOException("Bad protobuf length");byte[]o=Arrays.copyOfRange(d,p,p+n);p+=n;return o;}
        void skip(int w)throws IOException{if(w==0)varint();else if(w==1)p+=8;else if(w==2){int n=(int)varint();p+=n;}else if(w==5)p+=4;else throw new IOException("Unsupported protobuf wire type "+w);if(p>d.length)throw new IOException("Protobuf overflow");}
    }
    private static final class IntList{int[]a=new int[16];int n;void add(int v){if(n==a.length)a=Arrays.copyOf(a,n*2);a[n++]=v;}int[]toArray(){return Arrays.copyOf(a,n);}}
    private static final class LongList{long[]a=new long[32];int n;void add(long v){if(n==a.length)a=Arrays.copyOf(a,n*2);a[n++]=v;}long[]toArray(){return Arrays.copyOf(a,n);}}
}
