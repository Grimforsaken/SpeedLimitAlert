using dnlib.DotNet;
using dnlib.DotNet.Emit;
using dnlib.DotNet.Writer;
using System.Text;

if (args.Length < 2)
{
    Console.Error.WriteLine("Usage: AssemblyTool inspect <dll> [pattern] | dump <dll> <pattern> | patch <target.dll> <helper.dll> <roads.bin> <out.dll>");
    return 2;
}

var cmd = args[0].ToLowerInvariant();

if (cmd == "patch")
{
    if (args.Length != 5)
    {
        Console.Error.WriteLine("Usage: AssemblyTool patch <target.dll> <helper.dll> <roads.bin> <out.dll>");
        return 2;
    }

    PatchCarScanner(args[1], args[2], args[3], args[4]);
    return 0;
}

var path = args[1];
var pattern = args.Length > 2 ? args[2] : "";
var module = ModuleDefMD.Load(path);

static bool Match(string s, string p) =>
    string.IsNullOrEmpty(p) || s.Contains(p, StringComparison.OrdinalIgnoreCase);

if (cmd == "inspect")
{
    foreach (var type in module.GetTypes())
    {
        var full = type.FullName;
        if (Match(full, pattern))
            Console.WriteLine($"TYPE {full}");

        foreach (var field in type.Fields)
        {
            var s = $"{type.FullName}::{field.Name} : {field.FieldType}";
            if (Match(s, pattern))
                Console.WriteLine($"FIELD {field.MDToken.Raw:X8} {s}");
        }

        foreach (var method in type.Methods)
        {
            var sig = $"{type.FullName}::{method.Name}{method.MethodSig}";
            if (Match(sig, pattern))
                Console.WriteLine($"METHOD {method.MDToken.Raw:X8} {sig}");
        }
    }
    return 0;
}

if (cmd == "dump")
{
    foreach (var type in module.GetTypes())
    foreach (var method in type.Methods)
    {
        var sig = $"{type.FullName}::{method.Name}{method.MethodSig}";
        if (!Match(sig, pattern)) continue;
        Console.WriteLine($"=== {method.MDToken.Raw:X8} {sig} ===");
        if (!method.HasBody)
        {
            Console.WriteLine("<no body>");
            continue;
        }

        foreach (var ins in method.Body.Instructions)
        {
            string op = ins.OpCode.Name;
            string operand = "";
            if (ins.Operand is IMethod m) operand = m.FullName;
            else if (ins.Operand is IField fld) operand = fld.FullName;
            else if (ins.Operand is ITypeDefOrRef t) operand = t.FullName;
            else if (ins.Operand is Instruction target) operand = $"IL_{target.Offset:X4}";
            else if (ins.Operand is IList<Instruction> targets) operand = string.Join(",", targets.Select(x => $"IL_{x.Offset:X4}"));
            else if (ins.Operand != null) operand = ins.Operand.ToString() ?? "";
            Console.WriteLine($"IL_{ins.Offset:X4}: {op,-12} {operand}");
        }

        if (method.Body.HasExceptionHandlers)
        {
            Console.WriteLine("-- EH --");
            foreach (var eh in method.Body.ExceptionHandlers)
                Console.WriteLine($"{eh.HandlerType} try IL_{eh.TryStart?.Offset:X4}-IL_{eh.TryEnd?.Offset:X4} handler IL_{eh.HandlerStart?.Offset:X4}-IL_{eh.HandlerEnd?.Offset:X4}");
        }
    }
    return 0;
}

Console.Error.WriteLine($"Unknown command: {cmd}");
return 2;

static void PatchCarScanner(string targetPath, string helperPath, string roadsPath, string outputPath)
{
    var target = ModuleDefMD.Load(targetPath);
    var helper = ModuleDefMD.Load(helperPath);

    var injected = InjectSimpleType(
        helper.Find("CarScannerOffline.OfflineRoadLookup", false)
            ?? throw new InvalidOperationException("OfflineRoadLookup helper type not found"),
        target);

    var getLimit = injected.Methods.FirstOrDefault(m => m.Name == "GetLimitKph")
        ?? throw new InvalidOperationException("Injected GetLimitKph method not found");

    target.Resources.Remove(target.Resources.FirstOrDefault(r => r.Name == "CarScannerOffline.offline_roads.bin"));
    target.Resources.Add(new EmbeddedResource(
        "CarScannerOffline.offline_roads.bin",
        File.ReadAllBytes(roadsPath),
        ManifestResourceAttributes.Private));

    PatchAltitudeSensor(target);
    PatchGpsUpdate(target, getLimit);
    PatchGpsEnablement(target);

    var opts = new ModuleWriterOptions(target);
    opts.Logger = DummyLogger.NoThrowInstance;
    opts.MetadataOptions.Flags |= MetadataFlags.PreserveAll;
    target.Write(outputPath, opts);

    Console.WriteLine($"Patched assembly written: {outputPath}");
}

