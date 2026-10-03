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
package io.github.gapplex.jrootie.agent;

import java.lang.instrument.Instrumentation;

/**
 * 用于持有 {@link Instrumentation} 实例的静态容器。
 *
 * <p>该类型作为 Java Agent 的 {@code premain}/{@code agentmain} 与 jrootie 运行时
 * 之间的桥接点。Agent 在启动阶段调用 {@link #set(Instrumentation)} 注入实例，
 * 运行期其他代码通过 {@link #get()} 读取。</p>
 *
 * <p><b>线程安全：</b>字段使用 {@code volatile} 保证写入后的可见性，
 * 但不提供“写入一次后禁止覆写”的强制语义，调用方应自行约束只在 Agent
 * 初始化阶段设置一次。</p>
 *
 * <p><b>注意：</b>该类不是工具类，禁止实例化。</p>
 */
public final class InstrumentationHolder {

    /** 已注入的 Instrumentation 实例，未注入时为 {@code null}。 */
    private static volatile Instrumentation INST;

    private InstrumentationHolder() {
    }

    /**
     * 注入 Instrumentation 实例。
     *
     * @param inst Agent 启动时由 JVM 传入的 Instrumentation，不可为 {@code null}
     *             （当前实现未做校验，由调用方保证）
     */
    public static void set(Instrumentation inst) {
        INST = inst;
    }

    /**
     * 获取已注入的 Instrumentation 实例。
     *
     * @return 已注入的实例；若尚未注入则返回 {@code null}
     */
    public static Instrumentation get() {
        return INST;
    }
}