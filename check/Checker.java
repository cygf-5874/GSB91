import opvm.DecodeError;
import opvm.Opcode;
import opvm.Program;
import opvm.Vm;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * opvm 的固定验收程序（勿改）。
 *
 * <p>10 个场景覆盖 README「对外契约」的结构与行为：corpus 3 / extensible 3 / structure 2 / robust 2。
 * 每个场景都在**独立 JVM 子进程**里跑（{@link ProcessBuilder}），带 30 秒看门狗，超时判不通过；
 * 某个场景失败不会遮蔽其余场景（失败不早退）。判据全部确定性：没有随机源、没有墙钟、
 * 没有性能门槛；涉及顺序的断言只依赖 opcode 数值升序这条写死的规则。
 *
 * <p>用法：java -cp out;out-check Checker [-list] [--only &lt;组名&gt;[,&lt;组名&gt;...]]
 */
public final class Checker {

    /** 单场景看门狗（秒）。 */
    private static final long WATCHDOG_SECONDS = 30L;

    private interface Body {
        /** 返回 null 表示 PASS，否则返回可判定的失败原因（内含 期望=… 实际=…）。 */
        String run() throws Exception;
    }

    private static final class Scenario {
        final String name;
        final String group;
        final Body body;

        Scenario(String name, Body body) {
            this.name = name;
            this.body = body;
            int slash = name.indexOf('/');
            this.group = slash < 0 ? name : name.substring(0, slash);
        }
    }

    public static void main(String[] args) throws Exception {
        String scenarioOnly = null;
        List<String> onlyGroups = new ArrayList<>();
        boolean list = false;
        for (int i = 0; i < args.length; i++) {
            String a = args[i];
            if ("-list".equals(a)) {
                list = true;
            } else if ("--only".equals(a)) {
                if (i + 1 >= args.length) {
                    System.out.println("--only 需要一个组名");
                    System.exit(2);
                }
                for (String g : args[++i].split(",")) {
                    if (!g.isEmpty()) {
                        onlyGroups.add(g);
                    }
                }
            } else if ("--scenario".equals(a)) {
                if (i + 1 >= args.length) {
                    System.out.println("--scenario 需要一个场景名");
                    System.exit(2);
                }
                scenarioOnly = args[++i];
            } else {
                System.out.println("未知参数：" + a);
                System.exit(2);
            }
        }

        List<Scenario> all = scenarios();

        if (list) {
            for (Scenario s : all) {
                System.out.println(s.name);
            }
            return;
        }

        if (scenarioOnly != null) {
            System.exit(runOne(all, scenarioOnly));
        }

        List<Scenario> selected = new ArrayList<>();
        for (Scenario s : all) {
            if (onlyGroups.isEmpty() || onlyGroups.contains(s.group)) {
                selected.add(s);
            }
        }
        if (selected.isEmpty()) {
            System.out.println("没有匹配的场景（--only " + onlyGroups + "）");
            System.exit(2);
        }

        int pass = 0;
        for (Scenario s : selected) {
            String line = runInChildProcess(s);
            System.out.println(line);
            if (line.startsWith("PASS ")) {
                pass++;
            }
        }
        System.out.println("结果：通过 " + pass + "/" + selected.size());
        if (pass != selected.size()) {
            System.exit(1);
        }
    }

    // ------------------------------------------------- 子进程调度（一场景一 JVM）