static void PatchAltitudeSensor(ModuleDefMD target)
{
    var type = target.Find("CarScannerMaui.OBD2.PIDS.CalculatedPIDs.Common.PID_GPSAltitude", false)
        ?? throw new InvalidOperationException("PID_GPSAltitude type not found");

    var ctor = type.Methods.First(m => m.IsInstanceConstructor);
    var ins = ctor.Body.Instructions;

    for (int i = 0; i < ins.Count; i++)
    {
        if (ins[i].OpCode == OpCodes.Ldstr && (string?)ins[i].Operand == "PID_GPSAltitude")
        {
            ins[i].Operand = "Speed Limit";
            if (i + 1 < ins.Count && ins[i + 1].OpCode == OpCodes.Call)
            {
                ins[i + 1].OpCode = OpCodes.Nop;
                ins[i + 1].Operand = null;
            }
            break;
        }
    }

    foreach (var i in ins)
    {
        if (i.IsLdcI4() && i.GetLdcI4Value() == 20)
        {
            i.OpCode = OpCodes.Ldc_I4_1;
            i.Operand = null;
            break;
        }
    }

    Console.WriteLine("Patched GPS Altitude sensor -> Speed Limit");
}

static void PatchGpsUpdate(ModuleDefMD target, MethodDef getLimit)
{
    var stateType = target.Find(
        "CarScannerMaui.OBD2.PIDS.CalculatedPIDs.Common.PID_GPSSpeed/<StartUpdateCycle>d__8",
        false) ?? throw new InvalidOperationException("GPS update state machine not found");
    var move = stateType.Methods.First(m => m.Name == "MoveNext");
    var ins = move.Body.Instructions;

    var altitudeCalls = ins
        .Where(i => i.Operand is IMethod m &&
                    m.Name == "get_Altitude" &&
                    m.DeclaringType.FullName == "Microsoft.Maui.Devices.Sensors.Location")
        .ToList();
    if (altitudeCalls.Count < 2)
        throw new InvalidOperationException($"Expected two Location.get_Altitude calls, found {altitudeCalls.Count}");

    var speedRef = ins.Select(i => i.Operand).OfType<IMethod>()
        .FirstOrDefault(m => m.Name == "get_Speed" &&
                             m.DeclaringType.FullName == "Microsoft.Maui.Devices.Sensors.Location")
        ?? throw new InvalidOperationException("Location.get_Speed ref not found");

    var latRef = FindMethodRef(target, "Microsoft.Maui.Devices.Sensors.Location", "get_Latitude")
        ?? throw new InvalidOperationException("Location.get_Latitude ref not found");
    var lonRef = FindMethodRef(target, "Microsoft.Maui.Devices.Sensors.Location", "get_Longitude")
        ?? throw new InvalidOperationException("Location.get_Longitude ref not found");

    altitudeCalls[0].Operand = speedRef;

    int k = ins.IndexOf(altitudeCalls[1]);
    if (k < 1 || k + 4 >= ins.Count)
        throw new InvalidOperationException("Unexpected GPS altitude IL layout");

    // Preserve the PID_altitude instance already on the stack and replace the
    // altitude extraction with OfflineRoadLookup.GetLimitKph(latitude, longitude).
    ins[k - 1].OpCode = OpCodes.Ldloc_2;
    ins[k - 1].Operand = null;

    ins[k].OpCode = OpCodes.Callvirt;
    ins[k].Operand = latRef;

    ins[k + 1].OpCode = OpCodes.Ldloc_2;
    ins[k + 1].Operand = null;

    ins[k + 2].OpCode = OpCodes.Callvirt;
    ins[k + 2].Operand = lonRef;

    ins[k + 3].OpCode = OpCodes.Call;
    ins[k + 3].Operand = getLimit;

    Console.WriteLine("Patched GPS cycle to feed offline map speed limit");
}

static void PatchGpsEnablement(ModuleDefMD target)
{
    var type = target.Find("CarScannerMaui.OBD2.PIDS.CalculatedPIDs.Common.PID_GPSSpeed", false)
        ?? throw new InvalidOperationException("PID_GPSSpeed type not found");
    var method = type.Methods.First(m => m.Name == "CheckIsEnabled");
    var ins = method.Body.Instructions;

    var permissionStart = ins.FirstOrDefault(i =>
        i.Operand is IMethod m &&
        m.FullName.Contains("Permissions::CheckStatusAsync", StringComparison.Ordinal));

    if (permissionStart == null)
        throw new InvalidOperationException("GPS permission check not found");

    ins[0].OpCode = OpCodes.Br;
    ins[0].Operand = permissionStart;
    Console.WriteLine("Patched GPS calculated sensors to remain available when location permission is granted");
}

static IMethod? FindMethodRef(ModuleDefMD module, string declaringType, string name)
{
    foreach (var type in module.GetTypes())
    foreach (var method in type.Methods)
    {
        if (!method.HasBody) continue;
        foreach (var i in method.Body.Instructions)
        {
            if (i.Operand is IMethod m &&
                m.Name == name &&
                m.DeclaringType.FullName == declaringType)
                return m;
        }
    }
    return null;
}

