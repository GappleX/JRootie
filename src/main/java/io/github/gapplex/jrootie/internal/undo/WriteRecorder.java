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

import io.github.gapplex.jrootie.exceptions.OperateFailedException;
import io.github.gapplex.jrootie.operators.AcquireMode;
import io.github.gapplex.jrootie.operators.RootDoField;
import io.github.gapplex.jrootie.operators.Rootie;

import java.lang.reflect.Field;

/**
 * 字段写入前的回调，用于向 undo-log 记录条目。
 *
 * <p>由 {@link Rootie} 在 {@link AcquireMode#recordsUndo()} 为
 * {@code true} 时注入 {@link RootDoField}；{@link AcquireMode#NORMAL}
 * 下为 {@code null}，走零开销路径。</p>
 *
 * <p><b>回调时机：</b>在真正写入<b>之前</b>调用，{@code oldValue} 是
 * 调用时读到的当前值。回调抛异常时写入被中止。</p>
 *
 * <p><b>线程约束：</b>实现方（即 {@link Rootie}）负责校验回调发生在
 * 持有线程上，违反时抛
 * {@link OperateFailedException}。</p>
 *
 * @since 0.1.0
 */
@FunctionalInterface
public interface WriteRecorder {

    /**
     * @param target   目标实例；静态字段为 {@code null}
     * @param field    字段
     * @param oldValue 写入前的旧值（原始类型已装箱）
     * @param newValue 即将写入的值
     */
    void beforeWrite(Object target, Field field, Object oldValue, Object newValue);
}