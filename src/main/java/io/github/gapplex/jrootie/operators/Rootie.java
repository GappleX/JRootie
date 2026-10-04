/*
 * Copyright (C) 2026 GappleX
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0.txt
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.github.gapplex.jrootie.operators;

import io.github.gapplex.jrootie.agent.InstrumentationHolder;
import io.github.gapplex.jrootie.exceptions.AcquireFailedException;
import io.github.gapplex.jrootie.Audit;
import io.github.gapplex.jrootie.Log;
import io.github.gapplex.jrootie.exceptions.OperateFailedException;
import io.github.gapplex.jrootie.exceptions.ScopeCloseException;
import io.github.gapplex.jrootie.internal.undo.*;
import io.github.gapplex.jrootie.redefine.MethodRegistry;
import io.github.gapplex.jrootie.unsafe.IUnsafe;
import io.github.gapplex.jrootie.unsafe.UnsafeProvider;

import java.lang.instrument.Instrumentation;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

/**
 * jrootie 的核心入口。
 *
 * <p>{@code Rootie} 是一个“提权容器”：它在 {@code acquire} 时通过
 * {@link IUnsafe} 取得 {@code MethodHandles.Lookup.IMPL_LOOKUP}，并预解析
 * {@code Class} 上的若干 {@code getDeclaredXxx0} 原生方法句柄，
 * 之后对外提供 {@link RootDoField}、{@link RootDoMethod}、
 * {@link RootDoConstructor}、{@link RootDoClass}、{@link RootDoRedefine}
 * 五个操作器。</p>
 *
 * <h2>模式与自动恢复</h2>
 *
 * <p>提权模式由 {@link AcquireMode} 决定，在 {@code acquire} 时确定：</p>
 * <ul>
 *   <li>{@link AcquireMode#NORMAL}：默认，无 undo，零额外开销。</li>
 *   <li>{@link AcquireMode#TEST} / {@link AcquireMode#TEST_KEEP}：
 *       记录所有字段写入与方法体重定义的 undo-log，{@link #close()} 时
 *       按 LIFO 回滚。</li>
 *   <li>{@link AcquireMode#BEFORE_SECURITY_MANAGER}：与原语义一致，
 *       无 undo。</li>
 * </ul>
 *
 * <h2>自动恢复的边界</h2>
 *
 * <p>回滚覆盖两类操作：</p>
 * <ul>
 *   <li><b>字段写入</b>——写回旧值。注意是引用，不是快照：若字段指向的
 *       对象在 scope 存续期被外部修改，回滚不会撤销对象内部的变化。</li>
 *   <li><b>方法体重定义</b>——用 redefine 之前的字节码覆盖。不做冲突检测：
 *       多个 scope 改同一个类时，后关闭者覆盖先关闭者。</li>
 * </ul>
 *
 * <p>不覆盖：构造器调用与方法调用的副作用、数组元素写入。scope 不扫描
 * 全局、不追踪外部修改、不恢复对象内容。</p>
 *
 * <h2>线程绑定</h2>
 *
 * <p>{@code TEST*} 模式下 {@code Rootie} 绑定创建线程。非持有线程调用
 * 写方法或 {@link #close()} 时抛 {@link OperateFailedException}。
 * 并行测试应每线程各自 {@code acquire}。</p>
 *
 * <h2>典型用法</h2>
 *
 * <pre>{@code
 * try (Rootie root = Rootie.acquireTest()) {
 *     root.rtdoField().setStaticFieldValue(Collections.class, "EMPTY_MAP", null);
 *     // ... 测试断言
 * }  // close() 自动回滚
 * }</pre>
 *
 * <p><b>安全提示：</b>JDK 9–24 无需额外 JVM 参数；JDK 25+ 需要
 * {@code -javaagent:jrootie-0.2.0.jar} 以启用 JDK 内部类的 redefine 支持。</p>
 *
 * @since 0.1.0
 * @see AcquireMode
 * @see WriteRecord
 * @see RedefineRecord
 */
public class Rootie implements AutoCloseable {

    /** 审计日志器。 */
    private static final Audit log = Log.audit(Rootie.class);

    // ===== Core =====

