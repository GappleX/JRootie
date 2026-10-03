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
import io.github.gapplex.jrootie.unsafe.IUnsafe;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;

/**
 * 类级别的反射操作封装。
 *
 * <p>本类基于 {@code MethodHandles.Lookup#IMPL_LOOKUP} 与
 * {@code Class#getDeclaredClasses0} 的 {@link MethodHandle}，绕过常规反射的
 * 访问检查，访问任意类的内部成员。</p>
 *
 * <p>本类实例由 {@link Rootie#rtdoClass()} 统一创建与持有，不应由外部直接构造。</p>
 *
 * <p><b>安全审计：</b>所有公开操作的成功与失败都会经由 {@link Audit} 记录，
 * 记录内容仅包含结构信息（类名、调用点），不包含对象内容。</p>
 *
 * @see Rootie
 * @see IUnsafe
 * @since 0.1.0
 */
public class RootDoClass {

    /** 审计日志器。 */
    private static final Audit log = Log.audit(RootDoClass.class);

    /** 底层 Unsafe 抽象，用于实例分配。 */
    private final IUnsafe UNSAFE;

    /** IMPL_LOOKUP，用于绕过访问检查的 MethodHandle 解析。 */
    private final MethodHandles.Lookup IMPL_LOOKUP;

    /** {@code Class#getDeclaredClasses0()} 的句柄。 */
    private final MethodHandle GET_DECLARED_CLASSES_0;

    /**
     * 声明类缓存的 {@link ClassValue}。每个类首次访问时通过
     * {@link #GET_DECLARED_CLASSES_0} 获取其声明类数组并缓存，
     * 避免重复反射调用。
     */
    private final ClassValue<Class<?>[]> DECLARED_CLASSES = new ClassValue<>() {
        @Override
        protected Class<?>[] computeValue(Class<?> type) {
            try {
                return (Class<?>[]) GET_DECLARED_CLASSES_0.invoke(type);
            } catch (Throwable t) {
                throw new OperateFailedException(
                        "Failed to get declared classes of " + type.getName(), t);
            }
        }
    };

    /**
     * 包级构造器，仅供 {@link Rootie} 调用。
     *
     * @param unsafe   Unsafe 抽象
     * @param lookup   IMPL_LOOKUP
     * @param classes  {@code getDeclaredClasses0} 的 MethodHandle
     */
    RootDoClass(IUnsafe unsafe, MethodHandles.Lookup lookup, MethodHandle classes) {
        UNSAFE = unsafe;
        IMPL_LOOKUP = lookup;
        GET_DECLARED_CLASSES_0 = classes;
    }

    /**
     * 在<b>不调用构造器</b>的前提下分配一个实例。
     *
     * <p>等价于 {@code Unsafe#allocateInstance(Class)}，返回的对象其字段
     * 保持 JVM 默认值（{@code 0}/{@code null}），跳过任何构造器逻辑
     * （包括 {@code final} 字段赋值、父类构造、静态初始化块）。</p>
     *
     * @param type 目标类型，不可为 {@code null}
     * @param <T>  目标类型泛型
     * @return 已分配但未初始化的实例
     * @throws OperateFailedException 当 {@code type} 为 {@code null}，
     *                                或底层分配失败时抛出
     */
    @SuppressWarnings("unchecked")
    public <T> T allocate(Class<T> type) {
        if (type == null) throw new OperateFailedException("type must not be null");
        try {
            T instance = (T) UNSAFE.allocateInstance(type);
            log.constructorNew(type);
            return instance;
        } catch (OperateFailedException e) {
            throw e;
        } catch (Throwable t) {
            log.failed("allocate", type, "<new>", t);
            throw new OperateFailedException(
                    "Allocate instance of '" + type.getName() + "' failed.", t);
        }
    }

    /**
     * 获取指定类中声明的所有内部类（含 {@code private} 成员类）。
     *
     * <p>返回数组是缓存内容的防御性拷贝，调用方修改返回数组不会影响缓存。</p>
     *
     * @param owner 目标类，不可为 {@code null}
     * @return 该类的声明类数组；无声明类时返回空数组
     * @throws OperateFailedException 当 {@code owner} 为 {@code null}，
     *                                或底层调用失败时抛出
     */
    public Class<?>[] getDeclaredClasses(Class<?> owner) {
        if (owner == null) throw new OperateFailedException("owner must not be null");
        try {
            return DECLARED_CLASSES.get(owner).clone();
        } catch (OperateFailedException e) {
            throw e;
        } catch (Throwable t) {
            log.failed("get_declared_classes", owner, "<class>", t);
            throw new OperateFailedException(
                    "Get declared classes of '" + owner.getName() + "' failed.", t);
        }
    }
}