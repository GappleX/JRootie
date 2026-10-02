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
package io.github.gapplex.jrootie.agent;

import java.lang.instrument.Instrumentation;

/**
 * jrootie 的 Java Agent 入口。
 *
 * <p>{@code premain} 由 JVM 在启动阶段调用（{@code -javaagent}），
 * {@code agentmain} 由 {@code Instrumentation#loadAgent} 动态调用。
 * 两者都只做一件事：把 {@link Instrumentation} 存进
 * {@link InstrumentationHolder}，供运行时（JDK 25+ 的
 * {@code jdk.internal.misc.Unsafe} 路径）读取。</p>
 *
 * <p><b>编译级别约束：</b>本类必须能用 Java 9 编译，且必须位于 base
 * 源根（{@code src/main/java}）。JVM 加载 {@code Premain-Class} 时
 * 不保证走 MR-JAR 版本解析，把入口类放进 {@code versions/25} 可能
 * 导致 {@code ClassNotFoundException}。</p>
 *
 * @since 0.1.0
 */
public final class Agent {

    private Agent() {}

    /**
     * 启动阶段入口。
     *
     * @param args 命令行传入的 agent 参数（未使用）
     * @param inst JVM 注入的 Instrumentation
     */
    public static void premain(String args, Instrumentation inst) {
        InstrumentationHolder.set(inst);
    }

    /**
     * 动态附加入口。
     *
     * @param args agent 参数（未使用）
     * @param inst JVM 注入的 Instrumentation
     */
    public static void agentmain(String args, Instrumentation inst) {
        InstrumentationHolder.set(inst);
    }
}