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
import java.util.ArrayList;
import java.util.List;
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
 * <h2>单方法 vs 链式</h2>
 *
 * <p>两种调用形态：</p>
 * <ul>
 *   <li><b>单方法便利形式</b>：{@link #replace} / {@link #makeReturn} /
 *       {@link #makeThrow} / {@link #makeNoOp} 直接接受 {@code Class<?>} 参数，
 *       立即提交。</li>
 *   <li><b>链式形式</b>：{@link #on(Class)} 打开一个 {@link Session}，
 *       累积多个方法的重定义，{@link Session#apply()} 时一次性提交。
 *       同一类的多次修改只触发一次 deopt，且保证原子性。</li>
 * </ul>
 *
 * <h2>undo 回滚</h2>
 *
 * <p>{@link AcquireMode#TEST} / {@link AcquireMode#TEST_KEEP} 模式下，
 * 每次 redefine 之前的字节码会被记入 undo-log；{@link Rootie#close()}
 * 时按 LIFO 用旧字节码覆盖，同时注销 {@code replace} 注册的所有替换函数。
 * {@link AcquireMode#NORMAL} 模式下不记录、不回滚。</p>
 *
 * <p>回滚不做冲突检测——多个 scope 改同一个类时，后关闭者覆盖先关闭者。</p>
 *
 * <p>本类实例由 {@link Rootie#rtdoRedefine()} 创建并持有。
 * {@link Rootie#close()} 后所有公开方法（含 {@link Session} 的方法）
 * 抛 {@link OperateFailedException}；内部回滚路径
 * （{@link #restoreForRollback}）不受影响。</p>
 *
 * <h2>目标方法定位</h2>
 *
 * <p>所有操作接口均要求显式传入 {@code paramTypes}，与
 * {@link Class#getDeclaredMethod(String, Class[])} 的语义一致。无参方法
 * 传入空数组（{@code new Class<?>[0]}）。参数类型必须与声明完全一致，
 * 包括基本类型；引用类型不做协变匹配。</p>
 *
 * @since 0.2.0
 */
public class RootDoRedefine {

    /** 审计日志器。嵌套类 {@link Session} 直接访问。 */
    private static final Audit log = Log.audit(RootDoRedefine.class);

    private final Instrumentation inst;
    private final Thread owner;
    private final RedefineRecorder recorder;

    /** scope 共享状态，{@link Rootie#close()} 后置为已关闭。 */
    private final ScopeState state;

    /**
     * 每个类当前生效的字节码缓存。
     *
     * <p>{@link #snapshot(Class)} 首次从 classpath 读取，此后读取本缓存；
     * 每次成功的 redefine 覆盖对应条目。</p>
     *
     * <p><b>并发约束</b>：{@link ConcurrentHashMap} 仅保证单次读写安全，
     * 不保证同一类上两次并发 redefine 的原子性。同一目标类的 redefine
     * 调用方需自行串行化。</p>
     */
    private final ConcurrentHashMap<Class<?>, byte[]> bytecodeCache = new ConcurrentHashMap<>();

    /**
     * 包级构造器，仅供 {@link Rootie#rtdoRedefine()} 调用。
     *
     * @param inst     Agent 注入的 Instrumentation
     * @param owner    创建线程
     * @param recorder redefine 成功后的回调；{@code null} 表示不记录 undo
     * @param state    scope 共享状态
     */
    RootDoRedefine(Instrumentation inst, Thread owner,
                   RedefineRecorder recorder, ScopeState state) {
        this.inst = Objects.requireNonNull(inst, "inst");
        this.owner = Objects.requireNonNull(owner, "owner");
        this.recorder = recorder;
        this.state = state;
    }

    // ===== 链式入口 =====

    /**
     * 打开一个 redefine 会话，累积对同一个类的多次方法重定义。
     *
     * <p>session 读取当前生效的字节码作为起点。所有对 session 的修改在内存
     * 中进行，{@link Session#apply()} 时一次性提交。</p>
     *
     * <p><b>生命周期</b>：一个 session 只能 apply 或 cancel 一次。丢弃未提交
     * 的 session 会导致 {@link MethodRegistry} 中的替换函数泄漏。</p>
     *
     * @param target 目标类，不可为 {@code null}
     * @return 新的会话
     * @throws OperateFailedException scope 已关闭，或字节码读取失败时
     */
    public Session on(Class<?> target) {
        state.checkOpen();
        Objects.requireNonNull(target, "target");
        checkOwnerThread();

        byte[] current = snapshot(target);
        ClassReader cr = new ClassReader(current);
        ClassNode cn = new ClassNode();
        cr.accept(cn, ClassReader.EXPAND_FRAMES);
        return new Session(this, target, cn);
    }

    // ===== 单方法便利形式 =====

    /**
     * 使目标方法体只返回指定常量。等价于
     * {@code on(owner).makeReturn(name, paramTypes, value).apply()}。
     *
     * @param owner      方法所属类
     * @param name       方法名
     * @param paramTypes 参数类型数组；无参方法传 {@code new Class<?>[0]}
     * @param value      返回值
     * @throws OperateFailedException scope 已关闭、方法不存在、常量类型不兼容、
     *                                Agent 未加载或 redefine 失败
     */
    public void makeReturn(Class<?> owner, String name, Class<?>[] paramTypes, Object value) {
        on(owner).makeReturn(name, paramTypes, value).apply();
    }

    /**
     * 使目标方法体只抛出指定异常。等价于
     * {@code on(owner).makeThrow(name, paramTypes, exception).apply()}。
     *
     * @param owner      方法所属类
     * @param name       方法名
     * @param paramTypes 参数类型数组
     * @param exception  要抛出的异常实例
     * @throws OperateFailedException scope 已关闭、方法不存在、
     *                                异常类无可用构造器、Agent 未加载或
     *                                redefine 失败
     */
    public void makeThrow(Class<?> owner, String name, Class<?>[] paramTypes,
                          Throwable exception) {
        on(owner).makeThrow(name, paramTypes, exception).apply();
    }

    /**
     * 清空目标方法体。等价于
     * {@code on(owner).makeNoOp(name, paramTypes).apply()}。
     *
     * @param owner      方法所属类
     * @param name       方法名
     * @param paramTypes 参数类型数组
     * @throws OperateFailedException scope 已关闭、方法不存在、Agent 未加载
     *                                或 redefine 失败
     */
    public void makeNoOp(Class<?> owner, String name, Class<?>[] paramTypes) {
        on(owner).makeNoOp(name, paramTypes).apply();
    }

    /**
     * 用 Java 函数替换方法体。等价于
     * {@code on(owner).replace(name, paramTypes, fn).apply()}。
     *
     * <p>若需对同一个类的多个方法一次性重定义（一次 deopt、原子生效），
     * 用 {@link #on(Class)} 链式形式。</p>
     *
     * @param owner      方法所属类
     * @param name       方法名
     * @param paramTypes 参数类型数组
     * @param fn         替换逻辑
     * @throws OperateFailedException scope 已关闭、方法不存在、Agent 未加载
     *                                或 redefine 失败
     */
    public void replace(Class<?> owner, String name, Class<?>[] paramTypes,
                        Function<Context, Object> fn) {
        on(owner).replace(name, paramTypes, fn).apply();
    }

    // ===== 快照 / 恢复 =====

    /**
     * 保存类当前生效的字节码，供 {@link #restore(Class, byte[])} 使用。
     *
     * @param owner 目标类
     * @return 类的字节码快照
     * @throws OperateFailedException scope 已关闭，或无法读取字节码时
     */
    public byte[] snapshot(Class<?> owner) {
        state.checkOpen();
        Objects.requireNonNull(owner, "owner");

        byte[] cached = bytecodeCache.get(owner);
        if (cached != null) return cached;

        String resource = "/" + owner.getName().replace('.', '/') + ".class";
        byte[] bytes = readClassBytes(owner, resource);
        bytecodeCache.put(owner, bytes);
        return bytes;
    }

    /**
     * 使用先前 {@link #snapshot(Class)} 得到的字节码恢复类定义。
     *
     * @param owner    目标类
     * @param bytecode 之前保存的字节码
     * @throws OperateFailedException scope 已关闭、Agent 未加载或 redefine 失败
     */
    public void restore(Class<?> owner, byte[] bytecode) {
        state.checkOpen();
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(bytecode, "bytecode");
        redefineRaw(owner, bytecode);
        log.methodRedefine(owner, "<restore>", "restore");
    }

    // ===== 内部：session 协作 =====

    /**
     * 在 session 的 {@link ClassNode} 上定位并替换目标方法体。
     *
     * <p>清空原指令、try-catch 块、局部变量表，写入新 body。</p>
     */
    private void modifyMethod(ClassNode cn, Method method, Consumer<MethodVisitor> body) {
        String targetName = method.getName();
        String targetDesc = Type.getMethodDescriptor(method);

        for (MethodNode mn : cn.methods) {
            if (!mn.name.equals(targetName) || !mn.desc.equals(targetDesc)) continue;
            mn.instructions.clear();
            mn.tryCatchBlocks.clear();
            mn.localVariables = null;
            body.accept(mn);
            return;
        }

        throw new OperateFailedException(
                "Method '" + method.getDeclaringClass().getName() + "."
                        + targetName + targetDesc + "' not found in bytecode.");
    }

    /**
     * 将 session 的 {@link ClassNode} 序列化为字节码。
     */
    private byte[] toBytecode(Class<?> target, ClassNode cn) {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS) {
            @Override
            protected ClassLoader getClassLoader() {
                ClassLoader cl = target.getClassLoader();
                return cl != null ? cl : ClassLoader.getSystemClassLoader();
            }
        };
        cn.accept(cw);
        return cw.toByteArray();
    }

    /**
     * 提交 session 的字节码，并回调 recorder 记录 undo。
     *
     * @param target       目标类
     * @param bytecode     session 累积后的字节码
     * @param registryIds  所有 {@code replace} 注册的 id；apply 失败时
     *                     由 {@link Session} 负责注销
     */
    private void commitSession(Class<?> target, byte[] bytecode, List<Integer> registryIds) {
        checkOwnerThread();
        byte[] oldBytecode = (recorder == null) ? null : snapshot(target);
        redefineRaw(target, bytecode);
        if (recorder != null) {
            recorder.afterRedefine(target, oldBytecode, registryIds);
        }
    }

    // ===== 内部：方法解析 =====

    /**
     * 按名称与参数类型解析目标方法。
     *
     * <p>委托 {@link Class#getDeclaredMethod(String, Class[])}，仅查找目标类
     * 自身声明的方法，不含继承链。</p>
     */
    private static Method resolveMethod(Class<?> owner, String name, Class<?>[] paramTypes) {
        try {
            Method method = owner.getDeclaredMethod(name, paramTypes);
            checkRedefinable(owner, method);
            return method;
        } catch (NoSuchMethodException e) {
            throw new OperateFailedException(
                    "Method '" + owner.getName() + "." + name
                            + describe(paramTypes) + "' not found.", e);
        }
    }

    /**
     * 校验参数类型数组，返回防御性拷贝。
     */
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

    private static String describe(Class<?>[] types) {
        if (types.length == 0) return "()";
        StringBuilder sb = new StringBuilder("(");
        for (int i = 0; i < types.length; i++) {
            if (i > 0) sb.append(", ");
            sb.append(types[i].getName());
        }
        return sb.append(")").toString();
    }

    // ===== 内部：提交 =====

    /**
     * 无记录 redefine。供 {@link #restore} 与回滚路径使用。
     *
     * <p><b>不检查 scope 状态</b>——回滚发生在 scope 已关闭之后。</p>
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

    // ===== 字节码读取 =====

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
     * 写桥接方法体：
     * <pre>
     *   MethodRegistry.invoke(id, owner, receiver, new Object[]{args...})
     * </pre>
     * 随后按目标返回类型拆箱 / 转型。
     *
     * <p><b>描述符必须与 {@link MethodRegistry#invoke(int, Class, Object, Object[])}
     * 的 Java 签名一致。</b>修改 invoke 的签名时，同步更新下面
     * {@code visitMethodInsn} 的字符串。</p>
     */
    private static void emitBridge(MethodVisitor mv, Method method, int id) {
        boolean isStatic = Modifier.isStatic(method.getModifiers());
        Class<?> owner = method.getDeclaringClass();
        Class<?>[] params = method.getParameterTypes();
        Class<?> returnType = method.getReturnType();

        mv.visitLdcInsn(Integer.valueOf(id));

        mv.visitLdcInsn(Type.getType(owner));

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
                "(ILjava/lang/Class;Ljava/lang/Object;[Ljava/lang/Object;)Ljava/lang/Object;",
                false);

        emitReturnFromObject(mv, returnType);
    }

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

    // ===== 桥接字节码辅助 =====

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

    private static boolean hasConstructor(Class<?> type, Class<?>... params) {
        try {
            type.getConstructor(params);
            return true;
        } catch (NoSuchMethodException e) {
            return false;
        }
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
     * 包级回滚入口。供 {@code Rootie} 的 undo 回放路径使用。
     *
     * <p>与 {@link #restore(Class, byte[])} 的区别：不写审计日志；不检查
     * scope 状态。回滚由 {@code Rootie} 统一记录，且必然发生在 scope 已
     * 关闭之后。</p>
     *
     * @param owner    被 redefine 的类
     * @param bytecode redefine 之前的字节码
     */
    void restoreForRollback(Class<?> owner, byte[] bytecode) {
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(bytecode, "bytecode");
        redefineRaw(owner, bytecode);
    }

    // ===== 嵌套类：Session =====

    /**
     * 一个类的多个方法体重定义会话。
     *
     * <p>链式累积对同一个类的多次 redefine 操作，在 {@link #apply()} 时一次性
     * 提交。相比逐方法调用 {@link RootDoRedefine#replace}，本类提供：</p>
     *
     * <ul>
     *   <li><b>一次 deopt</b>：所有方法在一次 {@code redefineClasses} 调用中生效，
     *       而非每个方法触发一次。</li>
     *   <li><b>原子性</b>：JVM 保证所有方法同时生效，不存在「一部分改了、一部分
     *       没改」的中间窗口。</li>
     *   <li><b>一条 undo 记录</b>：{@link Rootie#close()} 时一次性恢复旧字节码，
     *       批量注销替换函数。</li>
     * </ul>
     *
     * <p><b>生命周期</b>：一个 session 只能 {@link #apply()} 或 {@link #cancel()}
     * 一次。提交后 session 变为已关闭状态，任何后续操作抛
     * {@link IllegalStateException}。丢弃未提交的 session（不调 apply/cancel）
     * 会导致 {@link MethodRegistry} 中注册的替换函数泄漏——用户需自行保证
     * 最终调用其中之一。</p>
     *
     * <p><b>scope 状态</b>：所有方法在打开 scope 的 {@link Rootie} 关闭后
     * 抛 {@link OperateFailedException}。</p>
     *
     * <p><b>典型用法</b>：</p>
     * <pre>{@code
     * try (Rootie r = Rootie.acquireTest()) {
     *     r.rtdoRedefine()
     *             .on(Foo.class)
     *             .makeReturn("compute", EMPTY, 42)
     *             .replace("greet", EMPTY, ctx -> "hacked")
     *             .makeNoOp("log", new Class<?>[]{String.class})
     *             .apply();
     * }
     * }</pre>
     *
     * @since 0.4.0
     */
    public static final class Session {

        private final RootDoRedefine redefineOps;
        private final Class<?> target;
        private final ClassNode classNode;
        private final List<Integer> registryIds = new ArrayList<>();
        private final List<LogEntry> pendingLogs = new ArrayList<>();
        private boolean applied = false;

        private Session(RootDoRedefine redefineOps, Class<?> target, ClassNode classNode) {
            this.redefineOps = Objects.requireNonNull(redefineOps, "redefineOps");
            this.target = Objects.requireNonNull(target, "target");
            this.classNode = Objects.requireNonNull(classNode, "classNode");
        }

        // ===== 实例方法重定义 =====

        /**
         * 用 lambda 替换目标方法体。
         *
         * <p>与 {@link RootDoRedefine#replace} 语义相同，但不在调用时提交
         * ——延后到 {@link #apply()}。</p>
         *
         * @param name       方法名
         * @param paramTypes 形参类型数组；无参方法传 {@code new Class<?>[0]}
         * @param fn         替换逻辑
         * @return {@code this}，用于链式调用
         * @throws IllegalStateException session 已提交或已取消
         * @throws OperateFailedException scope 已关闭、方法不存在或不可重定义
         */
        public Session replace(String name, Class<?>[] paramTypes,
                               Function<Context, Object> fn) {
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(fn, "fn");
            requireOpen();

            Class<?>[] params = requireParamTypes(paramTypes);
            Method method = resolveMethod(target, name, params);

            int id = MethodRegistry.register(fn);
            try {
                redefineOps.modifyMethod(classNode, method,
                        mv -> emitBridge(mv, method, id));
                registryIds.add(Integer.valueOf(id));
                pendingLogs.add(new LogEntry(name, "function#" + id));
            } catch (Throwable t) {
                MethodRegistry.unregister(id);
                throw t;
            }
            return this;
        }

        /**
         * 使目标方法体只返回常量。
         *
         * @param name       方法名
         * @param paramTypes 形参类型数组；无参方法传 {@code new Class<?>[0]}
         * @param value      返回值；类型必须与方法返回类型兼容
         * @return {@code this}
         * @throws IllegalStateException session 已提交或已取消
         * @throws OperateFailedException scope 已关闭、方法不存在或
         *                                常量类型不兼容
         */
        public Session makeReturn(String name, Class<?>[] paramTypes, Object value) {
            Objects.requireNonNull(name, "name");
            requireOpen();

            Class<?>[] params = requireParamTypes(paramTypes);
            Method method = resolveMethod(target, name, params);
            redefineOps.modifyMethod(classNode, method,
                    mv -> emitReturn(mv, method, value));
            pendingLogs.add(new LogEntry(name, "return"));
            return this;
        }

        /**
         * 使目标方法体只抛出异常。
         *
         * @param name       方法名
         * @param paramTypes 形参类型数组；无参方法传 {@code new Class<?>[0]}
         * @param exception  要抛出的异常实例
         * @return {@code this}
         * @throws IllegalStateException session 已提交或已取消
         * @throws OperateFailedException scope 已关闭、方法不存在或
         *                                异常类无可用构造器
         */
        public Session makeThrow(String name, Class<?>[] paramTypes, Throwable exception) {
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(exception, "exception");
            requireOpen();

            Class<?>[] params = requireParamTypes(paramTypes);
            Method method = resolveMethod(target, name, params);
            redefineOps.modifyMethod(classNode, method,
                    mv -> emitThrow(mv, exception));
            pendingLogs.add(new LogEntry(name, "throw"));
            return this;
        }

        /**
         * 清空目标方法体。
         *
         * @param name       方法名
         * @param paramTypes 形参类型数组；无参方法传 {@code new Class<?>[0]}
         * @return {@code this}
         * @throws IllegalStateException session 已提交或已取消
         * @throws OperateFailedException scope 已关闭或方法不存在
         */
        public Session makeNoOp(String name, Class<?>[] paramTypes) {
            Objects.requireNonNull(name, "name");
            requireOpen();

            Class<?>[] params = requireParamTypes(paramTypes);
            Method method = resolveMethod(target, name, params);
            redefineOps.modifyMethod(classNode, method,
                    mv -> emitNoOp(mv, method));
            pendingLogs.add(new LogEntry(name, "noop"));
            return this;
        }

        // ===== 提交 / 取消 =====

        /**
         * 提交所有累积的重定义。
         *
         * <p>所有修改合并为一次 {@code redefineClasses} 调用。成功后写入审计
         * 日志。失败时自动注销已注册的替换函数，session 保持已关闭状态。</p>
         *
         * <p>本方法幂等性为零：重复调用抛 {@link IllegalStateException}。</p>
         *
         * @throws IllegalStateException session 已提交或已取消
         * @throws OperateFailedException scope 已关闭或 redefine 失败时
         */
        public void apply() {
            requireOpen();
            applied = true;

            byte[] bytecode = redefineOps.toBytecode(target, classNode);

            boolean committed = false;
            try {
                redefineOps.commitSession(target, bytecode, registryIds);
                committed = true;
            } finally {
                if (!committed) {
                    for (Integer id : registryIds) {
                        MethodRegistry.unregister(id.intValue());
                    }
                }
            }

            // commit 成功后，日志与 registry 解耦
            for (LogEntry entry : pendingLogs) {
                log.methodRedefine(target, entry.method, entry.kind);
            }
        }

        /**
         * 取消本次会话。
         *
         * <p>注销所有已注册的替换函数，不提交任何字节码修改。session 变为已关闭。</p>
         *
         * @throws IllegalStateException session 已提交或已取消
         * @throws OperateFailedException scope 已关闭
         */
        public void cancel() {
            requireOpen();
            applied = true;
            for (Integer id : registryIds) {
                MethodRegistry.unregister(id.intValue());
            }
        }

        // ===== 访问器 =====

        /** @return 本次会话的目标类 */
        public Class<?> target() {
            return target;
        }

        /** @return 是否已提交或已取消 */
        public boolean isClosed() {
            return applied;
        }

        // ===== 内部 =====

        private void requireOpen() {
            redefineOps.state.checkOpen();       // scope 层
            if (applied) {                        // session 层
                throw new IllegalStateException(
                        "Session for " + target.getName()
                                + " already applied or cancelled");
            }
        }

        private static final class LogEntry {
            final String method;
            final String kind;

            LogEntry(String method, String kind) {
                this.method = method;
                this.kind = kind;
            }
        }
    }
}