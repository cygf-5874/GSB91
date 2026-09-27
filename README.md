# opvm

Java 17 的字节码解释器。只用 JDK 标准库，不用 Maven / Gradle / 任何第三方依赖。

- 构建：`bash scripts/build.sh`（main → `out/`，test → `out-test/`）
- 既有用例：`java -cp out:out-test opvm.VmTest`
- 自检：`bash scripts/check.sh`（固定验收程序，10 个场景）

## 对外契约

1. **指令集**：`PUSH n` / `POP` / `ADD` / `SUB` / `MUL` / `DUP` / `SWAP` / `JMP a` / `JZ a` /
   `LOAD s` / `STORE s` / `CALL a`（把返回地址压栈）/ `RET` / `HALT`。
2. **行为等价**：`run(program)` 的输出、异常类型、异常消息**原文**必须与重构前逐字相同。
   `check/corpus/` 里每条程序都带期望输出（`.out`）或期望错误消息（`.err`），逐字节比对。
3. `Opcode.byNumber(int)` 的编号 ↔ 助记符映射保持不变：`1 PUSH`、`2 POP`、`3 ADD`、`4 SUB`、
   `5 MUL`、`6 DUP`、`7 SWAP`、`8 JMP`、`9 JZ`、`10 LOAD`、`11 STORE`、`12 CALL`、`13 RET`、`14 HALT`。
4. `Opcode.list()` 返回全部指令，**按 opcode 数值升序**且顺序稳定。
5. `Vm.register(Opcode, Handler)` 允许注册自定义指令（编号 128 及以上）；注册后的指令
   必须能被 `run` 正常分派执行 —— 核心分派路径**不依赖固定指令表**。
6. `Vm.dispatchTable()` 返回当前生效的分派表视图，按 opcode 升序、内容与注册情况一致。
7. **重入**：同一个 `Vm` 实例可以在注册前后各跑一遍已有程序，结果都不受影响
   （注册不得改动已有指令的行为）。
8. **非法状态**：栈下溢、跳转目标越界、未定义 opcode、`CALL` 深度超过 256，必须抛出与重构前
   **同一类型、同一消息**的 `DecodeError`。
9. **纯确定性**：无线程、无时钟、无随机；`Map` 迭代顺序不得影响任何输出。

## 本次重构的目标

- **目标 1**：把嵌套 `switch` 分派改成**表驱动**，新增指令走注册表（对齐契约 5、6）。
- **目标 2**：把每处重复的栈校验收敛到**一条**统一路径，但契约 2 的错误消息必须一字不变。
- **目标 3**：`check/corpus/` 的全部程序输出逐字节不变（这是重构是否成功的唯一硬判据）。

## 目录

```
scripts/build.sh         编译 main 与 test
scripts/check.sh         固定验收程序入口（透传 -list / --only）
src/main/java/opvm/      Vm / Opcode / Program / DecodeError
src/test/java/opvm/      VmTest（既有 15 个用例）
check/Checker.java       固定验收程序（勿改）
check/corpus/            程序与期望输出/期望错误消息（勿改）
```