    /** 提权模式，生命周期内不可变。 */
    private final AcquireMode mode;

    /** 创建线程，用于 TEST* 模式下的写入线程绑定。 */
    private final Thread owner;

    /** 底层 Unsafe 抽象。 */
    private final IUnsafe unsafe;

    /** 通过 Unsafe 取得的 IMPL_LOOKUP。 */
    private final MethodHandles.Lookup implLookup;

    // ===== Native methods =====

    /** {@code Class#getDeclaredFields0(boolean)} 句柄。 */
    private final MethodHandle getDeclaredFields0;

    /** {@code Class#getDeclaredMethods0(boolean)} 句柄。 */
    private final MethodHandle getDeclaredMethods0;

    /** {@code Class#getDeclaredClasses0()} 句柄。 */
    private final MethodHandle getDeclaredClasses0;

    /** {@code Class#getDeclaredConstructors0(boolean)} 句柄。 */
    private final MethodHandle getDeclaredConstructors0;

    // ===== Undo log =====

    /**
     * undo-log，LIFO 回放。{@link AcquireMode#recordsUndo()} 为
     * {@code false} 时为 {@code null}，走零开销路径。
     *
     * <p>条目类型见 {@link UndoEntry}：字段写入（{@link WriteRecord}）与
     * 方法体重定义（{@link RedefineRecord}）。</p>
     */
    private final ArrayDeque<UndoEntry> undo;

    /** close 幂等标记。 */
    private volatile boolean closed;

    // ===== Operators =====

    /** 字段操作器，懒加载。 */
    private volatile RootDoField fieldOps;

    /** 方法操作器，懒加载。 */
    private volatile RootDoMethod methodOps;

    /** 构造器操作器，懒加载。 */
    private volatile RootDoConstructor ctorOps;

    /** 类操作器，懒加载。 */
    private volatile RootDoClass classOps;

    /** 方法体重定义操作器，懒加载。 */
    private volatile RootDoRedefine redefineOps;

    /**
     * 私有构造器，仅由 {@link #doAcquire(String)} 调用。
     */
    private Rootie(AcquireMode mode,
                   IUnsafe unsafe,
                   MethodHandles.Lookup implLookup,
                   MethodHandle getDeclaredFields0,
                   MethodHandle getDeclaredMethods0,
                   MethodHandle getDeclaredClasses0,
                   MethodHandle getDeclaredConstructors0) {
        this.mode = mode;
        this.owner = Thread.currentThread();
        this.unsafe = unsafe;
        this.implLookup = implLookup;
        this.getDeclaredFields0 = getDeclaredFields0;
        this.getDeclaredMethods0 = getDeclaredMethods0;
        this.getDeclaredClasses0 = getDeclaredClasses0;
        this.getDeclaredConstructors0 = getDeclaredConstructors0;
        this.undo = mode.recordsUndo() ? new ArrayDeque<UndoEntry>() : null;
    }

    // ===== 静态入口 =====

    /**
     * 常规提权入口。等价于 {@code doAcquire("normal")}。
     *
     * @return 已初始化的 {@code Rootie}（{@link AcquireMode#NORMAL}）
     * @throws AcquireFailedException 获取 Unsafe、IMPL_LOOKUP 或原生句柄失败时
     */
    public static Rootie acquire() {
        return doAcquire("normal");
    }

    /**
     * 测试模式入口。写入字段或重定义方法体时自动记录 undo，
     * {@link #close()} 时按 LIFO 回滚；冲突策略
     * {@link AcquireMode.ConflictPolicy#ROLLBACK_AND_REPORT}。
     *
     * @return 已初始化的 {@code Rootie}（{@link AcquireMode#TEST}）
     * @throws AcquireFailedException 提权失败时
     */
    public static Rootie acquireTest() {
        return doAcquire("test");
    }

    /**
     * 测试模式 + 冲突保留入口。字段冲突时不回滚，保留现场供调试。
     * 方法体重定义仍无条件回滚——字节码不做冲突检测。
     *
     * <p><b>仅用于调试。</b>常规测试请用 {@link #acquireTest()}。</p>
     *
     * @return 已初始化的 {@code Rootie}（{@link AcquireMode#TEST_KEEP}）
     * @throws AcquireFailedException 提权失败时
     */
    public static Rootie acquireTestKeep() {
        return doAcquire("test-keep");
    }

