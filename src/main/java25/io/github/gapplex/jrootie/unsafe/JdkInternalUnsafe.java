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

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

final class JdkInternalUnsafe implements IUnsafe {
    private final Object u;

    private final MethodHandle objectFieldOffset;
    private final MethodHandle staticFieldOffset;
    private final MethodHandle staticFieldBase;

    private final MethodHandle getObject;
    private final MethodHandle putObject;
    private final MethodHandle getInt;
    private final MethodHandle putInt;
    private final MethodHandle getLong;
    private final MethodHandle putLong;
    private final MethodHandle getBoolean;
    private final MethodHandle putBoolean;
    private final MethodHandle getByte;
    private final MethodHandle putByte;
    private final MethodHandle getShort;
    private final MethodHandle putShort;
    private final MethodHandle getChar;
    private final MethodHandle putChar;
    private final MethodHandle getFloat;
    private final MethodHandle putFloat;
    private final MethodHandle getDouble;
    private final MethodHandle putDouble;

    private final MethodHandle allocateInstance;

    JdkInternalUnsafe(Object u) throws Throwable {
        this.u = u;
        Class<?> c = Class.forName("jdk.internal.misc.Unsafe");
        MethodHandles.Lookup lookup = MethodHandles.lookup();

        objectFieldOffset = mh(lookup, c, "objectFieldOffset", Field.class);
        staticFieldOffset = mh(lookup, c, "staticFieldOffset", Field.class);
        staticFieldBase   = mh(lookup, c, "staticFieldBase",   Field.class);

        // ↓ 名字换成 Reference
        getObject  = mh(lookup, c, "getReference", Object.class, long.class);
        putObject  = mh(lookup, c, "putReference", Object.class, long.class, Object.class);

        getInt     = mh(lookup, c, "getInt",     Object.class, long.class);
        putInt     = mh(lookup, c, "putInt",     Object.class, long.class, int.class);
        getLong    = mh(lookup, c, "getLong",    Object.class, long.class);
        putLong    = mh(lookup, c, "putLong",    Object.class, long.class, long.class);
        getBoolean = mh(lookup, c, "getBoolean", Object.class, long.class);
        putBoolean = mh(lookup, c, "putBoolean", Object.class, long.class, boolean.class);
        getByte    = mh(lookup, c, "getByte",    Object.class, long.class);
        putByte    = mh(lookup, c, "putByte",    Object.class, long.class, byte.class);
        getShort   = mh(lookup, c, "getShort",   Object.class, long.class);
        putShort   = mh(lookup, c, "putShort",   Object.class, long.class, short.class);
        getChar    = mh(lookup, c, "getChar",    Object.class, long.class);
        putChar    = mh(lookup, c, "putChar",    Object.class, long.class, char.class);
        getFloat   = mh(lookup, c, "getFloat",   Object.class, long.class);
        putFloat   = mh(lookup, c, "putFloat",   Object.class, long.class, float.class);
        getDouble  = mh(lookup, c, "getDouble",  Object.class, long.class);
        putDouble  = mh(lookup, c, "putDouble",  Object.class, long.class, double.class);

        allocateInstance = mh(lookup, c, "allocateInstance", Class.class);
    }

    private static MethodHandle mh(MethodHandles.Lookup lookup, Class<?> c,
                                   String name, Class<?>... params) throws Throwable {
        Method m = c.getMethod(name, params);
        return lookup.unreflect(m);
    }

    // ===== 字段定位 =====

    @Override public long objectFieldOffset(Field field) {
        try { return (long) objectFieldOffset.invoke(u, field); }
        catch (Throwable t) { throw rethrow(t); }
    }

    @Override public long staticFieldOffset(Field field) {
        try { return (long) staticFieldOffset.invoke(u, field); }
        catch (Throwable t) { throw rethrow(t); }
    }

    @Override public Object staticFieldBase(Field field) {
        try { return staticFieldBase.invoke(u, field); }
        catch (Throwable t) { throw rethrow(t); }
    }

