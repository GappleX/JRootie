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

import io.github.gapplex.jrootie.operators.RootDoRedefine;

import java.util.List;
import java.util.Objects;

/**
 * undo-log 条目：一次方法体重定义。
 *
 * <p>回滚时用 {@link #oldBytecode()} 恢复 {@link #target()} 的类定义；
 * 并批量注销 {@link #registryIds()} 中的替换函数。</p>
 *
 * <p>链式 redefine（{@link RootDoRedefine.Session}）可能在一个条目中携带多个
 * 替换函数 id——它们在同一次 {@code redefineClasses} 调用中生效，
 * 回滚时也一起注销。</p>
 *
 * @since 0.2.0
 */
public final class RedefineRecord implements UndoEntry {

    private final Class<?> target;
    private final byte[] oldBytecode;
    private final List<Integer> registryIds;

    /**
     * @param target      被 redefine 的类
     * @param oldBytecode redefine 之前的字节码（回滚用）
     * @param registryIds {@code replace} 注册的所有 id；专用字节码路径传空列表
     */
    public RedefineRecord(Class<?> target, byte[] oldBytecode, List<Integer> registryIds) {
        this.target = Objects.requireNonNull(target, "target");
        this.oldBytecode = Objects.requireNonNull(oldBytecode, "oldBytecode");
        this.registryIds = List.copyOf(Objects.requireNonNull(registryIds, "registryIds"));
    }

    public Class<?> target() {
        return target;
    }

    public byte[] oldBytecode() {
        return oldBytecode;
    }

    public List<Integer> registryIds() {
        return registryIds;
    }
}