    /**
     * 在安装 {@code SecurityManager} 之前提权的入口。
     *
     * <p>不记录 undo：这一模式的目的是“抢在 SM 之前拿到能力”，
     * 通常发生在 agent 初始化阶段，没有合理的“回滚时机”。</p>
     *
     * @return 已初始化的 {@code Rootie}
     *         （{@link AcquireMode#BEFORE_SECURITY_MANAGER}）
     * @throws IllegalStateException 若 SecurityManager 已安装
     * @throws AcquireFailedException 提权失败时
     */
    public static Rootie acquireBeforeSecurityManager() {
        if (System.getSecurityManager() != null) {
            throw new IllegalStateException(
                    "SecurityManager already installed; cannot acquire before it");
        }
        return doAcquire("before-security-manager");
    }

    /**
     * 执行实际的提权流程。
     *
     * <p>保留字符串入口：{@code mode} 是外部可观测的（日志、agent 参数、
     * 系统属性都用字符串），{@link AcquireMode} 只是内部翻译。</p>
     *
     * @param modeTag 模式标识，须能被 {@link AcquireMode#fromTag(String)} 解析
     * @return 已初始化的 {@code Rootie}
     * @throws AcquireFailedException 模式未知，或提权任一步骤失败时
     */
    private static Rootie doAcquire(String modeTag) {
        final AcquireMode mode = AcquireMode.fromTag(modeTag)
                .orElseThrow(() -> new AcquireFailedException(
                        "unknown acquire mode: " + modeTag));
        try {
            IUnsafe unsafe = UnsafeProvider.get();

            Field implLookupField =
                    MethodHandles.Lookup.class.getDeclaredField("IMPL_LOOKUP");
            MethodHandles.Lookup implLookup = (MethodHandles.Lookup) unsafe.getObject(
                    unsafe.staticFieldBase(implLookupField),
                    unsafe.staticFieldOffset(implLookupField));

            MethodHandle getDeclaredFields0 = implLookup.unreflect(
                    Class.class.getDeclaredMethod("getDeclaredFields0", boolean.class));
            MethodHandle getDeclaredMethods0 = implLookup.unreflect(
                    Class.class.getDeclaredMethod("getDeclaredMethods0", boolean.class));
            MethodHandle getDeclaredClasses0 = implLookup.unreflect(
                    Class.class.getDeclaredMethod("getDeclaredClasses0"));
            MethodHandle getDeclaredConstructors0 = implLookup.unreflect(
                    Class.class.getDeclaredMethod("getDeclaredConstructors0", boolean.class));

            Rootie r = new Rootie(mode, unsafe, implLookup,
                    getDeclaredFields0, getDeclaredMethods0,
                    getDeclaredClasses0, getDeclaredConstructors0);

            log.acquired(mode.tag());
            return r;
        } catch (Throwable t) {
            log.failed("acquire", Rootie.class, modeTag, t);
            throw new AcquireFailedException(
                    "Rootie.acquire failed (mode=" + modeTag + ")", t);
        }
    }

    // ===== 模式访问 =====

    /**
     * @return 本次提权的模式，只读
     */
    public AcquireMode mode() {
        return mode;
    }

    // ===== 操作器 =====

    /**
     * 获取字段操作器（懒加载、线程安全）。
     *
     * <p>{@link AcquireMode#recordsUndo()} 为 {@code true} 时，
     * 该操作器的每次写入都会回调 {@link #recordWrite} 记录 undo。</p>
     *
     * @return {@link RootDoField} 单例
     */
    public RootDoField rtdoField() {
        RootDoField r = fieldOps;
        if (r == null) {
            synchronized (this) {
                r = fieldOps;
                if (r == null) {
                    WriteRecorder rec = (undo == null) ? null : this::recordWrite;
                    r = fieldOps = new RootDoField(
                            unsafe, implLookup, getDeclaredFields0, rec);
                }
            }
        }
        return r;
    }

