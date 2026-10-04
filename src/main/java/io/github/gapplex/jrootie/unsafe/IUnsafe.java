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
package io.github.gapplex.jrootie.unsafe;

import java.lang.reflect.Field;

/**
 * 对 Unsafe 的能力抽象。
 *
 * <p>本接口只暴露 jrootie 需要的最小子集：字段偏移访问、基本类型读写、
 * 实例分配。目的是让核心逻辑与具体 JVM 提供的 Unsafe 实现解耦
 * （在 Java25 可切换为 {@code jdk.internal.misc.Unsafe} ）。</p>
 *
 * <p>所有方法均假定调用方已持有合法的 {@code base} 与 {@code offset}，
 * 接口不做越界或空指针检查，由具体实现与 JVM 保证语义。</p>
 *
 * @since 0.1.0
 */
public interface IUnsafe {

    /**
     * 返回给定字段在该类（或父类）中的对象实例偏移。
     *
     * @param field 目标字段，必须为实例字段
     * @return 对象偏移量
     */
    long objectFieldOffset(Field field);

    /**
     * 返回给定静态字段的偏移量（相对 {@link #staticFieldBase(Field)}）。
     *
     * @param field 目标静态字段
     * @return 静态偏移量
     */
    long staticFieldOffset(Field field);

    /**
     * 返回静态字段所属的“基对象”。对于 HotSpot，通常是
     * {@code java.lang.Class} 的镜像对象或字段所在的 {@code Class}。
     *
     * @param field 目标静态字段
     * @return 基对象
     */
    Object staticFieldBase(Field field);

    /**
     * 从 {@code base + offset} 读取引用类型值。
     *
     * @param base   基对象
     * @param offset 偏移
     * @return 读取到的引用
     */
    Object getObject(Object base, long offset);

    /**
     * 向 {@code base + offset} 写入引用类型值。
     *
     * @param base   基对象
     * @param offset 偏移
     * @param value  新值
     */
    void putObject(Object base, long offset, Object value);

    /** @return {@code int} 字段值 */
    int getInt(Object base, long offset);

    /** @param value 新值 */
    void putInt(Object base, long offset, int value);

    /** @return {@code long} 字段值 */
    long getLong(Object base, long offset);

    /** @param value 新值 */
    void putLong(Object base, long offset, long value);

    /** @return {@code boolean} 字段值 */
    boolean getBoolean(Object base, long offset);

    /** @param value 新值 */
    void putBoolean(Object base, long offset, boolean value);

    /** @return {@code byte} 字段值 */
    byte getByte(Object base, long offset);

    /** @param value 新值 */
    void putByte(Object base, long offset, byte value);

    /** @return {@code short} 字段值 */
    short getShort(Object base, long offset);

    /** @param value 新值 */
    void putShort(Object base, long offset, short value);

    /** @return {@code char} 字段值 */
    char getChar(Object base, long offset);

    /** @param value 新值 */
    void putChar(Object base, long offset, char value);

    /** @return {@code float} 字段值 */
    float getFloat(Object base, long offset);

    /** @param value 新值 */
    void putFloat(Object base, long offset, float value);

    /** @return {@code double} 字段值 */
    double getDouble(Object base, long offset);

    /** @param value 新值 */
    void putDouble(Object base, long offset, double value);

    /**
     * 分配一个指定类的实例，不调用任何构造器。
     *
     * @param type 目标类
     * @return 未初始化实例
     * @throws InstantiationException 分配失败时（例如是抽象类/接口/数组类型）
     */
    Object allocateInstance(Class<?> type) throws InstantiationException;

    /**
     * 返回底层实现的版本描述，用于诊断与审计。
     *
     * @return 例如 {@code "jdk.internal.misc.Unsafe (JDK 17+)"}
     */
    String version();
}