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

import io.github.gapplex.jrootie.exceptions.OperateFailedException;

/**
 * 提权 scope 的共享状态。所有由同一个 {@link Rootie} 派生的操作器共享
 * 一个实例。
 *
 * <p>{@link Rootie#close()} 时调 {@link #close()}，之后所有操作器对
 * {@link #checkOpen()} 的调用抛 {@link OperateFailedException}。
 * {@link AcquireMode#NORMAL} 模式下 {@code close} 是 no-op——state 永不
 * 关闭，所有检查自动通过。</p>
 *
 * <p><b>内部回滚路径不检查。</b>{@link RootDoField#readRaw} /
 * {@link RootDoField#writeRaw} / {@code restoreForRollback} 等包级方法
 * 由 undo 回放调用——此时 scope 已关闭，但它们必须执行。它们不走
 * {@link #checkOpen()}。</p>
 *
 * @since 0.4.0
 */
final class ScopeState {

    private volatile boolean closed = false;

    void close() {
        closed = true;
    }

    boolean isClosed() {
        return closed;
    }

    void checkOpen() {
        if (closed) {
            throw new OperateFailedException(
                    "Rootie scope is closed; acquire a new Rootie.");
        }
    }
}