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
import io.github.gapplex.jrootie.agent.InstrumentationHolder;
import io.github.gapplex.jrootie.exceptions.OperateFailedException;
import io.github.gapplex.jrootie.internal.undo.RedefineRecorder;
import io.github.gapplex.jrootie.redefine.Context;
import io.github.gapplex.jrootie.redefine.MethodRegistry;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;

import java.io.IOException;
import java.io.InputStream;
import java.lang.instrument.ClassDefinition;
import java.lang.instrument.Instrumentation;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * 方法体重定义操作器。
 *
 * <p>通过 {@link Instrumentation#redefineClasses} 替换已加载类的方法体。
 * JVM 硬约束：不得增删字段、不得增删方法、不得修改方法签名、不得修改
 * 父类或接口列表，仅可改变方法体。</p>
 *
 * <h2>回滚</h2>
 *
 * <p>{@link AcquireMode#TEST} / {@link AcquireMode#TEST_KEEP} 模式下，
 * 每次 redefine 之前的字节码会被记入 undo-log；{@link Rootie#close()}
 * 时按 LIFO 用旧字节码覆盖，同时注销 {@code replace} 注册的替换函数。
 * {@link AcquireMode#NORMAL} 模式下不记录、不回滚。</p>
 *
 * <p>回滚不做冲突检测——多个 scope 改同一个类时，后关闭者覆盖先关闭者。
 * 这与字段回滚的策略不同：字段是单值，可以判断“当前值是否等于写入值”；
 * 字节码是整体，任何一次 redefine 都会让当前版本与先前记录不等，
 * 检测无意义。</p>
 *
 * <p>需要手动还原时，用 {@link #snapshot(Class)} 保存当前生效的字节码，
 * 之后调用 {@link #restore(Class, byte[])}。</p>
 *
 * <p>本类实例由 {@link Rootie#rtdoRedefine()} 创建并持有。</p>
 *
 * <h2>目标方法定位</h2>
 *
 * <p>所有操作接口均要求显式传入 {@code paramTypes}，与
 * {@link Class#getDeclaredMethod(String, Class[])} 的语义一致。
 * 无参方法传入空数组（{@code new Class<?>[0]}）。参数类型必须与声明
 * 完全一致，包括基本类型；引用类型不做协变匹配。</p>
 *
 * @since 0.2.0
 */
public class RootDoRedefine {

    private static final Audit log = Log.audit(RootDoRedefine.class);

    /** Agent 注入的 Instrumentation。 */
    private final Instrumentation inst;

    /** 创建线程；redefine 必须在该线程上执行。 */
    private final Thread owner;

    /**
     * redefine 成功后的回调；{@code null} 表示不记录 undo
     * （{@link AcquireMode#NORMAL}）。
     */
    private final RedefineRecorder recorder;

    /**
     * 每个类当前生效的字节码缓存。
     *
     * <p>{@link #snapshot(Class)} 首次从 classpath 读取，此后读取本缓存；
     * 每次成功的 redefine 覆盖对应条目。这样多次 replace 才能叠加生效，
     * 而非每次都基于磁盘上的原始版本。</p>
     *
     * <p><b>并发约束</b>：{@link ConcurrentHashMap} 仅保证单次读写安全，
     * 不保证同一类上两次并发 replace 的原子性。同一目标类的 redefine
     * 调用方需自行串行化。</p>
     */
    private final ConcurrentHashMap<Class<?>, byte[]> bytecodeCache = new ConcurrentHashMap<>();

    /**
     * 包级构造器，仅供 {@link Rootie#rtdoRedefine()} 调用。
     *
     * @param inst     Agent 注入的 Instrumentation，不可为 {@code null}
     * @param owner    创建线程，不可为 {@code null}
     * @param recorder redefine 成功后的回调；{@code null} 表示不记录 undo
     */
    RootDoRedefine(Instrumentation inst, Thread owner, RedefineRecorder recorder) {
        this.inst = Objects.requireNonNull(inst, "inst");
        this.owner = Objects.requireNonNull(owner, "owner");
        this.recorder = recorder;
    }

    // ===== 常量意图 =====

    /**
     * 使目标方法体只返回指定常量。
     *
     * @param owner      方法所属类
     * @param name       方法名
     * @param paramTypes 参数类型数组；无参方法传 {@code new Class<?>[0]}
     * @param value      返回值；类型必须与方法返回类型兼容
     * @throws OperateFailedException 方法不存在、常量类型不兼容、
     *                                Agent 未加载或 redefine 失败
     */
    public void makeReturn(Class<?> owner, String name, Class<?>[] paramTypes, Object value) {
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(name, "name");
        Class<?>[] params = requireParamTypes(paramTypes);

        Method method = resolveMethod(owner, name, params);
        byte[] patched = patch(owner, method, mv -> emitReturn(mv, method, value));
        redefineRecorded(owner, patched, null);
        log.methodRedefine(owner, name, "return");
    }

    /**
     * 使目标方法体只抛出指定异常。
     *
     * <p>生成字节码优先使用 {@code (String)} 构造器；若不存在则回退到无参
     * 构造器。两者皆无时立即抛 {@link OperateFailedException}。</p>
     *
     * @param owner      方法所属类
     * @param name       方法名
     * @param paramTypes 参数类型数组；无参方法传 {@code new Class<?>[0]}
     * @param exception  要抛出的异常实例；其运行时类型决定 {@code new} 目标
     * @throws OperateFailedException 方法不存在、异常类无可用构造器、
     *                                Agent 未加载或 redefine 失败
     */
    public void makeThrow(Class<?> owner, String name, Class<?>[] paramTypes, Throwable exception) {
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(exception, "exception");
        Class<?>[] params = requireParamTypes(paramTypes);

        Method method = resolveMethod(owner, name, params);
        byte[] patched = patch(owner, method, mv -> emitThrow(mv, exception));
        redefineRecorded(owner, patched, null);
        log.methodRedefine(owner, name, "throw");
    }

    /**
     * 清空目标方法体。
     *
     * <p>{@code void} 方法仅执行 {@code return}；非 {@code void} 方法返回
     * 默认值（{@code 0} / {@code false} / {@code null}）。</p>
     *
     * @param owner      方法所属类
     * @param name       方法名
     * @param paramTypes 参数类型数组；无参方法传 {@code new Class<?>[0]}
     * @throws OperateFailedException 方法不存在、Agent 未加载或 redefine 失败
     */
    public void makeNoOp(Class<?> owner, String name, Class<?>[] paramTypes) {
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(name, "name");
        Class<?>[] params = requireParamTypes(paramTypes);

        Method method = resolveMethod(owner, name, params);
        byte[] patched = patch(owner, method, mv -> emitNoOp(mv, method));
        redefineRecorded(owner, patched, null);
        log.methodRedefine(owner, name, "noop");
    }

    // ===== 函数替换 =====

    /**
     * 用 Java 函数替换方法体。
     *
     * <p>生成的桥接字节码将接收者与参数打包为 {@link Context}，调用
     * {@link MethodRegistry#invoke(int, Object, Object[])} 执行用户注册的
     * {@link Function}，再按目标返回类型拆箱 / 转型回传。</p>
     *
     * <p><b>回滚</b>：{@link AcquireMode#TEST} / {@link AcquireMode#TEST_KEEP}
     * 模式下会记录旧字节码与注册 id；{@link Rootie#close()} 时恢复旧字节码，
     * 并从 {@link MethodRegistry} 注销该函数。{@link AcquireMode#NORMAL}
     * 模式下不记录、不回滚。</p>
     *
     * <p><b>类加载器可见性</b>：被替换的类与 {@link MethodRegistry} 必须
     * 互相可见。应用类由同一 classloader 加载即可；替换 JDK 内部类需要
     * bootstrap 注入支持（由 {@code Agent} 负责）。</p>
     *
     * @param owner      方法所属类
     * @param name       方法名
     * @param paramTypes 参数类型数组；无参方法传 {@code new Class<?>[0]}
     * @param fn         替换逻辑；接收 {@link Context}，返回方法返回值
     * @throws OperateFailedException 方法不存在、Agent 未加载或 redefine 失败
     * @since 0.2.0
     */
    public void replace(Class<?> owner, String name, Class<?>[] paramTypes,
                        Function<Context, Object> fn) {
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(fn, "fn");
        Class<?>[] params = requireParamTypes(paramTypes);

        Method method = resolveMethod(owner, name, params);
        int id = MethodRegistry.register(fn);
        boolean success = false;
        try {
            byte[] patched = patch(owner, method, mv -> emitBridge(mv, method, id));
            redefineRecorded(owner, patched, Integer.valueOf(id));
            log.methodRedefine(owner, name, "function#" + id);
            success = true;
        } finally {
            if (!success) MethodRegistry.unregister(id);
        }
    }

    // ===== 快照 / 恢复 =====

    /**
     * 保存类当前生效的字节码，供 {@link #restore(Class, byte[])} 使用。
     *
     * <p>返回的是 JVM 当前生效版本（包含此前所有 replace 的累积效果），
     * 而非磁盘上的原始版本。首次调用从类加载路径读取，此后读取内部缓存。</p>
     *
     * <p><b>读取路径</b>：使用 {@link Class#getResourceAsStream(String)}，
     * 该 API 模块感知且使用类自身的 classloader。对 JDK 内部类，模块系统
     * 会解析到正确的 runtime image 位置。不使用
     * {@link ClassLoader#getSystemResourceAsStream}——对自定义 classloader
     * 加载的类，它可能读到另一个同名类的字节码。</p>
     *
     * @param owner 目标类
     * @return 类的字节码快照
     * @throws OperateFailedException 无法读取字节码时，异常消息携带类名、
     *                                资源路径、加载器与模块信息
     */
    public byte[] snapshot(Class<?> owner) {
        Objects.requireNonNull(owner, "owner");

        byte[] cached = bytecodeCache.get(owner);
        if (cached != null) return cached;

        String resource = "/" + owner.getName().replace('.', '/') + ".class";
        byte[] bytes = readClassBytes(owner, resource);
        bytecodeCache.put(owner, bytes);
        return bytes;
    }

    private static byte[] readClassBytes(Class<?> owner, String resource) {
        try (InputStream in = owner.getResourceAsStream(resource)) {
            if (in == null) {
                throw new OperateFailedException(buildReadFailureMessage(owner, resource));
            }
            return in.readAllBytes();
        } catch (IOException e) {
            throw new OperateFailedException(buildReadFailureMessage(owner, resource), e);
        }
    }

    private static String buildReadFailureMessage(Class<?> owner, String resource) {
        ClassLoader cl = owner.getClassLoader();
        String loaderDesc = (cl == null)
                ? "bootstrap"
                : cl.getClass().getName() + "@"
                + Integer.toHexString(System.identityHashCode(cl));

        Module module = owner.getModule();
        String moduleDesc = module.isNamed() ? module.getName() : "unnamed";

        return "Cannot read bytecode of " + owner.getName()
                + "\n  resource: " + resource
                + "\n  loader:   " + loaderDesc
                + "\n  module:   " + moduleDesc;
    }

    /**
     * 使用先前 {@link #snapshot(Class)} 得到的字节码恢复类定义。
     *
     * <p>与内部回滚路径（{@code restoreForRollback}）的区别：本方法写
     * 审计日志，供用户主动调用时使用。</p>
     *
     * @param owner    目标类
     * @param bytecode 之前保存的字节码
     * @throws OperateFailedException Agent 未加载或 redefine 失败
     */
    public void restore(Class<?> owner, byte[] bytecode) {
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(bytecode, "bytecode");
        redefineRaw(owner, bytecode);
        log.methodRedefine(owner, "<restore>", "restore");
    }

    // ===== 内部：方法解析 =====

    /**
     * 按名称与参数类型解析目标方法。
     *
     * <p>委托给 {@link Class#getDeclaredMethod(String, Class[])}，仅查找目标
     * 类自身声明的方法，不含继承链；参数类型需完全一致。</p>
     */
    private static Method resolveMethod(Class<?> owner, String name, Class<?>[] paramTypes) {
        try {
            Method method = owner.getDeclaredMethod(name, paramTypes);
            checkRedefinable(owner, method);
            return method;
        } catch (NoSuchMethodException e) {
            throw new OperateFailedException(
                    "Method '" + owner.getName() + "." + name
                            + Arrays.toString(paramTypes) + "' not found.", e);
        }
    }

    private static Class<?>[] requireParamTypes(Class<?>[] paramTypes) {
        Objects.requireNonNull(paramTypes, "paramTypes");
        Class<?>[] copy = paramTypes.clone();
        for (int i = 0; i < copy.length; i++) {
            Objects.requireNonNull(copy[i], "paramTypes[" + i + "]");
        }
        return copy;
    }

    private static void checkRedefinable(Class<?> owner, Method method) {
        if (Modifier.isAbstract(method.getModifiers())) {
            throw new OperateFailedException(
                    "Cannot redefine abstract method: "
                            + owner.getName() + "." + method.getName());
        }
    }

    // ===== 内部：字节码 patching =====

    /**
     * 读当前字节码 → 清空目标方法体 → 写入新方法体 → ASM 重算栈帧。
     *
     * @param body 写入新方法体的回调；直接向 {@link MethodNode} 写指令
     * @return patched 字节码
     */
    private byte[] patch(Class<?> owner, Method method, Consumer<MethodVisitor> body) {
        byte[] original = snapshot(owner);
        ClassReader cr = new ClassReader(original);
        ClassNode cn = new ClassNode();
        cr.accept(cn, ClassReader.EXPAND_FRAMES);

        String targetName = method.getName();
        String targetDesc = Type.getMethodDescriptor(method);
        boolean found = false;

        for (MethodNode mn : cn.methods) {
            if (!mn.name.equals(targetName) || !mn.desc.equals(targetDesc)) continue;

            mn.instructions.clear();
            mn.tryCatchBlocks.clear();
            mn.localVariables = null;
            body.accept(mn);
            found = true;
            break;
        }

        if (!found) {
            throw new OperateFailedException(
                    "Method '" + owner.getName() + "." + targetName + targetDesc
                            + "' not found in bytecode.");
        }

        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS) {
            @Override
            protected ClassLoader getClassLoader() {
                ClassLoader cl = owner.getClassLoader();
                return cl != null ? cl : ClassLoader.getSystemClassLoader();
            }
        };
        cn.accept(cw);
        return cw.toByteArray();
    }

    /**
     * 记录型 redefine。先快照旧字节码，提交新字节码，再回调 recorder。
     *
     * <p>{@code recorder == null}（{@link AcquireMode#NORMAL}）时跳过快照，
     * 走零开销路径。</p>
     *
     * @param registryId {@code replace} 分配的 id；专用字节码路径为 {@code null}
     */
    private void redefineRecorded(Class<?> owner, byte[] bytecode, Integer registryId) {
        checkOwnerThread();
        byte[] oldBytecode = (recorder == null) ? null : snapshot(owner);
        redefineRaw(owner, bytecode);
        if (recorder != null) {
            recorder.afterRedefine(owner, oldBytecode, registryId);
        }
    }

    /**
     * 无记录 redefine。供 {@link #restore(Class, byte[])} 与回滚路径使用。
     */
    private void redefineRaw(Class<?> owner, byte[] bytecode) {
        checkOwnerThread();
        Instrumentation instrument = InstrumentationHolder.get();
        if (instrument == null) {
            throw new OperateFailedException(
                    "redefine requires -javaagent:jrootie.jar. Agent not loaded.");
        }
        try {
            instrument.redefineClasses(new ClassDefinition(owner, bytecode));
            bytecodeCache.put(owner, bytecode);
        } catch (Throwable t) {
            log.failed("redefine", owner, "<class>", t);
            throw new OperateFailedException(
                    "Redefine '" + owner.getName() + "' failed.", t);
        }
    }

    /**
     * 校验当前线程是否为创建该操作器的线程。
     *
     * @throws OperateFailedException 跨线程调用时
     */
    private void checkOwnerThread() {
        Thread current = Thread.currentThread();
        if (current != owner) {
            throw new OperateFailedException(
                    "RootDoRedefine is bound to " + owner.getName()
                            + "; got " + current.getName()
                            + ". Open a separate Rootie per thread.");
        }
    }

    // ===== 内部：桥接字节码 =====

    /**
     * 写桥接方法体：
     * <pre>
     *   MethodRegistry.invoke(id, receiver, new Object[]{args...})
     * </pre>
     * 随后按目标返回类型拆箱 / 转型。
     */
    private void emitBridge(MethodVisitor mv, Method method, int id) {
        boolean isStatic = Modifier.isStatic(method.getModifiers());
        Class<?>[] params = method.getParameterTypes();
        Class<?> returnType = method.getReturnType();

        mv.visitLdcInsn(Integer.valueOf(id));

        if (isStatic) {
            mv.visitInsn(Opcodes.ACONST_NULL);
        } else {
            mv.visitVarInsn(Opcodes.ALOAD, 0);
        }

        pushInt(mv, params.length);
        mv.visitTypeInsn(Opcodes.ANEWARRAY, "java/lang/Object");

        int localIndex = isStatic ? 0 : 1;
        for (int i = 0; i < params.length; i++) {
            mv.visitInsn(Opcodes.DUP);
            pushInt(mv, i);
            emitLoadAndBox(mv, params[i], localIndex);
            mv.visitInsn(Opcodes.AASTORE);
            localIndex += (params[i] == long.class || params[i] == double.class) ? 2 : 1;
        }

        mv.visitMethodInsn(Opcodes.INVOKESTATIC,
                "io/github/gapplex/jrootie/redefine/MethodRegistry",
                "invoke",
                "(ILjava/lang/Object;[Ljava/lang/Object;)Ljava/lang/Object;",
                false);

        emitReturnFromObject(mv, returnType);
    }

    private static void emitLoadAndBox(MethodVisitor mv, Class<?> param, int localIndex) {
        if (param == int.class) {
            mv.visitVarInsn(Opcodes.ILOAD, localIndex);
            boxAs(mv, "java/lang/Integer", "(I)Ljava/lang/Integer;");
        } else if (param == boolean.class) {
            mv.visitVarInsn(Opcodes.ILOAD, localIndex);
            boxAs(mv, "java/lang/Boolean", "(Z)Ljava/lang/Boolean;");
        } else if (param == byte.class) {
            mv.visitVarInsn(Opcodes.ILOAD, localIndex);
            boxAs(mv, "java/lang/Byte", "(B)Ljava/lang/Byte;");
        } else if (param == char.class) {
            mv.visitVarInsn(Opcodes.ILOAD, localIndex);
            boxAs(mv, "java/lang/Character", "(C)Ljava/lang/Character;");
        } else if (param == short.class) {
            mv.visitVarInsn(Opcodes.ILOAD, localIndex);
            boxAs(mv, "java/lang/Short", "(S)Ljava/lang/Short;");
        } else if (param == long.class) {
            mv.visitVarInsn(Opcodes.LLOAD, localIndex);
            boxAs(mv, "java/lang/Long", "(J)Ljava/lang/Long;");
        } else if (param == float.class) {
            mv.visitVarInsn(Opcodes.FLOAD, localIndex);
            boxAs(mv, "java/lang/Float", "(F)Ljava/lang/Float;");
        } else if (param == double.class) {
            mv.visitVarInsn(Opcodes.DLOAD, localIndex);
            boxAs(mv, "java/lang/Double", "(D)Ljava/lang/Double;");
        } else {
            mv.visitVarInsn(Opcodes.ALOAD, localIndex);
        }
    }

    private static void boxAs(MethodVisitor mv, String owner, String desc) {
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, owner, "valueOf", desc, false);
    }

    /**
     * 将 {@code MethodRegistry.invoke} 返回的 {@code Object} 按目标返回类型
     * 拆箱 / 转型后返回。
     *
     * <p>装箱与拆箱必须严格对称：{@code boolean} 走
     * {@code Boolean.booleanValue()}，不得走 {@code Integer.intValue()}。</p>
     */
    private static void emitReturnFromObject(MethodVisitor mv, Class<?> returnType) {
        if (returnType == void.class) {
            mv.visitInsn(Opcodes.POP);
            mv.visitInsn(Opcodes.RETURN);
        } else if (returnType == int.class) {
            unboxAs(mv, "java/lang/Integer", "intValue", "()I");
            mv.visitInsn(Opcodes.IRETURN);
        } else if (returnType == boolean.class) {
            unboxAs(mv, "java/lang/Boolean", "booleanValue", "()Z");
            mv.visitInsn(Opcodes.IRETURN);
        } else if (returnType == byte.class) {
            unboxAs(mv, "java/lang/Byte", "byteValue", "()B");
            mv.visitInsn(Opcodes.IRETURN);
        } else if (returnType == char.class) {
            unboxAs(mv, "java/lang/Character", "charValue", "()C");
            mv.visitInsn(Opcodes.IRETURN);
        } else if (returnType == short.class) {
            unboxAs(mv, "java/lang/Short", "shortValue", "()S");
            mv.visitInsn(Opcodes.IRETURN);
        } else if (returnType == long.class) {
            unboxAs(mv, "java/lang/Long", "longValue", "()J");
            mv.visitInsn(Opcodes.LRETURN);
        } else if (returnType == float.class) {
            unboxAs(mv, "java/lang/Float", "floatValue", "()F");
            mv.visitInsn(Opcodes.FRETURN);
        } else if (returnType == double.class) {
            unboxAs(mv, "java/lang/Double", "doubleValue", "()D");
            mv.visitInsn(Opcodes.DRETURN);
        } else {
            mv.visitTypeInsn(Opcodes.CHECKCAST, Type.getInternalName(returnType));
            mv.visitInsn(Opcodes.ARETURN);
        }
    }

    private static void unboxAs(MethodVisitor mv, String owner,
                                String method, String desc) {
        mv.visitTypeInsn(Opcodes.CHECKCAST, owner);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, owner, method, desc, false);
    }

    // ===== 内部：常量意图字节码 =====

    private static void emitReturn(MethodVisitor mv, Method method, Object value) {
        Class<?> returnType = method.getReturnType();
        if (returnType == void.class) {
            mv.visitInsn(Opcodes.RETURN);
            return;
        }
        emitConstant(mv, returnType, value);
        mv.visitInsn(returnType.isPrimitive()
                ? primitiveReturnOpcode(returnType)
                : Opcodes.ARETURN);
    }

    /**
     * 生成 {@code throw new Ex(message);} 或 {@code throw new Ex();}。
     *
     * <p>优先使用 {@code (String)} 构造器；不存在时回退到无参构造器。
     * 两者皆无则在 redefine 阶段直接抛异常，不延迟到方法被调用时。</p>
     */
    private static void emitThrow(MethodVisitor mv, Throwable exception) {
        Class<?> exClass = exception.getClass();
        String internal = Type.getInternalName(exClass);

        boolean hasStringCtor = hasConstructor(exClass, String.class);
        boolean hasNoArgCtor = hasConstructor(exClass);

        if (!hasStringCtor && !hasNoArgCtor) {
            throw new OperateFailedException(
                    "Cannot throw '" + exClass.getName()
                            + "': no (String) or () constructor available.");
        }

        mv.visitTypeInsn(Opcodes.NEW, internal);
        mv.visitInsn(Opcodes.DUP);
        if (hasStringCtor) {
            String message = exception.getMessage();
            mv.visitLdcInsn(message == null ? "" : message);
            mv.visitMethodInsn(Opcodes.INVOKESPECIAL, internal,
                    "<init>", "(Ljava/lang/String;)V", false);
        } else {
            mv.visitMethodInsn(Opcodes.INVOKESPECIAL, internal,
                    "<init>", "()V", false);
        }
        mv.visitInsn(Opcodes.ATHROW);
    }

    private static boolean hasConstructor(Class<?> type, Class<?>... params) {
        try {
            type.getConstructor(params);
            return true;
        } catch (NoSuchMethodException e) {
            return false;
        }
    }

    private static void emitNoOp(MethodVisitor mv, Method method) {
        Class<?> returnType = method.getReturnType();
        if (returnType == void.class) {
            mv.visitInsn(Opcodes.RETURN);
        } else if (returnType == long.class) {
            mv.visitInsn(Opcodes.LCONST_0);
            mv.visitInsn(Opcodes.LRETURN);
        } else if (returnType == float.class) {
            mv.visitInsn(Opcodes.FCONST_0);
            mv.visitInsn(Opcodes.FRETURN);
        } else if (returnType == double.class) {
            mv.visitInsn(Opcodes.DCONST_0);
            mv.visitInsn(Opcodes.DRETURN);
        } else if (returnType.isPrimitive()) {
            mv.visitInsn(Opcodes.ICONST_0);
            mv.visitInsn(Opcodes.IRETURN);
        } else {
            mv.visitInsn(Opcodes.ACONST_NULL);
            mv.visitInsn(Opcodes.ARETURN);
        }
    }

    private static void emitConstant(MethodVisitor mv, Class<?> type, Object value) {
        if (value == null) {
            if (type.isPrimitive()) {
                throw new OperateFailedException(
                        "Cannot return null from primitive method: " + type.getName());
            }
            mv.visitInsn(Opcodes.ACONST_NULL);
            return;
        }

        if (type == boolean.class || type == Boolean.class) {
            if (!(value instanceof Boolean)) {
                throw new OperateFailedException(
                        "Expected Boolean for " + type.getName() + ", got " + value.getClass());
            }
            mv.visitInsn(((Boolean) value).booleanValue()
                    ? Opcodes.ICONST_1 : Opcodes.ICONST_0);
        } else if (type == char.class || type == Character.class) {
            char c = value instanceof Character
                    ? ((Character) value).charValue()
                    : (char) requireNumber(value, type).intValue();
            mv.visitLdcInsn(Integer.valueOf(c));
        } else if (type == byte.class || type == Byte.class) {
            mv.visitLdcInsn(Integer.valueOf(requireNumber(value, type).byteValue()));
        } else if (type == short.class || type == Short.class) {
            mv.visitLdcInsn(Integer.valueOf(requireNumber(value, type).shortValue()));
        } else if (type == int.class || type == Integer.class) {
            mv.visitLdcInsn(Integer.valueOf(requireNumber(value, type).intValue()));
        } else if (type == long.class || type == Long.class) {
            mv.visitLdcInsn(Long.valueOf(requireNumber(value, type).longValue()));
        } else if (type == float.class || type == Float.class) {
            mv.visitLdcInsn(Float.valueOf(requireNumber(value, type).floatValue()));
        } else if (type == double.class || type == Double.class) {
            mv.visitLdcInsn(Double.valueOf(requireNumber(value, type).doubleValue()));
        } else if (type == String.class) {
            if (!(value instanceof String)) {
                throw new OperateFailedException(
                        "Expected String for java.lang.String, got " + value.getClass());
            }
            mv.visitLdcInsn(value);
        } else {
            throw new OperateFailedException(
                    "Unsupported constant type: " + type.getName());
        }
    }

    private static Number requireNumber(Object value, Class<?> targetType) {
        if (!(value instanceof Number)) {
            throw new OperateFailedException(
                    "Expected Number for " + targetType.getName()
                            + ", got " + value.getClass().getName());
        }
        return (Number) value;
    }

    private static int primitiveReturnOpcode(Class<?> type) {
        if (type == long.class) return Opcodes.LRETURN;
        if (type == float.class) return Opcodes.FRETURN;
        if (type == double.class) return Opcodes.DRETURN;
        if (type == void.class) return Opcodes.RETURN;
        return Opcodes.IRETURN;
    }

    private static void pushInt(MethodVisitor mv, int value) {
        if (value >= -1 && value <= 5) {
            mv.visitInsn(Opcodes.ICONST_0 + value);
        } else if (value >= Byte.MIN_VALUE && value <= Byte.MAX_VALUE) {
            mv.visitIntInsn(Opcodes.BIPUSH, value);
        } else if (value >= Short.MIN_VALUE && value <= Short.MAX_VALUE) {
            mv.visitIntInsn(Opcodes.SIPUSH, value);
        } else {
            mv.visitLdcInsn(Integer.valueOf(value));
        }
    }

    /**
     * 包级回滚入口。供 {@code Rootie.close()} 的回放路径使用。
     *
     * <p>与 {@link #restore(Class, byte[])} 的区别：不写审计日志。回滚由
     * {@code Rootie} 统一记录，此处重复记录会造成日志双写与 caller 误导。</p>
     *
     * @param owner    被 redefine 的类
     * @param bytecode redefine 之前的字节码
     */
    void restoreForRollback(Class<?> owner, byte[] bytecode) {
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(bytecode, "bytecode");
        redefineRaw(owner, bytecode);
    }
}