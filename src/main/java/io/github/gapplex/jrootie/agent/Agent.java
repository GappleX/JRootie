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
package io.github.gapplex.jrootie.agent;

import java.io.InputStream;
import java.lang.instrument.Instrumentation;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;

/**
 * jrootie 的 Java Agent 入口。
 *
 * <p>{@code premain} / {@code agentmain} 完成三件事：</p>
 * <ol>
 *   <li>将 {@link Instrumentation} 存入 {@link InstrumentationHolder}；</li>
 *   <li>将 {@code MethodRegistry} 与 {@code Context} 注入 bootstrap
 *       classloader；</li>
 *   <li>为 {@code java.base} 添加对 unnamed module 的 read 权限——
 *       针对 bootstrap 的 unnamed module（与应用 unnamed module 不是同一对象）。</li>
 * </ol>
 *
 * <p>所有失败路径均以 {@code System.err} 报告并让出控制权，
 * 以免阻断宿主 JVM 启动；后续调用方应通过
 * {@link InstrumentationHolder#get()} 是否为空来判定 Agent 是否可用。</p>
 *
 * @since 0.1.0
 */
public final class Agent {
    private static final String[] BOOTSTRAP_CLASSES = {
            "io/github/gapplex/jrootie/redefine/MethodRegistry.class",
            "io/github/gapplex/jrootie/redefine/Context.class",
    };

    private static final String REGISTRY_NAME =
            "io.github.gapplex.jrootie.redefine.MethodRegistry";

    private Agent() {}

    public static void premain(String args, Instrumentation inst) {
        install(inst);
    }

    public static void agentmain(String args, Instrumentation inst) {
        install(inst);
    }

    private static void install(Instrumentation inst) {
        Objects.requireNonNull(inst, "inst");
        InstrumentationHolder.set(inst);
        injectBootstrapClasses(inst);
        openJavaBaseToUnnamed(inst);
    }

    /**
     * 将 {@link #BOOTSTRAP_CLASSES} 中的类追加到 bootstrap classloader 的
     * 搜索路径。若目标类已可从 bootstrap 加载，则本次调用为空操作。
     */
    private static void injectBootstrapClasses(Instrumentation inst) {
        if (alreadyOnBootstrap()) return;

        try {
            Path tmp = Files.createTempFile("jrootie-bootstrap-", ".jar");
            try (JarOutputStream jos = new JarOutputStream(Files.newOutputStream(tmp))) {
                ClassLoader source = Agent.class.getClassLoader();
                for (String resource : BOOTSTRAP_CLASSES) {
                    try (InputStream in = source.getResourceAsStream(resource)) {
                        if (in == null) {
                            throw new IllegalStateException(
                                    "Agent jar missing resource: " + resource);
                        }
                        jos.putNextEntry(new JarEntry(resource));
                        in.transferTo(jos);
                        jos.closeEntry();
                    }
                }
            }
            // JarFile 由 bootstrap classloader 持有，直至 JVM 退出才可关闭。
            tmp.toFile().deleteOnExit();
            inst.appendToBootstrapClassLoaderSearch(new JarFile(tmp.toFile()));
        } catch (Exception e) {
            System.err.println("[jrootie] Failed to inject bootstrap classes: " + e);
        }
    }

    /**
     * 为 {@code java.base} 添加对 unnamed module 的 read 权限。
     *
     * <p>JPMS 中每个 classloader 拥有独立的 unnamed module。注入到
     * bootstrap 的 {@code MethodRegistry} 属于 bootstrap 的 unnamed module，
     * 与应用类的 unnamed module 不是同一对象。若只授权应用 unnamed，
     * 桥接字节码在访问 JDK 内部类时仍会抛 {@code IllegalAccessError}。</p>
     */
    private static void openJavaBaseToUnnamed(Instrumentation inst) {
        try {
            Module javaBase = Object.class.getModule();

            Class<?> registryClass;
            try {
                registryClass = Class.forName(REGISTRY_NAME, false, null);
            } catch (ClassNotFoundException e) {
                System.err.println("[jrootie] MethodRegistry not on bootstrap; "
                        + "redefine on JDK internal classes will fail");
                return;
            }

            Set<Module> toRead = new HashSet<>();
            if (!javaBase.canRead(registryClass.getModule())) {
                toRead.add(registryClass.getModule());
            }
            if (!javaBase.canRead(Agent.class.getModule())) {
                toRead.add(Agent.class.getModule());
            }
            if (toRead.isEmpty()) return;

            if (!inst.isModifiableModule(javaBase)) {
                System.err.println("[jrootie] java.base is not modifiable; "
                        + "redefine on JDK internal classes will fail");
                return;
            }

            inst.redefineModule(
                    javaBase,
                    toRead,
                    Map.of(),
                    Map.of(),
                    Set.of(),
                    Map.of());
        } catch (Exception e) {
            System.err.println(
                    "[jrootie] Failed to open java.base to unnamed module: " + e);
        }
    }

    private static boolean alreadyOnBootstrap() {
        try {
            Class.forName(REGISTRY_NAME, false, null);
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }
}