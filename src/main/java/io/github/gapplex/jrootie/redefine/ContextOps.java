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
package io.github.gapplex.jrootie.redefine;

import io.github.gapplex.jrootie.operators.RootDoField;
import io.github.gapplex.jrootie.operators.RootDoMethod;
import io.github.gapplex.jrootie.operators.RootDoRedefine;
import io.github.gapplex.jrootie.operators.Rootie;

import java.util.Objects;

/**
 * {@link Context} 的便利包装，把 receiver 与 owner 绑定为操作的默认目标。
 *
 * <p>在 {@link RootDoRedefine#replace} 的 lambda 里，用户经常需要对
 * {@code ctx.receiver()} 读写字段、调用方法，或对 {@code ctx.owner()}
 * 读写静态字段、调用静态方法。直接用 {@link RootDoField} /
 * {@link RootDoMethod} 需要每次显式传目标。本类把目标绑定进来，简化调用。</p>
 *
 * <h2>实例操作 vs 静态操作</h2>
 *
 * <p>方法命名与 {@link RootDoField} / {@link RootDoMethod} 对齐：</p>
 *
 * <ul>
 *   <li><b>实例操作</b>（{@link #field} / {@link #setField} / {@link #invoke}）
 *       ——目标为 {@code ctx.receiver()}。需要 receiver 非 {@code null}，
 *       仅适用于实例方法的 lambda。</li>
 *   <li><b>静态操作</b>（{@link #staticField} / {@link #setStaticField} /
 *       {@link #invokeStatic}）——目标为 {@code ctx.owner()}，即被 redefine
 *       的方法所属的类。实例方法和静态方法的 lambda 中都能使用。</li>
 * </ul>
 *
 * <h2>owner 的来源</h2>
 *
 * <p>{@code ctx.owner()} 由 {@link RootDoRedefine} 在写入桥接字节码时嵌入
 * ——{@code ldc} 一个 Class 常量——而非运行时从 {@code receiver.getClass()}
 * 推导。因此：</p>
 *
 * <ul>
 *   <li>redefine 父类方法时，{@code owner()} 是父类，不是子类</li>
 *   <li>redefine 接口 static 方法时，{@code owner()} 是接口</li>
 *   <li>静态方法的 lambda 里，{@code owner()} 与 {@code receiver()} 无关，
 *       始终非 {@code null}</li>
 * </ul>
 *
 * <h2>无 classloader 桥接</h2>
 *
 * <p>本类与 {@link Rootie} 同在应用侧，构造时直接持有 {@link RootDoField} /
 * {@link RootDoMethod} 实例。不涉及 bootstrap 注入、不依赖 {@code Context}
 * 内部能力。</p>
 *
 * <h2>生命周期</h2>
 *
 * <p>本类只持有引用，不管理任何资源。可以自由构造、丢弃；不需要 close。</p>
 *
 * <h2>典型用法</h2>
 *
 * <pre>{@code
 * try (Rootie r = Rootie.acquireTest()) {
 *     RootDoRedefine redef = r.rtdoRedefine();
 *     redef.replace(Target.class, "update",
 *             new Class<?>[]{int.class},
 *             ctx -> {
 *                 ContextOps ops = new ContextOps(ctx, r);
 *
 *                 // 实例操作——目标 = ctx.receiver()
 *                 Object[] table = ops.field("table", Object[].class);
 *                 table[ctx.arg(0)] = "hacked";
 *                 ops.setField("cache", null);
 *                 ops.invoke("notifyChanged", new Class<?>[0]);
 *
 *                 // 静态操作——目标 = ctx.owner()
 *                 int counter = ops.staticField("count", int.class);
 *                 ops.setStaticField("count", counter + 1);
 *                 ops.invokeStatic("log",
 *                         new Class<?>[]{String.class}, "updated");
 *
 *                 return null;
 *             });
 * }
 * }</pre>
 *
 * @since 0.3.1
 */
public final class ContextOps {

    private final Context ctx;
    private final RootDoField field;
    private final RootDoMethod method;

    /**
     * @param ctx    调用上下文，不可为 {@code null}
     * @param rootie 提供字段与方法操作器的 {@link Rootie}，不可为 {@code null}
     */
    public ContextOps(Context ctx, Rootie rootie) {
        this.ctx = Objects.requireNonNull(ctx, "ctx");
        Objects.requireNonNull(rootie, "rootie");
        this.field = rootie.rtdoField();
        this.method = rootie.rtdoMethod();
    }

    // ===== 实例操作（目标 = ctx.receiver()） =====

    /**
     * 读取 receiver 的实例字段。
     *
     * <p>沿继承链查找。可以读 {@code final} 字段。</p>
     *
     * <p><b>基本类型</b>：返回值是装箱类型。读取 {@code int} 字段传
     * {@code Integer.class}（或 {@code int.class}，类型校验在
     * {@link RootDoField#getFieldValue} 内部处理）。</p>
     *
     * @param name 字段名
     * @param type 期望类型
     * @param <T>  期望类型
     * @return 字段值
     * @throws IllegalStateException 静态方法的 lambda 中调用（receiver 为
     *                               {@code null}）
     * @throws io.github.gapplex.jrootie.exceptions.OperateFailedException
     *         字段不存在或类型不兼容
     */
    public <T> T field(String name, Class<? extends T> type) {
        requireInstance();
        return field.getFieldValue(ctx.receiver(), name, type);
    }

