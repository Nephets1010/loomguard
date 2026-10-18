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

/** 钉住「有界化必须可观测」：被拒绝的登记要能被计数，否则泄漏只会以「审计数字偏小」的形式悄悄出现。 */
class ThreadLocalRegistryEvictionTest {

    private final ThreadLocalRegistry registry = new ThreadLocalRegistry();

    @AfterEach
    void tearDown() {
        registry.reset();
    }

    @Test
    void noRejectionsWhileBelowCapacity() {
        for (int i = 0; i < 10; i++) {
            registry.record("local-" + i, 1L);
        }
        assertThat(registry.capacityRejections()).isZero();
    }

    @Test
    void rejectedRegistrationsAreCounted() {
        fillToCapacity();
        registry.record("overflow-1", 1L);
        assertThat(registry.capacityRejections()).isEqualTo(1);
        registry.record("overflow-2", 1L);
        registry.record("overflow-3", 1L);
        assertThat(registry.capacityRejections()).isEqualTo(3);
    }

    /** 已登记条目继续更新不算拒绝：计数只反映「真的丢了数据」。 */
    @Test
    void updatingExistingEntriesIsNotCountedAsRejection() {
        fillToCapacity();
        registry.record("local-0", 42L);
        registry.record("local-0", 43L);
        assertThat(registry.capacityRejections()).isZero();
        assertThat(registry.distinctLocals()).isEqualTo(ThreadLocalRegistry.MAX_TRACKED_LOCALS);
    }

    /** reset 清空数据但不抹掉「曾经被拒过」这个事实。 */
    @Test
    void resetKeepsTheRejectionTally() {
        fillToCapacity();
        registry.record("overflow", 1L);
        registry.reset();
        assertThat(registry.distinctLocals()).isZero();
        assertThat(registry.capacityRejections()).isEqualTo(1);
    }

    /** 拒绝写之后快照里不能留下这个条目：半写入的状态比不写入更难排查。 */
    @Test
    void rejectedEntriesNeverAppearInSnapshot() {
        fillToCapacity();
        registry.record("overflow", 999L);
        assertThat(registry.snapshot()).noneMatch(e -> e.name().equals("overflow"));
        assertThat(registry.totalEstimatedBytes())
                .isEqualTo(ThreadLocalRegistry.MAX_TRACKED_LOCALS);
    }

    private void fillToCapacity() {
        for (int i = 0; i < ThreadLocalRegistry.MAX_TRACKED_LOCALS; i++) {
            registry.record("local-" + i, 1L);
        }
    }
}
