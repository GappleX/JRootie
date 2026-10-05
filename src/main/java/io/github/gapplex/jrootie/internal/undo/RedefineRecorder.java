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
package io.github.gapplex.jrootie.internal.undo;

import io.github.gapplex.jrootie.operators.RootDoRedefine.Session;
import io.github.gapplex.jrootie.operators.RootDoRedefine;
import io.github.gapplex.jrootie.operators.Rootie;

import java.util.List;

/**
 * redefine 成功后的回调，用于将操作记入 undo-log。
 *
 * <p>{@link Rootie} 在 TEST 模式下注入实现；NORMAL 模式下为 {@code null}，
 * {@link RootDoRedefine} 走零开销路径。</p>
 *
 * <p><b>回调时机</b>：字节码已提交到 JVM、缓存已更新之后。</p>
 *
 * <p><b>多 id 语义</b>：链式 session（{@link Session}）累积多个
 * {@code replace} 调用时，所有替换函数 id 在一次回调中传入。回滚时按 id
 * 列表批量注销。</p>
 *
 * @since 0.2.0
 */
@FunctionalInterface
public interface RedefineRecorder {

    /**
     * @param target       被 redefine 的类
     * @param oldBytecode  redefine 之前的字节码
     * @param registryIds  {@code replace} 注册的所有 id；非 {@code null}，
     *                     可能为空（专用字节码路径如 makeReturn / makeThrow / makeNoOp）
     */
    void afterRedefine(Class<?> target, byte[] oldBytecode, List<Integer> registryIds);
}