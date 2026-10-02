/*
 * Copyright (C) 2026 GapplX
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

import io.github.gapplex.jrootie.AcquireFailedException;
import io.github.gapplex.jrootie.Audit;
import io.github.gapplex.jrootie.Log;
import io.github.gapplex.jrootie.OperateFailedException;
import io.github.gapplex.jrootie.ScopeCloseException;
import io.github.gapplex.jrootie.unsafe.IUnsafe;
import io.github.gapplex.jrootie.unsafe.UnsafeProvider;

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
 * {@link RootDoConstructor}、{@link RootDoClass} 四个操作器。</p>
 *
 * <h2>模式与自动恢复</h2>
 *
 * <p>提权模式由 {@link AcquireMode} 决定，在 {@code acquire} 时确定：</p>
 * <ul>
 *   <li>{@link AcquireMode#NORMAL}：默认，无 undo，零额外开销。</li>
 *   <li>{@link AcquireMode#TEST} / {@link AcquireMode#TEST_KEEP}：
 *       记录所有字段写入的 undo-log，{@link #close()} 时按 LIFO 回滚。</li>
 *   <li>{@link AcquireMode#BEFORE_SECURITY_MANAGER}：与原语义一致，
 *       无 undo。</li>
 * </ul>
 *
 * <h2>自动恢复的边界</h2>
 *
 * <p>只覆盖<b>字段写入</b>。构造器调用与方法调用的副作用不在恢复范围——
 * 硬做只会给出“部分回滚”的假象。scope 只保证它自己写入过的字段按
 * LIFO 回滚；它不扫描全局、不追踪外部修改、不恢复对象内部状态。</p>
 *
 * <h2>线程绑定</h2>
 *
 * <p>{@code TEST*} 模式下 {@code Rootie} 绑定创建线程。非持有线程调用
 * 写方法时抛 {@link OperateFailedException}。并行测试应每线程各自
 * {@code acquire}。</p>
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
 * <p><b>安全提示：</b>获取 {@code sun.misc.Unsafe} 在 Java 16+ 上需要
 * 添加 {@code --add-opens java.base/sun.misc=ALL-UNNAMED}。</p>
 *
 * @since 0.1.0
 * @see AcquireMode
 * @see WriteRecord
 * @see WriteRecorder
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
     */
    private final ArrayDeque<WriteRecord> undo;

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
        this.undo = mode.recordsUndo() ? new ArrayDeque<WriteRecord>() : null;
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
     * 测试模式入口。写入字段时自动记录 undo，{@link #close()} 时
     * 按 LIFO 回滚；冲突策略
     * {@link AcquireMode.ConflictPolicy#ROLLBACK_AND_REPORT}。
     *
     * @return 已初始化的 {@code Rootie}（{@link AcquireMode#TEST}）
     * @throws AcquireFailedException 提权失败时
     */
    public static Rootie acquireTest() {
        return doAcquire("test");
    }

    /**
     * 测试模式 + 冲突保留入口。冲突时不回滚，保留现场供调试。
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
     * @throws OperateFailedException 当前线程不是持有线程时
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

    // ===== 生命周期 =====

    /**
     * 回放 undo-log 并关闭。
     *
     * <p>行为：</p>
     * <ul>
     *   <li>{@link AcquireMode#NORMAL} /
     *       {@link AcquireMode#BEFORE_SECURITY_MANAGER}：no-op。</li>
     *   <li>{@link AcquireMode#TEST}：按 LIFO 回滚；冲突时按
     *       {@link AcquireMode.ConflictPolicy#ROLLBACK_AND_REPORT}
     *       先回滚再抛 {@link ScopeCloseException}。</li>
     *   <li>{@link AcquireMode#TEST_KEEP}：按 LIFO 回滚洁净条目；
     *       冲突条目保留现场，汇总为 {@link ScopeCloseException} 抛出。</li>
     * </ul>
     *
     * <p>本方法幂等：重复调用是 no-op。</p>
     *
     * @throws OperateFailedException 从非持有线程调用时
     * @throws ScopeCloseException    回放检测到冲突时
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
     * <p>冲突判定使用引用比较（原始类型按值比较），见
     * {@link #sameValue(Object, Object, Class)}。“洁净”条目一定回滚；
     * “冲突”条目按 {@link AcquireMode#conflictPolicy()} 决定回滚或保留。</p>
     */
    private void replayUndo() {
        if (undo.isEmpty()) return;

        List<ScopeCloseException.Conflict> conflicts = new ArrayList<>();
        RootDoField fields = rtdoField();
        boolean rollbackOnConflict =
                mode.conflictPolicy() == AcquireMode.ConflictPolicy.ROLLBACK_AND_REPORT;

        while (!undo.isEmpty()) {
            WriteRecord r = undo.pollLast();
            try {
                Object current = fields.readRaw(r.target(), r.field());
                boolean clean = sameValue(current, r.newValue(), r.fieldType());
                if (clean || rollbackOnConflict) {
                    fields.writeRaw(r.target(), r.field(), r.oldValue());
                }
                if (!clean) {
                    conflicts.add(new ScopeCloseException.Conflict(r, current));
                }
            } catch (Throwable t) {
                conflicts.add(new ScopeCloseException.Conflict(r, null, t));
                log.failed("scope_rollback", Rootie.class, mode.tag(), t);
            }
        }

        if (!conflicts.isEmpty()) {
            ScopeCloseException ex = new ScopeCloseException(conflicts);
            log.failed("scope_close", Rootie.class, mode.tag(), ex);
            throw ex;
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
     * @return 例如 {@code "sun.misc.Unsafe (Java 9+)"}
     */
    public String unsafeVersion() {
        return unsafe.version();
    }
}