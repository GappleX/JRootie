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

import java.util.Objects;

/**
 * undo-log 条目：一次方法体重定义。
 *
 * <p>回滚时用 {@link #oldBytecode()} 恢复 {@link #target()} 的类定义；
 * 若 {@link #registryId()} 非空，同时从 {@link MethodRegistry} 注销。</p>
 *
 * @since 0.2.0
 */
final class RedefineRecord implements UndoEntry {

    private final Class<?> target;
    private final byte[] oldBytecode;
    private final Integer registryId;

    /**
     * @param target      被 redefine 的类
     * @param oldBytecode redefine 之前的字节码（回滚用）
     * @param registryId  {@code replace} 分配的 id；专用字节码路径为 {@code null}
     */
    RedefineRecord(Class<?> target, byte[] oldBytecode, Integer registryId) {
        this.target = Objects.requireNonNull(target, "target");
        this.oldBytecode = Objects.requireNonNull(oldBytecode, "oldBytecode");
        this.registryId = registryId;
    }

    Class<?> target() {
        return target;
    }

    byte[] oldBytecode() {
        return oldBytecode;
    }

    Integer registryId() {
        return registryId;
    }
}