    /** 在独立子进程里跑单个场景，把子进程给出的 PASS/FAIL 行原样返回。 */
    private static String runInChildProcess(Scenario s) {
        String name = s.name;
        String javaBin = Paths.get(System.getProperty("java.home"), "bin", "java").toString();
        String classpath = System.getProperty("java.class.path");
        ProcessBuilder pb = new ProcessBuilder(javaBin,
                "-Dfile.encoding=UTF-8", "-Dsun.stdout.encoding=UTF-8", "-Dsun.stderr.encoding=UTF-8",
                "-cp", classpath, "Checker", "--scenario", name);
        pb.redirectErrorStream(true);

        Process proc;
        try {
            proc = pb.start();
        } catch (Exception ex) {
            return "FAIL " + name + "  期望=能在独立 JVM 子进程里跑该场景 实际=子进程启动失败：" + ex;
        }

        StringBuilder sink = new StringBuilder();
        Thread reader = new Thread(() -> {
            try (InputStream in = proc.getInputStream()) {
                byte[] buf = new byte[4096];
                int n;
                while ((n = in.read(buf)) >= 0) {
                    String chunk = new String(buf, 0, n, StandardCharsets.UTF_8);
                    synchronized (sink) {
                        sink.append(chunk);
                    }
                }
            } catch (Exception ignored) {
                // 子进程被强杀时读流会失败，忽略即可
            }
        }, "checker-child-reader");
        reader.setDaemon(true);
        reader.start();

        boolean finished;
        try {
            finished = proc.waitFor(WATCHDOG_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            finished = false;
        }
        if (!finished) {
            proc.destroyForcibly();
            try {
                reader.join(2000L);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            return "FAIL " + name + "  期望=场景在 " + WATCHDOG_SECONDS + " 秒内给出 PASS/FAIL 实际="
                    + WATCHDOG_SECONDS + " 秒看门狗超时（子进程已强杀）";
        }
        try {
            reader.join(5000L);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }

        String out;
        synchronized (sink) {
            out = sink.toString().trim();
        }
        String line = null;
        for (String l : out.split("\n")) {
            String t = l.trim();
            if (t.startsWith("PASS ") || t.startsWith("FAIL ")) {
                line = t;
            }
        }
        if (line == null) {
            String shown = out.isEmpty() ? "(无输出)" : out.replace('\n', ' ');
            return "FAIL " + name + "  期望=子进程打印 PASS/FAIL 行 实际=退出码 "
                    + (proc.isAlive() ? "未知（仍存活）" : String.valueOf(proc.exitValue())) + "，输出=" + shown;
        }
        return line;
    }

    /** 子进程模式：就地跑一个场景，打印一行 PASS/FAIL，返回退出码。 */
    private static int runOne(List<Scenario> all, String name) {
        for (Scenario s : all) {
            if (!s.name.equals(name)) {
                continue;
            }
            String err;
            try {
                err = s.body.run();
            } catch (Throwable t) {
                err = "检查过程抛异常：" + t;
            }
            if (err == null) {
                System.out.println("PASS " + s.name);
                return 0;
            }
            if (!err.contains("期望=")) {
                err = "期望=场景 " + s.name + " 通过 实际=" + err;
            }
            System.out.println("FAIL " + s.name + "  " + err);
            return 1;
        }
        System.out.println("FAIL " + name + "  期望=场景名存在于固定件 实际=未找到该场景");
        return 1;
    }

    // ------------------------------------------------------------- 场景表

    private static List<Scenario> scenarios() {
        List<Scenario> out = new ArrayList<>();

        // ------------------------------------------------ corpus（3）：行为等价

        out.add(new Scenario("corpus/outputs-byte-identical", () -> {
            for (String[] c : corpusCases()) {
                String name = c[0];
                String ext = c[1];
                if (!"out".equals(ext)) {
                    continue;
                }
                byte[] wanted = Files.readAllBytes(corpusPath(name, "out"));
                String src = readText(corpusPath(name, "opvm"));
                String actual;
                try {
                    actual = new Vm().run(Program.parse(src));
                } catch (DecodeError e) {
                    return "期望=corpus/" + name + " 正常返回 实际=抛出 DecodeError(" + e.getMessage() + ")";
                }
                String e = expectBytes("corpus/" + name + " 输出逐字节一致", wanted, actual);
                if (e != null) {
                    return e;
                }
            }
            return null;
        }));

        out.add(new Scenario("corpus/error-message-verbatim", () -> {
            for (String[] c : corpusCases()) {
                String name = c[0];
                if (!"err".equals(c[1])) {
                    continue;
                }
                byte[] wanted = Files.readAllBytes(corpusPath(name, "err"));
                String src = readText(corpusPath(name, "opvm"));
                try {
                    String actual = new Vm().run(Program.parse(src));
                    return "期望=corpus/" + name + " 抛 DecodeError 实际=正常返回 " + actual;
                } catch (DecodeError err) {
                    String e = expectBytes("corpus/" + name + " 错误消息逐字一致", wanted, err.getMessage());
                    if (e != null) {
                        return e;
                    }
                }
            }
            return null;
        }));

        out.add(new Scenario("corpus/error-type", () -> {
            for (String[] c : corpusCases()) {
                String name = c[0];
                if (!"err".equals(c[1])) {
                    continue;
                }
                String src = readText(corpusPath(name, "opvm"));
                try {
                    String actual = new Vm().run(Program.parse(src));
                    return "期望=corpus/" + name + " 抛 DecodeError 实际=正常返回 " + actual;
                } catch (DecodeError ok) {
                    // 期望的类型，继续
                } catch (Throwable other) {
                    return "期望=corpus/" + name + " 抛 DecodeError 实际=" + other.getClass().getName()
                            + "(" + other.getMessage() + ")";
                }
            }
            return null;
        }));

        // ------------------------------------------------ extensible（3）：注册自定义指令

        out.add(new Scenario("extensible/custom-opcode-runs", () -> {
            Vm vm = new Vm();
            vm.register(NEG, (v, ins) -> v.push(-v.pop()));
            String e = expectStr("注册 NEG(200) 后可执行", vm.run(Program.parse("PUSH 5\n200\nHALT")), "-5");
            if (e != null) {
                return e;
            }
            Vm vm2 = new Vm();
            vm2.register(SUM2, (v, ins) -> {
                long b = v.pop();
                long a = v.pop();
                v.push(a + b);
            });
            return expectStr("注册 SUM2(201) 后可执行", vm2.run(Program.parse("PUSH 20\nPUSH 22\n201\nHALT")), "42");
        }));

        out.add(new Scenario("extensible/dispatch-table-contains", () -> {
            Vm vm = new Vm();
            vm.register(NEG, (v, ins) -> v.push(-v.pop()));
            vm.register(SUM2, (v, ins) -> {
                long b = v.pop();
                long a = v.pop();
                v.push(a + b);
            });
            List<Opcode> table = vm.dispatchTable();
            List<Integer> numbers = new ArrayList<>();
            for (Opcode op : table) {
                numbers.add(op.number());
            }
            for (int want : new int[]{200, 201}) {
                if (!numbers.contains(want)) {
                    return "期望=dispatchTable() 含自定义编号 " + want + " 实际=" + numbers;
                }
            }
            for (int i = 1; i < numbers.size(); i++) {
                if (numbers.get(i - 1) >= numbers.get(i)) {
                    return "期望=dispatchTable() 按 opcode 升序 实际=" + numbers;
                }
            }
            return null;
        }));

        out.add(new Scenario("extensible/registration-preserves-existing", () -> {
            Vm vm = new Vm();
            String src = "PUSH 2\nPUSH 3\nADD\nHALT";
            String before = vm.run(Program.parse(src));
            vm.register(NEG, (v, ins) -> v.push(-v.pop()));
            String after = vm.run(Program.parse(src));
            String e = expectStr("注册前后同一程序输出一致", after, before);
            if (e != null) {
                return e;
            }
            if (!"5".equals(before)) {
                return "期望=基线程序输出 5 实际=" + before;
            }
            return expectStr("注册后自定义指令可执行", vm.run(Program.parse("PUSH 9\n200\nHALT")), "-9");
        }));

        // ------------------------------------------------ structure（2）：结构约束

        out.add(new Scenario("structure/opcode-list-ascending-stable", () -> {
            List<Opcode> a = Opcode.list();
            List<Opcode> b = Opcode.list();
            a.size();
            List<Integer> na = new ArrayList<>();
            for (Opcode op : a) {
                na.add(op.number());
            }
            for (int i = 1; i < na.size(); i++) {
                if (na.get(i - 1) >= na.get(i)) {
                    return "期望=Opcode.list() 按 opcode 升序 实际=" + na;
                }
            }
            List<Integer> nb = new ArrayList<>();
            for (Opcode op : b) {
                nb.add(op.number());
            }
            if (!na.equals(nb)) {
                return "期望=Opcode.list() 两次调用顺序稳定 实际=" + na + " vs " + nb;
            }
            if (na.size() < 14) {
                return "期望=Opcode.list() 含全部 14 条内置指令 实际=" + na;
            }
            for (String m : new String[]{"PUSH", "POP", "ADD", "SUB", "MUL", "DUP", "SWAP",
                    "JMP", "JZ", "LOAD", "STORE", "CALL", "RET", "HALT"}) {
                boolean found = false;
                for (Opcode op : a) {
                    if (m.equals(op.mnemonic())) {
                        found = true;
                        break;
                    }
                }
                if (!found) {
                    return "期望=Opcode.list() 含助记符 " + m + " 实际=缺失";
                }
            }
            return null;
        }));

        out.add(new Scenario("structure/dispatch-not-fixed", () -> {
            // 断言一：注册一个高位 opcode 后即可被 run 正常分派。
            Vm vm = new Vm();
            vm.register(NEG, (v, ins) -> v.push(-v.pop()));
            String e = expectStr("注册后即可分派", vm.run(Program.parse("PUSH 3\n200\nHALT")), "-3");
            if (e != null) {
                return e;
            }
            // 断言二：未注册的高位 opcode 仍报原来那条消息，说明核心分派不依赖固定指令表。
            try {
                String actual = new Vm().run(Program.parse("PUSH 1\n202\nHALT"));
                return "期望=未注册 opcode 202 抛 DecodeError 实际=正常返回 " + actual;
            } catch (DecodeError err) {
                return expectStr("未注册高位 opcode 消息不变", err.getMessage(), "未定义 opcode：202");
            }
        }));

        // ------------------------------------------------ robust（2）：错误消息原文

        out.add(new Scenario("robust/builtin-errors-verbatim", () -> {
            String[][] cases = {
                {"PUSH 1\nADD", "栈下溢：ADD 需要 2 个操作数，实际 1"},
                {"DUP", "栈下溢：DUP 需要 1 个操作数，实际 0"},
                {"POP", "栈下溢：POP 需要 1 个操作数，实际 0"},
                {"PUSH 1\nSWAP", "栈下溢：SWAP 需要 2 个操作数，实际 1"},
                {"JMP 9", "跳转目标越界：9（程序长度 1）"},
                {"PUSH 0\nJZ 5", "跳转目标越界：5（程序长度 2）"},
                {"CALL 0", "调用深度超过上限：256"},
                {"PUSH 1\n77", "未定义 opcode：77"},
            };
            for (String[] c : cases) {
                String e = expectErr("robust 消息（" + c[0].replace('\n', ';') + "）", c[0], c[1]);
                if (e != null) {
                    return e;
                }
            }
            return null;
        }));

        out.add(new Scenario("robust/registered-arity-verbatim", () -> {
            Vm vm = new Vm();
            vm.register(NEG, (v, ins) -> v.push(-v.pop()));
            // NEG 需要 1 个操作数；空栈时走统一栈校验路径，消息里必须带自定义助记符。
            return expectErr("注册指令的栈下溢消息", "200", "栈下溢：NEG 需要 1 个操作数，实际 0", vm);
        }));

        return out;
    }

    // ------------------------------------------------------------- 被测指令与工具

    private static final Opcode NEG = new Opcode(200, "NEG");
    private static final Opcode SUM2 = new Opcode(201, "SUM2");

    private static String readText(Path p) throws IOException {
        return Files.readString(p, StandardCharsets.UTF_8);
    }

    private static Path corpusPath(String name, String ext) {
        return Paths.get("check", "corpus", name + "." + ext);
    }

    /** 返回 corpus 里的「程序名 → 期望扩展名（out / err，空串表示两者都缺或都有）」。 */
    private static Map<String, String> corpusIndex() throws IOException {
        Path dir = Paths.get("check", "corpus");
        Map<String, String> index = new LinkedHashMap<>();
        List<String> names = new ArrayList<>();
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(dir, "*.opvm")) {
            for (Path p : ds) {
                names.add(p.getFileName().toString().replaceFirst("\\.opvm$", ""));
            }
        }
        Collections.sort(names);
        for (String name : names) {
            boolean hasOut = Files.exists(corpusPath(name, "out"));
            boolean hasErr = Files.exists(corpusPath(name, "err"));
            String ext = hasOut ^ hasErr ? (hasOut ? "out" : "err") : "";
            index.put(name, ext);
        }
        return index;
    }

    private static List<String[]> corpusCases() throws IOException {
        List<String[]> out = new ArrayList<>();
        for (Map.Entry<String, String> en : corpusIndex().entrySet()) {
            if (en.getValue().isEmpty()) {
                throw new IOException("corpus 用例 " + en.getKey() + " 必须恰好带一个 .out 或 .err 期望文件");
            }
            out.add(new String[]{en.getKey(), en.getValue()});
        }
        if (out.isEmpty()) {
            throw new IOException("check/corpus 下没有 .opvm 程序");
        }
        return out;
    }

    private static String expectBytes(String label, byte[] wanted, String actual) {
        byte[] got = (actual + "\n").getBytes(StandardCharsets.UTF_8);
        if (Arrays.equals(wanted, got)) {
            return null;
        }
        return label + " 期望=[" + new String(wanted, StandardCharsets.UTF_8).trim()
                + "] 实际=[" + actual + "]";
    }

    private static String expectStr(String label, String actual, String wanted) {
        if (wanted.equals(actual)) {
            return null;
        }
        return label + " 期望=" + wanted + " 实际=" + actual;
    }

    private static String expectErr(String label, String src, String wantedMsg) {
        return expectErr(label, src, wantedMsg, new Vm());
    }

    private static String expectErr(String label, String src, String wantedMsg, Vm vm) {
        try {
            String actual = vm.run(Program.parse(src));
            return label + " 期望=抛 DecodeError(" + wantedMsg + ") 实际=正常返回 " + actual;
        } catch (DecodeError err) {
            if (wantedMsg.equals(err.getMessage())) {
                return null;
            }
            return label + " 期望消息=" + wantedMsg + " 实际消息=" + err.getMessage();
        } catch (Throwable t) {
            return label + " 期望=DecodeError 实际=" + t;
        }
    }
}
