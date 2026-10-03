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
package io.github.gapplex.jrootie.exceptions;

import io.github.gapplex.jrootie.operators.WriteRecord;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * {@code Rootie.close()} 回放 undo-log 时检测到冲突。
 *
 * <p>冲突定义：回放某条记录时，字段的当前值与 scope 写入值不等
 * （引用比较，见 {@link io.github.gapplex.jrootie.operators.Rootie}）。
 * 这意味着 scope 存续期间有外部代码修改了同一字段。</p>
 *
 * <p>{@link io.github.gapplex.jrootie.operators.AcquireMode#TEST}
 * 下冲突会先回滚再抛出本异常；
 * {@link io.github.gapplex.jrootie.operators.AcquireMode#TEST_KEEP}
 * 下保留现场并抛出。</p>
 *
 * @since 0.1.0
 */
public class ScopeCloseException extends RuntimeException {

    /**
     * 单条冲突记录。不可变值对象。
     *
     * @since 0.1.0
     */
    public static final class Conflict {

        private final WriteRecord record;
        private final Object currentValue;
        private final Throwable failure;

        /**
         * @param record       undo 条目，不可为 {@code null}
         * @param currentValue 回放时读到的当前值
         */
        public Conflict(WriteRecord record, Object currentValue) {
            this(record, currentValue, null);
        }

        public Conflict(WriteRecord record, Object currentValue, Throwable failure) {
            if (record == null) throw new IllegalArgumentException("record must not be null");
            this.record = record;
            this.currentValue = currentValue;
            this.failure = failure;
        }

        /** @return undo 条目 */
        public WriteRecord record() {
            return record;
        }

        /** @return 回放时读到的当前值 */
        public Object currentValue() {
            return currentValue;
        }

        public Throwable failure()     { return failure; }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof Conflict)) return false;
            Conflict conflict = (Conflict) o;
            return Objects.equals(record, conflict.record)
                    && Objects.equals(currentValue, conflict.currentValue)
                    && Objects.equals(failure, conflict.failure);
        }

        @Override
        public int hashCode() {
            return Objects.hash(record, currentValue, failure);
        }

        @Override
        public String toString() {
            StringBuilder sb = new StringBuilder("Conflict{");
            sb.append(record);
            if (failure != null) {
                sb.append(", rollbackFailed=").append(failure.getClass().getName());
                String msg = failure.getMessage();
                if (msg != null) sb.append(": ").append(msg);
            } else {
                sb.append(", currentValue=").append(describe(currentValue));
            }
            return sb.append('}').toString();
        }
    }

    private final List<Conflict> conflicts;

    /**
     * @param conflicts 冲突列表，不可为空
     */
    public ScopeCloseException(List<Conflict> conflicts) {
        super(buildMessage(conflicts));
        this.conflicts = Collections.unmodifiableList(new ArrayList<>(conflicts));
    }

    /** @return 不可变的冲突列表 */
    public List<Conflict> conflicts() {
        return conflicts;
    }

    private static String buildMessage(List<Conflict> conflicts) {
        StringBuilder sb = new StringBuilder();
        sb.append("Rootie scope closed with ").append(conflicts.size())
                .append(" conflict(s):");
        for (Conflict c : conflicts) {
            WriteRecord r = c.record();
            sb.append("\n  - ")
                    .append(r.field().getDeclaringClass().getName())
                    .append('.').append(r.field().getName())
                    .append(r.isStatic() ? " (static)" : "");

            if (c.failure() != null) {
                sb.append(": rollback FAILED with ")
                        .append(c.failure().getClass().getName())
                        .append(" (record NOT rolled back)");
            } else {
                sb.append(": expected=").append(describe(r.newValue()))
                        .append(", current=").append(describe(c.currentValue()))
                        .append(" (rolled back)");
            }
        }
        return sb.toString();
    }

    private static String describe(Object v) {
        if (v == null) return "null";
        return v.getClass().getSimpleName()
                + "@" + Integer.toHexString(System.identityHashCode(v));
    }
}