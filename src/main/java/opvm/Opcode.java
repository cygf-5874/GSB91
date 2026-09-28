package opvm;

import java.util.ArrayList;
import java.util.List;

/**
 * 一条指令的编号与助记符。
 *
 * <p>内置指令的编号是稳定契约（见 README 契约 1、3）：{@code byNumber(int)} 对同一编号
 * 永远返回同一个实例。编号 128 及以上预留给调用方通过 {@link Vm#register} 注册的自定义指令，
 * 这里不预置任何 128 以上的常量。
 */
public final class Opcode {

    // ---- 内置指令。按「功能分组」定义：栈族 → 算术族 → 控制族 → 存储族。
    //      编号与助记符的映射见 README 契约 1。

    public static final Opcode PUSH = new Opcode(1, "PUSH");
    public static final Opcode POP = new Opcode(2, "POP");
    public static final Opcode DUP = new Opcode(6, "DUP");
    public static final Opcode SWAP = new Opcode(7, "SWAP");

    public static final Opcode ADD = new Opcode(3, "ADD");
    public static final Opcode SUB = new Opcode(4, "SUB");
    public static final Opcode MUL = new Opcode(5, "MUL");

    public static final Opcode JMP = new Opcode(8, "JMP");
    public static final Opcode JZ = new Opcode(9, "JZ");
    public static final Opcode CALL = new Opcode(12, "CALL");
    public static final Opcode RET = new Opcode(13, "RET");

    public static final Opcode LOAD = new Opcode(10, "LOAD");
    public static final Opcode STORE = new Opcode(11, "STORE");

    public static final Opcode HALT = new Opcode(14, "HALT");

    /** 内置指令的固定清单：按 opcode 数值升序（契约 4：顺序稳定）。 */
    private static final List<Opcode> BUILTINS = List.of(
            PUSH, POP, ADD, SUB, MUL, DUP, SWAP,
            JMP, JZ, LOAD, STORE, CALL, RET, HALT);

    private final int number;
    private final String mnemonic;

    public Opcode(int number, String mnemonic) {
        if (mnemonic == null || mnemonic.isEmpty()) {
            throw new IllegalArgumentException("助记符不能为空");
        }
        this.number = number;
        this.mnemonic = mnemonic;
    }

    public int number() {
        return number;
    }

    public String mnemonic() {
        return mnemonic;
    }

    /**
     * 返回全部指令的清单。
     */
    public static List<Opcode> list() {
        return new ArrayList<>(BUILTINS);
    }

    /** 按编号取内置指令；编号未定义时返回 {@code null}。 */
    public static Opcode byNumber(int number) {
        for (Opcode op : BUILTINS) {
            if (op.number == number) {
                return op;
            }
        }
        return null;
    }

    /** 按助记符取内置指令（大小写敏感）；未定义时返回 {@code null}。 */
    public static Opcode byMnemonic(String mnemonic) {
        for (Opcode op : BUILTINS) {
            if (op.mnemonic.equals(mnemonic)) {
                return op;
            }
        }
        return null;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof Opcode)) {
            return false;
        }
        return number == ((Opcode) other).number;
    }

    @Override
    public int hashCode() {
        return Integer.hashCode(number);
    }

    @Override
    public String toString() {
        return mnemonic;
    }
}