    /**
     * 获取方法操作器（懒加载、线程安全）。
     *
     * @return {@link RootDoMethod} 单例
     */
    public RootDoMethod rtdoMethod() {
        RootDoMethod r = methodOps;
        if (r == null) {
            synchronized (this) {
                r = methodOps;
                if (r == null) {
                    r = methodOps = new RootDoMethod(
                            unsafe, implLookup, getDeclaredMethods0);
                }
            }
        }
        return r;
    }

    /**
     * 获取构造器操作器（懒加载、线程安全）。
     *
     * @return {@link RootDoConstructor} 单例
     */
    public RootDoConstructor rtdoConstructor() {
        RootDoConstructor r = ctorOps;
        if (r == null) {
            synchronized (this) {
                r = ctorOps;
                if (r == null) {
                    r = ctorOps = new RootDoConstructor(
                            unsafe, implLookup, getDeclaredConstructors0);
                }
            }
        }
        return r;
    }

    /**
     * 获取类操作器（懒加载、线程安全）。
     *
     * @return {@link RootDoClass} 单例
     */
    public RootDoClass rtdoClass() {
        RootDoClass r = classOps;
        if (r == null) {
            synchronized (this) {
                r = classOps;
                if (r == null) {
                    r = classOps = new RootDoClass(
                            unsafe, implLookup, getDeclaredClasses0);
                }
            }
        }
        return r;
    }

    /**
     * 获取方法体重定义操作器（懒加载、线程安全）。
     *
     * <p>需要 Agent 已加载——redefine 依赖 {@code Instrumentation}。
     * {@link AcquireMode#TEST} / {@link AcquireMode#TEST_KEEP} 模式下，
     * 该操作器的 redefine 会记录到 undo-log，由 {@link #close()} 回滚。</p>
     *
     * @return {@link RootDoRedefine} 单例
     * @throws OperateFailedException Agent 未加载（缺少 {@code -javaagent}）时
     */
    public RootDoRedefine rtdoRedefine() {
        RootDoRedefine r = redefineOps;
        if (r == null) {
            synchronized (this) {
                r = redefineOps;
                if (r == null) {
                    Instrumentation inst = InstrumentationHolder.get();
                    if (inst == null) {
                        throw new OperateFailedException(
                                "rtdoRedefine requires -javaagent:jrootie.jar");
                    }
                    RedefineRecorder rec = (undo == null) ? null : this::recordRedefine;
                    r = redefineOps = new RootDoRedefine(inst, owner, rec);
                }
            }
        }
        return r;
    }

    // ===== undo 记录 =====

    /**
     * {@link WriteRecorder} 的实现：校验线程，记录 undo 条目。
     *
     * <p>回调时机在写入之前，{@code oldValue} 是<b>写入瞬间</b>读到的值，
     * 不是 scope 打开时缓存的——这是嵌套 scope 正确回滚的前提。</p>
     *
     * @param target   目标实例；静态字段为 {@code null}
     * @param field    字段
     * @param oldValue 写入前的旧值
     * @param newValue 即将写入的值
     * @throws OperateFailedException 当前线程不是持有线程，或 scope 已关闭时
     */
    private void recordWrite(Object target, Field field, Object oldValue, Object newValue) {
        if (closed) {
            throw new OperateFailedException(
                    "Rootie scope is closed; cannot write. "
                            + "Open a new Rootie via Rootie.acquireTest().");
        }
        Thread current = Thread.currentThread();
        if (current != owner) {
            throw new OperateFailedException(
                    "TEST mode Rootie is bound to " + owner.getName()
                            + "; got " + current.getName()
                            + ". Open a separate Rootie per thread, "
                            + "or use NORMAL mode.");
        }
        undo.addLast(new WriteRecord(
                target,
                field,
                field.getType(),
                oldValue,
                newValue,
                Modifier.isStatic(field.getModifiers())));
    }

