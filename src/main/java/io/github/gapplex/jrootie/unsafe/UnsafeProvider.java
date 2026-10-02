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
package io.github.gapplex.jrootie.unsafe;

import sun.misc.Unsafe;

import java.lang.reflect.Field;

/**
 * {@link IUnsafe} 的全局提供者。
 *
 * <p>通过反射访问 {@code sun.misc.Unsafe#theUnsafe} 字段获取唯一实例，
 * 并包装为 {@link SunMiscUnsafe} 缓存。获取成功后不再重复反射。</p>
 *
 * <p><b>兼容性：</b>在部分 JDK 上，由于 JPMS 强封装，访问
 * {@code sun.misc.Unsafe} 需要添加 JVM 参数：</p>
 * <pre>{@code --add-opens java.base/sun.misc=ALL-UNNAMED}</pre>
 *
 * @since 0.1.0
 */
public final class UnsafeProvider {

    /** 已缓存的 {@link IUnsafe} 实例，双重检查锁定。 */
    private static volatile IUnsafe CACHED;

    private UnsafeProvider() {
    }

    /**
     * 返回全局唯一的 {@link IUnsafe} 实例。
     *
     * @return {@link IUnsafe} 实例
     * @throws IllegalStateException 无法获取 {@code sun.misc.Unsafe} 时
     *                               （常见原因：模块未开放、缺少必要 JVM 参数）
     */
    public static IUnsafe get() {
        IUnsafe cached = CACHED;
        if (cached != null) return cached;

        synchronized (UnsafeProvider.class) {
            cached = CACHED;
            if (cached != null) return cached;

            cached = doGet();
            CACHED = cached;
            return cached;
        }
    }

    /**
     * 执行真正的获取逻辑：反射 {@code theUnsafe} 字段并包装。
     *
     * @return 包装后的 {@link IUnsafe}
     * @throws IllegalStateException 反射失败时
     */
    private static IUnsafe doGet() {
        try {
            Field f = Unsafe.class.getDeclaredField("theUnsafe");
            f.setAccessible(true);
            Unsafe u = (Unsafe) f.get(null);
            return new SunMiscUnsafe(u);
        } catch (Throwable t) {
            throw new IllegalStateException(
                    "Cannot acquire sun.misc.Unsafe. On Java 16+ this requires "
                            + "--add-opens java.base/sun.misc=ALL-UNNAMED", t);
        }
    }
}