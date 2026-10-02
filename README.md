# JRootie

> **仅用于 JVM 内部研究、测试与安全教育。**  
> 本项目假定短生命周期进程、测试级 classpath、进程级 `-javaagent` 参数。  
> **不要放进生产 classpath。** 即使代码路径从不调用它，JVM 参数也可能泄漏到生产环境。

---

## 这是什么

JRootie 是一个基于反射的 JVM 内部访问工具包。它通过 `MethodHandles.Lookup.IMPL_LOOKUP` 与 `Unsafe` 组合，绕过常规访问检查，读写任意字段、调用任意方法、构造任意对象——包括 `private`、`final`、`static final`。

它提供**可控的自动恢复**：以 `Rootie.acquireTest()` 打开的作用域会记录所有字段写入，`close()` 时按 LIFO 回滚，让测试对 JVM 全局状态的破坏在退出时自动撤销。

**它不做什么 / 不保证什么：**

- 不追踪方法调用的副作用
- 不恢复字段所指向对象的内容
- 不做并行执行检测
- 不试图规避 JIT 对 `static final` 的稳定值优化

---

## 快速开始

```java
import io.github.gapplex.jrootie.operators.Rootie;
import io.github.gapplex.jrootie.operators.RootDoField;

import java.util.Map;

public class Demo {
    public static void main(String[] args) {
        try (Rootie r = Rootie.acquireTest()) {
            RootDoField f = r.rtdoField();

            // 破坏一个不可变 Map
            Map<String, Object> m = Map.of("hi", "1");
            f.setFieldValue(m, "v0", "hacked");
            System.out.println(m);   // {hi=hacked}

            // close() 自动回滚
        }
    }
}
```

启动命令：

```bash
java -javaagent:/abs/path/to/jrootie-0.1.0.jar -jar yourapp.jar
```

> JDK 9–24 无需 `-javaagent`，可直接运行。  
> JDK 25+ 使用上述命令加载 agent。

---

## JDK 支持矩阵

| JDK | 启动参数 | Unsafe 实现 | 备注 |
|---|---|---|---|
| 9 – 24 | 无 | `sun.misc.Unsafe` | 开箱即用 |
| 25+ | `-javaagent:.../jrootie-0.1.0.jar` | `jdk.internal.misc.Unsafe` | MR-JAR 加载 25 专用实现 |

**JDK 9–24 零配置。JDK 25+ 一个 `-javaagent`。**

- 不需要 `--add-opens`。agent 在运行期通过 `Instrumentation.redefineModule` 自动开放所需模块。
- `-javaagent` 必须使用绝对路径，或使用 IDEA 宏 `$USER_HOME$` / `$PROJECT_DIR$`。
- `-javaagent` 必须位于 `-jar` / `-cp` 之前。

---

## 运行模式

| 模式 | 入口 | 自动恢复 | 适用场景 |
|---|---|---|---|
| `NORMAL` | `Rootie.acquire()` | 无 | 只读探查、调用方法、能力测试 |
| `TEST` | `Rootie.acquireTest()` | LIFO 回滚；冲突时回滚并抛异常 | 需要临时改状态并恢复的测试 |
| `TEST_KEEP` | `Rootie.acquireTestKeep()` | 非冲突回滚；冲突时保留现场并抛异常 | 调试字段冲突 |

`NORMAL` 模式无 undo-log，零额外开销；`close()` 是 no-op，可以不写 try-with-resources。

---

## 自动恢复的边界

**undo-log 只覆盖字段写入。** 以下动作不被追踪、不被回滚：

### 1. 方法调用副作用

```java
try (Rootie r = Rootie.acquireTest()) {
    List<Object> list = r.rtdoField().getFieldValue(obj, "list", List.class);
    list.add("x");     // ← 不被追踪
}   // close 只回滚 obj.list 字段引用，list 内容不变
```

### 2. 字段指向对象的内部状态

```java
try (Rootie r = Rootie.acquireTest()) {
    r.rtdoField().setFieldValue(obj, "list", someList);
    someList.clear();  // ← 不被追踪
}   // close 把 obj.list 写回旧引用，someList 内容不变
```

### 3. 数组元素

```java
Object[] arr = r.rtdoField().getFieldValue(obj, "arr", Object[].class);
arr[0] = "x";          // ← 不被追踪
```

**核心限制：引用不是快照。**  
`oldValue` 记录的是引用或原始值，不是引用目标的内容。需要恢复内容时，由调用方在 scope 外自行快照。

---

## 嵌套与 LIFO

支持嵌套 `Rootie`。每个实例独立维护自己的 undo-log。内层先 close，外层后 close：

```java
// 初始 foo = 0
try (Rootie outer = Rootie.acquireTest()) {
    outer.rtdoField().setFieldValue(obj, "foo", 1);

    try (Rootie inner = Rootie.acquireTest()) {
        inner.rtdoField().setFieldValue(obj, "foo", 2);
    }   // inner close → foo = 1
}   // outer close → foo = 0
```

**实现前提：** `oldValue` 在写入瞬间读取，而不是在 scope 打开时缓存。

