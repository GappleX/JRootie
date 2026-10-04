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

import java.util.Objects;

/**
 * 替换函数观察到的调用上下文。
 *
 * <p>暴露被 redefine 的方法所属的类、接收者、参数数组。线程、调用栈、
 * 时间等信息由调用方自行采集，本类不做隐式捕获。</p>
 *
 * <p>实例由 {@link MethodRegistry#invoke(int, Class, Object, Object[])}
 * 在每次桥接调用时创建，不可变。</p>
 *
 * @since 0.2.0
 */
public final class Context {

    private final Class<?> owner;
    private final Object receiver;
    private final Object[] args;

    Context(Class<?> owner, Object receiver, Object[] args) {
        this.owner = Objects.requireNonNull(owner, "owner");
        this.receiver = receiver;
        this.args = args;
    }

    /**
     * 返回被 redefine 的方法所属的类。
     *
     * <p>这是方法定义所在的类——即使 {@code receiver} 是子类实例，
     * 也返回方法声明所在的父类。静态方法有相同的返回。</p>
     *
     * <p>用途：</p>
     * <ul>
     *   <li>{@code ContextOps} 用它定位静态字段与静态方法的 owner 类</li>
     *   <li>诊断：{@code ctx.owner().getName()} 告诉你正在替换谁的方法</li>
     *   <li>接口 static 方法：{@code receiver.getClass()} 走不到接口，
     *       {@code owner()} 是唯一入口</li>
     * </ul>
     *
     * @return 方法所属的类
     */
    public Class<?> owner() {
        return owner;
    }

    /** @return 接收者对象；静态方法为 {@code null} */
    public Object receiver() {
        return receiver;
    }

    /** @return 是否为静态方法调用——等价于 {@code receiver() == null} */
    public boolean isStatic() {
        return receiver == null;
    }

    /** @return 参数数组；非 {@code null}，可能长度为 0 */
    public Object[] args() {
        return args;
    }

    /** @return 参数个数，等价于 {@code args().length} */
    public int argCount() {
        return args.length;
    }

    /**
     * 按索引读取参数。
     *
     * @param index 参数下标，从 0 开始
     * @param <T>   期望类型，由调用方保证
     * @return 参数值
     * @throws ArrayIndexOutOfBoundsException 下标越界时
     */
    @SuppressWarnings("unchecked")
    public <T> T arg(int index) {
        return (T) args[index];
    }

    @Override
    public String toString() {
        return "Context{owner=" + owner.getName()
                + ", receiver="
                + (receiver == null ? "null" : receiver.getClass().getName())
                + ", argCount=" + args.length + '}';
    }
}