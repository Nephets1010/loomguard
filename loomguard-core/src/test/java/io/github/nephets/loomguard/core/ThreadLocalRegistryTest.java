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
}
