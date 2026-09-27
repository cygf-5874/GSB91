package opvm;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * opvm 的字节码解释器。
 *
 * <p>状态：操作数栈（{@code long}）、变量表（名字 → {@code long}）、调用栈（返回地址）。
 * {@link #run(Program)} 每次运行前会重置全部状态，因此同一个实例可以反复运行。
 *
 * <p>非法状态统一抛 {@link DecodeError}：栈下溢、跳转目标越界、未定义 opcode、
 * {@code CALL} 调用深度超过 {@value #MAX_CALL_DEPTH}。这些消息的原文是对外契约
 * （README 契约 2、8），不得改动。
 */
public final class Vm {

    /** 调用深度上限：第 257 次嵌套 {@code CALL} 抛出 DecodeError。 */
    public static final int MAX_CALL_DEPTH = 256;

    /** 自定义指令的执行体。注册者通过 {@link Vm} 暴露的栈操作读写操作数栈。 */
    @FunctionalInterface
    public interface Handler {
        void execute(Vm vm, Program.Instruction ins);
    }

    private final Deque<Long> stack = new ArrayDeque<>();
    private final Map<String, Long> vars = new LinkedHashMap<>();
    private final Deque<Integer> callStack = new ArrayDeque<>();

    /** 已注册的自定义指令（编号 → 定义）。当前分派路径不读取它。 */
    private final Map<Integer, Opcode> registeredOpcodes = new LinkedHashMap<>();
    /** 已注册的自定义指令执行体。 */
    private final Map<Integer, Handler> registeredHandlers = new LinkedHashMap<>();

    /** 当前指令对应的内置 Opcode，供栈校验构造消息用；自定义指令时为 null。 */
    private Opcode current;

    private int pc;
    private boolean halted;

    /**
     * 运行程序直到 {@code HALT} 或指令耗尽，返回最终操作数栈的文本表示
     * （自栈底到栈顶、以 {@code ,} 分隔；空栈返回空串）。
     */
    public String run(Program program) {
        stack.clear();
        vars.clear();
        callStack.clear();
        pc = 0;
        halted = false;

        while (pc < program.size() && !halted) {
            Program.Instruction ins = program.at(pc);
            int n = ins.number();
            current = Opcode.byNumber(n);
            int next = pc + 1;

            switch (n) {
                case 1: { // PUSH n
                    push(Long.parseLong(ins.arg()));
                    break;
                }
                case 2: { // POP
                    pop();
                    break;
                }
                case 6: { // DUP
                    if (stack.size() < 1) {
                        throw new DecodeError("栈下溢：DUP 需要 1 个操作数，实际 " + stack.size());
                    }
                    push(stack.peek());
                    break;
                }
                case 7: { // SWAP
                    if (stack.size() < 2) {
                        throw new DecodeError("栈下溢：SWAP 需要 2 个操作数，实际 " + stack.size());
                    }
                    long top = stack.pop();
                    long under = stack.pop();
                    push(top);
                    push(under);
                    break;
                }
                case 3: case 4: case 5: { // ADD / SUB / MUL
                    if (stack.size() < 2) {
                        throw new DecodeError("栈下溢：" + current.mnemonic() + " 需要 2 个操作数，实际 " + stack.size());
                    }
                    long b = stack.pop();
                    long a = stack.pop();
                    switch (n) {
                        case 3: push(a + b); break;
                        case 4: push(a - b); break;
                        default: push(a * b); break;
                    }
                    break;
                }
                case 8: { // JMP a
                    int target = ins.argInt();
                    checkTarget(target, program);
                    next = target;
                    break;
                }
                case 9: { // JZ a
                    if (stack.size() < 1) {
                        throw new DecodeError("栈下溢：JZ 需要 1 个操作数，实际 " + stack.size());
                    }
                    long cond = stack.pop();
                    if (cond == 0L) {
                        int target = ins.argInt();
                        checkTarget(target, program);
                        next = target;
                    }
                    break;
                }
                case 12: { // CALL a
                    int target = ins.argInt();
                    checkTarget(target, program);
                    if (callStack.size() >= MAX_CALL_DEPTH) {
                        throw new DecodeError("调用深度超过上限：" + MAX_CALL_DEPTH);
                    }
                    callStack.push(next);
                    next = target;
                    break;
                }
                case 13: { // RET
                    if (callStack.isEmpty()) {
                        throw new DecodeError("调用栈下溢：RET 时调用栈为空");
                    }
                    next = callStack.pop();
                    break;
                }
                case 10: { // LOAD s（未赋值变量按 0 处理）
                    stack.push(vars.getOrDefault(ins.arg(), 0L));
                    break;
                }
                case 11: { // STORE s
                    if (stack.size() < 1) {
                        throw new DecodeError("栈下溢：STORE 需要 1 个操作数，实际 " + stack.size());
                    }
                    vars.put(ins.arg(), stack.pop());
                    break;
                }
                case 14: { // HALT
                    halted = true;
                    break;
                }
                default: {
                    throw new DecodeError("未定义 opcode：" + n);
                }
            }
            pc = next;
        }
        return stackText();
    }

    /**
     * 注册一条自定义指令（编号 128 及以上）。
     */
    public void register(Opcode opcode, Handler handler) {
        if (opcode == null || handler == null) {
            throw new IllegalArgumentException("opcode 与 handler 都不能为空");
        }
        registeredOpcodes.put(opcode.number(), opcode);
        registeredHandlers.put(opcode.number(), handler);
    }

    /** 当前生效的分派表视图。 */
    public List<Opcode> dispatchTable() {
        return Opcode.list();
    }

    // ------------------------------------------------------------ 栈操作（Handler 可用）

    public void push(long value) {
        stack.push(value);
    }

    public long pop() {
        if (stack.isEmpty()) {
            throw new DecodeError("栈下溢：" + currentMnemonic() + " 需要 1 个操作数，实际 " + stack.size());
        }
        return stack.pop();
    }

    public long peek() {
        if (stack.isEmpty()) {
            throw new DecodeError("栈下溢：" + currentMnemonic() + " 需要 1 个操作数，实际 " + stack.size());
        }
        return stack.peek();
    }

    public int stackSize() {
        return stack.size();
    }

    // ------------------------------------------------------------ 内部

    private String currentMnemonic() {
        return current == null ? "?" : current.mnemonic();
    }

    private void checkTarget(int target, Program program) {
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
