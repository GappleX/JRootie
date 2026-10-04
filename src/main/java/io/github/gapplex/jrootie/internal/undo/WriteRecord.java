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
package io.github.gapplex.jrootie.internal.undo;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.Objects;

/**
 * 一条 undo-log 条目，表示一次字段写入。
 *
 * <p>{@code oldValue} 在<b>写入瞬间</b>读取，不是 scope 打开时缓存——
 * 这是嵌套 scope 正确回滚的前提。</p>
 *
 * <p><b>语义边界：</b>{@code oldValue} 是引用（对引用类型字段）。
 * 回滚是“写回旧引用”，不是“恢复对象内部状态”。如果 {@code oldValue}
 * 指向的对象在 scope 存续期被外部修改，回滚不会撤销这些修改。</p>
 *
 * <p>本类是值对象，不可变。</p>
 *
 * @since 0.1.0
 */
public final class WriteRecord implements UndoEntry {

    private final Object target;
    private final Field field;
    private final Class<?> fieldType;
    private final Object oldValue;
    private final Object newValue;
    private final boolean isStatic;

    /**
     * @param target     目标实例；静态字段为 {@code null}
     * @param field      字段，不可为 {@code null}
     * @param fieldType  字段的声明类型，不可为 {@code null}
     * @param oldValue   写入前的旧值（原始类型已装箱）
     * @param newValue   本次写入的值
     * @param isStatic   是否为静态字段，必须与 {@code field} 修饰符一致
     * @throws IllegalArgumentException 参数不合法或 {@code isStatic} 不一致时
     */
    public WriteRecord(Object target,
                       Field field,
                       Class<?> fieldType,
                       Object oldValue,
                       Object newValue,
                       boolean isStatic) {
        if (field == null) throw new IllegalArgumentException("field must not be null");
        if (fieldType == null) throw new IllegalArgumentException("fieldType must not be null");
        if (Modifier.isStatic(field.getModifiers()) != isStatic) {
            throw new IllegalArgumentException(
                    "isStatic mismatch for " + field);
        }
        this.target = target;
        this.field = field;
        this.fieldType = fieldType;
        this.oldValue = oldValue;
        this.newValue = newValue;
        this.isStatic = isStatic;
    }

    /** @return 目标实例；静态字段为 {@code null} */
    public Object target() {
        return target;
    }

    /** @return 字段 */
    public Field field() {
        return field;
    }

    /** @return 字段的声明类型 */
    public Class<?> fieldType() {
        return fieldType;
    }

    /** @return 写入前的旧值 */
    public Object oldValue() {
        return oldValue;
    }

    /** @return 本次写入的值 */
    public Object newValue() {
        return newValue;
    }

    /** @return 是否为静态字段 */
    public boolean isStatic() {
        return isStatic;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof WriteRecord)) return false;
        WriteRecord that = (WriteRecord) o;
        return isStatic == that.isStatic
                && Objects.equals(target, that.target)
                && Objects.equals(field, that.field)
                && Objects.equals(fieldType, that.fieldType)
                && Objects.equals(oldValue, that.oldValue)
                && Objects.equals(newValue, that.newValue);
    }

    @Override
    public int hashCode() {
        return Objects.hash(target, field, fieldType, oldValue, newValue, isStatic);
    }

    @Override
    public String toString() {
        return "WriteRecord{"
                + "field=" + field
                + ", isStatic=" + isStatic
                + ", oldValue=" + describe(oldValue)
                + ", newValue=" + describe(newValue)
                + '}';
    }

    private static String describe(Object v) {
        if (v == null) return "null";
        return v.getClass().getSimpleName()
                + "@" + Integer.toHexString(System.identityHashCode(v));
    }
}