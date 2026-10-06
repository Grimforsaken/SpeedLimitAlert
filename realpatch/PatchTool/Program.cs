using Mono.Cecil;
using Mono.Cecil.Cil;

static class Program
{
    private const string BridgeFullName = "CarScannerSpeedLimitInjected.Bridge";
    private const string RuntimeResourceName = "CarScannerSpeedLimitRuntime.dll";

    public static int Main(string[] args)
    {
        if (args.Length == 4 && args[0] == "--test-pbf")
        {
            var runtime = System.Reflection.Assembly.LoadFrom(Path.GetFullPath(args[1]));
            var type = runtime.GetType("CarScannerSpeedLimitInjected.PbfExtractor", true)!;
            var method = type.GetMethod(
                "Extract",
                System.Reflection.BindingFlags.Static |
                System.Reflection.BindingFlags.Public |
                System.Reflection.BindingFlags.NonPublic)
                ?? throw new InvalidOperationException("PBF extractor not found.");
            method.Invoke(null, new object?[] {
                Path.GetFullPath(args[2]),
                Path.GetFullPath(args[3]),
                null
            });
            Console.WriteLine("PBF extraction test completed: " + args[3]);
            return 0;
        }

        if (args.Length != 4)
        {
            Console.Error.WriteLine("Usage: PatchTool <CarScannerMaui.dll> <BridgeTemplate.dll> <SpeedLimitRuntime.dll> <output.dll>");
            Console.Error.WriteLine("   or: PatchTool --test-pbf <SpeedLimitRuntime.dll> <input.osm.pbf> <output.sldb>");
            return 2;
        }

        string input = Path.GetFullPath(args[0]);
        string bridgePath = Path.GetFullPath(args[1]);
        string runtimePath = Path.GetFullPath(args[2]);
        string output = Path.GetFullPath(args[3]);

        var resolver = new DefaultAssemblyResolver();
        resolver.AddSearchDirectory(Path.GetDirectoryName(input)!);
        resolver.AddSearchDirectory(Path.GetDirectoryName(bridgePath)!);

        var reader = new ReaderParameters {
            AssemblyResolver = resolver,
            InMemory = true,
            ReadSymbols = false
        };

        using var target = AssemblyDefinition.ReadAssembly(input, reader);
        using var bridgeAsm = AssemblyDefinition.ReadAssembly(bridgePath, new ReaderParameters { InMemory = true });

        RemoveExistingInjectedArtifacts(target.MainModule);

        var sourceBridge = FindType(bridgeAsm.MainModule, BridgeFullName)
            ?? throw new InvalidOperationException("Bridge template type not found.");
        var bridge = CopyType(sourceBridge, target.MainModule);

        target.MainModule.Resources.Add(
            new EmbeddedResource(RuntimeResourceName, ManifestResourceAttributes.Private, File.ReadAllBytes(runtimePath)));

        var registerPid = bridge.Methods.Single(m => m.Name == "RegisterPid");
        var onLocation = bridge.Methods.Single(m => m.Name == "OnLocation");
        var addSettings = bridge.Methods.Single(m => m.Name == "AddSettingsEntries");

        PatchCalculatedPid(target.MainModule, registerPid);
        PatchGpsLocation(target.MainModule, onLocation);
        PatchSettings(target.MainModule, addSettings);

        Directory.CreateDirectory(Path.GetDirectoryName(output)!);
        target.Write(output, new WriterParameters { WriteSymbols = false });

        Console.WriteLine("Patched: " + output);
        Console.WriteLine("Added Speed Limit as a normal Car Scanner SensorPID.");
        Console.WriteLine("Hooked offline road matching into Car Scanner's existing GPS update loop.");
        Console.WriteLine("Added offline-map controls to Car Scanner settings.");
        return 0;
    }

    private static void RemoveExistingInjectedArtifacts(ModuleDefinition module)
    {
        var old = module.Types.FirstOrDefault(t => t.FullName == BridgeFullName);
        if (old != null) module.Types.Remove(old);

        for (int i = module.Resources.Count - 1; i >= 0; i--)
            if (module.Resources[i].Name == RuntimeResourceName)
                module.Resources.RemoveAt(i);
    }

