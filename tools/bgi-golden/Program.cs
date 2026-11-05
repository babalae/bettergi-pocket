using System.IO;
using System.Reflection;
using System.Runtime.CompilerServices;
using System.Runtime.Loader;

namespace BgiGolden;

/// <summary>
/// 入口只负责把 BetterGI 编译目录挂到程序集解析路径上，真正的逻辑放在 <see cref="Generator"/>，
/// 避免 JIT 编译 Main 时提前解析 BetterGI 类型。
/// </summary>
public static class Program
{
    public static int Main(string[] args)
    {
        var options = Options.Parse(args);
        var nativeDir = Path.Combine(options.BgiBin, "runtimes", "win-x64", "native");
        Environment.SetEnvironmentVariable("PATH", nativeDir + ";" + options.BgiBin + ";" + Environment.GetEnvironmentVariable("PATH"));
        AssemblyLoadContext.Default.Resolving += (_, name) =>
        {
            var candidate = Path.Combine(options.BgiBin, name.Name + ".dll");
            return File.Exists(candidate) ? AssemblyLoadContext.Default.LoadFromAssemblyPath(candidate) : null;
        };
        return Run(options);
    }

    [MethodImpl(MethodImplOptions.NoInlining)]
    private static int Run(Options options) => Generator.Run(options);
}

public sealed record Options(string BgiBin, string TestAssets, string OutDir, int SiftGlobalLimit)
{
    public static Options Parse(string[] args)
    {
        string bgiBin = @"E:\HuiPrograming\MainProject\better-genshin-impact\BetterGenshinImpact\bin\Debug\net10.0-windows10.0.22621.0";
        string testAssets = @"E:\HuiPrograming\MainProject\better-genshin-impact\Test\BetterGenshinImpact.UnitTest\Assets";
        // 默认在仓库根目录运行
        string outDir = Path.Combine(Directory.GetCurrentDirectory(), "app", "src", "test", "resources", "pathing", "golden");
        int siftGlobalLimit = 3;
        for (var i = 0; i < args.Length - 1; i++)
        {
            switch (args[i])
            {
                case "--bgi": bgiBin = args[++i]; break;
                case "--tests": testAssets = args[++i]; break;
                case "--out": outDir = args[++i]; break;
                case "--sift-global": siftGlobalLimit = int.Parse(args[++i]); break;
            }
        }
        return new Options(bgiBin, testAssets, outDir, siftGlobalLimit);
    }
}
