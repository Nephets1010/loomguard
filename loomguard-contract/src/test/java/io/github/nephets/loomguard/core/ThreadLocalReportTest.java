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

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 审计结果的契约：条目列表必须是防御性拷贝，否则调用方可以反向改写审计结论。 */
class ThreadLocalReportTest {

    @Test
    void emptyReportHasNoEntries() {
        ThreadLocalReport report = ThreadLocalReport.of(0L, 0, List.of());
        assertThat(report.totalEstimatedBytes()).isZero();
        assertThat(report.distinctLocals()).isZero();
        assertThat(report.entries()).isEmpty();
    }

    @Test
    void componentsAreExposedVerbatim() {
        ThreadLocalReport report =
                ThreadLocalReport.of(100L, 2, List.of(new ThreadLocalEntry("a", 10L, 5L, 50L)));
        assertThat(report.totalEstimatedBytes()).isEqualTo(100L);
        assertThat(report.distinctLocals()).isEqualTo(2);
        assertThat(report.entries()).hasSize(1);
    }

    /** 防御性拷贝：传进来的 list 之后被改动，不能影响已经构造好的报告。 */
    @Test
    void entryListIsCopiedDefensively() {
        List<ThreadLocalEntry> source = new ArrayList<>();
        source.add(new ThreadLocalEntry("a", 1L, 1L, 1L));
        ThreadLocalReport report = ThreadLocalReport.of(1L, 1, source);
        source.clear();
        assertThat(report.entries()).hasSize(1);
    }

    @Test
    void returnedEntryListIsImmutable() {
        ThreadLocalReport report =
                ThreadLocalReport.of(1L, 1, List.of(new ThreadLocalEntry("a", 1L, 1L, 1L)));
        assertThatThrownBy(() -> report.entries().add(new ThreadLocalEntry("b", 1L, 1L, 1L)))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void nullEntriesAreRejected() {
        assertThatThrownBy(() -> ThreadLocalReport.of(0L, 0, null))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(
                        () ->
                                ThreadLocalReport.of(
                                        0L, 0, java.util.Arrays.asList((ThreadLocalEntry) null)))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void equalReportsShareHashCode() {
        ThreadLocalReport a =
                ThreadLocalReport.of(1L, 1, List.of(new ThreadLocalEntry("a", 1L, 1L, 1L)));
        ThreadLocalReport b =
                ThreadLocalReport.of(1L, 1, List.of(new ThreadLocalEntry("a", 1L, 1L, 1L)));
        assertThat(a).isEqualTo(b);
        assertThat(a.hashCode()).isEqualTo(b.hashCode());
    }

    @Test
    void toStringIsDiagnosable() {
        assertThat(ThreadLocalReport.of(7L, 1, List.of()).toString())
                .contains("totalEstimatedBytes=7");
    }
}
