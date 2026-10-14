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

/** TaskSpec 工厂设置正确的元数据。 */
class TaskSpecTest {

    @Test
    void blockingIoFactory() {
        TaskSpec spec = TaskSpec.blockingIo("io");
        assertThat(spec.blockingIo()).isTrue();
        assertThat(spec.cpuBound()).isFalse();
        assertThat(spec.name()).isEqualTo("io");
    }

    @Test
    void cpuBoundFactory() {
        TaskSpec spec = TaskSpec.cpuBound("cpu");
        assertThat(spec.cpuBound()).isTrue();
        assertThat(spec.blockingIo()).isFalse();
    }

    @Test
    void plainFactory() {
        TaskSpec spec = TaskSpec.plain("plain");
        assertThat(spec.blockingIo()).isFalse();
        assertThat(spec.cpuBound()).isFalse();
    }

    @Test
    void ofSetsAllFields() {
        TaskSpec spec = TaskSpec.of(true, true, "both");
        assertThat(spec.blockingIo()).isTrue();
        assertThat(spec.cpuBound()).isTrue();
        assertThat(spec.name()).isEqualTo("both");
    }
}
