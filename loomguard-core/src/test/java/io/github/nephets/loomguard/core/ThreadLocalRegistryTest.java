/*
 * Copyright 2026 The loomguard Authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.github.nephets.loomguard.core;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 验证注册表本身的累加与快照语义。 */
class ThreadLocalRegistryTest {

    private final ThreadLocalRegistry registry = new ThreadLocalRegistry();

    @AfterEach
    void tearDown() {
        registry.reset();
    }

    @Test
    void emptyInitially() {
        assertThat(registry.distinctLocals()).isZero();
        assertThat(registry.snapshot()).isEmpty();
    }

    @Test
    void recordAccumulatesSetCallCount() {
        registry.record("a", 10);
        registry.record("a", 10);
        registry.record("b", 20);
        assertThat(registry.distinctLocals()).isEqualTo(2);
        assertThat(registry.snapshot())
                .anyMatch(e -> e.name().equals("a") && e.setCallCount() == 2);
    }

    @Test
    void latestBytesWins() {
        registry.record("a", 10);
        registry.record("a", 99);
        assertThat(registry.snapshot())
                .anyMatch(e -> e.name().equals("a") && e.bytesPerThread() == 99);
    }

    @Test
    void singletonIsShared() {
        assertThat(ThreadLocalRegistry.getInstance()).isSameAs(ThreadLocalRegistry.getInstance());
    }

    @Test
    void recordRejectsNullName() {
        assertThatThrownBy(() -> registry.record(null, 1L))
                .isInstanceOf(NullPointerException.class);
    }

    /** 回归测试：注册表是进程级单例，必须拒绝超出上限的新登记项， 否则「动态命名的 ThreadLocal」会让这两张 map 只增不减。 */
    @Test
    void newNamesBeyondCapacityAreIgnored() {
        int max = ThreadLocalRegistry.MAX_TRACKED_LOCALS;
        for (int i = 0; i < max; i++) {
            registry.record("local-" + i, 1L);
        }
        assertThat(registry.distinctLocals()).isEqualTo(max);

        registry.record("overflow-1", 1L);
        registry.record("overflow-2", 1L);
        assertThat(registry.distinctLocals()).isEqualTo(max);
        assertThat(registry.snapshot()).noneMatch(e -> e.name().startsWith("overflow-"));
    }

    /** 已达上限时，此前登记过的条目仍要能继续更新（不能因为超限就把老数据也停掉）。 */
    @Test
    void existingEntriesStillUpdateAtCapacity() {
        int max = ThreadLocalRegistry.MAX_TRACKED_LOCALS;
        for (int i = 0; i < max; i++) {
            registry.record("local-" + i, 1L);
        }
        // 循环里已经登记过一次 local-0，这里再登记两次 -> 计数应为 3
        registry.record("local-0", 42L);
        registry.record("local-0", 42L);
        assertThat(registry.snapshot())
                .anyMatch(
                        e ->
                                e.name().equals("local-0")
                                        && e.bytesPerThread() == 42
                                        && e.setCallCount() == 3);
        assertThat(registry.distinctLocals()).isEqualTo(max);
    }

    /** reset 之后容量告警标记也要复位，否则下一次超限不会再提示。 */
    @Test
    void resetReenablesCapacityWarningState() {
        int max = ThreadLocalRegistry.MAX_TRACKED_LOCALS;
        for (int i = 0; i < max; i++) {
            registry.record("local-" + i, 1L);
        }
        registry.record("overflow", 1L);
        registry.reset();
        assertThat(registry.distinctLocals()).isZero();
        registry.record("after-reset", 7L);
        assertThat(registry.distinctLocals()).isEqualTo(1);
    }
}
