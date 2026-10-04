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
package io.github.gapplex.jrootie.redefine;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

/**
 * 方法替换函数的注册表。
 *
 * <p>桥接字节码通过整数 id 在此查表并取出用户注册的
 * {@link Function}。id 由 {@link #register(Function)} 单调分配，永不复用。</p>
 *
 * <p><b>可见性</b>：本类必须对被 redefine 的类可见。应用类由同一
 * classloader 加载即可直接访问；替换 JDK 内部类需将其追加到 bootstrap
 * 搜索路径（由 {@code Agent} 负责）。</p>
 *
 * <p><b>生命周期</b>：注册项在 {@link #unregister(int)} 调用前一直有效。
 * 已注册的函数不会被 GC 回收，调用方在长期运行场景下需自行控制注册总量。</p>
 *
 * @since 0.2.0
 */
public final class MethodRegistry {

    private static final AtomicInteger NEXT_ID = new AtomicInteger(1);

    private static final ConcurrentHashMap<Integer, Function<Context, Object>> FUNCTIONS =
            new ConcurrentHashMap<>();

    private MethodRegistry() {}

    /**
     * 注册一个替换函数并返回其 id。
     *
     * @param fn 替换逻辑，不可为 {@code null}
     * @return 用于桥接字节码的整数 id，{@code >= 1}
     */
    public static int register(Function<Context, Object> fn) {
        if (fn == null) throw new IllegalArgumentException("fn must not be null");
        int id = NEXT_ID.getAndIncrement();
        FUNCTIONS.put(Integer.valueOf(id), fn);
        return id;
    }

    /**
     * 桥接字节码的调用入口。
     *
     * @param id       替换函数 id
     * @param receiver 接收者；静态方法为 {@code null}
     * @param args     参数数组
     * @return 替换函数的返回值
     * @throws Throwable 替换函数自身抛出的异常，原样透传
     */
    public static Object invoke(int id, Object receiver, Object[] args) throws Throwable {
        Function<Context, Object> fn = FUNCTIONS.get(Integer.valueOf(id));
        if (fn == null) {
            throw new IllegalStateException(
                    "No replacement registered for id " + id);
        }
        return fn.apply(new Context(receiver, args));
    }

    /**
     * 注销一个替换函数。对未注册或已注销的 id 调用为空操作。
     *
     * @param id 注册时返回的 id
     */
    public static void unregister(int id) {
        FUNCTIONS.remove(Integer.valueOf(id));
    }

    /**
     * 清空所有注册项。
     *
     * <p>用于测试收尾或应用关闭阶段；正常业务路径不应调用，否则会导致
     * 已 redefine 的方法在下次调用时抛 {@link IllegalStateException}。</p>
     */
    public static void clear() {
        FUNCTIONS.clear();
    }

    /** @return 当前注册的替换函数个数，用于诊断 */
    public static int size() {
        return FUNCTIONS.size();
    }
}