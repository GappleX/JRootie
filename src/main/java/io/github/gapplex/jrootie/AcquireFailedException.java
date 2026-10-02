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

/**
 * 表示 {@code Rootie.acquire()}（及其变体）提权流程失败。
 *
 * <p>通常由以下原因触发：</p>
 * <ul>
 *   <li>无法获取 {@code sun.misc.Unsafe}（缺少 {@code --add-opens}）</li>
 *   <li>无法通过反射读取 {@code MethodHandles.Lookup.IMPL_LOOKUP}</li>
 *   <li>无法解析 {@code Class#getDeclaredXxx0} 等原生方法句柄</li>
 * </ul>
 *
 * @since 0.1.0
 */
public class AcquireFailedException extends RuntimeException {

    /**
     * @param message 描述信息
     * @param cause   原始异常
     */
    public AcquireFailedException(String message, Throwable cause){
        super(message, cause);
    }

    /**
     * @param message 描述信息
     */
    public AcquireFailedException(String message){
        super(message);
    }

    /**
     * @param cause 原始异常
     */
    public AcquireFailedException(Throwable cause){
        super(cause);
    }
}