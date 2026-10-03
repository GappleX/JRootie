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

import io.github.gapplex.jrootie.agent.InstrumentationHolder;

import java.lang.instrument.Instrumentation;
import java.lang.reflect.Field;
import java.util.Map;
import java.util.Set;

public final class UnsafeProvider {
    private static volatile IUnsafe CACHED;

    private UnsafeProvider() {}

    public static IUnsafe get() {
        IUnsafe cached = CACHED;
        if (cached != null) return cached;

        synchronized (UnsafeProvider.class) {
            cached = CACHED;
            if (cached != null) return cached;

            cached = doGet();
            CACHED = cached;
            return cached;
        }
    }

    private static IUnsafe doGet() {
        Instrumentation inst = InstrumentationHolder.get();
        if (inst == null) {
            throw new IllegalStateException(
                    "jrootie on Java 25+ requires -javaagent:jrootie-agent.jar");
        }
        try {
            Class<?> unsafeClass = Class.forName("jdk.internal.misc.Unsafe");
            Module src = unsafeClass.getModule();                         // java.base
            Module target = UnsafeProvider.class.getModule();

            if (!inst.isModifiableModule(src)) {
                throw new IllegalStateException("java.base is not modifiable");
            }

            // 只做一次：opens jdk.internal.misc 给 jrootie 所在模块
            inst.redefineModule(
                    src,
                    Set.of(),                                        // extraReads
                    Map.of(),                                        // extraExports
                    Map.of("jdk.internal.misc", Set.of(target)),     // extraOpens
                    Set.of(),                                        // extraUses
                    Map.of());                                       // extraProvides

            Field theUnsafe = unsafeClass.getDeclaredField("theUnsafe");
            theUnsafe.setAccessible(true);
            Object u = theUnsafe.get(null);
            return new JdkInternalUnsafe(u);
        } catch (Throwable t) {
            throw new IllegalStateException(
                    "Cannot acquire jdk.internal.misc.Unsafe via agent", t);
        }
    }
}