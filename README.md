# CPU 自动测试工具

用扩展版 MARS 汇编、单步执行测试程序，再将结果与真实 Logisim 电路逐条比较。支持导入原创指令 `.class`、固定种子随机测试和错误定位。

## 默认配置和测试范围

`adapter.properties` 按仓库内附带的 `CPU_p3.circ` 配置。更换电路时，需要相应修改端口映射、ROM/RAM 层级路径；GRF 内部结构不同，可关闭 `snapshot.registers`。

默认运行 `tests/base` 中的六份定向测试，再由 `Generator` 追加一份含 256 条随机主体指令的混合程序。覆盖的基础指令为：

```text
add、sub、ori、lw、sw、beq、lui、nop
```

| 测试文件 | 主要检查内容 |
| --- | --- |
| `arithmetic.asm` | 加减法边界、32 位回绕、源和目标重叠、写 `$0` |
| `immediate.asm` | ORI 零扩展、LUI 高位装入及立即数边界 |
| `memory.asm` | 正负基址和偏移、立即数边界、DM 首尾地址、加载到 `$0` |
| `branch_flow.asm` | 分支成立与不成立、前后跳转、循环、零偏移 |
| `branch_self.asm` | 有界观察跳回自身的分支 |
| `course_sample.asm` | 课程提供的混合测试样例 |

这些测试不代表完整覆盖。原创指令需要扩展后的电路、对应 `.class` 和测试汇编；`Generator` 不会根据 `.class` 自动生成新指令测试。

## 运行环境

- Windows，PowerShell 5.1 或 7。
- 完整 JDK 17 及以上，包含 `java` 和 `javac`，并加入 PATH；也可用 `-JavaBin` 指定 JDK 的 `bin` 目录。
- MARS 和原版 Logisim 2.7.1 的 JAR 已放在 `lib`，无需另外下载。

## 启动

在仓库根目录打开 PowerShell，直接测试附带电路：

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\run.ps1 -Circuit ".\CPU_p3.circ"
```

测试自己的电路和汇编，关闭附加随机测试：

```powershell
.\run.ps1 -Circuit "D:\path\cpu.circ" -Tests "D:\path\test.asm" -RandomCount 0
```

`-Tests` 也可以指向存放 `.asm` 的目录。测试原创指令时，再加上 `.class` 路径：

```powershell
.\run.ps1 -Circuit "D:\path\extended_cpu.circ" -Tests "D:\path\tests" -Extensions "D:\path\instruction.class" -RandomCount 0
```

多个扩展以 PowerShell 数组传入：`-Extensions @("D:\path\a.class", "D:\path\b.class")`。扩展在每次 MARS 进程启动时加载，不会写入 JAR。

## 常用参数

| 参数 | 用途或默认值 |
| --- | --- |
| `-Circuit` | 必填，待测 `.circ` 文件 |
| `-Adapter` | 电路配置，默认 `adapter.properties` |
| `-Tests` | 测试文件或目录，默认 `tests/base` |
| `-Extensions` | 原创指令 `.class` 路径数组 |
| `-Seed` | 随机种子，默认 `20261101`；相同种子可复现同一程序 |
| `-RandomCount` | 随机主体指令数，默认 `256`，设为 `0` 关闭 |
| `-MaxSteps` | 参考执行步数上限，默认 `10000` |
| `-TimeoutMs` | 执行超时设置，默认 `15000` 毫秒 |
| `-OutputDirectory` | 输出根目录，默认 `output` |
| `-JavaBin` | JDK 的 `bin` 目录 |
| `-MarsJar` / `-LogisimJar` | 替换兼容的依赖 JAR，默认使用 `lib` 中的文件 |

## 查看结果

每次运行在 `output` 下建立单独目录：

- `summary.tsv`：各测试结果；每个测试的 `result.txt` 给出第一处错误。
- `program.hex`、`program.lst`：实际机器码及地址、源代码行对应关系。
- `expected-trace.tsv`、`actual-trace.tsv`：MARS 和电路执行记录。
- `expected-memory.hex`、`observed-memory.hex`：预期和实际 RAM 内容。
- `reference.log`：MARS 汇编和运行日志。

`PASS` 表示已执行检查通过，`FAIL` 表示电路结果不匹配，`TIMEOUT` 表示电路执行超时，`ERROR` 表示参考执行、配置或环境出错。主程序退出码为：全部通过 `0`，功能失败或电路超时 `1`，工具或参考执行错误 `2`；启动或编译失败也可能由 PowerShell 返回 `1`。

## 测试约定

- 当前支持 32 位单周期 MIPS、每条指令至多一次 GRF 写和一次 DM 写；实际数据存储器为单个 32 位 RAM。
- PC 起始地址 `0x3000`，DM 地址范围 `0～0x2fff`，小端、无延迟槽，忽略算术溢出。两边初始通用寄存器和数据内存均为 0。
- 使用自包含 `.asm`，不使用伪指令、系统调用或非零 `.data` 初值；由程序中的存储指令初始化数据。
- 可用 `.expect reg $t0 8`、`.expect mem 0 8` 核对手算结果；`.steps 12` 用于有界观察循环。
- MARS 模式必须保留 `snapshot.memory=true`；可选的 GRF 快照按配置中的寄存器标签读取。
- 时钟替换和 ROM 装载只作用于副本或仿真状态，不保存修改原电路。

## 文件和来源

`src` 保留工具 Java 源码，`run.ps1` 负责编译和启动，`adapter.properties` 描述电路接口，`tests/base` 和 `CPU_p3.circ` 提供默认测试与电路。

工具由 Codex 协助编写。MARS 来自 [Mars-with-BUAA-CO-extension](https://github.com/Toby-Shi-cloud/Mars-with-BUAA-CO-extension)，由提供的源码包本地编译；JAR 保留运行类和资源，许可及源码包标识见 [MARS-LICENSE.txt](lib/MARS-LICENSE.txt)。Logisim 以未修改的原始 JAR 附带，JAR 内含源码及许可，另附 [LOGISIM-LICENSE.txt](lib/LOGISIM-LICENSE.txt)。第三方依赖不属于本工具原创内容。
