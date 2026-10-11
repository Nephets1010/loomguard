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

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Finding 构造器设置所有字段。 */
class FindingTest {

    @Test
    void builderSetsAllFields() {
        Finding f =
                Finding.builder(Severity.CRITICAL, "LOOM-PINNED")
                        .message("pinned")
                        .suggestedFix("use ReentrantLock")
                        .evidenceStack(List.of("a#b"))
                        .build();
        assertThat(f.severity()).isEqualTo(Severity.CRITICAL);
        assertThat(f.ruleId()).isEqualTo("LOOM-PINNED");
        assertThat(f.message()).isEqualTo("pinned");
        assertThat(f.suggestedFix()).isEqualTo("use ReentrantLock");
        assertThat(f.evidenceStack()).containsExactly("a#b");
    }

    @Test
    void builderDefaultsAreEmpty() {
        Finding f = Finding.builder(Severity.OK, "LOOM-OK").build();
        assertThat(f.message()).isEmpty();
        assertThat(f.suggestedFix()).isEmpty();
        assertThat(f.evidenceStack()).isEmpty();
    }
}
