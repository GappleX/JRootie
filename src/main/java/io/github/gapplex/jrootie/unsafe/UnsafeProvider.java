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
package io.github.gapplex.jrootie.unsafe;

import io.github.gapplex.jrootie.agent.InstrumentationHolder;

import java.lang.instrument.Instrumentation;
import java.lang.reflect.Field;
import java.util.Map;
import java.util.Set;

/**
 * {@link IUnsafe} 的全局提供者。
 *
 * <p>统一使用 {@code jdk.internal.misc.Unsafe}。通过 agent 注入的
 * {@link Instrumentation#redefineModule} 在运行期开放
 * {@code java.base/jdk.internal.misc} 给当前模块，无需 {@code --add-opens}。</p>
 *
 * <p><b>MR-JAR 版本选择</b>：{@code JdkInternalUnsafe} 在 jar 里存在两份——
 * base 版（JDK 11–16，用 {@code getObject} / {@code putObject}）与
 * {@code META-INF/versions/17/} 版（JDK 17+，用 {@code getReference} /
 * {@code putReference}）。JVM 加载本类里 {@code new JdkInternalUnsafe(u)} 的
 * 符号引用时，自动按运行 JDK 选择正确版本。</p>
 *
 * <p><b>强制 agent</b>：所有 JDK 版本都需要
 * {@code -javaagent:jrootie.jar}。没有 agent 直接抛
 * {@link IllegalStateException}。</p>
 *
 * @since 0.3.0
 */
public final class UnsafeProvider {

    private static final String UNSAFE_CLASS = "jdk.internal.misc.Unsafe";
    private static final String INTERNAL_PACKAGE = "jdk.internal.misc";

    private static volatile IUnsafe CACHED;

    private UnsafeProvider() {}

    /**
     * 返回全局唯一的 {@link IUnsafe} 实例。
     *
     * @return {@link IUnsafe} 实例
     * @throws IllegalStateException agent 未加载，或无法获取
     *                               {@code jdk.internal.misc.Unsafe} 时
     */
    public static IUnsafe get() {
        IUnsafe cached = CACHED;
        if (cached != null) return cached;

        synchronized (UnsafeProvider.class) {
            cached = CACHED;
            if (cached != null) return cached;

            cached = doGet();
            CACHED = cached;
            return cached;
        }
    }

    private static IUnsafe doGet() {
        Instrumentation inst = InstrumentationHolder.get();
        if (inst == null) {
            throw new IllegalStateException(
                    "jrootie requires -javaagent:jrootie.jar. "
                            + "All JDK versions need the agent.");
        }

        try {
            Class<?> unsafeClass = Class.forName(UNSAFE_CLASS);
            Module src = unsafeClass.getModule();
            Module target = UnsafeProvider.class.getModule();

            if (inst.isModifiableModule(src)
                    && !src.isOpen(INTERNAL_PACKAGE, target)) {
                inst.redefineModule(
                        src,
                        Set.of(),
                        Map.of(),
                        Map.of(INTERNAL_PACKAGE, Set.of(target)),
                        Set.of(),
                        Map.of());
            }

            Field theUnsafe = unsafeClass.getDeclaredField("theUnsafe");
            theUnsafe.setAccessible(true);
            Object u = theUnsafe.get(null);

            return new JdkInternalUnsafe(u);
        } catch (Throwable t) {
            throw new IllegalStateException(
                    "Cannot acquire jdk.internal.misc.Unsafe via agent", t);
        }
    }
}