    /**
     * {@link RedefineRecorder} 的实现：记录一次方法体重定义到 undo-log。
     *
     * <p>线程检查由 {@link RootDoRedefine} 在提交前完成。此处的
     * {@code closed} 检查为防御性检查——正常流程下，调用方不应在 scope
     * 关闭后继续 redefine。</p>
     *
     * @param target      被 redefine 的类
     * @param oldBytecode redefine 之前的字节码
     * @param registryId  {@code replace} 分配的 id；专用字节码路径为 {@code null}
     * @throws OperateFailedException scope 已关闭时
     */
    private void recordRedefine(Class<?> target, byte[] oldBytecode, Integer registryId) {
        if (closed) {
            throw new OperateFailedException(
                    "Rootie scope is closed; cannot redefine.");
        }
        undo.addLast(new RedefineRecord(target, oldBytecode, registryId));
    }

    // ===== 生命周期 =====

    /**
     * 回放 undo-log 并关闭。
     *
     * <p>行为：</p>
     * <ul>
     *   <li>{@link AcquireMode#NORMAL} /
     *       {@link AcquireMode#BEFORE_SECURITY_MANAGER}：no-op。</li>
     *   <li>{@link AcquireMode#TEST}：按 LIFO 回滚；字段冲突时按
     *       {@link AcquireMode.ConflictPolicy#ROLLBACK_AND_REPORT}
     *       先回滚再抛 {@link ScopeCloseException}。</li>
     *   <li>{@link AcquireMode#TEST_KEEP}：按 LIFO 回滚洁净字段条目；
     *       冲突字段条目保留现场，汇总为 {@link ScopeCloseException}
     *       抛出。redefine 条目仍无条件回滚。</li>
     * </ul>
     *
     * <p>本方法幂等：重复调用是 no-op。</p>
     *
     * @throws OperateFailedException 从非持有线程调用，或 redefine 回滚失败时
     * @throws ScopeCloseException    字段回放检测到冲突时
     */
    @Override
    public void close() {
        if (undo == null) return;                 // NORMAL / BEFORE_SM
        if (Thread.currentThread() != owner) {
            throw new OperateFailedException(
                    "TEST mode Rootie is bound to " + owner.getName()
                            + "; close() called from "
                            + Thread.currentThread().getName() + ".");
        }
        if (closed) return;
        closed = true;
        replayUndo();
    }

    /**
     * 按 LIFO 回放 undo-log。
     *
     * <p>字段条目的冲突判定使用引用比较（原始类型按值比较），见
     * {@link #sameValue(Object, Object, Class)}。“洁净”条目一定回滚；
     * “冲突”条目按 {@link AcquireMode#conflictPolicy()} 决定回滚或保留。
     * redefine 条目无条件回滚——字节码不做冲突检测，直接以旧版本覆盖，
     * 并注销 {@code replace} 注册的替换函数。</p>
     *
     * <p>字段冲突与 redefine 回滚失败分别汇总：字段冲突抛
     * {@link ScopeCloseException}，redefine 失败抛
     * {@link OperateFailedException}。若两者同时存在，redefine 失败作为
     * suppressed 附加在 {@link ScopeCloseException} 上。</p>
     */
    private void replayUndo() {
        if (undo.isEmpty()) {
            log.scopeClosed(mode.tag(), 0, 0);
            return;
        }

        List<ScopeCloseException.Conflict> conflicts = new ArrayList<>();
        List<Throwable> redefineFailures = new ArrayList<>();
        boolean rollbackOnConflict =
                mode.conflictPolicy() == AcquireMode.ConflictPolicy.ROLLBACK_AND_REPORT;
        int rolledBack = 0;

        while (!undo.isEmpty()) {
            UndoEntry entry = undo.pollLast();       // LIFO

            if (entry instanceof WriteRecord) {
                rolledBack += replayWrite((WriteRecord) entry, conflicts, rollbackOnConflict);
            } else if (entry instanceof RedefineRecord) {
                rolledBack += replayRedefine((RedefineRecord) entry, redefineFailures);
            }
        }

        log.scopeClosed(mode.tag(), rolledBack, conflicts.size());

        if (!conflicts.isEmpty()) {
            ScopeCloseException ex = new ScopeCloseException(conflicts);
            for (Throwable t : redefineFailures) ex.addSuppressed(t);
            log.failed("scope_close", Rootie.class, mode.tag(), ex);
            throw ex;
        }
        if (!redefineFailures.isEmpty()) {
            OperateFailedException ex = new OperateFailedException(
                    "Scope closed with " + redefineFailures.size()
                            + " redefine rollback failure(s).",
                    redefineFailures.get(0));
            for (int i = 1; i < redefineFailures.size(); i++) {
                ex.addSuppressed(redefineFailures.get(i));
            }
            log.failed("scope_close", Rootie.class, mode.tag(), ex);
            throw ex;
        }
    }

