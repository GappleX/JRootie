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

import java.lang.invoke.MethodHandle;

/**
 * 类级别的反射操作封装。
 *
 * <p>基于 {@code Class#getDeclaredClasses0} 的 {@link MethodHandle}，
 * 绕过常规反射的访问检查，访问任意类声明的内部类（含 {@code private}
 * 成员类）。</p>
 *
 * <p>本类实例由 {@link Rootie#rtdoClass()} 统一创建与持有，不应由外部
 * 直接构造。{@link Rootie#close()} 后所有公开方法抛
 * {@link OperateFailedException}。</p>
 *
 * <p><b>安全审计：</b>操作的成功与失败都会经由 {@link Audit} 记录，
 * 记录内容仅包含结构信息（类名、调用点），不包含对象内容。</p>
 *
 * @see Rootie
 * @since 0.1.0
 */
public class RootDoClass {

    /** 审计日志器。 */
    private static final Audit log = Log.audit(RootDoClass.class);

    /** scope 共享状态，{@link Rootie#close()} 后置为已关闭。 */
    private final ScopeState state;

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
     * @param classes {@code getDeclaredClasses0} 的 MethodHandle
     * @param state   scope 共享状态
     */
    RootDoClass(MethodHandle classes, ScopeState state) {
        GET_DECLARED_CLASSES_0 = classes;
        this.state = state;
    }

    /**
     * 获取指定类中声明的所有内部类（含 {@code private} 成员类）。
     *
     * <p>返回数组是缓存内容的防御性拷贝，调用方修改返回数组不会影响缓存。</p>
     *
     * @param owner 目标类，不可为 {@code null}
     * @return 该类的声明类数组；无声明类时返回空数组
     * @throws OperateFailedException scope 已关闭、{@code owner} 为 {@code null}，
     *                                或底层调用失败时抛出
     */
    public Class<?>[] getDeclaredClasses(Class<?> owner) {
        state.checkOpen();
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