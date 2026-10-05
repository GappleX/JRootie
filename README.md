# JRootie

[![Maven Central](https://img.shields.io/maven-central/v/io.github.gapplex/jrootie.svg)](https://central.sonatype.com/artifact/io.github.gapplex/jrootie)
[![License](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](LICENSE)

> **尽我所能，现你所想。**
> *All that I can, all that you will.*

> **仅用于 JVM 内部研究、测试与安全教育。**
> 本项目假定短生命周期进程、测试级 classpath、进程级 `-javaagent` 参数。
> **不要放进生产 classpath。** 即使代码路径从不调用它，JVM 参数也可能泄漏到生产环境。

---

## 这是什么

JRootie 是一个 JVM 内部访问工具包。它通过 `MethodHandles.Lookup.IMPL_LOOKUP`、`Unsafe` 与 `Instrumentation` 组合，绕过常规访问检查，读写任意字段、调用任意方法、构造任意对象、**重定义任意方法体**——包括 `private`、`final`、`static final`，以及 JDK 内部类。

它提供**可控的自动恢复**：以 `Rootie.acquireTest()` 打开的作用域会记录字段写入与方法体重定义，`close()` 时按 LIFO 回滚，让测试对 JVM 全局状态的破坏在退出时自动撤销。

**能力矩阵：**

| 操作 | 入口 | 覆盖范围 |
|---|---|---|
| 字段读写 | `rtdoField()` | 实例 / 静态，含 `final` |
| 方法调用 | `rtdoMethod()` | 实例 / 静态，含 `private` |
| 创建实例 | `rtdoConstructor()` | 含 `private` 构造器；`allocate()` 无构造分配 |
| 类枚举 | `rtdoClass()` | 声明类（含 `private` 成员类） |
| 方法体重定义 | `rtdoRedefine()` | 应用类 + JDK 内部类；单方法或链式批量 |

**它不做什么：**

- 不追踪方法调用的副作用
- 不恢复字段所指向对象的内容
- 不做并行执行检测
- 不试图规避 JIT 对 `static final` 的稳定值优化
- 不允许增删字段、修改方法签名、修改父类或接口列表（JVM 硬约束）

---

## 快速开始

### 依赖

```xml
<dependency>
    <groupId>io.github.gapplex</groupId>
    <artifactId>jrootie</artifactId>
    <version>0.4.0</version>
    <scope>test</scope>
</dependency>
```

> **使用 `rtdoRedefine()` 时需要 ASM**。JRootie 未 shade ASM，如用 redefine 功能，需额外声明：
>
> ```xml
> <dependency>
>     <groupId>org.ow2.asm</groupId>
>     <artifactId>asm</artifactId>
>     <version>9.10.1</version>
>     <scope>test</scope>
> </dependency>
> <dependency>
>     <groupId>org.ow2.asm</groupId>
>     <artifactId>asm-tree</artifactId>
>     <version>9.10.1</version>
>     <scope>test</scope>
> </dependency>
> ```

### 第一个例子

```java
import io.github.gapplex.jrootie.operators.Rootie;

import java.util.Objects;

public class Demo {
    public static void main(String[] args) {
        try (Rootie r = Rootie.acquireTest()) {
            // 用 lambda 替换 JDK 内部方法的行为
            r.rtdoRedefine().replace(
                    Objects.class, "toString",
                    new Class<?>[]{Object.class},
                    ctx -> "hacked:" + ctx.arg(0));

            System.out.println(Objects.toString("hi"));   // hacked:hi
        }
        // close() 后 Objects.toString 恢复原行为
        System.out.println(Objects.toString("hi"));       // hi
    }
}
```

启动命令：

```bash
java -javaagent:/abs/path/to/jrootie-0.4.0.jar -jar yourapp.jar
```

> 所有 JDK 版本都需要 `-javaagent`。JDK 11+ 均支持。
> JDK 17+ 通过 MR-JAR 加载 `getReference` / `putReference` 实现，
> JDK 11–16 使用 `getObject` / `putObject`。

---

## JDK 支持矩阵

| JDK | 启动参数 | `jdk.internal.misc.Unsafe` 引用读写 |
|---|---|---|
| 11 – 16 | `-javaagent:.../jrootie-0.4.0.jar` | `getObject` / `putObject` |
| 17+ | `-javaagent:.../jrootie-0.4.0.jar` | `getReference` / `putReference`（MR-JAR 版本选择） |

**所有 JDK 版本都需要 agent。** 它提供：

- `Instrumentation` 用于 `redefineModule`，开放 `java.base/jdk.internal.misc` 给当前模块
- 注入 `MethodRegistry` / `Context` 到 bootstrap classloader，使 JDK 内部类的 redefine 可用

不需要 `--add-opens`。所有模块配置由 agent 在运行期完成。

---

## 五个操作器

### `rtdoField()` —— 字段读写

```java
RootDoField f = r.rtdoField();

// 实例字段
String name = f.getFieldValue(obj, "name", String.class);
f.setFieldValue(obj, "name", "new");

// 静态字段
int x = f.getStaticFieldValue(Foo.class, "x", int.class);
f.setStaticFieldValue(Foo.class, "x", 999);
```

字段查询沿继承链自顶向下。类型校验先对基本类型包装，再按 `==` 精确比较。

### `rtdoMethod()` —— 方法调用

```java
Object result = r.rtdoMethod().invoke(
        instance, "methodName",
        new Class<?>[]{int.class, String.class},
        1, "arg");

Object staticResult = r.rtdoMethod().invokeStatic(
        Foo.class, "staticMethod",
        new Class<?>[]{},
        new Object[0]);
```

参数匹配规则同字段：基本类型包装后按 `==` 比较，不做协变匹配。

### `rtdoConstructor()` —— 创建实例

```java
// 通过匹配参数类型调用构造器（包括 private 构造器）
Foo foo = r.rtdoConstructor().newInstance(
        Foo.class,
        new Class<?>[]{String.class, String.class},
        "John", "Black");

// 不调用构造器，字段保持 JVM 默认值
Foo empty = r.rtdoConstructor().allocate(Foo.class);
```

### `rtdoClass()` —— 类枚举

```java
Class<?>[] inner = r.rtdoClass().getDeclaredClasses(Owner.class);
```

### `rtdoRedefine()` —— 方法体重定义

见下一节。

---

## 方法体重定义

通过 `Instrumentation.redefineClasses` 替换已加载类的方法体。JVM 硬约束：不得增删字段、不得增删方法、不得修改方法签名、不得修改父类或接口列表。

**所有 redefine 接口都要求显式传入 `paramTypes`**，与 `Class.getDeclaredMethod(String, Class[])` 语义一致。无参方法传 `new Class<?>[0]`。

### 单方法形式

```java
RootDoRedefine redef = r.rtdoRedefine();

// 1. 方法体只返回常量
redef.makeReturn(Foo.class, "compute", new Class<?>[0], 42);

// 2. 方法体只抛异常
redef.makeThrow(Foo.class, "fetch", new Class<?>[0], new IOException("injected"));

// 3. 方法体清空
redef.makeNoOp(Foo.class, "log", new Class<?>[]{String.class});

// 4. 用 lambda 替换方法体（任意逻辑）
redef.replace(Foo.class, "compute",
        new Class<?>[]{int.class},
        ctx -> {
            int x = ctx.arg(0);
            return x * 2;
        });
```

每个调用立即提交，触发一次 `redefineClasses`。

### 链式形式

同一个类的多个方法需要一次性重定义时，用 `on(Class)` 打开会话：

```java
try (Rootie r = Rootie.acquireTest()) {
    r.rtdoRedefine()
            .on(Foo.class)
            .makeReturn("compute", new Class<?>[0], 42)
            .replace("greet", new Class<?>[0], ctx -> "hacked")
            .makeNoOp("log", new Class<?>[]{String.class})
            .apply();
}
```

**三项好处：**

- **一次 deopt** —— 所有方法在一次 `redefineClasses` 调用中生效，而非每个方法触发一次。
- **原子性** —— JVM 保证所有方法同时生效，不存在「一部分改了、一部分没改」的中间窗口。
- **一条 undo 记录** —— `close()` 时一次性恢复旧字节码，批量注销替换函数。

**生命周期：** 一个 session 只能 `apply()` 或 `cancel()` 一次。丢弃未提交的 session 会导致 `MethodRegistry` 中的替换函数泄漏。`cancel()` 注销所有已注册的替换函数，不提交字节码。

### `replace` 的 `Context`

```java
public final class Context {
    public Class<?> owner();        // 方法所属的类
    public Object receiver();       // 静态方法返回 null
    public boolean isStatic();      // == (receiver() == null)
    public Object[] args();
    public int argCount();
    public <T> T arg(int index);    // 按索引取参数
}
```

桥接字节码自动装箱参数、调用 `MethodRegistry`、拆箱返回值。装箱与拆箱对称——`boolean` 走 `Boolean.booleanValue()`，不走 `Integer.intValue()`。

**`owner()` 的来源**：由 `RootDoRedefine` 在写入桥接字节码时嵌入（`ldc` 一个 Class 常量），是方法定义所在的类。即使 receiver 是子类实例，也返回方法声明所在的父类。接口 static 方法、父类方法 redefine 时都准确。

### 便捷操作：`ContextOps`

在 lambda 里频繁读写 `receiver` 的字段、调用 `receiver` 的方法，或对 `ctx.owner()` 操作静态成员时，每次都显式传目标冗余。`ContextOps` 把两个目标绑定为默认：

```java
try (Rootie r = Rootie.acquireTest()) {
    r.rtdoRedefine().replace(Target.class, "update",
            new Class<?>[]{int.class},
            ctx -> {
                ContextOps ops = new ContextOps(ctx, r);

                // 实例操作——目标 = ctx.receiver()
                Object[] table = ops.field("table", Object[].class);
                table[ctx.arg(0)] = "hacked";
                ops.setField("cache", null);
                ops.invoke("notifyChanged", new Class<?>[0]);

                // 静态操作——目标 = ctx.owner()
                int counter = ops.staticField("count", int.class);
                ops.setStaticField("count", counter + 1);
                ops.invokeStatic("log",
                        new Class<?>[]{String.class}, "updated");

                return null;
            });
}
```

| 方法 | 目标 | 等价于 |
|---|---|---|
| `ops.field(name, type)` | `ctx.receiver()` | `r.rtdoField().getFieldValue(ctx.receiver(), name, type)` |
| `ops.setField(name, value)` | `ctx.receiver()` | `r.rtdoField().setFieldValue(ctx.receiver(), name, value)` |
| `ops.invoke(name, paramTypes, args)` | `ctx.receiver()` | `r.rtdoMethod().invoke(ctx.receiver(), name, paramTypes, args)` |
| `ops.staticField(name, type)` | `ctx.owner()` | `r.rtdoField().getStaticFieldValue(ctx.owner(), name, type)` |
| `ops.setStaticField(name, value)` | `ctx.owner()` | `r.rtdoField().setStaticFieldValue(ctx.owner(), name, value)` |
| `ops.invokeStatic(name, paramTypes, args)` | `ctx.owner()` | `r.rtdoMethod().invokeStatic(ctx.owner(), name, paramTypes, args)` |

实例操作仅在实例方法的 lambda 中可用——receiver 为 `null` 时抛 `IllegalStateException`，错误信息指向静态变体。静态操作在实例方法和静态方法的 lambda 中都能用。

**`ops.invoke` / `ops.invokeStatic` 的异常语义**：方法自身抛出的异常被 `RootDoMethod` 包装为 `OperateFailedException`，原始异常在 `getCause()`。这与原方法直接调用的行为不同——需要原始类型时，用 `Unsafe.throwException` 或直接调 `RootDoMethod`。

### 类加载器可见性

`MethodRegistry` 与 `Context` 由 Agent 在启动时注入 bootstrap classloader，因此：

- **应用类**（同一 classloader）：直接可见
- **JDK 内部类**（bootstrap classloader）：Agent 已处理可见性，同时开放了 `java.base` 的 read 权限

无需用户额外配置。

### 手动快照 / 恢复

```java
byte[] original = redef.snapshot(Foo.class);
redef.replace(Foo.class, "compute", new Class<?>[0], ctx -> 42);
// ... 测试
redef.restore(Foo.class, original);
```

`snapshot` 返回 JVM 当前生效版本（含此前累积的 redefine），不是磁盘原始版本。

---

## 运行模式

| 模式 | 入口 | 自动恢复 | 适用场景 |
|---|---|---|---|
| `NORMAL` | `Rootie.acquire()` | 无 | 只读探查、调用方法、能力测试 |
| `TEST` | `Rootie.acquireTest()` | LIFO 回滚；字段冲突时回滚并抛异常 | 需要临时改状态并恢复的测试 |
| `TEST_KEEP` | `Rootie.acquireTestKeep()` | 字段非冲突回滚；字段冲突保留现场并抛异常。redefine 无条件回滚 | 调试字段冲突 |

`NORMAL` 模式无 undo-log，零额外开销；`close()` 是 no-op，可以不写 try-with-resources。

---

## close 之后

`close()` 会关闭 scope 的共享状态。之后：

- 所有操作器的**公开方法**（含 `Session` 的方法）抛 `OperateFailedException`
- **内部回滚路径不受影响**——回滚本身发生在 close 过程中，走包级方法绕过检查

若 `close()` 后仍需操作，acquire 新的 `Rootie`：

```java
Rootie r = Rootie.acquireTest();
// ... 使用
r.close();

// 以下全部抛 OperateFailedException
r.rtdoField().setFieldValue(obj, "x", 1);
r.rtdoRedefine().on(Foo.class);

// 正确做法：acquire 新的
try (Rootie r2 = Rootie.acquireTest()) { /* ... */ }
```

`NORMAL` 模式下 `close()` 是 no-op，scope 状态永不关闭——所有检查自动通过。

---

## 自动恢复

`TEST` / `TEST_KEEP` 模式下，undo-log 覆盖两类操作：

### 字段写入

写回旧值。旧值在**写入瞬间**读取，不是 scope 打开时缓存。

### 方法体重定义

用 redefine 之前的字节码覆盖。同时注销 `replace` 注册的所有替换函数（链式会话中可能有多个）。

### 回放顺序

严格 LIFO（后进先出）。字段写入与方法体 redefine 混在同一个栈中，按操作发生顺序回放。

```java
try (Rootie r = Rootie.acquireTest()) {
    r.rtdoField().setFieldValue(obj, "a", 1);                    // 栈 1
    r.rtdoRedefine().makeReturn(Foo.class, "b", EMPTY, 2);       // 栈 2
    r.rtdoField().setFieldValue(obj, "c", 3);                    // 栈 3
}
// close 时：3 → 2 → 1
```

### 回滚的语义差异

| 类型 | 冲突检测 | 冲突时动作 |
|---|---|---|
| 字段 | **有**（当前值 vs scope 写入值） | `TEST`：回滚并报错；`TEST_KEEP`：保留现场并报错 |
| redefine | **无** | 无条件覆盖为旧字节码 |

**redefine 不做冲突检测的理由**：字段是单值，可以判断“当前值是否等于写入值”；字节码是整体，任何一次 redefine 都会让当前版本与先前记录不等，检测无意义。多个 scope 改同一个类时，后关闭者覆盖先关闭者。

---

## 自动恢复的边界

**undo-log 只覆盖字段写入与方法体重定义。** 以下动作不被追踪、不被回滚：

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

## 冲突检测（字段）

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
| 提权（`acquire`） | `INFO` |
| 字段写入 `final` / 敏感字段 | `WARN` |
| 回滚跳过（`TEST_KEEP` 冲突） | `WARN` |
| 普通字段写入 | `DEBUG` |
| 字段 / redefine 回滚 | `DEBUG` |
| 方法体 redefine | `INFO` |
| scope 关闭汇总 | `INFO` |
| 字段读取 | `TRACE` |
| 方法调用 / 构造 | `DEBUG` |
| 操作失败 | `ERROR`（只记异常类名，不记 message） |

**日志故障隔离**：底层 SLF4J provider 抛出的任何异常都被 `Audit` 吞掉。审计是观测手段，不是业务逻辑——日志故障不会中断写入、回滚或 `close`。首次故障向 `System.err` 报告一次，之后静默。

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

## JVM 硬边界

### `static final` 与 JIT

通过 Unsafe 修改 `static final` 字段，在 JLS 语义下是**未定义的**。

HotSpot 可能在 JIT 编译期把该字段的值折叠为常量。即使字段内存被改写，已编译代码仍可能读到旧值，直到 deopt 发生。

JRootie 不试图规避这一点，也无法规避。**涉及 `static final` 修改的测试必须视为“可能不稳定”，不应作为回归测试断言。**

`-XX:-TieredCompilation` 不能作为解药——它只降低编译激进程度，不保证稳定值优化不生效。

### redefine JDK 核心方法会级联崩 JVM

`ArrayList.size()`、`HashMap.get()`、`String.length()` 这类被 JVM 自身大量调用的方法，一旦 redefine，JVM 内部组件（如 `StringConcatFactory`、`InvokerBytecodeGenerator`）会拿到错误的值，级联崩溃。

**这不是 JRootie 的 bug，是 redefine 的固有性质。** 任何工具——Byte Buddy、Mockito inline、手写 ASM——做同样的事都会遇到。

选择 redefine 目标时，优先考虑调用者少的 JDK 方法（如 `Objects.toString`），避免核心方法。

### 类结构不可变

`redefineClasses` 只允许改方法体。以下操作 JVM 拒绝：

- 加/删字段
- 加/删方法
- 修改方法签名
- 修改父类或接口列表

### 方法调用副作用不可回滚

`replace` 替换了方法体，但用户 lambda 内的副作用（IO、状态修改、外部调用）不在 undo-log 覆盖范围。

### 局部变量不可访问

方法体被替换后，原方法的局部变量**从未被分配**——不是“访问不到”，是“不存在”。这是任何 redefine 方案（Byte Buddy、Mockito inline、手写 ASM）的共同限制。

`Context` 提供 `owner` / `receiver` / `args`，`ContextOps` 提供 `receiver` 与 `owner` 的字段读写。原方法的局部变量不在其中。

---

## 已知不受支持

- **修改 `Record` 组件字段**：record 的组件字段是 `final`，但语义上属于值对象。JRootie 能改，但不保证行为一致。
- **修改 `Enum` 常量**：同上，且 `Class.getEnumConstants()` 有缓存。
- **动态模块上的 `redefineModule`**：agent 只在启动阶段改一次 `java.base`，不再重复。
- **GraalVM Native Image**：未测试。
- **强制杀死线程**：JVM 不提供此能力。
- **撤销模块开放**：`Instrumentation.redefineModule` 只能加，不能撤。

---

## 构建

要求 JDK 17+（MR-JAR 的 17 分支需要 `--release 17`）。

```bash
mvn clean package
```

产物：

- `target/jrootie-0.4.0.jar`：主 JAR，同时是 agent JAR
- `target/jrootie-0.4.0-sources.jar`
- `target/jrootie-0.4.0-javadoc.jar`

`META-INF/versions/17/` 下是 JDK 17+ 专用实现（`JdkInternalUnsafe`，使用 `getReference` / `putReference`）。

---

## 测试

技术兼容性测试套件（TCK）在独立仓库：[GappleX/JRootie-TCK](https://github.com/GappleX/JRootie-TCK)。

覆盖字段读写、方法调用、实例创建、redefine（应用类 + JDK 内部类 + 链式）、undo 回滚。通过 Maven Toolchains 在 JDK 11 / 17 / 21 / 25 上验证。

---

## 许可

Apache License 2.0。见 [LICENSE](LICENSE)。

---

## 反馈

这是单人维护的研究/测试工具，不是生产库。Issue 欢迎，但不承诺响应时效。

如果你的场景需要“通用反射工具”，`java.lang.reflect` 加 `--add-opens` 就够了——JRootie 的存在只为了解决那些常规手段解决不了的场景。