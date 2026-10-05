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

import io.github.gapplex.jrootie.Audit;
import io.github.gapplex.jrootie.Log;
import io.github.gapplex.jrootie.exceptions.OperateFailedException;
import io.github.gapplex.jrootie.PrimitiveUtils;
import io.github.gapplex.jrootie.unsafe.IUnsafe;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 方法级别的反射操作封装。
 *
 * <p>通过 {@code MethodHandles.Lookup#IMPL_LOOKUP} 的
 * {@link MethodHandles.Lookup#unreflect(Method)} 解析方法句柄，
 * 绕过访问检查调用任意可见性的方法（包括 {@code private}）。</p>
 *
 * <p>方法查找沿继承链进行，同名方法按声明顺序登记，参数类型精确匹配
 * （先基本类型包装，再 {@code ==} 比较）。</p>
 *
 * <p>本类实例由 {@link Rootie#rtdoMethod()} 创建并持有。
 * {@link Rootie#close()} 后所有公开方法抛 {@link OperateFailedException}。</p>
 *
 * @see Rootie
 * @since 0.1.0
 */
public class RootDoMethod {

    /** 审计日志器。 */
    private static final Audit log = Log.audit(RootDoMethod.class);

    /** 空参数类型数组常量。 */
    private static final Class<?>[] NO_PARAMS = new Class<?>[0];

    /** 空参数数组常量。 */
    private static final Object[] NO_ARGS = new Object[0];

    /** 底层 Unsafe 抽象。 */
    private final IUnsafe UNSAFE;

    /** IMPL_LOOKUP。 */
    private final MethodHandles.Lookup IMPL_LOOKUP;

    /** {@code Class#getDeclaredMethods0(boolean)} 的句柄。 */
    private final MethodHandle GET_DECLARED_METHODS_0;

    /** scope 共享状态，{@link Rootie#close()} 后置为已关闭。 */
    private final ScopeState state;

    /**
     * 方法缓存的 {@link ClassValue}。以类为键，值为“方法名 → 方法列表”的映射，
     * 沿继承链自上而下收集，父类与子类同名方法都保留在同名列表中。
     */
    private final ClassValue<Map<String, List<Method>>> METHODS = new ClassValue<>() {
        @Override
        protected Map<String, List<Method>> computeValue(Class<?> type) {
            Map<String, List<Method>> map = new HashMap<>();
            for (Class<?> c = type; c != null; c = c.getSuperclass()) {
                for (Method m : declaredMethods(c)) {
                    map.computeIfAbsent(m.getName(), k -> new ArrayList<>()).add(m);
                }
            }
            return map;
        }
    };

    /**
     * 包级构造器，仅供 {@link Rootie} 调用。
     *
     * @param unsafe  Unsafe 抽象
     * @param lookup  IMPL_LOOKUP
     * @param methods {@code getDeclaredMethods0} 的 MethodHandle
     * @param state   scope 共享状态
     */
    RootDoMethod(IUnsafe unsafe, MethodHandles.Lookup lookup,
                 MethodHandle methods, ScopeState state) {
        UNSAFE = unsafe;
        IMPL_LOOKUP = lookup;
        GET_DECLARED_METHODS_0 = methods;
        this.state = state;
    }

    /**
     * 调用实例方法。
     *
     * @param instance   目标实例，不可为 {@code null}
     * @param methodName 方法名，不可为 {@code null}
     * @param paramTypes 形参类型数组，{@code null} 表示无参
     * @param args       实参，{@code null} 视为空数组
     * @return 方法返回值（{@code void} 返回 {@code null}）
     * @throws OperateFailedException scope 已关闭、参数非法、方法未找到、
     *                                方法抛出异常或底层调用失败时抛出
     */
    public Object invoke(Object instance, String methodName,
                         Class<?>[] paramTypes, Object... args) {
        state.checkOpen();
        if (instance == null) throw new OperateFailedException("instance must not be null");
        if (methodName == null) throw new OperateFailedException("methodName must not be null");

        Class<?> owner = instance.getClass();
        try {
            Method m = findMethod(owner, methodName, paramTypes, false);
            Object result = doInvoke(instance, m, normalize(args));
            log.methodInvoke(owner, methodName);
            return result;
        } catch (OperateFailedException e) {
            log.failed("invoke_method", owner, methodName, e);
            throw e;
        } catch (Throwable t) {
            log.failed("invoke_method", owner, methodName, t);
            throw new OperateFailedException(
                    "Invoke method '" + owner.getName() + "." + methodName + "' failed.", t);
        }
    }

    /**
     * 调用静态方法。
     *
     * @param owner      所属类，不可为 {@code null}
     * @param methodName 方法名，不可为 {@code null}
     * @param paramTypes 形参类型数组，{@code null} 表示无参
     * @param args       实参，{@code null} 视为空数组
     * @return 方法返回值（{@code void} 返回 {@code null}）
     * @throws OperateFailedException scope 已关闭、参数非法、方法未找到、
     *                                方法抛出异常或底层调用失败时抛出
     */
    public Object invokeStatic(Class<?> owner, String methodName,
                               Class<?>[] paramTypes, Object... args) {
        state.checkOpen();
        if (owner == null) throw new OperateFailedException("owner must not be null");
        if (methodName == null) throw new OperateFailedException("methodName must not be null");

        try {
            Method m = findMethod(owner, methodName, paramTypes, true);
            Object result = doInvoke(null, m, normalize(args));
            log.methodInvoke(owner, methodName);
            return result;
        } catch (OperateFailedException e) {
            log.failed("invoke_static_method", owner, methodName, e);
            throw e;
        } catch (Throwable t) {
            log.failed("invoke_static_method", owner, methodName, t);
            throw new OperateFailedException(
                    "Invoke static method '" + owner.getName() + "." + methodName + "' failed.", t);
        }
    }

    /**
     * 通过 IMPL_LOOKUP 解析并调用方法。
     *
     * @param receiver 接收者；静态方法传 {@code null}
     * @param m        目标方法
     * @param args     实参数组
     * @return 方法返回值
     * @throws Throwable 方法自身抛出的异常
     */
    private Object doInvoke(Object receiver, Method m, Object[] args) throws Throwable {
        MethodHandle mh = IMPL_LOOKUP.unreflect(m);
        if (!Modifier.isStatic(m.getModifiers())) {
            mh = mh.bindTo(receiver);
        }
        return mh.invokeWithArguments(args);
    }

    /**
     * 在给定类型的方法表中查找匹配方法。
     *
     * @param type         起始查找类
     * @param name         方法名
     * @param paramTypes   期望的形参类型数组，{@code null} 视为空
     * @param staticMethod 是否要求静态方法
     * @return 匹配的 {@link Method}
     * @throws OperateFailedException 无匹配方法时抛出
     */
    private Method findMethod(Class<?> type, String name,
                              Class<?>[] paramTypes, boolean staticMethod) {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(name, "name");

        Class<?>[] wanted = (paramTypes == null) ? NO_PARAMS : paramTypes;

        List<Method> candidates = METHODS.get(type).get(name);
        if (candidates != null) {
            for (Method m : candidates) {
                if (Modifier.isStatic(m.getModifiers()) != staticMethod) continue;
                if (matches(m.getParameterTypes(), wanted)) return m;
            }
        }
        throw new OperateFailedException(
                "Method '" + type.getName() + "." + name + describe(wanted) + "' not found.");
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
     * 通过 MethodHandle 获取类的所有声明方法。
     *
     * @param type 目标类
     * @return 声明方法数组
     * @throws OperateFailedException 底层调用失败时
     */
    private Method[] declaredMethods(Class<?> type) {
        try {
            return (Method[]) GET_DECLARED_METHODS_0.invoke(type, false);
        } catch (Throwable t) {
            throw new OperateFailedException(
                    "Failed to get declared methods of " + type.getName(), t);
        }
    }

    /**
     * 将可能为 {@code null} 的实参数组归一化为非 {@code null}。
     *
     * @param args 输入实参，可为 {@code null}
     * @return 非 {@code null} 的实参数组
     */
    private static Object[] normalize(Object[] args) {
        return (args == null) ? NO_ARGS : args;
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