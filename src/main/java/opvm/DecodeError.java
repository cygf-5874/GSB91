package opvm;

/**
 * 指令解释器在运行期遇到非法状态时抛出的唯一异常类型。
 *
 * <p>非法状态包括：栈下溢、跳转目标越界、未定义 opcode、{@code CALL} 调用深度超过
 * 上限（256）。消息原文由 {@link Vm} 在各处构造，调用方只应比较 {@code getMessage()}。
 */
public final class DecodeError extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public DecodeError(String message) {
        super(message);
    }
}
