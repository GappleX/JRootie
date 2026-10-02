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
package io.github.gapplex.jrootie;

import org.slf4j.LoggerFactory;

/**
 * jrootie 审计日志入口。
 *
 * <p><b>设计约束：</b>本类不提供记录任意值的 API。
 * 所有方法只接受“结构信息”（类名、字段名、调用点），
 * 不接受“数据信息”（字段值、对象内容、hashCode）。</p>
 *
 * <p>需要记录值时，调用者应显式承担泄漏后果——
 * 这是应用层的决策，不是库的默认行为。</p>
 *
 * @since 0.1.0
 */
public final class Log {

    /** logger 名称前缀，最终 logger 名为 {@code jrootie.<ClassName>}。 */
    private static final String PREFIX = "jrootie";

    private Log() {
    }

    /**
     * 为指定类创建审计日志器。
     *
     * @param target 目标类，其全限定名会被拼接到 logger 名称中
     * @return 新的 {@link Audit} 实例
     */
    public static Audit audit(Class<?> target) {
        return new Audit(LoggerFactory.getLogger(PREFIX + "." + target.getName()));
    }

    /**
     * 为指定名称创建审计日志器。
     *
     * @param name 逻辑名（例如模块名）
     * @return 新的 {@link Audit} 实例
     */
    public static Audit audit(String name) {
        return new Audit(LoggerFactory.getLogger(PREFIX + "." + name));
    }
}