    private static void PatchCalculatedPid(ModuleDefinition module, MethodDefinition registerPid)
    {
        var carData = FindType(module, "CarScannerMaui.OBD2.CarData")
            ?? throw new InvalidOperationException("CarData type not found.");
        var method = carData.Methods.Single(m => m.Name == "CreateCalculatedPIDs" && !m.HasParameters);
        var body = method.Body;
        var il = body.GetILProcessor();

        var sensorPid = FindType(module, "CarScannerMaui.OBD2.PIDS.SensorPID")
            ?? throw new InvalidOperationException("SensorPID type not found.");
        var pid = FindType(module, "CarScannerMaui.OBD2.PIDS.PID")
            ?? throw new InvalidOperationException("PID type not found.");
        var carDataGetter = carData.Methods.Single(m => m.Name == "get_LiveDataPIDs");
        var sensorCtor = sensorPid.Methods.Single(m => m.IsConstructor && m.Parameters.Count == 3);
        var setId = pid.Methods.Single(m => m.Name == "set_Id");
        var setMinimum = pid.Methods.Single(m => m.Name == "set_Minimum");
        var setMaximum = pid.Methods.Single(m => m.Name == "set_Maximum");
        var setCommand = pid.Methods.Single(m => m.Name == "set_Command");
        var setRole = sensorPid.Methods.Single(m => m.Name == "set_Role");
        var start = sensorPid.Methods.Single(m => m.Name == "Start" && !m.HasParameters);

        var listAdd = method.Body.Instructions
            .Select(i => i.Operand as MethodReference)
            .First(m => m != null
                && m.Name == "Add"
                && m.DeclaringType.FullName.StartsWith("System.Collections.Generic.List", StringComparison.Ordinal)
                && m.Parameters.Count == 1)!;

        VariableDefinition? gpsVar = null;
        for (int i = 0; i < body.Instructions.Count; i++)
        {
            var ins = body.Instructions[i];
            if (ins.OpCode == OpCodes.Newobj
                && ins.Operand is MethodReference mr
                && mr.DeclaringType.FullName == "CarScannerMaui.OBD2.PIDS.CalculatedPIDs.Common.PID_GPSSpeed")
            {
                for (int j = i + 1; j < Math.Min(i + 12, body.Instructions.Count); j++)
                {
                    var x = body.Instructions[j];
                    if ((x.OpCode == OpCodes.Stloc || x.OpCode == OpCodes.Stloc_S) && x.Operand is VariableDefinition v)
                    {
                        gpsVar = v;
                        break;
                    }
                    if (x.OpCode.Code is Code.Stloc_0 or Code.Stloc_1 or Code.Stloc_2 or Code.Stloc_3)
                    {
                        int idx = x.OpCode.Code switch {
                            Code.Stloc_0 => 0,
                            Code.Stloc_1 => 1,
                            Code.Stloc_2 => 2,
                            _ => 3
                        };
                        gpsVar = body.Variables[idx];
                        break;
                    }
                }
                break;
            }
        }

        Instruction insertBefore;
        if (body.ExceptionHandlers.Count > 0 && body.ExceptionHandlers[0].TryEnd != null)
        {
            Instruction tryEnd = body.ExceptionHandlers[0].TryEnd;
            int boundary = body.Instructions.IndexOf(tryEnd);
            insertBefore = body.Instructions
                .Take(boundary)
                .Last(i => i.OpCode == OpCodes.Leave || i.OpCode == OpCodes.Leave_S);
        }
        else
        {
            insertBefore = body.Instructions.Last(i => i.OpCode == OpCodes.Ret);
        }

        var seq = new List<Instruction> {
            il.Create(OpCodes.Ldarg_0),
            il.Create(OpCodes.Call, module.ImportReference(carDataGetter)),
            il.Create(OpCodes.Ldstr, "Speed Limit"),
            il.Create(OpCodes.Ldstr, "SPEED_LIMIT"),
            il.Create(OpCodes.Ldc_I4_1),
            il.Create(OpCodes.Newobj, module.ImportReference(sensorCtor)),
            il.Create(OpCodes.Dup),
            il.Create(OpCodes.Ldc_I4, 99001),
            il.Create(OpCodes.Callvirt, module.ImportReference(setId)),
            il.Create(OpCodes.Dup),
            il.Create(OpCodes.Ldc_R8, 0.0),
            il.Create(OpCodes.Callvirt, module.ImportReference(setMinimum)),
            il.Create(OpCodes.Dup),
            il.Create(OpCodes.Ldc_R8, 200.0),
            il.Create(OpCodes.Callvirt, module.ImportReference(setMaximum)),
            il.Create(OpCodes.Dup),
            il.Create(OpCodes.Ldstr, "SPEED_LIMIT"),
            il.Create(OpCodes.Callvirt, module.ImportReference(setCommand)),
            il.Create(OpCodes.Dup),
            il.Create(OpCodes.Ldc_I4_S, (sbyte)35),
            il.Create(OpCodes.Callvirt, module.ImportReference(setRole)),
            il.Create(OpCodes.Dup),
            il.Create(OpCodes.Call, module.ImportReference(registerPid)),
            il.Create(OpCodes.Callvirt, module.ImportReference(listAdd))
        };

        if (gpsVar != null)
        {
            var skipStart = il.Create(OpCodes.Nop);
            seq.Add(il.Create(OpCodes.Ldloc, gpsVar));
            seq.Add(il.Create(OpCodes.Brfalse_S, skipStart));
            seq.Add(il.Create(OpCodes.Ldloc, gpsVar));
            seq.Add(il.Create(OpCodes.Callvirt, module.ImportReference(start)));
            seq.Add(skipStart);
        }

        foreach (var x in seq) il.InsertBefore(insertBefore, x);
        Console.WriteLine("Patched CreateCalculatedPIDs.");
    }

