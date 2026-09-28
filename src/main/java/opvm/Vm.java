package opvm;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * opvm 的字节码解释器。
 *
 * <p>状态：操作数栈（{@code long}）、变量表（名字 → {@code long}）、调用栈（返回地址）。
 * {@link #run(Program)} 每次运行前会重置全部状态，因此同一个实例可以反复运行。
 *
 * <p>分派是<b>表驱动</b>的：内置指令在构造时与自定义指令一样通过
 * {@link #install(Opcode, Handler)} 进入同一张分派表，{@link #run} 的核心循环只按
 * opcode 编号查表，不依赖固定指令表；{@link #register} 只是往同一张表里追加条目。
 *
 * <p>非法状态统一抛 {@link DecodeError}：栈下溢、跳转目标越界、未定义 opcode、
 * {@code CALL} 调用深度超过 {@value #MAX_CALL_DEPTH}。所有「需要 k 个操作数」的检查
 * 只走 {@link #requireOperands} 一条路径，消息原文是对外契约（README 契约 2、8），
 * 不得改动。
 */
public final class Vm {

    /** 调用深度上限：第 257 次嵌套 {@code CALL} 抛出 DecodeError。 */
    public static final int MAX_CALL_DEPTH = 256;

    /** 自定义指令的执行体。注册者通过 {@link Vm} 暴露的栈操作读写操作数栈。 */
    @FunctionalInterface
    public interface Handler {
        void execute(Vm vm, Program.Instruction ins);
    }

    /** 分派表条目：指令定义与其执行体一一对应。 */
    private static final class Entry {
        private final Opcode opcode;
        private final Handler handler;

        private Entry(Opcode opcode, Handler handler) {
            this.opcode = opcode;
            this.handler = handler;
        }
    }

    // ------------------------------------------------------------ 内置指令执行体
    // 不捕获任何状态；状态全部挂在 Vm 实例上，因此同一张表可服务任意多次 run。

    private static final Handler PUSH_HANDLER =
            (vm, ins) -> vm.push(Long.parseLong(ins.arg()));

    private static final Handler POP_HANDLER = (vm, ins) -> vm.pop();

    private static final Handler DUP_HANDLER = (vm, ins) -> {
        vm.requireOperands(1);
        vm.push(vm.peek());
    };

    private static final Handler SWAP_HANDLER = (vm, ins) -> {
        vm.requireOperands(2);
        long top = vm.stack.pop();
        long under = vm.stack.pop();
        vm.push(top);
        vm.push(under);
    };

    private static final Handler ADD_HANDLER = (vm, ins) -> {
        vm.requireOperands(2);
        long b = vm.stack.pop();
        long a = vm.stack.pop();
        vm.push(a + b);
    };

    private static final Handler SUB_HANDLER = (vm, ins) -> {
        vm.requireOperands(2);
        long b = vm.stack.pop();
        long a = vm.stack.pop();
        vm.push(a - b);
    };

    private static final Handler MUL_HANDLER = (vm, ins) -> {
        vm.requireOperands(2);
        long b = vm.stack.pop();
        long a = vm.stack.pop();
        vm.push(a * b);
    };

    private static final Handler JMP_HANDLER =
            (vm, ins) -> vm.jump(ins.argInt());

    private static final Handler JZ_HANDLER = (vm, ins) -> {
        vm.requireOperands(1);
        long cond = vm.stack.pop();
        if (cond == 0L) {
            vm.jump(ins.argInt());
        }
    };

    private static final Handler CALL_HANDLER =
            (vm, ins) -> vm.call(ins.argInt());

    private static final Handler RET_HANDLER = (vm, ins) -> {
        if (vm.callStack.isEmpty()) {
            throw new DecodeError("调用栈下溢：RET 时调用栈为空");
        }
        vm.nextPc = vm.callStack.pop();
    };

    private static final Handler LOAD_HANDLER =
            (vm, ins) -> vm.stack.push(vm.vars.getOrDefault(ins.arg(), 0L));

    private static final Handler STORE_HANDLER = (vm, ins) -> {
        vm.requireOperands(1);
        vm.vars.put(ins.arg(), vm.stack.pop());
    };

    private static final Handler HALT_HANDLER = (vm, ins) -> vm.halted = true;

    // ------------------------------------------------------------ 运行期状态

    private final Deque<Long> stack = new ArrayDeque<>();
    private final Map<String, Long> vars = new java.util.LinkedHashMap<>();
    private final Deque<Integer> callStack = new ArrayDeque<>();

    /**
     * 当前生效的分派表。用 {@link TreeMap} 按 opcode 编号排序，
     * {@link #dispatchTable()} 的升序视图直接来自它的迭代顺序。
     */
    private final TreeMap<Integer, Entry> table = new TreeMap<>();

    /** 当前正在执行的指令定义，供统一栈校验构造消息；自定义指令时为注册的 Opcode。 */
    private Opcode current;
    /** 当前运行的程序，供跳转目标越界校验取程序长度。 */
    private Program program;
    /** 当前指令执行后下一条要取的指令下标；跳转与 RET 通过它改写流程。 */
    private int nextPc;
    private boolean halted;

    public Vm() {
        install(Opcode.PUSH, PUSH_HANDLER);
        install(Opcode.POP, POP_HANDLER);
        install(Opcode.ADD, ADD_HANDLER);
        install(Opcode.SUB, SUB_HANDLER);
        install(Opcode.MUL, MUL_HANDLER);
        install(Opcode.DUP, DUP_HANDLER);
        install(Opcode.SWAP, SWAP_HANDLER);
        install(Opcode.JMP, JMP_HANDLER);
        install(Opcode.JZ, JZ_HANDLER);
        install(Opcode.LOAD, LOAD_HANDLER);
        install(Opcode.STORE, STORE_HANDLER);
        install(Opcode.CALL, CALL_HANDLER);
        install(Opcode.RET, RET_HANDLER);
        install(Opcode.HALT, HALT_HANDLER);
    }

    /**
     * 运行程序直到 {@code HALT} 或指令耗尽，返回最终操作数栈的文本表示
     * （自栈底到栈顶、以 {@code ,} 分隔；空栈返回空串）。
     */
    public String run(Program program) {
        stack.clear();
        vars.clear();
        callStack.clear();
        this.program = program;
        this.halted = false;

        int pc = 0;
        while (pc < program.size() && !halted) {
            Program.Instruction ins = program.at(pc);
            int n = ins.number();
            Entry entry = table.get(n);
            if (entry == null) {
                throw new DecodeError("未定义 opcode：" + n);
            }
            current = entry.opcode;
            nextPc = pc + 1;
            entry.handler.execute(this, ins);
            pc = nextPc;
        }
        return stackText();
    }

    /**
     * 注册一条自定义指令（编号 128 及以上）。注册即进入与内置指令同一张分派表，
     * 之后的 {@link #run} 按编号直接分派到它。
     */
    public void register(Opcode opcode, Handler handler) {
        if (opcode == null || handler == null) {
            throw new IllegalArgumentException("opcode 与 handler 都不能为空");
        }
        install(opcode, handler);
    }

    /**
     * 当前生效的分派表视图：内置指令与已注册的自定义指令合并，按 opcode 数值升序。
     */
    public List<Opcode> dispatchTable() {
        List<Opcode> out = new ArrayList<>(table.size());
        for (Entry entry : table.values()) {
            out.add(entry.opcode);
        }
        return Collections.unmodifiableList(out);
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

    // ------------------------------------------------------------ 内部

    /** 往分派表写入一条目（内置与自定义指令的唯一入口）。 */
    private void install(Opcode opcode, Handler handler) {
        table.put(opcode.number(), new Entry(opcode, handler));
    }

    /**
     * 唯一的操作数个数校验入口：所有内置指令与自定义指令的栈下溢消息都在这里构造，
     * 助记符取当前指令的定义。
     */
    private void requireOperands(int wanted) {
        if (stack.size() < wanted) {
            throw new DecodeError("栈下溢：" + current.mnemonic()
                    + " 需要 " + wanted + " 个操作数，实际 " + stack.size());
        }
    }

    /** 跳转到目标下标（JMP / JZ 命中时），先做越界校验再改写 pc。 */
    private void jump(int target) {
        checkTarget(target);
        nextPc = target;
    }

    /** CALL：先校验目标，再校验调用深度，最后压入返回地址并跳转。 */
    private void call(int target) {
        checkTarget(target);
        if (callStack.size() >= MAX_CALL_DEPTH) {
            throw new DecodeError("调用深度超过上限：" + MAX_CALL_DEPTH);
        }
        callStack.push(nextPc);
        nextPc = target;
    }

    private void checkTarget(int target) {
        if (target < 0 || target >= program.size()) {
            throw new DecodeError("跳转目标越界：" + target + "（程序长度 " + program.size() + "）");
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
