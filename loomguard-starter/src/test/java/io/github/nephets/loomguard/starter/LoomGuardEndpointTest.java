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
package io.github.nephets.loomguard.starter;

import io.github.nephets.loomguard.core.Finding;
import io.github.nephets.loomguard.core.LoomGuard;
import io.github.nephets.loomguard.core.LoomGuardReport;
import io.github.nephets.loomguard.core.Severity;
import io.github.nephets.loomguard.core.TaskObservation;
import io.github.nephets.loomguard.core.TaskSpec;
import io.github.nephets.loomguard.core.ThreadLocalEntry;
import io.github.nephets.loomguard.core.ThreadLocalReport;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 验证 Actuator 端点的快照内容。 */
class LoomGuardEndpointTest {

    @Test
    void readReturnsSnapshotWithEnabledAndTotals() {
        LoomGuardProperties props = new LoomGuardProperties();
        LoomGuard guard = LoomGuard.create();
        LoomGuardCollector collector = new LoomGuardCollector();
        LoomGuardEndpoint endpoint = new LoomGuardEndpoint(props, guard, collector);

        LoomGuardEndpoint.LoomGuardSnapshot snapshot = endpoint.read();
        assertThat(snapshot.enabled()).isTrue();
        assertThat(snapshot.distinctLocals()).isGreaterThanOrEqualTo(0);
        assertThat(snapshot.topThreadLocals()).isNotNull();
        assertThat(snapshot.recentFindings()).isEmpty();
    }

    @Test
    void readReflectsCollectedFindings() {
        LoomGuardProperties props = new LoomGuardProperties();
        LoomGuard guard = LoomGuard.create();
        LoomGuardCollector collector = new LoomGuardCollector();
        LoomGuardEndpoint endpoint = new LoomGuardEndpoint(props, guard, collector);

        LoomGuardReport report =
                LoomGuardReport.of(
                        new TaskObservation(
                                io.github.nephets.loomguard.core.PinningReport.empty(),
                                1,
                                TaskSpec.plain("x"),
                                true,
                                null),
                        List.of(
                                Finding.builder(Severity.CRITICAL, "LOOM-PINNED")
                                        .message("pinned")
                                        .build()),
                        ThreadLocalReport.of(0, 0, List.of()));
        collector.record(report);

        LoomGuardEndpoint.LoomGuardSnapshot snapshot = endpoint.read();
        assertThat(snapshot.recentFindings())
                .anyMatch(f -> f.severity().equals("CRITICAL") && f.ruleId().equals("LOOM-PINNED"));
    }

    /** 端点回显必须有上界：最近结果里若堆积海量发现，不能把响应体撑爆。 */
    @Test
    void readCapsRecentFindings() {
        LoomGuardProperties props = new LoomGuardProperties();
        LoomGuard guard = LoomGuard.create();
        LoomGuardCollector collector = new LoomGuardCollector();
        LoomGuardEndpoint endpoint = new LoomGuardEndpoint(props, guard, collector);

        List<Finding> many = new java.util.ArrayList<>();
        for (int i = 0; i < 25; i++) {
            many.add(Finding.builder(Severity.CRITICAL, "LOOM-PINNED-" + i).message("m").build());
        }
        collector.record(
                LoomGuardReport.of(
                        new TaskObservation(
                                io.github.nephets.loomguard.core.PinningReport.empty(),
                                1,
                                TaskSpec.plain("x"),
                                true,
                                null),
                        many,
                        ThreadLocalReport.of(0, 0, List.of())));

        assertThat(endpoint.read().recentFindings()).hasSize(20);
    }

    @Test
    void constructorRejectsNullDependencies() {
        LoomGuardProperties props = new LoomGuardProperties();
        LoomGuard guard = LoomGuard.create();
        LoomGuardCollector collector = new LoomGuardCollector();
        assertThatThrownBy(() -> new LoomGuardEndpoint(null, guard, collector))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new LoomGuardEndpoint(props, null, collector))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new LoomGuardEndpoint(props, guard, null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void ofHelperBuildsSnapshot() {
        ThreadLocalReport tl =
                ThreadLocalReport.of(100, 1, List.of(new ThreadLocalEntry("x", 10, 10, 100)));
        Finding f = LoomGuardEndpoint.critical("LOOM-PINNED", "pinned");
        LoomGuardEndpoint.LoomGuardSnapshot snapshot = LoomGuardEndpoint.of(true, tl, List.of(f));
        assertThat(snapshot.enabled()).isTrue();
        assertThat(snapshot.totalEstimatedBytes()).isEqualTo(100);
        assertThat(snapshot.recentFindings()).hasSize(1);
    }
}
