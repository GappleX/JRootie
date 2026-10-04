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
 * {@link Context} 的便利包装，把 {@code receiver} 绑定为所有操作的默认目标。
 *
 * <p>在 {@link RootDoRedefine#replace} 的 lambda 里，用户经常需要对
 * {@code ctx.receiver()} 读写字段、调用方法。直接用
 * {@link RootDoField} / {@link RootDoMethod} 需要每次显式传 receiver。
 * 本类把 receiver 绑定进来，简化调用。</p>
 *
 * <p><b>无 classloader 桥接</b>：本类与 {@link Rootie} 同在应用侧，
 * 构造时直接持有 {@link RootDoField} / {@link RootDoMethod} 实例。
 * 不涉及 bootstrap 注入、不依赖 {@code Context} 内部能力。</p>
 *
 * <p><b>生命周期</b>：本类只持有引用，不管理任何资源。可以自由构造、
 * 丢弃；不需要 close。</p>
 *
 * <p><b>典型用法</b>：</p>
 * <pre>{@code
 * try (Rootie r = Rootie.acquireTest()) {
 *     RootDoRedefine redef = r.rtdoRedefine();
 *     redef.replace(Target.class, "update",
 *             new Class<?>[]{int.class},
 *             ctx -> {
 *                 ContextOps ops = new ContextOps(ctx, r);
 *                 Object[] table = ops.field("table", Object[].class);
 *                 table[ctx.arg(0)] = "hacked";
 *                 ops.setField("cache", null);
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
     * @throws io.github.gapplex.jrootie.exceptions.OperateFailedException
     *         字段不存在或类型不兼容
     */
    public <T> T field(String name, Class<? extends T> type) {
        return field.getFieldValue(ctx.receiver(), name, type);
    }

    /**
     * 写入 receiver 的实例字段。
     *
     * <p>可以写 {@code final} 字段——走 Unsafe 偏移直连，绕过 JVM 的
     * final 保护。</p>
     *
     * @param name  字段名
     * @param value 新值；基本类型需装箱；{@code null} 清空引用字段
     * @throws io.github.gapplex.jrootie.exceptions.OperateFailedException
     *         字段不存在、类型不兼容或底层写入失败
     */
    public void setField(String name, Object value) {
        field.setFieldValue(ctx.receiver(), name, value);
    }

    /**
     * 调用 receiver 的实例方法。
     *
     * <p>支持 {@code private} 方法——通过 {@code IMPL_LOOKUP} 解析。</p>
     *
     * <p><b>异常语义</b>：方法自身抛出的异常被 {@link RootDoMethod#invoke}
     * 包装为 {@link io.github.gapplex.jrootie.exceptions.OperateFailedException}，
     * 原始异常在 {@code getCause()}。这与原方法直接调用的行为不同——
     * 需要原始类型时用 {@code ctx} 的其他手段或直接调
     * {@link RootDoMethod}。</p>
     *
     * @param name       方法名
     * @param paramTypes 形参类型数组；无参方法传 {@code new Class<?>[0]}
     * @param args       实参；可为空
     * @return 方法返回值；{@code void} 返回 {@code null}
     * @throws io.github.gapplex.jrootie.exceptions.OperateFailedException
     *         方法不存在、方法抛异常或底层调用失败
     */
    public Object invoke(String name, Class<?>[] paramTypes, Object... args) {
        return method.invoke(ctx.receiver(), name, paramTypes, args);
    }

    /** @return 本对象绑定的 {@link Context}，便于链式访问 {@code ctx()} */
    public Context ctx() {
        return ctx;
    }
}