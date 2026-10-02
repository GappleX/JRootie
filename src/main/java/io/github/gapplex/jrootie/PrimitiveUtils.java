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
 * 基本类型与包装类型之间的转换工具。
 *
 * <p>jrootie 在反射匹配参数/字段类型时，先将基本类型统一包装为对应的
 * 包装类，再做 {@code ==} 比较，从而避免 {@code int} 与 {@code Integer}
 * 被视为不同。</p>
 *
 * <p>本类为工具类，禁止实例化。</p>
 *
 * @since 0.1.0
 */
public class PrimitiveUtils {

    /**
     * 私有构造器，防止实例化。
     *
     * @throws IllegalStateException 始终抛出
     */
    private PrimitiveUtils(){
        throw new IllegalStateException();
    }

    /**
     * 将基本类型包装为其对应的包装类；非基本类型原样返回。
     *
     * <p>映射关系：</p>
     * <ul>
     *   <li>{@code int} → {@code Integer}</li>
     *   <li>{@code long} → {@code Long}</li>
     *   <li>{@code double} → {@code Double}</li>
     *   <li>{@code float} → {@code Float}</li>
     *   <li>{@code short} → {@code Short}</li>
     *   <li>{@code byte} → {@code Byte}</li>
     *   <li>{@code boolean} → {@code Boolean}</li>
     *   <li>{@code char} → {@code Character}</li>
     *   <li>{@code void} → {@code Void}</li>
     * </ul>
     *
     * @param c 输入类型；可为 {@code null}（返回 {@code null}）
     * @return 包装类型或原类型
     */
    public static Class<?> wrap(Class<?> c) {
        if (c == int.class) return Integer.class;
        if (c == long.class) return Long.class;
        if (c == double.class) return Double.class;
        if (c == float.class) return Float.class;
        if (c == short.class) return Short.class;
        if (c == byte.class) return Byte.class;
        if (c == boolean.class) return Boolean.class;
        if (c == char.class) return Character.class;
        if (c == void.class) return Void.class;
        return c;
    }
}