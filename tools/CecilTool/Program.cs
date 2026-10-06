using Mono.Cecil;
using Mono.Cecil.Cil;

static IEnumerable<TypeDefinition> AllTypes(IEnumerable<TypeDefinition> roots) {
    foreach (var t in roots) {
        yield return t;
        foreach (var n in AllTypes(t.NestedTypes)) yield return n;
    }
}
static string Sig(MethodDefinition m) => $"{m.ReturnType.FullName} {m.FullName}";
static void Usage() {
    Console.Error.WriteLine("CecilTool list <dll> [filter]");
    Console.Error.WriteLine("CecilTool methods <dll> <typeFilter>");
    Console.Error.WriteLine("CecilTool search <dll> <text>");
    Console.Error.WriteLine("CecilTool dump <dll> <typeFilter> <methodFilter>");
    Console.Error.WriteLine("CecilTool resources <dll>");
    Console.Error.WriteLine("CecilTool extract-resource <dll> <resourceName> <out>");
}
if (args.Length < 2) { Usage(); return 2; }
var cmd=args[0]; var file=args[1];
var asm=AssemblyDefinition.ReadAssembly(file, new ReaderParameters{ReadSymbols=false,InMemory=true});
var types=AllTypes(asm.MainModule.Types).ToList();
switch(cmd) {
case "list": {
    var f=args.Length>2?args[2]:"";
    foreach(var t in types.Where(t=>t.FullName.Contains(f,StringComparison.OrdinalIgnoreCase)))
        Console.WriteLine(t.FullName);
    break;
}
case "methods": {
    if(args.Length<3){Usage();return 2;}
    var f=args[2];
    foreach(var t in types.Where(t=>t.FullName.Contains(f,StringComparison.OrdinalIgnoreCase))) {
        Console.WriteLine("TYPE "+t.FullName);
        foreach(var m in t.Methods) Console.WriteLine("  "+Sig(m));
    }
    break;
}
case "search": {
    if(args.Length<3){Usage();return 2;}
    var q=args[2];
    foreach(var t in types) {
        if(t.FullName.Contains(q,StringComparison.OrdinalIgnoreCase)) Console.WriteLine("TYPE "+t.FullName);
        foreach(var m in t.Methods) {
            if(m.FullName.Contains(q,StringComparison.OrdinalIgnoreCase)) Console.WriteLine("METHOD "+m.FullName);
            if(!m.HasBody) continue;
            foreach(var i in m.Body.Instructions) {
                string? s=i.Operand switch {
                    string x=>x,
                    MemberReference mr=>mr.FullName,
                    _=>null
                };
                if(s!=null && s.Contains(q,StringComparison.OrdinalIgnoreCase))
                    Console.WriteLine($"IL {m.FullName} @ IL_{i.Offset:x4}: {i.OpCode} {s}");
            }
        }
    }
    break;
}
case "dump": {
    if(args.Length<4){Usage();return 2;}
    var tf=args[2]; var mf=args[3];
    foreach(var t in types.Where(t=>t.FullName.Contains(tf,StringComparison.OrdinalIgnoreCase))) {
        foreach(var m in t.Methods.Where(m=>m.Name.Contains(mf,StringComparison.OrdinalIgnoreCase))) {
            Console.WriteLine("METHOD "+m.FullName);
            if(!m.HasBody){Console.WriteLine("  <no body>");continue;}
            Console.WriteLine($"  Locals={m.Body.Variables.Count} MaxStack={m.Body.MaxStackSize}");
            foreach(var i in m.Body.Instructions)
                Console.WriteLine($"  IL_{i.Offset:x4}: {i.OpCode} {i.Operand}");
        }
    }
    break;
}
case "resources": {
    foreach(var r in asm.MainModule.Resources) {
        long sz=-1;
        if(r is EmbeddedResource er) using(var st=er.GetResourceStream()) sz=st.Length;
        Console.WriteLine($"{r.ResourceType}\t{sz}\t{r.Name}");
    }
    break;
}
case "extract-resource": {
    if(args.Length<4){Usage();return 2;}
    var rn=args[2]; var outp=args[3];
    var r=asm.MainModule.Resources.FirstOrDefault(x=>x.Name==rn) as EmbeddedResource;
    if(r==null) throw new Exception("Embedded resource not found: "+rn);
    using var input=r.GetResourceStream();
    using var output=File.Create(outp);
    input.CopyTo(output);
    Console.WriteLine(outp);
    break;
}
default: Usage(); return 2;
}
return 0;
