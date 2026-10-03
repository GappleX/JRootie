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
package io.github.gapplex.jrootie.exceptions;

/**
 * 表示一次运行时反射操作失败。
 *
 * <p>典型触发场景：</p>
 * <ul>
 *   <li>参数非法（例如 {@code null}）</li>
 *   <li>目标字段 / 方法 / 构造器不存在</li>
 *   <li>类型不兼容</li>
 *   <li>底层 {@code Unsafe} / {@code MethodHandle} 调用失败</li>
 * </ul>
 *
 * @since 0.1.0
 */
public class OperateFailedException extends RuntimeException {

    /**
     * @param message 描述信息
     */
    public OperateFailedException(String message) {
        super(message);
    }

    /**
     * @param message 描述信息
     * @param t       原始异常
     */
    public OperateFailedException(String message, Throwable t) {
        super(message, t);
    }
}