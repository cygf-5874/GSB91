package opvm;

import java.util.ArrayList;
import java.util.List;

/**
 * opvm 的既有用例（15 个），覆盖全部指令与全部错误消息原文。
 *
 * <p>不依赖 JUnit：静态方法加 {@code main} 聚合自跑，全部通过时打印 {@code 15/15 passed} 并以
 * 退出码 0 结束；有失败时打印失败清单并以退出码 1 结束。
 */
public final class VmTest {

    private interface Body {
        /** 返回 null 表示通过，否则返回失败原因。 */
        String run();
    }

    private static int passed = 0;
    private static final List<String> failures = new ArrayList<>();

    public static void main(String[] args) {
        run("push-halt", VmTest::testPushHalt);
        run("pop", VmTest::testPop);
        run("add", VmTest::testAdd);
        run("sub", VmTest::testSub);
        run("mul", VmTest::testMul);
        run("dup", VmTest::testDup);
        run("swap", VmTest::testSwap);
        run("jmp", VmTest::testJmp);
        run("jz-taken-and-not-taken", VmTest::testJz);
        run("load-store", VmTest::testLoadStore);
        run("call-ret", VmTest::testCallRet);
        run("err-stack-underflow", VmTest::testUnderflowMessage);
        run("err-jump-out-of-range", VmTest::testJumpOutOfRangeMessage);
        run("err-undefined-opcode", VmTest::testUndefinedOpcodeMessage);
        run("err-call-depth", VmTest::testCallDepthMessage);

        System.out.println(passed + "/15 passed");
        if (!failures.isEmpty()) {
            for (String f : failures) {
                System.out.println("FAIL " + f);
            }
            System.exit(1);
        }
    }

    private static void run(String name, Body body) {
        try {
            String err = body.run();
            if (err == null) {
                passed++;
            } else {
                failures.add(name + "：" + err);
            }
        } catch (Throwable t) {
            failures.add(name + "：抛出 " + t);
        }
    }

    // ---------------------------------------------------------------- 正常路径

    private static String testPushHalt() {
        return expect("push-halt 输出", exec("PUSH 42\nHALT"), "42");
    }

    private static String testPop() {
        return expect("pop 输出", exec("PUSH 1\nPUSH 2\nPOP\nHALT"), "1");
    }

    private static String testAdd() {
        return expect("add 输出", exec("PUSH 2\nPUSH 3\nADD\nHALT"), "5");
    }

    private static String testSub() {
        return expect("sub 输出（被减数在下、减数在上）", exec("PUSH 10\nPUSH 4\nSUB\nHALT"), "6");
    }

    private static String testMul() {
        return expect("mul 输出", exec("PUSH 6\nPUSH 7\nMUL\nHALT"), "42");
    }

    private static String testDup() {
        return expect("dup 输出", exec("PUSH 5\nDUP\nHALT"), "5,5");
    }

    private static String testSwap() {
        return expect("swap 输出", exec("PUSH 1\nPUSH 2\nSWAP\nHALT"), "2,1");
    }

    private static String testJmp() {
        return expect("jmp 跳过中间指令", exec("PUSH 1\nJMP 3\nPUSH 99\nHALT"), "1");
    }

    private static String testJz() {
        String taken = exec("PUSH 0\nJZ 3\nPUSH 99\nHALT");
        String e = expect("jz 条件为 0 时跳转", taken, "");
        if (e != null) {
            return e;
        }
        return expect("jz 条件非 0 时不跳转", exec("PUSH 5\nJZ 4\nPUSH 42\nHALT"), "42");
    }

    private static String testLoadStore() {
        return expect("load/store 读写变量",
                exec("PUSH 10\nSTORE x\nPUSH 4\nSTORE y\nLOAD x\nLOAD y\nADD\nHALT"), "14");
    }

    private static String testCallRet() {
        return expect("call/ret 调用子过程",
                exec("PUSH 7\nCALL 3\nHALT\nDUP\nPUSH 2\nMUL\nRET"), "7,14");
    }

    // ---------------------------------------------------------------- 错误路径

    private static String testUnderflowMessage() {
        String e = expectError("ADD 栈下溢消息", "PUSH 1\nADD", "栈下溢：ADD 需要 2 个操作数，实际 1");
        if (e != null) {
            return e;
        }
        return expectError("DUP 栈下溢消息", "DUP", "栈下溢：DUP 需要 1 个操作数，实际 0");
    }

    private static String testJumpOutOfRangeMessage() {
        return expectError("跳转越界消息", "JMP 9", "跳转目标越界：9（程序长度 1）");
    }

    private static String testUndefinedOpcodeMessage() {
        return expectError("未定义 opcode 消息", "PUSH 1\n77", "未定义 opcode：77");
    }

    private static String testCallDepthMessage() {
        return expectError("调用深度上限消息", "CALL 0", "调用深度超过上限：256");
    }

    // ---------------------------------------------------------------- 工具

    private static String exec(String source) {
        return new Vm().run(Program.parse(source));
    }

    private static String expect(String label, String actual, String wanted) {
        if (wanted.equals(actual)) {
            return null;
        }
        return label + " 期望=" + wanted + " 实际=" + actual;
    }

    private static String expectError(String label, String source, String wantedMessage) {
        try {
            String out = exec(source);
            return label + " 期望=抛 DecodeError(" + wantedMessage + ") 实际=正常返回 " + out;
        } catch (DecodeError err) {
            if (wantedMessage.equals(err.getMessage())) {
                return null;
            }
            return label + " 期望消息=" + wantedMessage + " 实际消息=" + err.getMessage();
        } catch (Throwable t) {
            return label + " 期望=DecodeError 实际=" + t;
        }
    }

    private VmTest() {
    }
}
