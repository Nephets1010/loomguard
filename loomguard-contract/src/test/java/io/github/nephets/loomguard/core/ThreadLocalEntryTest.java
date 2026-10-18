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

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** 审计条目的值语义：它是要被排序、被汇总、被序列化出去的数据，语义必须稳定。 */
class ThreadLocalEntryTest {

    @Test
    void componentsAreExposedVerbatim() {
        ThreadLocalEntry entry = new ThreadLocalEntry("tenant", 16L, 3L, 48L);
        assertThat(entry.name()).isEqualTo("tenant");
        assertThat(entry.bytesPerThread()).isEqualTo(16L);
        assertThat(entry.setCallCount()).isEqualTo(3L);
        assertThat(entry.estimatedBytes()).isEqualTo(48L);
    }

    @Test
    void equalEntriesShareHashCode() {
        ThreadLocalEntry a = new ThreadLocalEntry("tenant", 16L, 3L, 48L);
        ThreadLocalEntry b = new ThreadLocalEntry("tenant", 16L, 3L, 48L);
        assertThat(a).isEqualTo(b);
        assertThat(a.hashCode()).isEqualTo(b.hashCode());
    }

    @Test
    void entriesDifferByName() {
        assertThat(new ThreadLocalEntry("a", 1L, 1L, 1L))
                .isNotEqualTo(new ThreadLocalEntry("b", 1L, 1L, 1L));
    }

    @Test
    void entriesDifferByAnyNumericComponent() {
        assertThat(new ThreadLocalEntry("a", 1L, 1L, 1L))
                .isNotEqualTo(new ThreadLocalEntry("a", 2L, 1L, 1L));
        assertThat(new ThreadLocalEntry("a", 1L, 1L, 1L))
                .isNotEqualTo(new ThreadLocalEntry("a", 1L, 2L, 1L));
        assertThat(new ThreadLocalEntry("a", 1L, 1L, 1L))
                .isNotEqualTo(new ThreadLocalEntry("a", 1L, 1L, 2L));
    }

    @Test
    void zeroSizedEntryIsLegal() {
        ThreadLocalEntry entry = new ThreadLocalEntry("empty", 0L, 0L, 0L);
        assertThat(entry.estimatedBytes()).isZero();
        assertThat(entry.toString()).contains("empty");
    }

    @Test
    void toStringIsDiagnosable() {
        assertThat(new ThreadLocalEntry("tenant", 16L, 3L, 48L).toString())
                .contains("tenant")
                .contains("48");
    }
}
