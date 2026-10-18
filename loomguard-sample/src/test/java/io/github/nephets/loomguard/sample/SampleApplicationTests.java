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
package io.github.nephets.loomguard.sample;

import io.github.nephets.loomguard.core.LoomGuard;
import io.github.nephets.loomguard.core.LoomGuardReport;
import io.github.nephets.loomguard.core.TaskSpec;
import io.github.nephets.loomguard.starter.LoomGuardCollector;
import io.github.nephets.loomguard.starter.LoomGuardEndpoint;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/** 应用上下文能正常加载，且 loomguard 自动装配出的门面确实可用。 */
@SpringBootTest
class SampleApplicationTests {

    @Autowired private LoomGuard loomGuard;
    @Autowired private LoomGuardCollector loomGuardCollector;
    @Autowired private LoomGuardEndpoint loomGuardEndpoint;

    @Test
    void contextLoadsLoomGuardBeans() {
        // 上下文能启动，且三个 bean 都真的被自动装配进来（而不是空跑一个无断言的冒烟测试）
        assertThat(loomGuard).isNotNull();
        assertThat(loomGuardCollector).isNotNull();
        assertThat(loomGuardEndpoint).isNotNull();
        assertThat(loomGuard.config()).isNotNull();
    }

    @Test
    void loomGuardFacadeProducesUsableReport() {
        LoomGuardReport report = loomGuard.inspect(TaskSpec.plain("smoke-plain"), () -> {});

        assertThat(report).isNotNull();
        assertThat(report.observation()).isNotNull();
        assertThat(report.observation().spec().name()).isEqualTo("smoke-plain");
        assertThat(report.observation().succeeded()).isTrue();
        // 普通任务不该被钉住，也不该出现 CRITICAL 级发现
        assertThat(report.observation().pinning().events()).isEmpty();
        assertThat(report.hasCritical()).isFalse();
        assertThat(report.threadLocalReport()).isNotNull();
        assertThat(report.findings()).isNotNull();
        assertThat(report.observedAt()).isNotNull().isBeforeOrEqualTo(Instant.now());
    }

    @Test
    void collectorKeepsLatestReport() {
        LoomGuardReport report = loomGuard.inspect(TaskSpec.plain("smoke-collector"), () -> {});
        loomGuardCollector.record(report);

        assertThat(loomGuardCollector.latest()).isNotNull();
        assertThat(loomGuardCollector.latest().observation().spec().name())
                .isEqualTo("smoke-collector");
        assertThat(loomGuardCollector.recent()).isNotEmpty();
    }
}
