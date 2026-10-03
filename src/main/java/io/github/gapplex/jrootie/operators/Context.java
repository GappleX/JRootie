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

/**
 * 替换函数观察到的调用上下文。
 *
 * <p>仅暴露接收者与参数数组。线程、调用栈、时间等信息由调用方自行采集，
 * 本类不做隐式捕获。</p>
 *
 * <p>实例由 {@link MethodRegistry#invoke(int, Object, Object[])} 在每次
 * 桥接调用时创建，不可变；{@link #args()} 返回的数组由 JVM 侧装箱产生，
 * 调用方不应假设其长度或元素类型之外的性质。</p>
 *
 * @since 0.2.0
 */
public final class Context {

    private final Object receiver;
    private final Object[] args;

    Context(Object receiver, Object[] args) {
        this.receiver = receiver;
        this.args = args;
    }

    /** @return 接收者对象；静态方法为 {@code null} */
    public Object receiver() {
        return receiver;
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
        return "Context{receiver="
                + (receiver == null ? "null" : receiver.getClass().getName())
                + ", argCount=" + args.length + '}';
    }
}