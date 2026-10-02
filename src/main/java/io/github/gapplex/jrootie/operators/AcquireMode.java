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

import java.util.Optional;

/**
 * 提权模式。决定 {@link Rootie} 是否记录 undo-log、是否自动回滚，
 * 以及回滚冲突时的默认策略。
 *
 * <p><b>契约：</b>模式在 {@code acquire} 时确定，生命周期内不可变。
 * 它是“这次提权拿到的能力”的声明，不是“这次写入怎么做”的配置。</p>
 *
 * @since 0.1.0
 */
public enum AcquireMode {

    /**
     * 默认模式。不记录 undo，不自动恢复。
     * 与原 {@code doAcquire("normal")} 完全一致，向后兼容。
     */
    NORMAL("normal"),

    /**
     * 测试模式。所有经该 {@link Rootie} 写入的字段自动记录 undo，
     * {@link Rootie#close()} 时按 LIFO 回滚。
     *
     * <p>冲突策略：{@link ConflictPolicy#ROLLBACK_AND_REPORT}——
     * 检测到外部修改仍回滚，并把冲突汇总为
     * {@link io.github.gapplex.jrootie.ScopeCloseException} 抛出。</p>
     */
    TEST("test"),

    /**
     * 测试模式 + 冲突保留。与 {@link #TEST} 相同，但检测到外部修改时
     * <b>不回滚</b>，保留现场供人工排查。
     *
     * <p>仅用于调试。常规测试请用 {@link #TEST}——保留脏现场会污染
     * 后续测试，让失败以更迷惑的方式扩散。</p>
     */
    TEST_KEEP("test-keep"),

    /**
     * 在 SecurityManager 之前提权。与原语义一致，不记录 undo。
     * 不提供自动恢复：这一模式的目的是“抢在 SM 之前拿到能力”，
     * 通常发生在 agent 初始化阶段，没有合理的“回滚时机”。
     */
    BEFORE_SECURITY_MANAGER("before-security-manager"),
    ;

    private final String tag;

    AcquireMode(String tag) {
        this.tag = tag;
    }

    /**
     * @return 外部可观测的模式标识（日志、agent 参数、系统属性使用此值）
     */
    public String tag() {
        return tag;
    }

    /**
     * @return 本模式是否记录 undo-log
     */
    public boolean recordsUndo() {
        return this == TEST || this == TEST_KEEP;
    }

    /**
     * 冲突（当前值 ≠ scope 写入值）时的默认策略。
     *
     * @return 冲突策略；{@link #NORMAL} 与
     *         {@link #BEFORE_SECURITY_MANAGER} 无 undo，返回值无意义
     */
    public ConflictPolicy conflictPolicy() {
        switch (this) {
            case TEST:
                return ConflictPolicy.ROLLBACK_AND_REPORT;
            case TEST_KEEP:
                return ConflictPolicy.KEEP_AND_REPORT;
            default:
                return ConflictPolicy.ROLLBACK_AND_REPORT;
        }
    }

    /**
     * 按字符串标识查找模式。
     *
     * @param tag 模式标识，可为 {@code null}
     * @return 命中的模式；未命中返回 {@link Optional#empty()}
     */
    public static Optional<AcquireMode> fromTag(String tag) {
        if (tag == null) return Optional.empty();
        for (AcquireMode m : values()) {
            if (m.tag.equals(tag)) return Optional.of(m);
        }
        return Optional.empty();
    }

    /**
     * 冲突处理策略。
     */
    public enum ConflictPolicy {
        /**
         * 回滚到 {@code oldValue}，并把冲突作为 {@code close()} 的失败抛出。
         */
        ROLLBACK_AND_REPORT,
        /**
         * 保留冲突后的现场，把冲突作为 {@code close()} 的失败抛出。
         */
        KEEP_AND_REPORT,
    }
}