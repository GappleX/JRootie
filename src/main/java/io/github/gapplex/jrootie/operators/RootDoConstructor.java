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
package io.github.gapplex.jrootie.operators;

import io.github.gapplex.jrootie.Audit;
import io.github.gapplex.jrootie.Log;
import io.github.gapplex.jrootie.OperateFailedException;
import io.github.gapplex.jrootie.PrimitiveUtils;
import io.github.gapplex.jrootie.unsafe.IUnsafe;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.Constructor;

/**
 * 构造器级别的反射操作封装。
 *
 * <p>通过 {@code MethodHandles.Lookup#IMPL_LOOKUP} 的
 * {@link MethodHandles.Lookup#unreflectConstructor(Constructor)} 直接调用
 * 任意可见性的构造器（包括 {@code private}），不经过
 * {@code setAccessible} 机制。</p>
 *
 * <p>本类实例由 {@link Rootie#rtdoConstructor()} 创建并持有。</p>
 *
 * <p><b>安全审计：</b>构造成功与失败都会记录到 {@link Audit}，
 * 不记录任何参数值。</p>
 *
 * @see Rootie
 * @since 0.1.0
 */
public class RootDoConstructor {

    /** 审计日志器。 */
    private static final Audit log = Log.audit(RootDoConstructor.class);

    /** 空参数类型数组常量，避免每次分配。 */
    private static final Class<?>[] NO_PARAMS = new Class<?>[0];

    /** 空参数数组常量，避免每次分配。 */
    private static final Object[] NO_ARGS = new Object[0];

    /** 底层 Unsafe 抽象。 */
    private final IUnsafe UNSAFE;

    /** IMPL_LOOKUP。 */
    private final MethodHandles.Lookup IMPL_LOOKUP;

    /** {@code Class#getDeclaredConstructors0(boolean)} 的句柄。 */
    private final MethodHandle GET_DECLARED_CONSTRUCTORS_0;

    /**
     * 构造器缓存的 {@link ClassValue}。首次访问时获取类的全部声明构造器，
     * 后续按参数类型线性查找。
     */
    private final ClassValue<Constructor<?>[]> CONSTRUCTORS = new ClassValue<>() {
        @Override
        protected Constructor<?>[] computeValue(Class<?> type) {
            return declaredConstructors(type);
        }
    };

    /**
     * 包级构造器，仅供 {@link Rootie} 调用。
     *
     * @param unsafe        Unsafe 抽象
     * @param lookup        IMPL_LOOKUP
     * @param constructors  {@code getDeclaredConstructors0} 的 MethodHandle
     */
    RootDoConstructor(IUnsafe unsafe, MethodHandles.Lookup lookup, MethodHandle constructors) {
        UNSAFE = unsafe;
        IMPL_LOOKUP = lookup;
        GET_DECLARED_CONSTRUCTORS_0 = constructors;
    }

    /**
     * 通过匹配参数类型调用构造器创建实例。
     *
     * <p>参数匹配规则：先对形参与实参的 {@code Class} 做基本类型包装
     * （见 {@link PrimitiveUtils#wrap(Class)}），再按 {@code ==} 精确比较。
     * 不做自动装箱/协变匹配，参数类型必须严格一致。</p>
     *
     * @param type       目标类型，不可为 {@code null}
     * @param paramTypes 构造器形参类型数组，{@code null} 表示无参
     * @param args       构造器实参，{@code null} 视为空数组
     * @param <T>        目标类型泛型
     * @return 已初始化的新实例
     * @throws OperateFailedException 当类型为 {@code null}、找不到匹配构造器、
     *                                构造器调用抛出异常或参数不合法时
     */
    public <T> T newInstance(Class<T> type, Class<?>[] paramTypes, Object... args) {
        if (type == null) throw new OperateFailedException("type must not be null");

        try {
            Constructor<T> c = findConstructor(type, paramTypes);
            T instance = doNew(c, args == null ? NO_ARGS : args);
            log.constructorNew(type);
            return instance;
        } catch (OperateFailedException e) {
            log.failed("new_instance", type, "<init>", e);
            throw e;
        } catch (Throwable t) {
            log.failed("new_instance", type, "<init>", t);
            throw new OperateFailedException(
                    "Construct '" + type.getName() + "' failed.", t);
        }
    }

    /**
     * 在给定类型的声明构造器中查找与参数类型匹配的构造器。
     *
     * @param type       目标类型
     * @param paramTypes 期望的形参类型数组，{@code null} 视为空
     * @param <T>        目标类型泛型
     * @return 匹配的构造器
     * @throws OperateFailedException 无匹配构造器时
     */
    @SuppressWarnings("unchecked")
    private <T> Constructor<T> findConstructor(Class<T> type, Class<?>[] paramTypes) {
        Class<?>[] wanted = (paramTypes == null) ? NO_PARAMS : paramTypes;

        for (Constructor<?> c : CONSTRUCTORS.get(type)) {
            if (matches(c.getParameterTypes(), wanted)) {
                return (Constructor<T>) c;
            }
        }
        throw new OperateFailedException(
                "Constructor '" + type.getName() + describe(wanted) + "' not found.");
    }

    /**
     * 通过 IMPL_LOOKUP 解析构造器并调用。
     *
     * @param c    目标构造器
     * @param args 实参数组（非 {@code null}）
     * @param <T>  目标类型泛型
     * @return 新实例
     * @throws Throwable 构造器自身抛出的异常
     */
    @SuppressWarnings("unchecked")
    private <T> T doNew(Constructor<T> c, Object[] args) throws Throwable {
        MethodHandle mh = IMPL_LOOKUP.unreflectConstructor(c);
        return (T) mh.invokeWithArguments(args);
    }

    /**
     * 判断两组参数类型是否匹配（先基本类型包装，再 {@code ==} 比较）。
     *
     * @param formal 形参类型
     * @param actual 实参类型
     * @return 长度一致且逐项包装后相等时返回 {@code true}
     */
    private static boolean matches(Class<?>[] formal, Class<?>[] actual) {
        if (formal.length != actual.length) return false;
        for (int i = 0; i < formal.length; i++) {
            if (PrimitiveUtils.wrap(formal[i]) != PrimitiveUtils.wrap(actual[i])) return false;
        }
        return true;
    }

    /**
     * 通过 MethodHandle 获取类的所有声明构造器。
     *
     * @param type 目标类
     * @return 声明构造器数组
     * @throws OperateFailedException 底层调用失败时
     */
    private Constructor<?>[] declaredConstructors(Class<?> type) {
        try {
            return (Constructor<?>[]) GET_DECLARED_CONSTRUCTORS_0.invoke(type, false);
        } catch (Throwable t) {
            throw new OperateFailedException(
                    "Failed to get declared constructors of " + type.getName(), t);
        }
    }

    /**
     * 将参数类型数组格式化为 {@code "(A, B)"} 形式，用于错误信息。
     *
     * @param types 参数类型
     * @return 可读描述
     */
    private static String describe(Class<?>[] types) {
        if (types.length == 0) return "()";
        StringBuilder sb = new StringBuilder("(");
        for (int i = 0; i < types.length; i++) {
            if (i > 0) sb.append(", ");
            sb.append(types[i].getName());
        }
        return sb.append(")").toString();
    }
}