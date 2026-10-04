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
package io.github.gapplex.jrootie;

import org.slf4j.Logger;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 结构化审计日志器。
 *
 * <p><b>设计约束：</b>所有公开方法只接受“结构信息”——类名、字段名、
 * 动作名、调用点，不接受任何“值信息”（字段值、对象内容、hashCode）。
 * 需要记录值时，由调用方在应用层显式承担泄漏后果。</p>
 *
 * <p><b>故障隔离：</b>审计日志是观测手段，不是业务逻辑。底层 SLF4J
 * provider 抛出的任何异常都会被本类吞掉，以保证写入、回滚、close 等
 * 关键路径不因日志故障中断。首次故障向 {@code System.err} 报告一次，
 * 之后静默以避免刷屏。</p>
 *
 * <p>审计分级：</p>
 * <ul>
 *   <li>{@link Level#NORMAL}：普通写入，{@code DEBUG} 级别</li>
 *   <li>{@link Level#FINAL}：写入 {@code final} 字段，{@code WARN} 级别</li>
 * </ul>
 *
 * <p>本类实例由 {@link Log} 工厂方法创建。</p>
 *
 * @since 0.1.0
 */
public final class Audit {

    /**
     * 已报告日志故障标记。首次故障打印堆栈，后续静默。
     */
    private static final AtomicBoolean LOG_FAILURE_REPORTED = new AtomicBoolean();

    /** 底层 SLF4J logger。 */
    private final Logger logger;

    /**
     * 包级构造器，仅供 {@link Log} 调用。
     *
     * @param logger 已按 {@code jrootie.<ClassName>} 命名的 logger
     */
    Audit(Logger logger) {
        this.logger = logger;
    }

    /**
     * 记录一次提权。
     *
     * @param mode 模式标识，例如 {@code "normal"} 或
     *             {@code "before-security-manager"}
     */
    public void acquired(String mode) {
        safe(() -> logger.info("acquire mode={} caller={}", mode, caller()));
    }

    /**
     * 记录一次字段读取（仅结构信息）。仅当 TRACE 开启时才写出。
     *
     * @param owner 字段所属类
     * @param name  字段名
     */
    public void fieldRead(Class<?> owner, String name) {
        safe(() -> {
            if (logger.isTraceEnabled()) {
                logger.trace("read {}.{} [{}] caller={}",
                        owner.getName(), name, flags(owner, name), caller());
            }
        });
    }

    /**
     * 记录一次字段写入，按危险级别选择日志级别。
     *
     * @param owner 字段所属类
     * @param name  字段名
     * @param level 危险级别，见 {@link Level}
     */
    public void fieldWrite(Class<?> owner, String name, int level) {
        safe(() -> {
            if (level >= Level.FINAL) {
                logger.warn("write {}.{} [{}] caller={}",
                        owner.getName(), name, flags(owner, name), caller());
            } else if (logger.isDebugEnabled()) {
                logger.debug("write {}.{} caller={}",
                        owner.getName(), name, caller());
            }
        });
    }

    /**
     * 记录一次方法调用（仅结构信息）。仅当 DEBUG 开启时才写出。
     *
     * @param owner 方法所属类
     * @param name  方法名
     */
    public void methodInvoke(Class<?> owner, String name) {
        safe(() -> {
            if (logger.isDebugEnabled()) {
                logger.debug("invoke {}.{} caller={}",
                        owner.getName(), name, caller());
            }
        });
    }

    /**
     * 记录一次构造器调用（仅结构信息）。仅当 DEBUG 开启时才写出。
     *
     * @param target 目标类
     */
    public void constructorNew(Class<?> target) {
        safe(() -> {
            if (logger.isDebugEnabled()) {
                logger.debug("construct {} caller={}", target.getName(), caller());
            }
        });
    }

    /**
     * 记录一次字段回滚（成功）。
     *
     * @param owner 字段所属类
     * @param name  字段名
     * @param level 危险级别，见 {@link Level#of(Field)}
     */
    public void fieldRollback(Class<?> owner, String name, int level) {
        safe(() -> {
            if (logger.isDebugEnabled()) {
                logger.debug("rollback {}.{}", owner.getName(), name);
            }
        });
    }

    /**
     * 记录一次字段回滚被跳过（{@code TEST_KEEP} 模式下冲突条目保留现场）。
     *
     * @param owner 字段所属类
     * @param name  字段名
     * @param level 危险级别，见 {@link Level#of(Field)}
     */
    public void fieldRollbackSkipped(Class<?> owner, String name, int level) {
        safe(() -> logger.warn("rollback SKIPPED {}.{} [{}] caller={}",
                owner.getName(), name, flags(owner, name), caller()));
    }

    /**
     * 记录一次 scope 关闭汇总。
     *
     * @param mode        提权模式 tag
     * @param rolledBack  成功回滚的条目数
     * @param conflicts   冲突条目数（含回滚失败）
     */
    public void scopeClosed(String mode, int rolledBack, int conflicts) {
        safe(() -> logger.info(
                "scope closed mode={} rolledBack={} conflicts={} caller={}",
                mode, rolledBack, conflicts, caller()));
    }

    /**
     * 记录一次方法体重定义。
     *
     * <p>{@code kind} 是动作类型标识，由调用方提供，用于日志区分
     * {@code return} / {@code throw} / {@code noop} / {@code function#<id>}
     * 等不同意图。不记录任何参数值或字节码内容。</p>
     *
     * @param owner 方法所属类
     * @param name  方法名；{@code restore} 动作用 {@code "<restore>"} 占位
     * @param kind  动作类型标识
     */
    public void methodRedefine(Class<?> owner, String name, String kind) {
        safe(() -> logger.info("redefine {}.{} ({}) caller={}",
                owner.getName(), name, kind, caller()));
    }

    /**
     * 记录一次方法体重定义的回滚。
     *
     * @param target 被恢复的类
     */
    public void redefineRollback(Class<?> target) {
        safe(() -> {
            if (logger.isDebugEnabled()) {
                logger.debug("rollback redefine {}", target.getName());
            }
        });
    }

    /**
     * 记录一次操作失败。
     *
     * <p>只记录异常类名，不记录异常 message——message 可能携带字段值，
     * 会破坏“不记值”的约束。</p>
     *
     * @param action 动作名，例如 {@code "read_field"}
     * @param owner  目标类
     * @param name   目标名（字段名 / 方法名 / 模式）
     * @param t      原始异常
     */
    public void failed(String action, Class<?> owner, String name, Throwable t) {
        safe(() -> logger.error("{} {}.{} failed: {} caller={}",
                action, owner.getName(), name, t.getClass().getName(), caller()));
    }

    /**
     * 执行一次日志动作，吞掉底层 provider 抛出的任何异常。
     *
     * <p>日志系统故障（自定义 appender 抛异常、编码失败、IO 故障、
     * {@code isEnabled} 探测失败等）不应中断写入、回滚或 {@code close}。
     * 首次故障向 {@code System.err} 报告一次，之后静默。</p>
     *
     * <p><b>覆盖范围包括</b>：{@code isDebugEnabled} / {@code isTraceEnabled}
     * 探测、{@code caller()} 的 {@link StackWalker} 调用、{@code flags()}
     * 的反射调用、以及实际的 logger 输出。任何一环抛异常都被吞掉。</p>
     *
     * @param action 日志动作
     */
    private void safe(Runnable action) {
        try {
            action.run();
        } catch (Throwable t) {
            reportLogFailure(t);
        }
    }

    /**
     * 报告一次日志故障。首次调用打印堆栈，后续调用静默。
     *
     * <p>{@code System.err} 本身抛异常时，本方法会向上传播——这种情况
     * 通常意味着 JVM 已经不可用，任何策略都无法处理。</p>
     */
    private static void reportLogFailure(Throwable t) {
        if (LOG_FAILURE_REPORTED.compareAndSet(false, true)) {
            try {
                System.err.println("[jrootie] Audit log failure (suppressed; "
                        + "further failures will be silent): " + t);
                t.printStackTrace(System.err);
            } catch (Throwable ignored) {}
        }
    }

    // ===== 内部：结构信息组装 =====

    /**
     * 尝试组合字段修饰符标记，用于审计日志。
     *
     * @param owner 字段所属类
     * @param name  字段名
     * @return 例如 {@code "static final volatile"}；失败时返回 {@code "unknown"}
     */
    private static String flags(Class<?> owner, String name) {
        try {
            Field f = findField(owner, name);
            if (f == null) return "unknown";
            StringBuilder sb = new StringBuilder();
            int m = f.getModifiers();
            if (Modifier.isStatic(m)) sb.append("static ");
            if (Modifier.isFinal(m)) sb.append("final ");
            if (Modifier.isVolatile(m)) sb.append("volatile ");
            return sb.toString().trim();
        } catch (Throwable t) {
            return "unknown";
        }
    }

    /**
     * 沿继承链查找声明字段。
     *
     * @param owner 起始类
     * @param name  字段名
     * @return 找到的字段；未找到返回 {@code null}
     */
    private static Field findField(Class<?> owner, String name) {
        for (Class<?> c = owner; c != null; c = c.getSuperclass()) {
            try {
                return c.getDeclaredField(name);
            } catch (NoSuchFieldException ignored) {}
        }
        return null;
    }

    /**
     * 获取调用点描述，格式为 {@code Class.method:line}。
     *
     * <p>使用 {@link StackWalker} 避免构造完整栈帧，性能可控。
     * 过滤掉所有 {@code io.github.gapplex.jrootie.*} 内部帧，
     * 使 caller 指向真正触发操作的用户代码。</p>
     *
     * @return 调用点描述；无法获取时返回 {@code "unknown"}
     */
    private static String caller() {
        return StackWalker.getInstance()
                .walk(f -> f
                        .filter(e -> !e.getClassName().startsWith("io.github.gapplex.jrootie."))
                        .findFirst()
                        .map(e -> e.getClassName() + "." + e.getMethodName()
                                + ":" + e.getLineNumber())
                        .orElse("unknown"));
    }

    /**
     * 字段写入危险级别常量。
     *
     * <p>用于 {@link Audit#fieldWrite(Class, String, int)} 决定日志级别。</p>
     */
    public static final class Level {
        /** 普通字段，写入无需特别告警。 */
        public static final int NORMAL = 0;
        /** {@code final} 字段，写入属于突破不可变约定。 */
        public static final int FINAL = 1;
        private Level() {}

        /**
         * 按字段修饰符计算写入/回滚的危险级别。
         *
         * @param field 目标字段
         * @return {@link #NORMAL}、{@link #FINAL}
         *
         * @since 0.1.1
         */
        public static int of(Field field) {
            int mod = field.getModifiers();
            if (Modifier.isFinal(mod)) return FINAL;
            return NORMAL;
        }
    }
}