package opvm;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.LongBinaryOperator;

/**
 * opvm 的字节码解释器。
 *
 * <p>状态：操作数栈（{@code long}）、变量表（名字 → {@code long}）、调用栈（返回地址）。
 * {@link #run(Program)} 每次运行前会重置全部状态，因此同一个实例可以反复运行。
 *
 * <p>分派是<b>表驱动</b>的：每条指令（内置与自定义一视同仁）在分派表里占一个
 * {@link Op} 执行体，核心 {@code run} 循环只做「按编号查表 → 执行」，不依赖任何固定
 * 指令表；新增指令只走 {@link #register(Opcode, Handler)} 注册表这一条路。
 *
 * <p>非法状态统一抛 {@link DecodeError}：栈下溢、跳转目标越界、未定义 opcode、
 * {@code CALL} 调用深度超过 {@value #MAX_CALL_DEPTH}。这些消息的原文是对外契约
 * （README 契约 2、8），不得改动。
 */
public final class Vm {

    /** 调用深度上限：第 257 次嵌套 {@code CALL} 抛出 DecodeError。 */
    public static final int MAX_CALL_DEPTH = 256;

    /** 自定义指令编号下限；低于该编号的位置永远保留给内置指令。 */
    public static final int CUSTOM_OPCODE_BASE = 128;

    /** 自定义指令的执行体。注册者通过 {@link Vm} 暴露的栈操作读写操作数栈。 */
    @FunctionalInterface
    public interface Handler {
        void execute(Vm vm, Program.Instruction ins);
    }

    /**
     * 分派表里的执行体：内置指令与已注册的自定义指令在 {@code run} 看来没有区别。
     *
     * <p>控制流方法（跳转、停机、调用栈）仅内置指令在本类内部使用；外部注册的
     * {@link Handler} 只能通过 {@link Vm} 暴露的栈操作读写机器。
     */
    @FunctionalInterface
    private interface Op {
        void execute(Program.Instruction ins);
    }

    /** 分派表的一行：指令定义 + 执行体。 */
    private record Entry(Opcode opcode, Op op) {
    }

    private final Deque<Long> stack = new ArrayDeque<>();
    private final Map<String, Long> vars = new LinkedHashMap<>();
    private final Deque<Integer> callStack = new ArrayDeque<>();

    /**
     * 当前生效的分派表，按 opcode 编号升序（{@link TreeMap} 保证顺序与注册顺序无关，
     * 满足契约 9 的纯确定性）。内置指令与自定义指令同住一张表。
     */
    private final TreeMap<Integer, Entry> dispatch = new TreeMap<>();

    /** 当前指令的定义，供统一栈校验构造消息用；未定义 opcode 时为 {@code null}。 */
    private Opcode current;

    private int pc;
    private int nextPc;
    private int programSize;
    private boolean halted;

    public Vm() {
        installBuiltins();
    }

    /**
     * 运行程序直到 {@code HALT} 或指令耗尽，返回最终操作数栈的文本表示
     * （自栈底到栈顶、以 {@code ,} 分隔；空栈返回空串）。
     */
    public String run(Program program) {
        stack.clear();
        vars.clear();
        callStack.clear();
        pc = 0;
        programSize = program.size();
        halted = false;

        while (pc < program.size() && !halted) {
            Program.Instruction ins = program.at(pc);
            int number = ins.number();
            nextPc = pc + 1;

            Entry entry = dispatch.get(number);
            if (entry == null) {
                current = Opcode.byNumber(number);
                throw new DecodeError("未定义 opcode：" + number);
            }
            current = entry.opcode();
            entry.op().execute(ins);
            pc = nextPc;
        }
        return stackText();
    }

    /**
     * 注册一条自定义指令（编号 128 及以上）。注册后的指令立即进入分派表，可被
     * {@link #run(Program)} 正常分派；注册不会触碰任何内置指令（契约 7）。
     */
    public void register(Opcode opcode, Handler handler) {
        if (opcode == null || handler == null) {
            throw new IllegalArgumentException("opcode 与 handler 都不能为空");
        }
        if (opcode.number() < CUSTOM_OPCODE_BASE) {
            throw new IllegalArgumentException(
                    "自定义指令编号必须 >= " + CUSTOM_OPCODE_BASE + "，实际 " + opcode.number());
        }
        define(opcode, ins -> handler.execute(this, ins));
    }