    /**
     * 回放单条字段写入条目。
     *
     * @param r                  字段写入记录
     * @param conflicts          冲突收集器
     * @param rollbackOnConflict 冲突时是否回滚
     * @return 成功回滚返回 {@code 1}，否则 {@code 0}
     */
    private int replayWrite(WriteRecord r,
                            List<ScopeCloseException.Conflict> conflicts,
                            boolean rollbackOnConflict) {
        RootDoField fields = rtdoField();
        Class<?> owner = r.field().getDeclaringClass();
        String name = r.field().getName();
        int level = Audit.Level.of(r.field());

        try {
            Object current = fields.readRaw(r.target(), r.field());
            boolean clean = sameValue(current, r.newValue(), r.fieldType());

            if (clean || rollbackOnConflict) {
                fields.writeRaw(r.target(), r.field(), r.oldValue());
                log.fieldRollback(owner, name, level);
                if (!clean) conflicts.add(new ScopeCloseException.Conflict(r, current));
                return 1;
            } else {
                log.fieldRollbackSkipped(owner, name, level);
                conflicts.add(new ScopeCloseException.Conflict(r, current));
                return 0;
            }
        } catch (Throwable t) {
            conflicts.add(new ScopeCloseException.Conflict(r, null, t));
            log.failed("scope_rollback", owner, name, t);
            return 0;
        }
    }

    /**
     * 回放单条方法体重定义条目。
     *
     * @param rr       重定义记录
     * @param failures 失败收集器
     * @return 成功回滚返回 {@code 1}，否则 {@code 0}
     */
    private int replayRedefine(RedefineRecord rr, List<Throwable> failures) {
        Class<?> target = rr.target();
        try {
            redefineOps.restoreForRollback(target, rr.oldBytecode());
            if (rr.registryId() != null) {
                MethodRegistry.unregister(rr.registryId().intValue());
            }
            log.redefineRollback(target);
            return 1;
        } catch (Throwable t) {
            log.failed("scope_rollback_redefine", target, "<class>", t);
            failures.add(t);
            return 0;
        }
    }

    // ===== 工具 =====

    /**
     * 按字段声明类型分派的值比较。
     *
     * <p>原始类型按值比较（{@code float}/{@code double} 用
     * {@link Float#compare}/{@link Double#compare} 以正确处理
     * {@code NaN}）；引用类型按<b>引用</b>比较，不调 {@code equals}——
     * 我们要判断的是“外部有没有换过引用”，不是“内容是否相等”。</p>
     *
     * @param a     当前值
     * @param b     期望值
     * @param type  字段声明类型
     * @return 相等返回 {@code true}
     */
    private static boolean sameValue(Object a, Object b, Class<?> type) {
        if (type == int.class)     return ((Integer) a).intValue()     == ((Integer) b).intValue();
        if (type == long.class)    return ((Long) a).longValue()       == ((Long) b).longValue();
        if (type == boolean.class) return ((Boolean) a).booleanValue() == ((Boolean) b).booleanValue();
        if (type == byte.class)    return ((Byte) a).byteValue()       == ((Byte) b).byteValue();
        if (type == short.class)   return ((Short) a).shortValue()     == ((Short) b).shortValue();
        if (type == char.class)    return ((Character) a).charValue()  == ((Character) b).charValue();
        if (type == float.class)   return Float.compare((Float) a, (Float) b) == 0;
        if (type == double.class)  return Double.compare((Double) a, (Double) b) == 0;
        return a == b;
    }

    /**
     * 返回底层 {@link IUnsafe} 实现所报告的平台版本字符串。
     *
     * @return 例如 {@code "jdk.internal.misc.Unsafe (JDK 17+)"}
     */
    public String unsafeVersion() {
        return unsafe.version();
    }
}