    /**
     * 写入 receiver 的实例字段。
     *
     * <p>可以写 {@code final} 字段——走 Unsafe 偏移直连，绕过 JVM 的
     * final 保护。</p>
     *
     * <p><b>基本类型</b>：值是装箱类型。写入 {@code int} 字段时传
     * {@code Integer}；写入引用字段时可以传 {@code null}。</p>
     *
     * @param name  字段名
     * @param value 新值；基本类型需装箱；{@code null} 清空引用字段
     * @throws IllegalStateException 静态方法的 lambda 中调用
     * @throws io.github.gapplex.jrootie.exceptions.OperateFailedException
     *         字段不存在、类型不兼容或底层写入失败
     */
    public void setField(String name, Object value) {
        requireInstance();
        field.setFieldValue(ctx.receiver(), name, value);
    }

    /**
     * 调用 receiver 的实例方法。
     *
     * <p>支持 {@code private} 方法——通过 {@code IMPL_LOOKUP} 解析。</p>
     *
     * <p><b>异常语义</b>：方法自身抛出的异常被
     * {@link RootDoMethod#invoke} 包装为
     * {@link io.github.gapplex.jrootie.exceptions.OperateFailedException}，
     * 原始异常在 {@code getCause()}。这与原方法直接调用的行为不同——
     * 需要原始类型时，用 {@link io.github.gapplex.jrootie.unsafe.IUnsafe}
     * 的 {@code throwException} 或直接调 {@link RootDoMethod}。</p>
     *
     * @param name       方法名
     * @param paramTypes 形参类型数组；无参方法传 {@code new Class<?>[0]}
     * @param args       实参；可为空
     * @return 方法返回值；{@code void} 返回 {@code null}
     * @throws IllegalStateException 静态方法的 lambda 中调用
     * @throws io.github.gapplex.jrootie.exceptions.OperateFailedException
     *         方法不存在、方法抛异常或底层调用失败
     */
    public Object invoke(String name, Class<?>[] paramTypes, Object... args) {
        requireInstance();
        return method.invoke(ctx.receiver(), name, paramTypes, args);
    }

    /**
     * 读取被 redefine 的方法所属类上的静态字段。
     *
     * <p>owner 类是 {@code ctx.owner()}——方法定义所在的类。不需要显式传入。</p>
     *
     * <p>沿继承链查找。可以读 {@code static final} 字段。</p>
     *
     * @param name 字段名
     * @param type 期望类型
     * @param <T>  期望类型
     * @return 字段值
     * @throws io.github.gapplex.jrootie.exceptions.OperateFailedException
     *         字段不存在、非静态字段或类型不兼容
     */
    public <T> T staticField(String name, Class<? extends T> type) {
        return field.getStaticFieldValue(ctx.owner(), name, type);
    }

    /**
     * 写入被 redefine 的方法所属类上的静态字段。
     *
     * <p>owner 类是 {@code ctx.owner()}——方法定义所在的类。不需要显式传入。</p>
     *
     * <p>可以写 {@code static final} 字段——走 Unsafe 偏移直连。</p>
     *
     * @param name  字段名
     * @param value 新值；基本类型需装箱；{@code null} 清空引用字段
     * @throws io.github.gapplex.jrootie.exceptions.OperateFailedException
     *         字段不存在、非静态字段、类型不兼容或底层写入失败
     */
    public void setStaticField(String name, Object value) {
        field.setStaticFieldValue(ctx.owner(), name, value);
    }

    /**
     * 调用被 redefine 的方法所属类上的静态方法。
     *
     * <p>owner 类是 {@code ctx.owner()}——方法定义所在的类。不需要显式传入。</p>
     *
     * <p>支持 {@code private static} 方法——通过 {@code IMPL_LOOKUP} 解析。</p>
     *
     * @param name       方法名
     * @param paramTypes 形参类型数组；无参方法传 {@code new Class<?>[0]}
     * @param args       实参；可为空
     * @return 方法返回值；{@code void} 返回 {@code null}
     * @throws io.github.gapplex.jrootie.exceptions.OperateFailedException
     *         方法不存在、非静态方法、方法抛异常或底层调用失败
     */
    public Object invokeStatic(String name, Class<?>[] paramTypes, Object... args) {
        return method.invokeStatic(ctx.owner(), name, paramTypes, args);
    }

    /**
     * 返回本对象绑定的 {@link Context}。
     *
     * <p>用于访问 {@link Context#owner()}、{@link Context#receiver()}、
     * {@link Context#args()} 等未被本类包装的能力。</p>
     *
     * @return 绑定的 Context 实例
     */
    public Context ctx() {
        return ctx;
    }

    /**
     * 校验当前 lambda 上下文有 receiver。
     *
     * <p>实例操作在静态方法的 lambda 里调用会触发——{@code ctx.receiver()}
     * 为 {@code null}。错误信息明确指向「你需要用静态操作」。</p>
     *
     * @throws IllegalStateException receiver 为 {@code null} 时
     */
    private void requireInstance() {
        if (ctx.receiver() == null) {
            throw new IllegalStateException(
                    "ContextOps instance methods require an instance context "
                            + "(receiver is null). "
                            + "Use the static variants "
                            + "(staticField / setStaticField / invokeStatic) "
                            + "in static method lambdas.");
        }
    }
}