    /**
     * 当前生效的分派表视图，按 opcode 数值升序；内置指令与已注册的自定义指令
     * 内容保持一致（契约 6）。
     */
    public List<Opcode> dispatchTable() {
        List<Opcode> table = new ArrayList<>(dispatch.size());
        for (Entry entry : dispatch.values()) {
            table.add(entry.opcode());
        }
        return table;
    }

    // ------------------------------------------------------------ 栈操作（Handler 可用）

    public void push(long value) {
        stack.push(value);
    }

    public long pop() {
        requireOperands(1);
        return stack.pop();
    }

    public long peek() {
        requireOperands(1);
        return stack.peek();
    }

    public int stackSize() {
        return stack.size();
    }

    // ------------------------------------------------------------ 内置指令

    /**
     * 内置指令也通过同一张分派表安装：{@code run} 的热路径里不存在「内置」与
     * 「自定义」之分，新增指令永远只需要注册一行。
     */
    private void installBuiltins() {
        define(Opcode.PUSH, ins -> push(ins.argLong()));
        define(Opcode.POP, ins -> pop());
        define(Opcode.ADD, ins -> binaryApply((a, b) -> a + b));
        define(Opcode.SUB, ins -> binaryApply((a, b) -> a - b));
        define(Opcode.MUL, ins -> binaryApply((a, b) -> a * b));
        define(Opcode.DUP, ins -> {
            requireOperands(1);
            push(stack.peek());
        });
        define(Opcode.SWAP, ins -> {
            requireOperands(2);
            long top = stack.pop();
            long under = stack.pop();
            push(top);
            push(under);
        });
        define(Opcode.JMP, ins -> jump(ins.argInt()));
        define(Opcode.JZ, ins -> {
            long cond = pop();
            if (cond == 0L) {
                jump(ins.argInt());
            }
        });
        define(Opcode.LOAD, ins -> stack.push(vars.getOrDefault(ins.arg(), 0L)));
        define(Opcode.STORE, ins -> vars.put(ins.arg(), pop()));
        define(Opcode.CALL, ins -> {
            int target = ins.argInt();
            checkTarget(target);
            if (callStack.size() >= MAX_CALL_DEPTH) {
                throw new DecodeError("调用深度超过上限：" + MAX_CALL_DEPTH);
            }
            callStack.push(nextPc);
            nextPc = target;
        });
        define(Opcode.RET, ins -> {
            if (callStack.isEmpty()) {
                throw new DecodeError("调用栈下溢：RET 时调用栈为空");
            }
            nextPc = callStack.pop();
        });
        define(Opcode.HALT, ins -> halted = true);
    }

    /** 往分派表登记一行。内置指令与自定义指令走同一个入口。 */
    private void define(Opcode opcode, Op op) {
        dispatch.put(opcode.number(), new Entry(opcode, op));
    }

    /** 二元算术指令的统一执行体：先统一校验两个操作数，再弹栈计算。 */
    private void binaryApply(LongBinaryOperator op) {
        requireOperands(2);
        long b = stack.pop();
        long a = stack.pop();
        push(op.applyAsLong(a, b));
    }

    // ------------------------------------------------------------ 统一校验路径

    /**
     * 唯一的栈下溢校验入口：内置指令与自定义指令的操作数不足都在这里抛出同一句式的
     * {@link DecodeError}，消息原文为契约（README 契约 2、8）。
     */
    private void requireOperands(int needed) {
        if (stack.size() < needed) {
            throw new DecodeError(
                    "栈下溢：" + currentMnemonic() + " 需要 " + needed + " 个操作数，实际 " + stack.size());
        }
    }

    private String currentMnemonic() {
        return current == null ? "?" : current.mnemonic();
    }

    /** 跳转（含 {@code JMP} / {@code JZ} 命中）的统一越界校验，消息原文为契约。 */
    private void jump(int target) {
        checkTarget(target);
        nextPc = target;
    }

    private void checkTarget(int target) {
        if (target < 0 || target >= programSize) {
            throw new DecodeError("跳转目标越界：" + target + "（程序长度 " + programSize + "）");
        }
    }

    private String stackText() {
        StringBuilder sb = new StringBuilder();
        Iterator<Long> it = stack.descendingIterator();
        while (it.hasNext()) {
            if (sb.length() > 0) {
                sb.append(',');
            }
            sb.append(it.next());
        }
        return sb.toString();
    }
}
