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

/** Severity 级别排序。 */
class SeverityTest {

    @Test
    void okIsLowest() {
        assertThat(Severity.OK.max(Severity.WARN)).isEqualTo(Severity.WARN);
        assertThat(Severity.OK.max(Severity.CRITICAL)).isEqualTo(Severity.CRITICAL);
    }

    @Test
    void criticalIsHighest() {
        assertThat(Severity.CRITICAL.max(Severity.WARN)).isEqualTo(Severity.CRITICAL);
        assertThat(Severity.CRITICAL.max(Severity.OK)).isEqualTo(Severity.CRITICAL);
    }

    @Test
    void warnBetween() {
        assertThat(Severity.WARN.max(Severity.OK)).isEqualTo(Severity.WARN);
        assertThat(Severity.WARN.max(Severity.CRITICAL)).isEqualTo(Severity.CRITICAL);
    }

    @Test
    void sameLevelIsIdempotent() {
        assertThat(Severity.WARN.max(Severity.WARN)).isEqualTo(Severity.WARN);
    }
}
