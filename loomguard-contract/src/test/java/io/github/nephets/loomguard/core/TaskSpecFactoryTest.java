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

/** 钉住任务元数据的三种命名工厂：它们决定了规则引擎走哪条分支，语义不能漂。 */
class TaskSpecFactoryTest {

    @Test
    void blockingIoMarksOnlyBlockingIo() {
        TaskSpec spec = TaskSpec.blockingIo("db-query");
        assertThat(spec.blockingIo()).isTrue();
        assertThat(spec.cpuBound()).isFalse();
        assertThat(spec.name()).isEqualTo("db-query");
    }

    @Test
    void cpuBoundMarksOnlyCpuBound() {
        TaskSpec spec = TaskSpec.cpuBound("hash");
        assertThat(spec.cpuBound()).isTrue();
        assertThat(spec.blockingIo()).isFalse();
    }

    @Test
    void plainMarksNeither() {
        TaskSpec spec = TaskSpec.plain("noop");
        assertThat(spec.blockingIo()).isFalse();
        assertThat(spec.cpuBound()).isFalse();
    }

    @Test
    void ofAllowsBothFlagsAtOnce() {
        TaskSpec spec = TaskSpec.of(true, true, "mixed");
        assertThat(spec.blockingIo()).isTrue();
        assertThat(spec.cpuBound()).isTrue();
    }

    @Test
    void equalSpecsShareHashCode() {
        assertThat(TaskSpec.plain("x")).isEqualTo(TaskSpec.of(false, false, "x"));
        assertThat(TaskSpec.plain("x").hashCode())
                .isEqualTo(TaskSpec.of(false, false, "x").hashCode());
    }

    @Test
    void nameIsPartOfIdentity() {
        assertThat(TaskSpec.plain("a")).isNotEqualTo(TaskSpec.plain("b"));
    }
}