    private static void PatchGpsLocation(ModuleDefinition module, MethodDefinition onLocation)
    {
        var sm = FindType(module, "CarScannerMaui.OBD2.PIDS.CalculatedPIDs.Common.PID_GPSSpeed/<StartUpdateCycle>d__8")
            ?? throw new InvalidOperationException("GPS update state machine not found.");
        var moveNext = sm.Methods.Single(m => m.Name == "MoveNext");
        var body = moveNext.Body;
        var il = body.GetILProcessor();

        Instruction? locationStore = null;
        VariableDefinition? locationVar = null;

        foreach (var ins in body.Instructions)
        {
            VariableDefinition? candidate = null;
            if ((ins.OpCode == OpCodes.Stloc || ins.OpCode == OpCodes.Stloc_S) && ins.Operand is VariableDefinition v)
                candidate = v;
            else if (ins.OpCode.Code is Code.Stloc_0 or Code.Stloc_1 or Code.Stloc_2 or Code.Stloc_3)
            {
                int idx = ins.OpCode.Code switch {
                    Code.Stloc_0 => 0,
                    Code.Stloc_1 => 1,
                    Code.Stloc_2 => 2,
                    _ => 3
                };
                candidate = body.Variables[idx];
            }

            if (candidate != null && candidate.VariableType.FullName == "Microsoft.Maui.Devices.Sensors.Location")
            {
                locationStore = ins;
                locationVar = candidate;
                break;
            }
        }

        if (locationStore == null || locationVar == null)
            throw new InvalidOperationException("GPS Location local variable not found.");

        var load = il.Create(OpCodes.Ldloc, locationVar);
        var call = il.Create(OpCodes.Call, module.ImportReference(onLocation));
        il.InsertAfter(locationStore, load);
        il.InsertAfter(load, call);

        Console.WriteLine("Patched GPS location hook.");
    }

    private static void PatchSettings(ModuleDefinition module, MethodDefinition addSettings)
    {
        var sm = FindType(module, "CarScannerMaui.Settings.SettingsPages.SettingsRootPage/<<OnSizeAllocated>b__2_0>d")
            ?? throw new InvalidOperationException("SettingsRootPage initialization state machine not found.");
        var moveNext = sm.Methods.Single(m => m.Name == "MoveNext");
        var body = moveNext.Body;
        var il = body.GetILProcessor();

        var initCall = body.Instructions.FirstOrDefault(i =>
            (i.OpCode == OpCodes.Call || i.OpCode == OpCodes.Callvirt)
            && i.Operand is MethodReference mr
            && mr.Name == "InitializeComponent"
            && mr.DeclaringType.FullName == "CarScannerMaui.Settings.SettingsPages.SettingsRootPage")
            ?? throw new InvalidOperationException("Settings InitializeComponent call not found.");

        var pageVar = body.Variables.FirstOrDefault(v =>
            v.VariableType.FullName == "CarScannerMaui.Settings.SettingsPages.SettingsRootPage")
            ?? throw new InvalidOperationException("Settings page local variable not found.");

        var load = il.Create(OpCodes.Ldloc, pageVar);
        var call = il.Create(OpCodes.Call, module.ImportReference(addSettings));
        il.InsertAfter(initCall, load);
        il.InsertAfter(load, call);

        Console.WriteLine("Patched settings page.");
    }