static TypeDef InjectSimpleType(TypeDef source, ModuleDef target)
{
    var importer = new Importer(target, ImporterOptions.TryToUseTypeDefs);
    var dst = new TypeDefUser(
        source.Namespace,
        source.Name,
        source.BaseType == null ? target.CorLibTypes.Object.TypeDefOrRef : importer.Import(source.BaseType))
    {
        Attributes = source.Attributes
    };
    target.Types.Add(dst);

    var fieldMap = new Dictionary<FieldDef, FieldDef>();
    foreach (var sf in source.Fields)
    {
        var df = new FieldDefUser(
            sf.Name,
            importer.Import(sf.FieldSig),
            sf.Attributes);
        if (sf.HasConstant)
            df.Constant = new ConstantUser(sf.Constant.Value, sf.Constant.Type);
        dst.Fields.Add(df);
        fieldMap[sf] = df;
    }

    var methodMap = new Dictionary<MethodDef, MethodDef>();
    foreach (var sm in source.Methods)
    {
        var dm = new MethodDefUser(
            sm.Name,
            importer.Import(sm.MethodSig),
            sm.ImplAttributes,
            sm.Attributes);
        foreach (var pd in sm.ParamDefs)
            dm.ParamDefs.Add(new ParamDefUser(pd.Name, pd.Sequence, pd.Attributes));
        dst.Methods.Add(dm);
        methodMap[sm] = dm;
    }

    foreach (var sm in source.Methods)
    {
        if (!sm.HasBody) continue;
        var dm = methodMap[sm];
        var sb = sm.Body;
        var db = new CilBody
        {
            InitLocals = sb.InitLocals,
            MaxStack = sb.MaxStack,
            KeepOldMaxStack = sb.KeepOldMaxStack
        };
        dm.Body = db;

        var localMap = new Dictionary<Local, Local>();
        foreach (var sl in sb.Variables)
        {
            var dl = new Local(importer.Import(sl.Type), sl.Name);
            db.Variables.Add(dl);
            localMap[sl] = dl;
        }

        var insMap = new Dictionary<Instruction, Instruction>();
        foreach (var si in sb.Instructions)
        {
            var di = Instruction.Create(OpCodes.Nop);
            di.OpCode = si.OpCode;
            db.Instructions.Add(di);
            insMap[si] = di;
        }

        foreach (var si in sb.Instructions)
        {
            var di = insMap[si];
            di.Operand = MapOperand(si.Operand, importer, source, dst, fieldMap, methodMap, localMap, insMap, sm, dm);
        }

        foreach (var seh in sb.ExceptionHandlers)
        {
            var deh = new ExceptionHandler(seh.HandlerType)
            {
                CatchType = seh.CatchType == null ? null : importer.Import(seh.CatchType),
                TryStart = seh.TryStart == null ? null : insMap[seh.TryStart],
                TryEnd = seh.TryEnd == null ? null : insMap[seh.TryEnd],
                HandlerStart = seh.HandlerStart == null ? null : insMap[seh.HandlerStart],
                HandlerEnd = seh.HandlerEnd == null ? null : insMap[seh.HandlerEnd],
                FilterStart = seh.FilterStart == null ? null : insMap[seh.FilterStart]
            };
            db.ExceptionHandlers.Add(deh);
        }
    }

    return dst;
}

static object? MapOperand(
    object? operand,
    Importer importer,
    TypeDef sourceType,
    TypeDef destType,
    Dictionary<FieldDef, FieldDef> fieldMap,
    Dictionary<MethodDef, MethodDef> methodMap,
    Dictionary<Local, Local> localMap,
    Dictionary<Instruction, Instruction> insMap,
    MethodDef sourceMethod,
    MethodDef destMethod)
{
    if (operand == null) return null;
    if (operand is Instruction i) return insMap[i];
    if (operand is Instruction[] ia) return ia.Select(x => insMap[x]).ToArray();
    if (operand is IList<Instruction> il) return il.Select(x => insMap[x]).ToArray();
    if (operand is Local l) return localMap[l];
    if (operand is Parameter p)
    {
        int idx = sourceMethod.Parameters.IndexOf(p);
        return idx >= 0 && idx < destMethod.Parameters.Count
            ? destMethod.Parameters[idx]
            : throw new InvalidOperationException("Parameter mapping failed");
    }
    if (operand is MethodDef md && methodMap.TryGetValue(md, out var mappedMethod)) return mappedMethod;
    if (operand is FieldDef fd && fieldMap.TryGetValue(fd, out var mappedField)) return mappedField;
    if (operand is TypeDef td && td == sourceType) return destType;
    if (operand is IMethod m) return importer.Import(m);
    if (operand is IField f) return importer.Import(f);
    if (operand is ITypeDefOrRef tr) return importer.Import(tr);
    if (operand is TypeSig ts) return importer.Import(ts);
    return operand;
}
