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
package io.github.gapplex.jrootie;

import org.slf4j.Logger;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;

/**
 * 结构化审计日志器。
 *
 * <p><b>设计约束：</b>所有公开方法只接受“结构信息”——类名、字段名、
 * 动作名、调用点，不接受任何“值信息”（字段值、对象内容、hashCode）。
 * 需要记录值时，由调用方在应用层显式承担泄漏后果。</p>
 *
 * <p>审计分级：</p>
 * <ul>
 *   <li>{@link Level#NORMAL}：普通写入，{@code DEBUG} 级别</li>
 *   <li>{@link Level#FINAL}：写入 {@code final} 字段，{@code WARN} 级别</li>
 *   <li>{@link Level#CRITICAL}：写入非 {@code final} 但可视为敏感的字段，{@code WARN} 级别</li>
 * </ul>
 *
 * <p>本类实例由 {@link Log} 工厂方法创建。</p>
 *
 * @since 0.1.0
 */
public final class Audit {

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
        logger.info("acquire mode={} caller={}", mode, caller());
    }

    /**
     * 记录一次字段读取（仅结构信息）。仅当 TRACE 开启时才写出。
     *
     * @param owner 字段所属类
     * @param name  字段名
     */
    public void fieldRead(Class<?> owner, String name) {
        if (logger.isTraceEnabled()) {
            logger.trace("read {}.{} [{}] caller={}",
                    owner.getName(), name, flags(owner, name), caller());
        }
    }

    /**
     * 记录一次字段写入，按危险级别选择日志级别。
     *
     * @param owner 字段所属类
     * @param name  字段名
     * @param level 危险级别，见 {@link Level}
     */
    public void fieldWrite(Class<?> owner, String name, int level) {
        if (level >= Level.CRITICAL) {
            logger.warn("write {}.{} [{}] caller={}",
                    owner.getName(), name, flags(owner, name), caller());
        } else if (level >= Level.FINAL) {
            logger.warn("write {}.{} [final] caller={}",
                    owner.getName(), name, caller());
        } else {
            if (logger.isDebugEnabled()) {
                logger.debug("write {}.{} caller={}",
                        owner.getName(), name, caller());
            }
        }
    }

    /**
     * 记录一次方法调用（仅结构信息）。仅当 DEBUG 开启时才写出。
     *
     * @param owner 方法所属类
     * @param name  方法名
     */
    public void methodInvoke(Class<?> owner, String name) {
        if (logger.isDebugEnabled()) {
            logger.debug("invoke {}.{} caller={}",
                    owner.getName(), name, caller());
        }
    }

    /**
     * 记录一次构造器调用（仅结构信息）。仅当 DEBUG 开启时才写出。
     *
     * @param target 目标类
     */
    public void constructorNew(Class<?> target) {
        if (logger.isDebugEnabled()) {
            logger.debug("construct {} caller={}", target.getName(), caller());
        }
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
        logger.error("{} {}.{} failed: {} caller={}",
                action, owner.getName(), name, t.getClass().getName(), caller());
    }

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
     * <p>使用 {@link StackWalker} 避免构造完整栈帧，性能可控。</p>
     *
     * @return 调用点描述；无法获取时返回 {@code "unknown"}
     */
    private static String caller() {
        return StackWalker.getInstance()
                .walk(f -> f.skip(2)
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
        /** 允许更细粒度地标记敏感字段（当前未被内置使用，供调用方扩展）。 */
        public static final int CRITICAL = 2;
        private Level() {}
    }
}