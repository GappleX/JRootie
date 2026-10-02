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
import java.lang.invoke.VarHandle;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * 字段级别的反射操作封装。
 *
 * <p>读写策略分两级：</p>
 * <ol>
 *   <li>优先使用 {@link MethodHandles.Lookup#unreflectVarHandle(Field)} 获得
 *       {@link VarHandle} 进行读写（语义清晰，支持 {@code volatile} 语义）。</li>
 *   <li>若第一条路径不可用（如 {@code final} 字段、受限字段），回退到
 *       {@link IUnsafe} 的字段偏移读写，绕过 JIT 常量折叠。</li>
 * </ol>
 *
 * <p>字段查询沿继承链自顶向下，子类字段优先。</p>
 *
 * <h2>undo 记录</h2>
 *
 * <p>构造时若传入非 {@code null} 的 {@link WriteRecorder}，每次通过公开
 * 写入路径（{@link #setFieldValue} / {@link #setStaticFieldValue}）写字段
 * 之前会先回调该 recorder。<b>内部回放路径</b>（{@link #writeRaw}）不经
 * recorder，避免回滚操作污染 undo-log。</p>
 *
 * <p>本类实例由 {@link Rootie#rtdoField()} 创建并持有。</p>
 *
 * @see Rootie
 * @see WriteRecorder
 * @since 0.1.0
 */
public class RootDoField {

    /** 审计日志器。 */
    private static final Audit log = Log.audit(RootDoField.class);

    /** 底层 Unsafe 抽象。 */
    private final IUnsafe UNSAFE;

    /** IMPL_LOOKUP。 */
    private final MethodHandles.Lookup IMPL_LOOKUP;

    /** {@code Class#getDeclaredFields0(boolean)} 的句柄。 */
    private final MethodHandle GET_DECLARED_FIELDS_0;

    /**
     * 写入前回调；{@code null} 表示不记录 undo（{@link AcquireMode#NORMAL}）。
     */
    private final WriteRecorder recorder;

    /**
     * 字段缓存的 {@link ClassValue}。以类为键，值为“字段名 → Field”的映射。
     * 沿继承链自上而下遍历，子类同名 {@code field} 不会被父类覆盖。
     */
    private final ClassValue<Map<String, Field>> FIELDS = new ClassValue<Map<String, Field>>() {
        @Override
        protected Map<String, Field> computeValue(Class<?> type) {
            Map<String, Field> map = new HashMap<String, Field>();
            for (Class<?> c = type; c != null; c = c.getSuperclass()) {
                for (Field f : declaredFields(c)) {
                    if (!map.containsKey(f.getName())) {
                        map.put(f.getName(), f);
                    }
                }
            }
            return map;
        }
    };

    /**
     * 包级构造器，仅供 {@link Rootie} 调用。
     *
     * @param unsafe   Unsafe 抽象
     * @param lookup   IMPL_LOOKUP
     * @param fields   {@code getDeclaredFields0} 的 MethodHandle
     * @param recorder 写入前回调，{@code null} 表示不记录 undo
     */
    RootDoField(IUnsafe unsafe, MethodHandles.Lookup lookup,
                MethodHandle fields, WriteRecorder recorder) {
        UNSAFE = unsafe;
        IMPL_LOOKUP = lookup;
        GET_DECLARED_FIELDS_0 = fields;
        this.recorder = recorder;
    }

    /**
     * 根据字段修饰符计算写入的危险级别，用于审计日志分级。
     *
     * @param field 目标字段
     * @return {@link Audit.Level#NORMAL}、{@link Audit.Level#FINAL}
     *         或 {@link Audit.Level#CRITICAL} 之一
     */
    private static int level(Field field) {
        int mod = field.getModifiers();
        if (Modifier.isFinal(mod)) return 2;
        if (Modifier.isPrivate(mod) || Modifier.isProtected(mod)) return 1;
        return 0;
    }

    /**
     * 按字段类型从 {@link IUnsafe} 读取值。
     *
     * @param unsafe 底层抽象
     * @param base   目标对象（静态字段为对应基对象）
     * @param offset 字段偏移
     * @param type   字段类型
     * @return 读到的值（基本类型装箱）
     */
    private static Object readAt(IUnsafe unsafe, Object base, long offset, Class<?> type) {
        if (type == int.class) return unsafe.getInt(base, offset);
        if (type == long.class) return unsafe.getLong(base, offset);
        if (type == boolean.class) return unsafe.getBoolean(base, offset);
        if (type == byte.class) return unsafe.getByte(base, offset);
        if (type == short.class) return unsafe.getShort(base, offset);
        if (type == char.class) return unsafe.getChar(base, offset);
        if (type == float.class) return unsafe.getFloat(base, offset);
        if (type == double.class) return unsafe.getDouble(base, offset);
        return unsafe.getObject(base, offset);
    }

    /**
     * 按字段类型写入 {@link IUnsafe}。
     *
     * @param unsafe 底层抽象
     * @param base   目标对象
     * @param offset 字段偏移
     * @param type   字段类型
     * @param value  要写入的值（基本类型已装箱）
     */
    private static void writeAt(IUnsafe unsafe, Object base, long offset,
                                Class<?> type, Object value) {
        if (type == int.class) unsafe.putInt(base, offset, (Integer) value);
        else if (type == long.class) unsafe.putLong(base, offset, (Long) value);
        else if (type == boolean.class) unsafe.putBoolean(base, offset, (Boolean) value);
        else if (type == byte.class) unsafe.putByte(base, offset, (Byte) value);
        else if (type == short.class) unsafe.putShort(base, offset, (Short) value);
        else if (type == char.class) unsafe.putChar(base, offset, (Character) value);
        else if (type == float.class) unsafe.putFloat(base, offset, (Float) value);
        else if (type == double.class) unsafe.putDouble(base, offset, (Double) value);
        else unsafe.putObject(base, offset, value);
    }

    /**
     * 读取实例字段的值。
     *
     * @param instance  目标实例，不可为 {@code null}
     * @param fieldName 字段名，不可为 {@code null}
     * @param type      期望的类型（用于可读性校验），不可为 {@code null}
     * @param <T>       期望的返回类型
     * @return 字段值
     * @throws OperateFailedException 参数非法、字段不存在、类型不兼容、
     *                                或底层读取失败时抛出
     */
    @SuppressWarnings("unchecked")
    public <T> T getFieldValue(Object instance, String fieldName, Class<? extends T> type) {
        if (instance == null) throw new OperateFailedException("instance must not be null");
        if (fieldName == null) throw new OperateFailedException("fieldName must not be null");
        if (type == null) throw new OperateFailedException("type must not be null");

        Class<?> owner = instance.getClass();
        try {
            Field target = findField(owner, fieldName, type, false, false);
            Object value = read(instance, target);
            log.fieldRead(owner, fieldName);
            return (T) value;
        } catch (OperateFailedException e) {
            log.failed("read_field", owner, fieldName, e);
            throw e;
        } catch (Throwable t) {
            log.failed("read_field", owner, fieldName, t);
            throw new OperateFailedException(
                    "Read field '" + owner.getName() + "." + fieldName + "' failed.", t);
        }
    }

    /**
     * 写入实例字段的值。
     *
     * <p>若字段为 {@code final}，会跳过 {@link VarHandle} 路径，
     * 直接使用 {@link IUnsafe} 的偏移写入以绕过 JIT 常量折叠。</p>
     *
     * <p>若构造时提供了 {@link WriteRecorder}，本方法在真正写入之前回调它。</p>
     *
     * @param instance  目标实例，不可为 {@code null}
     * @param fieldName 字段名，不可为 {@code null}
     * @param value     要写入的值；可为 {@code null}（非基本类型字段）
     * @throws OperateFailedException 参数非法、字段不存在、类型不兼容、
     *                                或底层写入失败时抛出
     */
    public void setFieldValue(Object instance, String fieldName, Object value) {
        if (instance == null) throw new OperateFailedException("instance must not be null");
        if (fieldName == null) throw new OperateFailedException("fieldName must not be null");

        Class<?> owner = instance.getClass();
        try {
            Field target = findField(owner, fieldName,
                    value == null ? null : value.getClass(), true, false);
            write(instance, target, value);
            log.fieldWrite(owner, fieldName, level(target));
        } catch (OperateFailedException e) {
            log.failed("write_field", owner, fieldName, e);
            throw e;
        } catch (Throwable t) {
            log.failed("write_field", owner, fieldName, t);
            throw new OperateFailedException(
                    "Write field '" + owner.getName() + "." + fieldName + "' failed.", t);
        }
    }

    /**
     * 读取静态字段的值。
     *
     * @param owner     字段所属类，不可为 {@code null}
     * @param fieldName 字段名，不可为 {@code null}
     * @param type      期望类型，不可为 {@code null}
     * @param <T>       期望返回类型
     * @return 字段值
     * @throws OperateFailedException 参数非法、字段不存在、非静态字段、
     *                                类型不兼容或底层读取失败时抛出
     */
    @SuppressWarnings("unchecked")
    public <T> T getStaticFieldValue(Class<?> owner, String fieldName, Class<? extends T> type) {
        if (owner == null) throw new OperateFailedException("owner must not be null");
        if (fieldName == null) throw new OperateFailedException("fieldName must not be null");
        if (type == null) throw new OperateFailedException("type must not be null");

        try {
            Field target = findField(owner, fieldName, type, false, true);
            Object value = readStatic(target);
            log.fieldRead(owner, fieldName);
            return (T) value;
        } catch (OperateFailedException e) {
            log.failed("read_static_field", owner, fieldName, e);
            throw e;
        } catch (Throwable t) {
            log.failed("read_static_field", owner, fieldName, t);
            throw new OperateFailedException(
                    "Read static field '" + owner.getName() + "." + fieldName + "' failed.", t);
        }
    }

    /**
     * 写入静态字段的值。
     *
     * <p>若构造时提供了 {@link WriteRecorder}，本方法在真正写入之前回调它。</p>
     *
     * @param owner     字段所属类，不可为 {@code null}
     * @param fieldName 字段名，不可为 {@code null}
     * @param value     要写入的值；可为 {@code null}
     * @throws OperateFailedException 参数非法、字段不存在、非静态字段、
     *                                类型不兼容或底层写入失败时抛出
     */
    public void setStaticFieldValue(Class<?> owner, String fieldName, Object value) {
        if (owner == null) throw new OperateFailedException("owner must not be null");
        if (fieldName == null) throw new OperateFailedException("fieldName must not be null");

        try {
            Field target = findField(owner, fieldName,
                    value == null ? null : value.getClass(), true, true);
            writeStatic(target, value);
            log.fieldWrite(owner, fieldName, level(target));
        } catch (OperateFailedException e) {
            log.failed("write_static_field", owner, fieldName, e);
            throw e;
        } catch (Throwable t) {
            log.failed("write_static_field", owner, fieldName, t);
            throw new OperateFailedException(
                    "Write static field '" + owner.getName() + "." + fieldName + "' failed.", t);
        }
    }

    // ===== undo 回放专用 API（包级可见，仅供 Rootie.close() 使用） =====

    /**
     * 无审计、无 recorder、无类型校验的读取。
     *
     * <p><b>仅供</b> {@link Rootie#close()} 回放 undo-log 使用。不触发
     * {@link WriteRecorder}；不写审计日志。</p>
     *
     * @param target 目标实例；静态字段为 {@code null}
     * @param field  字段
     * @return 当前值（原始类型已装箱）
     */
    Object readRaw(Object target, Field field) {
        return Modifier.isStatic(field.getModifiers())
                ? readStatic(field)
                : read(target, field);
    }

    /**
     * 无审计、无 recorder、无类型校验的写入。
     *
     * <p><b>仅供</b> {@link Rootie#close()} 回放 undo-log 使用。不触发
     * {@link WriteRecorder}（否则回滚操作会把新条目压回 undo-log）；
     * 不写审计日志。</p>
     *
     * @param target 目标实例；静态字段为 {@code null}
     * @param field  字段
     * @param value  要写入的值
     */
    void writeRaw(Object target, Field field, Object value) {
        if (Modifier.isStatic(field.getModifiers())) {
            doWriteStatic(field, value);
        } else {
            doWrite(target, field, value);
        }
    }

    // ===== 内部查找 =====

    /**
     * 按名字查找字段，并校验“静态/实例”“可写性/可读性”“类型兼容性”。
     *
     * @param type        起始查找类
     * @param name        字段名
     * @param expected    期望类型（写入时为值的实际类型，读取时为期望返回类型）
     * @param forWrite    {@code true} 表示用于写入校验
     * @param staticField 期望是否为静态字段
     * @return 匹配的 {@link Field}
     * @throws OperateFailedException 字段不存在、静态性不匹配或类型不兼容时
     */
    private Field findField(Class<?> type, String name, Class<?> expected,
                            boolean forWrite, boolean staticField) {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(name, "name");

        Field byName = FIELDS.get(type).get(name);
        if (byName == null) {
            throw new OperateFailedException(
                    "Field '" + type.getName() + "." + name + "' not found.");
        }

        boolean isStatic = Modifier.isStatic(byName.getModifiers());
        if (isStatic != staticField) {
            throw new OperateFailedException(
                    "Field '" + type.getName() + "." + name + "' is "
                            + (isStatic ? "static" : "instance")
                            + "; use the " + (isStatic ? "static" : "instance") + " API.");
        }

        Class<?> fieldType = PrimitiveUtils.wrap(byName.getType());

        if (forWrite) {
            if (expected == null) {
                if (byName.getType().isPrimitive()) {
                    throw new OperateFailedException(
                            "Cannot assign null to primitive field '"
                                    + type.getName() + "." + name + "'.");
                }
                return byName;
            }
            Class<?> wanted = PrimitiveUtils.wrap(expected);
            if (!fieldType.isAssignableFrom(wanted)) {
                throw new OperateFailedException(
                        "Field '" + type.getName() + "." + name + "' has type "
                                + byName.getType().getName()
                                + ", cannot accept " + expected.getName() + ".");
            }
        } else {
            Class<?> wanted = PrimitiveUtils.wrap(expected);
            if (!wanted.isAssignableFrom(fieldType)) {
                throw new OperateFailedException(
                        "Field '" + type.getName() + "." + name + "' has type "
                                + byName.getType().getName()
                                + ", not assignable to " + expected.getName() + ".");
            }
        }
        return byName;
    }

    /**
     * 通过 {@code getDeclaredFields0} 句柄获取类的声明字段。
     *
     * @param type 目标类
     * @return 声明字段数组
     * @throws OperateFailedException 底层调用失败时
     */
    private Field[] declaredFields(Class<?> type) {
        try {
            return (Field[]) GET_DECLARED_FIELDS_0.invoke(type, false);
        } catch (Throwable t) {
            throw new OperateFailedException(
                    "Failed to get declared fields of " + type.getName(), t);
        }
    }

    // ===== 内部读写 =====

    /**
     * 公开写路径（记录 undo）：先回调 recorder，再走无记录实体。
     *
     * @param instance 实例对象
     * @param field    目标字段
     * @param value    要写入的值
     */
    private void write(Object instance, Field field, Object value) {
        if (recorder != null) {
            recorder.beforeWrite(instance, field, read(instance, field), value);
        }
        doWrite(instance, field, value);
    }

    /**
     * 公开写路径（记录 undo）：先回调 recorder，再走无记录实体。
     *
     * @param field 静态字段
     * @param value 要写入的值
     */
    private void writeStatic(Field field, Object value) {
        if (recorder != null) {
            recorder.beforeWrite(null, field, readStatic(field), value);
        }
        doWriteStatic(field, value);
    }

    /**
     * 读取字段值：优先 VarHandle，失败则回退 Unsafe 偏移读写。
     *
     * @param instance 实例对象
     * @param field    目标字段
     * @return 字段值
     */
    private Object read(Object instance, Field field) {
        try {
            VarHandle vh = IMPL_LOOKUP.unreflectVarHandle(field);
            return vh.get(instance);
        } catch (Throwable ignored) {
            // fall through to Unsafe path
        }
        long offset = UNSAFE.objectFieldOffset(field);
        return readAt(UNSAFE, instance, offset, field.getType());
    }

    /**
     * 读取静态字段值：优先 VarHandle，失败则回退 Unsafe 偏移读写。
     *
     * @param field 静态字段
     * @return 字段值
     */
    private Object readStatic(Field field) {
        try {
            VarHandle vh = IMPL_LOOKUP.unreflectVarHandle(field);
            return vh.get();
        } catch (Throwable ignored) {
            // fall through to Unsafe path
        }
        Object base = UNSAFE.staticFieldBase(field);
        long offset = UNSAFE.staticFieldOffset(field);
        return readAt(UNSAFE, base, offset, field.getType());
    }

    /**
     * 无记录写实体（实例字段）：非 {@code final} 优先 VarHandle，
     * 否则回退 Unsafe 偏移写。
     *
     * <p>不触发 {@link WriteRecorder}，不写审计日志。公开写路径与
     * undo 回放路径都最终落到这里。</p>
     *
     * @param instance 实例对象
     * @param field    目标字段
     * @param value    要写入的值
     */
    private void doWrite(Object instance, Field field, Object value) {
        if (!Modifier.isFinal(field.getModifiers())) {
            try {
                VarHandle vh = IMPL_LOOKUP.unreflectVarHandle(field);
                vh.set(instance, value);
                return;
            } catch (Throwable ignored) {
                // fall through to Unsafe path
            }
        }
        long offset = UNSAFE.objectFieldOffset(field);
        writeAt(UNSAFE, instance, offset, field.getType(), value);
    }

    /**
     * 无记录写实体（静态字段）：非 {@code final} 优先 VarHandle，
     * 否则回退 Unsafe 偏移写。
     *
     * <p>不触发 {@link WriteRecorder}，不写审计日志。公开写路径与
     * undo 回放路径都最终落到这里。</p>
     *
     * @param field 静态字段
     * @param value 要写入的值
     */
    private void doWriteStatic(Field field, Object value) {
        if (!Modifier.isFinal(field.getModifiers())) {
            try {
                VarHandle vh = IMPL_LOOKUP.unreflectVarHandle(field);
                vh.set(value);
                return;
            } catch (Throwable ignored) {
                // fall through to Unsafe path
            }
        }
        Object base = UNSAFE.staticFieldBase(field);
        long offset = UNSAFE.staticFieldOffset(field);
        writeAt(UNSAFE, base, offset, field.getType(), value);
    }
}