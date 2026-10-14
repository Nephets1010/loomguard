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

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** PinningEvent 的 hotFrame 选择。 */
class PinningEventTest {

    @Test
    void hotFramePrefersOurPackage() {
        PinningEvent e =
                new PinningEvent(
                        List.of(
                                "java.lang.Thread#sleep",
                                "io.github.nephets.loomguard.core.Foo#bar"),
                        0,
                        0,
                        Instant.now());
        assertThat(e.hotFrame()).isEqualTo("io.github.nephets.loomguard.core.Foo#bar");
    }

    @Test
    void hotFrameFallsBackToFirstWhenNoOurPackage() {
        PinningEvent e = new PinningEvent(List.of("java.lang.Thread#sleep"), 0, 0, Instant.now());
        assertThat(e.hotFrame()).isEqualTo("java.lang.Thread#sleep");
    }

    @Test
    void hotFrameHandlesEmptyStack() {
        PinningEvent e = new PinningEvent(List.of(), 0, 0, Instant.now());
        assertThat(e.hotFrame()).isEqualTo("<unknown>");
    }
}
