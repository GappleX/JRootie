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

import io.github.gapplex.jrootie.OperateFailedException;
import io.github.gapplex.jrootie.ScopeCloseException;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link Rootie} 的 undo-log 语义验证。
 *
 * <p>覆盖：</p>
 * <ul>
 *   <li>基础回滚（实例字段 / 静态字段 / null 值）</li>
 *   <li>LIFO 回放顺序</li>
 *   <li>嵌套 scope</li>
 *   <li>NORMAL 模式无回滚</li>
 *   <li>冲突检测（TEST 回滚报错 / TEST_KEEP 保留报错）</li>
 *   <li>线程绑定</li>
 *   <li>close 幂等</li>
 * </ul>
 *
 * <p><b>前置条件：</b>Java 16+ 运行时需要
 * {@code --add-opens java.base/sun.misc=ALL-UNNAMED}。</p>
 */
class RootieTest {

    /**
     * 测试目标：带私有实例字段与静态字段，只能通过反射访问。
     */
    static class Target {
        private int intField = 42;
        private String strField = "hello";
        private Object objField = null;

        private static String staticStr = "static-original";
        private static int staticInt = 7;

        int intField() { return intField; }
        String strField() { return strField; }
        Object objField() { return objField; }
        static String staticStr() { return staticStr; }
        static int staticInt() { return staticInt; }
    }

    // ===== 基础回滚 =====

    @Test
    void testBasicRollback() {
        Target t = new Target();
        try (Rootie r = Rootie.acquireTest()) {
            r.rtdoField().setFieldValue(t, "intField", 999);
            // 写入确实生效
            assertEquals(999, t.intField());
        }
        // close 后恢复
        assertEquals(42, t.intField());
    }

    @Test
    void testNullRollback() {
        Target t = new Target();
        try (Rootie r = Rootie.acquireTest()) {
            r.rtdoField().setFieldValue(t, "strField", null);
            assertNull(t.strField());
        }
        assertEquals("hello", t.strField());
    }

    @Test
    void testReferenceRollback() {
        Target t = new Target();
        Object original = new Object();
        Object replacement = new Object();
        try (Rootie r = Rootie.acquireTest()) {
            r.rtdoField().setFieldValue(t, "objField", original);
            assertSame(original, t.objField());
            r.rtdoField().setFieldValue(t, "objField", replacement);
            assertSame(replacement, t.objField());
        }
        // 回到初始值 null
        assertNull(t.objField());
    }

    @Test
    void testStaticRollback() {
        try (Rootie r = Rootie.acquireTest()) {
            r.rtdoField().setStaticFieldValue(Target.class, "staticStr", "modified");
            r.rtdoField().setStaticFieldValue(Target.class, "staticInt", 1000);
            assertEquals("modified", Target.staticStr());
            assertEquals(1000, Target.staticInt());
        }
        assertEquals("static-original", Target.staticStr());
        assertEquals(7, Target.staticInt());
    }

    // ===== LIFO 顺序 =====

    @Test
    void testLifoReplayOrder() {
        Target t = new Target();
        try (Rootie r = Rootie.acquireTest()) {
            r.rtdoField().setFieldValue(t, "intField", 100);
            r.rtdoField().setFieldValue(t, "intField", 200);
            r.rtdoField().setFieldValue(t, "intField", 300);
            assertEquals(300, t.intField());
        }
        // LIFO 回放，最终回到初始 42
        assertEquals(42, t.intField());
    }

    // ===== 嵌套 scope =====

    @Test
    void testNestedScopes() {
        Target t = new Target();
        try (Rootie outer = Rootie.acquireTest()) {
            outer.rtdoField().setFieldValue(t, "intField", 100);
            assertEquals(100, t.intField());

            try (Rootie inner = Rootie.acquireTest()) {
                inner.rtdoField().setFieldValue(t, "intField", 200);
                assertEquals(200, t.intField());
            }
            // inner 已回滚到 outer 写入的 100
            assertEquals(100, t.intField());
        }
        // outer 回滚到初始 42
        assertEquals(42, t.intField());
    }

    // ===== NORMAL 模式：无回滚 =====

    @Test
    void testNormalModeNoRollback() {
        Target t = new Target();
        try (Rootie r = Rootie.acquire()) {
            assertEquals(AcquireMode.NORMAL, r.mode());
            r.rtdoField().setFieldValue(t, "intField", 999);
        }
        // NORMAL 模式 close 是 no-op，值保持不变
        assertEquals(999, t.intField());
    }

    @Test
    void testNormalModeCloseIsNoop() {
        // 不 try-with-resources 也不该有资源泄漏；显式 close 是 no-op
        Rootie r = Rootie.acquire();
        r.close();
        r.close();
        assertEquals(AcquireMode.NORMAL, r.mode());
    }

