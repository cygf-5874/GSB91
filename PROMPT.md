当初这套指令解释器是照着需求一路堆 case 堆出来的，现在每加一条指令都要改三个地方。
opvm 是 Java 17 的字节码解释器，只用 JDK 标准库，构建与自检走 `scripts/*.sh`
（`scripts/check.sh` 是固定验收程序，别改）。

现在的实现是对的：`VmTest` 的 15 个用例全绿，`check/corpus/` 里的程序输出也是对的。
要动的是结构，不是行为。

任务：按 README「本次重构的目标」三件事重构，让契约 2 的输出与错误消息一字不变，
同时满足契约 5、6 的可扩展性要求。

验收：
- bash scripts/build.sh 退出码 0；
- java -cp out:out-test opvm.VmTest 退出码 0（15 个用例仍全绿）；
- bash scripts/check.sh 退出码 0，10 个场景全过（corpus 3 + extensible 3 + structure 2 + robust 2）。

约束：
1. 不改 `check/`、不改 `check/corpus/`、不改 `VmTest.java`。
2. 不引入第三方依赖，不用 Maven / Gradle，只用 JDK 标准库。
3. 公开的类与方法签名不变（可新增类型与内部结构）。
4. `Opcode.byNumber` 的映射、错误类型与错误消息原文都不许变。
