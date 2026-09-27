package opvm;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 一段已解码的指令序列。
 *
 * <p>文本格式：每行一条指令，空行与 {@code #} 起的注释忽略。首个 token 可以是指令助记符
 * （{@code PUSH}、{@code ADD} …），也可以是十进制编号（用于引用注册的自定义指令）。
 * 需要操作数的指令（{@code PUSH} / {@code JMP} / {@code JZ} / {@code CALL} / {@code LOAD} /
 * {@code STORE}）在助记符后跟一个 token。地址是「指令下标」，从 0 开始，注释与空行不计入。
 */
public final class Program {

    /** 单条指令：编号（可对应内置助记符，也可是注册的自定义 opcode）+ 可选的操作数原文。 */
    public static final class Instruction {

        private final int number;
        private final String arg;

        public Instruction(int number, String arg) {
            this.number = number;
            this.arg = arg;
        }

        public int number() {
            return number;
        }

        public String arg() {
            return arg;
        }

        public boolean hasArg() {
            return arg != null;
        }

        public long argLong() {
            return Long.parseLong(arg);
        }

        public int argInt() {
            return Integer.parseInt(arg);
        }

        @Override
        public String toString() {
            return arg == null ? String.valueOf(number) : number + " " + arg;
        }
    }

    private final List<Instruction> instructions;

    private Program(List<Instruction> instructions) {
        this.instructions = Collections.unmodifiableList(instructions);
    }

    /** 解析文本程序。格式非法时抛出 {@link DecodeError}。 */
    public static Program parse(String source) {
        List<Instruction> out = new ArrayList<>();
        int lineNo = 0;
        for (String rawLine : source.split("\n", -1)) {
            lineNo++;
            String line = rawLine;
            int hash = line.indexOf('#');
            if (hash >= 0) {
                line = line.substring(0, hash);
            }
            line = line.trim();
            if (line.isEmpty()) {
                continue;
            }
            String[] tokens = line.split("\\s+");
            if (tokens.length > 2) {
                throw new DecodeError("非法指令：" + line);
            }
            String head = tokens[0];
            String arg = tokens.length == 2 ? tokens[1] : null;
            int number;
            if (isNumber(head)) {
                number = Integer.parseInt(head);
            } else {
                Opcode op = Opcode.byMnemonic(head);
                if (op == null) {
                    throw new DecodeError("非法指令：" + line);
                }
                number = op.number();
                validateOperand(op, arg, line);
            }
            out.add(new Instruction(number, arg));
        }
        return new Program(out);
    }

    /** 用现成的指令列表构造程序（主要给固定件与测试用）。 */
    public static Program of(List<Instruction> instructions) {
        return new Program(new ArrayList<>(instructions));
    }

    public int size() {
        return instructions.size();
    }

    public Instruction at(int index) {
        return instructions.get(index);
    }

    public List<Instruction> instructions() {
        return instructions;
    }

    private static void validateOperand(Opcode op, String arg, String line) {
        if (op == Opcode.PUSH) {
            requireNumeric(arg, line);
        } else if (op == Opcode.JMP || op == Opcode.JZ || op == Opcode.CALL) {
            requireNumeric(arg, line);
        } else if (op == Opcode.LOAD || op == Opcode.STORE) {
            if (arg == null) {
                throw new DecodeError("非法指令：" + line);
            }
        } else if (arg != null) {
            throw new DecodeError("非法指令：" + line);
        }
    }

    private static void requireNumeric(String arg, String line) {
        if (arg == null || !isNumber(arg)) {
            throw new DecodeError("非法操作数：" + line);
        }
    }

    private static boolean isNumber(String token) {
        if (token.isEmpty()) {
            return false;
        }
        int start = token.charAt(0) == '-' ? 1 : 0;
        if (start == token.length()) {
            return false;
        }
        for (int i = start; i < token.length(); i++) {
            if (!Character.isDigit(token.charAt(i))) {
                return false;
            }
        }
        return true;
    }
}