    // ===== 冲突检测 =====

    @Test
    void testConflictRollbackAndReport() {
        Target t = new Target();
        ScopeCloseException ex = assertThrows(ScopeCloseException.class, () -> {
            try (Rootie r = Rootie.acquireTest()) {
                r.rtdoField().setFieldValue(t, "intField", 100);
                // 外部通过另一个 NORMAL Rootie 修改同一字段
                try (Rootie r2 = Rootie.acquire()) {
                    r2.rtdoField().setFieldValue(t, "intField", 200);
                }
                assertEquals(200, t.intField());
            }
        });

        // 冲突被记录
        List<ScopeCloseException.Conflict> conflicts = ex.conflicts();
        assertEquals(1, conflicts.size());
        ScopeCloseException.Conflict c = conflicts.get(0);
        assertEquals("intField", c.record().field().getName());
        assertEquals(Integer.valueOf(100), c.record().newValue());
        assertEquals(Integer.valueOf(200), c.currentValue());

        // TEST 模式下即使冲突也回滚到 oldValue
        assertEquals(42, t.intField());
    }

    @Test
    void testConflictKeepAndReport() {
        Target t = new Target();
        ScopeCloseException ex = assertThrows(ScopeCloseException.class, () -> {
            try (Rootie r = Rootie.acquireTestKeep()) {
                r.rtdoField().setFieldValue(t, "intField", 100);
                try (Rootie r2 = Rootie.acquire()) {
                    r2.rtdoField().setFieldValue(t, "intField", 200);
                }
            }
        });

        assertEquals(1, ex.conflicts().size());
        // TEST_KEEP 模式保留外部修改的现场
        assertEquals(200, t.intField());
    }

    @Test
    void testNoConflictWhenValueUnchanged() {
        Target t = new Target();
        // 无外部修改，close 不抛异常
        try (Rootie r = Rootie.acquireTest()) {
            r.rtdoField().setFieldValue(t, "intField", 100);
            // 二次写入相同值：recorder 两次回调，undo 两条
            r.rtdoField().setFieldValue(t, "intField", 100);
        }
        assertEquals(42, t.intField());
    }

    // ===== 线程绑定 =====

    @Test
    void testThreadBindingOnWrite() throws Exception {
        Rootie r = Rootie.acquireTest();
        try {
            Throwable[] captured = new Throwable[1];
            Thread th = new Thread(() -> {
                try {
                    r.rtdoField().setFieldValue(new Target(), "intField", 1);
                } catch (Throwable e) {
                    captured[0] = e;
                }
            }, "foreign-thread");
            th.start();
            th.join();

            assertNotNull(captured[0], "foreign thread write should fail");
            assertTrue(captured[0] instanceof OperateFailedException,
                    "expected OperateFailedException, got " + captured[0]);
        } finally {
            r.close();
        }
    }

    @Test
    void testThreadBindingOnClose() throws Exception {
        Rootie r = Rootie.acquireTest();
        Throwable[] captured = new Throwable[1];
        Thread th = new Thread(() -> {
            try {
                r.close();
            } catch (Throwable e) {
                captured[0] = e;
            }
        }, "foreign-thread");
        th.start();
        th.join();

        assertNotNull(captured[0]);
        assertTrue(captured[0] instanceof OperateFailedException);
        // 清理：从 owner 线程关闭
        r.close();
    }

    // ===== 幂等 =====

    @Test
    void testCloseIdempotent() {
        Rootie r = Rootie.acquireTest();
        Target t = new Target();
        r.rtdoField().setFieldValue(t, "intField", 999);
        r.close();
        assertEquals(42, t.intField());

        // 再次 close 不抛异常，也不重复回滚
        r.close();
        r.close();
        assertEquals(42, t.intField());
    }

    // ===== 模式入口 =====

    @Test
    void testAcquireModes() {
        try (Rootie r = Rootie.acquire()) {
            assertEquals(AcquireMode.NORMAL, r.mode());
        }
        try (Rootie r = Rootie.acquireTest()) {
            assertEquals(AcquireMode.TEST, r.mode());
        }
        try (Rootie r = Rootie.acquireTestKeep()) {
            assertEquals(AcquireMode.TEST_KEEP, r.mode());
        }
    }

    @Test
    void testUnknownModeRejected() {
        // 通过反射调用 doAcquire 验证未知模式被拒
        assertThrows(io.github.gapplex.jrootie.AcquireFailedException.class, () -> {
            java.lang.reflect.Method m = Rootie.class.getDeclaredMethod(
                    "doAcquire", String.class);
            m.setAccessible(true);
            try {
                m.invoke(null, "definitely-not-a-mode");
            } catch (java.lang.reflect.InvocationTargetException e) {
                throw (Throwable) e.getCause();
            }
        });
    }
}