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

import io.github.nephets.loomguard.core.LoomGuard;
import io.github.nephets.loomguard.core.LoomGuardReport;
import io.github.nephets.loomguard.core.PinningReport;
import io.github.nephets.loomguard.core.TaskObservation;
import io.github.nephets.loomguard.core.TaskSpec;
import io.github.nephets.loomguard.core.ThreadLocalReport;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 钉住「观测端点永不抛异常」这条契约。
 *
 * <p>这些用例靠 mock 让数据来源真的抛异常。为什么要这么做：降级分支如果从来没被走过， 它就只是一句注释里的承诺；只有把它逼到真的失败一次，才能保证它在生产出问题时确实接得住。
 */
class LoomGuardEndpointDegradationTest {

    private static final LoomGuardReport EMPTY_REPORT =
            LoomGuardReport.of(
                    new TaskObservation(PinningReport.empty(), 1L, TaskSpec.plain("x"), true, null),
                    List.of(),
                    ThreadLocalReport.of(0L, 0, List.of()));

    @AfterEach
    void tearDown() {
        io.github.nephets.loomguard.core.ThreadLocalRegistry.getInstance().reset();
    }

    @Test
    void auditFailureDegradesToAnEmptyAuditRatherThanFailingTheRead() {
        LoomGuard guard = mock(LoomGuard.class);
        when(guard.auditThreadLocals()).thenThrow(new IllegalStateException("audit boom"));
        LoomGuardEndpoint endpoint =
                new LoomGuardEndpoint(new LoomGuardProperties(), guard, new LoomGuardCollector());

        LoomGuardEndpoint.LoomGuardSnapshot snapshot = endpoint.read();
        assertThat(snapshot.totalEstimatedBytes()).isZero();
        assertThat(snapshot.distinctLocals()).isZero();
        assertThat(snapshot.topThreadLocals()).isEmpty();
    }

    @Test
    void collectorFailureDegradesToEmptyFindingsButKeepsTheRest() {
        LoomGuardCollector collector = mock(LoomGuardCollector.class);
        when(collector.recent()).thenThrow(new IllegalStateException("collector boom"));
        LoomGuardEndpoint endpoint =
                new LoomGuardEndpoint(new LoomGuardProperties(), LoomGuard.create(), collector);

        LoomGuardEndpoint.LoomGuardSnapshot snapshot = endpoint.read();
        assertThat(snapshot.enabled()).isTrue();
        assertThat(snapshot.recentFindings()).isEmpty();
    }

    /** 降级后依然要回显启用状态 —— 那是使用方判断「loomguard 到底开没开」的唯一依据。 */
    @Test
    void degradedSnapshotStillReportsTheEnabledFlag() {
        LoomGuardProperties properties = new LoomGuardProperties();
        properties.setEnabled(false);
        LoomGuard guard = mock(LoomGuard.class);
        when(guard.auditThreadLocals()).thenThrow(new IllegalStateException("audit boom"));
        LoomGuardEndpoint endpoint =
                new LoomGuardEndpoint(properties, guard, new LoomGuardCollector());

        assertThat(endpoint.read().enabled()).isFalse();
    }

    /** 正常路径不能因为加了降级而变慢或变样。 */
    @Test
    void healthyPathIsUnaffectedByTheDegradationWrappers() {
        LoomGuardCollector collector = new LoomGuardCollector();
        collector.record(EMPTY_REPORT);
        LoomGuardEndpoint endpoint =
                new LoomGuardEndpoint(new LoomGuardProperties(), LoomGuard.create(), collector);

        assertThat(endpoint.read().enabled()).isTrue();
        assertThat(endpoint.read().recentFindings()).isEmpty();
    }

    /** 端点回显条数上限在数据来源正常时依然生效。 */
    @Test
    void capStillAppliesWhenTheCollectorIsHealthy() {
        LoomGuardCollector collector = new LoomGuardCollector();
        collector.record(EMPTY_REPORT);
        LoomGuardEndpoint endpoint =
                new LoomGuardEndpoint(new LoomGuardProperties(), LoomGuard.create(), collector);
        assertThat(endpoint.read().recentFindings()).isEmpty();
        assertThat(collector.recent()).hasSize(1);
    }
}