    private static TypeDefinition CopyType(TypeDefinition source, ModuleDefinition target)
    {
        var type = new TypeDefinition(
            source.Namespace,
            source.Name,
            source.Attributes,
            source.BaseType == null ? null : target.ImportReference(source.BaseType));

        target.Types.Add(type);

        var fieldMap = new Dictionary<FieldDefinition, FieldDefinition>();
        foreach (var f in source.Fields)
        {
            var nf = new FieldDefinition(f.Name, f.Attributes, ImportType(f.FieldType, source, type, target));
            if (f.HasConstant) nf.Constant = f.Constant;
            type.Fields.Add(nf);
            fieldMap[f] = nf;
        }

        var methodMap = new Dictionary<MethodDefinition, MethodDefinition>();
        foreach (var m in source.Methods)
        {
            var nm = new MethodDefinition(
                m.Name,
                m.Attributes,
                ImportType(m.ReturnType, source, type, target))
            {
                ImplAttributes = m.ImplAttributes,
                CallingConvention = m.CallingConvention
            };

            foreach (var p in m.Parameters)
                nm.Parameters.Add(new ParameterDefinition(p.Name, p.Attributes, ImportType(p.ParameterType, source, type, target)));

            type.Methods.Add(nm);
            methodMap[m] = nm;
        }

        foreach (var m in source.Methods)
        {
            if (!m.HasBody) continue;
            var nm = methodMap[m];
            CloneBody(m, nm, source, type, target, fieldMap, methodMap);
        }

        return type;
    }

    private static void CloneBody(
        MethodDefinition src,
        MethodDefinition dst,
        TypeDefinition sourceType,
        TypeDefinition targetType,
        ModuleDefinition targetModule,
        Dictionary<FieldDefinition, FieldDefinition> fieldMap,
        Dictionary<MethodDefinition, MethodDefinition> methodMap)
    {
        dst.Body.InitLocals = src.Body.InitLocals;
        dst.Body.MaxStackSize = src.Body.MaxStackSize;

        var varMap = new Dictionary<VariableDefinition, VariableDefinition>();
        foreach (var v in src.Body.Variables)
        {
            var nv = new VariableDefinition(ImportType(v.VariableType, sourceType, targetType, targetModule));
            dst.Body.Variables.Add(nv);
            varMap[v] = nv;
        }

        var insMap = new Dictionary<Instruction, Instruction>();
        foreach (var i in src.Body.Instructions)
        {
            Instruction ni = MakeInstructionShell(i);
            dst.Body.Instructions.Add(ni);
            insMap[i] = ni;
        }

        foreach (var i in src.Body.Instructions)
        {
            var ni = insMap[i];
            ni.Operand = RemapOperand(
                i.Operand, src, dst, sourceType, targetType, targetModule,
                fieldMap, methodMap, varMap, insMap);
        }

        foreach (var h in src.Body.ExceptionHandlers)
        {
            dst.Body.ExceptionHandlers.Add(new ExceptionHandler(h.HandlerType) {
                TryStart = h.TryStart == null ? null : insMap[h.TryStart],
                TryEnd = h.TryEnd == null ? null : insMap[h.TryEnd],
                HandlerStart = h.HandlerStart == null ? null : insMap[h.HandlerStart],
                HandlerEnd = h.HandlerEnd == null ? null : insMap[h.HandlerEnd],
                FilterStart = h.FilterStart == null ? null : insMap[h.FilterStart],
                CatchType = h.CatchType == null ? null : ImportType(h.CatchType, sourceType, targetType, targetModule)
            });
        }
    }

    private static Instruction MakeInstructionShell(Instruction i)
    {
        object? o = i.Operand;
        if (o == null) return Instruction.Create(i.OpCode);
        if (o is sbyte sb) return Instruction.Create(i.OpCode, sb);
        if (o is byte b) return Instruction.Create(i.OpCode, (sbyte)b);
        if (o is int x) return Instruction.Create(i.OpCode, x);
        if (o is long l) return Instruction.Create(i.OpCode, l);
        if (o is float f) return Instruction.Create(i.OpCode, f);
        if (o is double d) return Instruction.Create(i.OpCode, d);
        if (o is string s) return Instruction.Create(i.OpCode, s);
        if (o is Instruction) return Instruction.Create(i.OpCode, Instruction.Create(OpCodes.Nop));
        if (o is Instruction[]) return Instruction.Create(i.OpCode, Array.Empty<Instruction>());
        if (o is MethodReference mr) return Instruction.Create(i.OpCode, mr);
        if (o is FieldReference fr) return Instruction.Create(i.OpCode, fr);
        if (o is TypeReference tr) return Instruction.Create(i.OpCode, tr);
        if (o is ParameterDefinition pd) return Instruction.Create(i.OpCode, pd);
        if (o is VariableDefinition vd) return Instruction.Create(i.OpCode, vd);
        if (o is CallSite cs) return Instruction.Create(i.OpCode, cs);
        throw new NotSupportedException("Unsupported instruction operand: " + o.GetType().FullName);
    }