`System.out` 的重写演示了 LIFO 的实际意义：

```java
setStaticFieldValue(System.class, "out", null);       // (1) old = 原始流
setStaticFieldValue(System.class, "out", newStream);  // (2) old = null

// close:
//   (2) 回滚 → out = null
//   (1) 回滚 → out = 原始流
```

如果按 FIFO 回放，`out` 会变成 `null`，后续代码会 NPE。

---

## 冲突检测

`close()` 时逐条检查：字段当前值是否等于 scope 写入值。

- **相等**：引用类型按引用比较，原始类型按值比较 → 回滚
- **不等**：外部代码在 scope 存续期间改过同一字段 → 冲突

| 模式 | 冲突时的动作 |
|---|---|
| `TEST` | **回滚**并抛 `ScopeCloseException` |
| `TEST_KEEP` | **不回滚**，抛 `ScopeCloseException` |

**默认回滚而非保留**：测试语境下，“外部改了同一字段”几乎一定是顺序错误或并行冲突。保留脏现场会让下一条测试被前一条污染，以更迷惑的方式失败。

需要保留现场时，显式使用 `TEST_KEEP`。

`ScopeCloseException.conflicts()` 返回的每条 `Conflict` 携带：

- `record()`：undo 条目（字段、旧值、新值）
- `currentValue()`：回放时读到的当前值
- `failure()`：回滚本身失败时的异常（读/写抛异常）

---

## 线程绑定

`TEST*` 模式下的 `Rootie` 绑定创建线程。非持有线程调用写方法或 `close()` 时，抛 `OperateFailedException`。

**并行测试应每线程各自 `acquire`。** 不检测 JUnit 并行配置——探测不可靠，交给用户显式声明。

---

## 与 try-with-resources 的异常顺序

如果 try 块抛异常 `V`，而 `close()` 同时抛 `ScopeCloseException V2`，JLS 14.20.3.2 规定最终抛出 `V2`，`V` 被添加到 `V2` 的 suppressed 列表：

```java
try (Rootie r = Rootie.acquireTest()) {
    assertThat(x).isEqualTo(y);      // 抛 AssertionError V
} catch (ScopeCloseException e) {     // 主异常是 V2
    for (Conflict c : e.conflicts()) {
        // ...
    }
    for (Throwable s : e.getSuppressed()) {
        s.printStackTrace();          // 原始 V 在这里
    }
}
```

这是 `try-with-resources` 的语言行为，库无法改变。库能做的只有让 `conflicts()` 携带足够的诊断信息。

---

## 审计日志

所有操作通过 SLF4J 记录，**只记结构信息**（类名、字段名、调用点），不记值。

| 事件 | 级别 |
|---|---|
| 字段写入 `final` / 敏感字段 | `WARN` |
| 普通字段写入 | `DEBUG` |
| 字段读取 | `TRACE` |
| 操作失败 | `ERROR`（只记异常类名，不记 message） |

需要在测试里看到日志，加入 provider：

```xml
<dependency>
    <groupId>org.slf4j</groupId>
    <artifactId>slf4j-simple</artifactId>
    <version>2.0.18</version>
    <scope>test</scope>
</dependency>
```

配合 `simplelogger.properties`：

```properties
org.slf4j.simpleLogger.defaultLogLevel=warn
```

---

## `static final` 与 JIT 的边界

通过 Unsafe 修改 `static final` 字段，在 JLS 语义下是**未定义的**。

HotSpot 可能在 JIT 编译期把该字段的值折叠为常量。即使字段内存被改写，已编译代码仍可能读到旧值，直到 deopt 发生。

JRootie 不试图规避这一点，也无法规避。**涉及 `static final` 修改的测试必须视为“可能不稳定”，不应作为回归测试断言。**

`-XX:-TieredCompilation` 不能作为解药——它只降低编译激进程度，不保证稳定值优化不生效。

---

## 已知不受支持

- **修改 `Record` 组件字段**：record 的组件字段是 `final`，但语义上属于值对象。JRootie 能改，但不保证行为一致。
- **修改 `Enum` 常量**：同上，且 `Class.getEnumConstants()` 有缓存。
- **动态模块上的 `redefineModule`**：agent 只在启动阶段改一次 `java.base`，不再重复。
- **GraalVM Native Image**：未测试。

---

## 构建

要求 JDK 25+（MR-JAR 的 25 分支需要 `--release 25`）。

```bash
mvn clean package
```

产物：

- `target/jrootie-0.1.0.jar`：主 JAR，同时是 agent JAR
- `target/jrootie-0.1.0-sources.jar`
- `target/jrootie-0.1.0-javadoc.jar`

`META-INF/versions/25/` 下是 JDK 25 专用实现（`UnsafeProvider` + `JdkInternalUnsafe`）。

---

## 许可

Apache License 2.0。见 [LICENSE](LICENSE)。

---

## 反馈

这是单人维护的研究/测试工具，不是生产库。Issue 欢迎，但不承诺响应时效。

如果你的场景需要“通用反射工具”，`java.lang.reflect` 加 `--add-opens` 就够了——JRootie 的存在只为了解决那些常规手段解决不了的场景。