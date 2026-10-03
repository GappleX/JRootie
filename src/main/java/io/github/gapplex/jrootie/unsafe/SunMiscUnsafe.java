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

import sun.misc.Unsafe;

import java.lang.reflect.Field;

/**
 * {@link IUnsafe} 基于 {@code sun.misc.Unsafe} 的实现。
 *
 * <p>本类直接转发所有调用到真实的 {@code sun.misc.Unsafe} 实例，
 * 不做任何额外检查或缓存。</p>
 *
 * <p>本类为包级可见（{@code final class}），由
 * {@link UnsafeProvider} 创建并返回。</p>
 *
 * @since 0.1.0
 */
final class SunMiscUnsafe implements IUnsafe {

    /** 真实的 {@code sun.misc.Unsafe} 实例。 */
    private final Unsafe u;

    /**
     * @param u 通过反射取得的 {@code sun.misc.Unsafe#theUnsafe}，不可为 {@code null}
     */
    SunMiscUnsafe(Unsafe u) {
        this.u = u;
    }

    @Override public long objectFieldOffset(Field f) { return u.objectFieldOffset(f); }
    @Override public long staticFieldOffset(Field f) { return u.staticFieldOffset(f); }
    @Override public Object staticFieldBase(Field f) { return u.staticFieldBase(f); }

    @Override public Object  getObject (Object b, long o) { return u.getObject(b, o); }
    @Override public void    putObject (Object b, long o, Object  v) { u.putObject(b, o, v); }
    @Override public int     getInt    (Object b, long o) { return u.getInt(b, o); }
    @Override public void    putInt    (Object b, long o, int     v) { u.putInt(b, o, v); }
    @Override public long    getLong   (Object b, long o) { return u.getLong(b, o); }
    @Override public void    putLong   (Object b, long o, long    v) { u.putLong(b, o, v); }
    @Override public boolean getBoolean(Object b, long o) { return u.getBoolean(b, o); }
    @Override public void    putBoolean(Object b, long o, boolean v) { u.putBoolean(b, o, v); }
    @Override public byte    getByte   (Object b, long o) { return u.getByte(b, o); }
    @Override public void    putByte   (Object b, long o, byte    v) { u.putByte(b, o, v); }
    @Override public short   getShort  (Object b, long o) { return u.getShort(b, o); }
    @Override public void    putShort  (Object b, long o, short   v) { u.putShort(b, o, v); }
    @Override public char    getChar   (Object b, long o) { return u.getChar(b, o); }
    @Override public void    putChar   (Object b, long o, char    v) { u.putChar(b, o, v); }
    @Override public float   getFloat  (Object b, long o) { return u.getFloat(b, o); }
    @Override public void    putFloat  (Object b, long o, float   v) { u.putFloat(b, o, v); }
    @Override public double  getDouble (Object b, long o) { return u.getDouble(b, o); }
    @Override public void    putDouble (Object b, long o, double  v) { u.putDouble(b, o, v); }

    @Override public Object allocateInstance(Class<?> t) throws InstantiationException {
        return u.allocateInstance(t);
    }

    @Override public String version() { return "sun.misc.Unsafe (Java 9+)"; }
}