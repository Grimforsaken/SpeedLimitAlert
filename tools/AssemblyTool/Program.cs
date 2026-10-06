using dnlib.DotNet;
using dnlib.DotNet.Emit;

if (args.Length < 2)
{
    Console.Error.WriteLine("Usage: AssemblyTool inspect <dll> [pattern] | dump <dll> <type-or-method-pattern>");
    return 2;
}

var cmd = args[0].ToLowerInvariant();
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
            else if (ins.Operand is IField f) operand = f.FullName;
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