    // ===== 引用 =====

    @Override public Object getObject(Object base, long offset) {
        try { return getObject.invoke(u, base, offset); }
        catch (Throwable t) { throw rethrow(t); }
    }

    @Override public void putObject(Object base, long offset, Object value) {
        try { putObject.invoke(u, base, offset, value); }
        catch (Throwable t) { throw rethrow(t); }
    }

    // ===== int =====

    @Override public int getInt(Object base, long offset) {
        try { return (int) getInt.invoke(u, base, offset); }
        catch (Throwable t) { throw rethrow(t); }
    }

    @Override public void putInt(Object base, long offset, int value) {
        try { putInt.invoke(u, base, offset, value); }
        catch (Throwable t) { throw rethrow(t); }
    }

    // ===== long =====

    @Override public long getLong(Object base, long offset) {
        try { return (long) getLong.invoke(u, base, offset); }
        catch (Throwable t) { throw rethrow(t); }
    }

    @Override public void putLong(Object base, long offset, long value) {
        try { putLong.invoke(u, base, offset, value); }
        catch (Throwable t) { throw rethrow(t); }
    }

    // ===== boolean =====

    @Override public boolean getBoolean(Object base, long offset) {
        try { return (boolean) getBoolean.invoke(u, base, offset); }
        catch (Throwable t) { throw rethrow(t); }
    }

    @Override public void putBoolean(Object base, long offset, boolean value) {
        try { putBoolean.invoke(u, base, offset, value); }
        catch (Throwable t) { throw rethrow(t); }
    }

    // ===== byte =====

    @Override public byte getByte(Object base, long offset) {
        try { return (byte) getByte.invoke(u, base, offset); }
        catch (Throwable t) { throw rethrow(t); }
    }

    @Override public void putByte(Object base, long offset, byte value) {
        try { putByte.invoke(u, base, offset, value); }
        catch (Throwable t) { throw rethrow(t); }
    }

    // ===== short =====

    @Override public short getShort(Object base, long offset) {
        try { return (short) getShort.invoke(u, base, offset); }
        catch (Throwable t) { throw rethrow(t); }
    }

    @Override public void putShort(Object base, long offset, short value) {
        try { putShort.invoke(u, base, offset, value); }
        catch (Throwable t) { throw rethrow(t); }
    }

    // ===== char =====

    @Override public char getChar(Object base, long offset) {
        try { return (char) getChar.invoke(u, base, offset); }
        catch (Throwable t) { throw rethrow(t); }
    }

    @Override public void putChar(Object base, long offset, char value) {
        try { putChar.invoke(u, base, offset, value); }
        catch (Throwable t) { throw rethrow(t); }
    }

    // ===== float =====

    @Override public float getFloat(Object base, long offset) {
        try { return (float) getFloat.invoke(u, base, offset); }
        catch (Throwable t) { throw rethrow(t); }
    }

    @Override public void putFloat(Object base, long offset, float value) {
        try { putFloat.invoke(u, base, offset, value); }
        catch (Throwable t) { throw rethrow(t); }
    }

    // ===== double =====

    @Override public double getDouble(Object base, long offset) {
        try { return (double) getDouble.invoke(u, base, offset); }
        catch (Throwable t) { throw rethrow(t); }
    }

    @Override public void putDouble(Object base, long offset, double value) {
        try { putDouble.invoke(u, base, offset, value); }
        catch (Throwable t) { throw rethrow(t); }
    }

    @Override public Object allocateInstance(Class<?> type) throws InstantiationException {
        try {
            return allocateInstance.invoke(u, type);
        } catch (Throwable t) {
            if (t instanceof InstantiationException ie) throw ie;
            throw rethrow(t);
        }
    }

    @Override public String version() {
        return "jdk.internal.misc.Unsafe (Java 25+, Agent Mode)";
    }

    private static RuntimeException rethrow(Throwable t) {
        if (t instanceof RuntimeException re) throw re;
        if (t instanceof Error e) throw e;
        return new IllegalStateException(t);
    }
}