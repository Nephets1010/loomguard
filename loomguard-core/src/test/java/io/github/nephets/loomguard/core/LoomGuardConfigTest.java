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

/** LoomGuardConfig 取值。 */
class LoomGuardConfigTest {

    @Test
    void defaultsEnabled() {
        assertThat(LoomGuardConfig.defaults().enabled()).isTrue();
    }

    @Test
    void ofControlsEnabled() {
        assertThat(LoomGuardConfig.of(true).enabled()).isTrue();
        assertThat(LoomGuardConfig.of(false).enabled()).isFalse();
    }

    @Test
    void createWithConfigPropagates() {
        LoomGuard guard = LoomGuard.create(LoomGuardConfig.of(false));
        assertThat(guard.config().enabled()).isFalse();
    }
}