    private static object? RemapOperand(
        object? operand,
        MethodDefinition src,
        MethodDefinition dst,
        TypeDefinition sourceType,
        TypeDefinition targetType,
        ModuleDefinition targetModule,
        Dictionary<FieldDefinition, FieldDefinition> fieldMap,
        Dictionary<MethodDefinition, MethodDefinition> methodMap,
        Dictionary<VariableDefinition, VariableDefinition> varMap,
        Dictionary<Instruction, Instruction> insMap)
    {
        if (operand == null || operand is sbyte || operand is byte || operand is int || operand is long
            || operand is float || operand is double || operand is string)
            return operand;

        if (operand is Instruction bi) return insMap[bi];
        if (operand is Instruction[] sw) return sw.Select(x => insMap[x]).ToArray();
        if (operand is VariableDefinition vd) return varMap[vd];
        if (operand is ParameterDefinition pd) return dst.Parameters[pd.Index];

        if (operand is FieldReference fr)
        {
            var resolved = SafeResolveField(fr);
            if (resolved != null && fieldMap.TryGetValue(resolved, out var mapped)) return mapped;
            return targetModule.ImportReference(fr);
        }

        if (operand is MethodReference mr)
        {
            var resolved = SafeResolveMethod(mr);
            if (resolved != null && methodMap.TryGetValue(resolved, out var mapped)) return mapped;

            if (mr is GenericInstanceMethod gim)
            {
                var importedElement = (MethodReference)RemapOperand(
                    gim.ElementMethod, src, dst, sourceType, targetType, targetModule,
                    fieldMap, methodMap, varMap, insMap)!;
                var ng = new GenericInstanceMethod(importedElement);
                foreach (var ga in gim.GenericArguments)
                    ng.GenericArguments.Add(ImportType(ga, sourceType, targetType, targetModule));
                return ng;
            }

            return targetModule.ImportReference(mr);
        }

        if (operand is TypeReference tr)
            return ImportType(tr, sourceType, targetType, targetModule);

        if (operand is CallSite cs)
        {
            var ncs = new CallSite(ImportType(cs.ReturnType, sourceType, targetType, targetModule)) {
                CallingConvention = cs.CallingConvention,
                HasThis = cs.HasThis,
                ExplicitThis = cs.ExplicitThis
            };
            foreach (var p in cs.Parameters)
                ncs.Parameters.Add(new ParameterDefinition(ImportType(p.ParameterType, sourceType, targetType, targetModule)));
            return ncs;
        }

        throw new NotSupportedException("Unsupported remap operand: " + operand.GetType().FullName);
    }

    private static TypeReference ImportType(
        TypeReference tr,
        TypeDefinition sourceType,
        TypeDefinition targetType,
        ModuleDefinition target)
    {
        if (tr.FullName == sourceType.FullName) return targetType;

        if (tr is ArrayType a)
            return new ArrayType(ImportType(a.ElementType, sourceType, targetType, target), a.Rank);
        if (tr is ByReferenceType br)
            return new ByReferenceType(ImportType(br.ElementType, sourceType, targetType, target));
        if (tr is PointerType pr)
            return new PointerType(ImportType(pr.ElementType, sourceType, targetType, target));
        if (tr is GenericInstanceType git)
        {
            var ng = new GenericInstanceType(ImportType(git.ElementType, sourceType, targetType, target));
            foreach (var x in git.GenericArguments)
                ng.GenericArguments.Add(ImportType(x, sourceType, targetType, target));
            return ng;
        }

        return target.ImportReference(tr);
    }

    private static FieldDefinition? SafeResolveField(FieldReference reference)
    {
        try { return reference.Resolve(); }
        catch { return null; }
    }

    private static MethodDefinition? SafeResolveMethod(MethodReference reference)
    {
        try { return reference.Resolve(); }
        catch { return null; }
    }

    private static TypeDefinition? FindType(ModuleDefinition module, string fullName)
    {
        foreach (var t in module.Types)
        {
            var found = FindType(t, fullName);
            if (found != null) return found;
        }
        return null;
    }

    private static TypeDefinition? FindType(TypeDefinition type, string fullName)
    {
        if (type.FullName == fullName) return type;
        foreach (var n in type.NestedTypes)
        {
            var found = FindType(n, fullName);
            if (found != null) return found;
        }
        return null;
    }
}
