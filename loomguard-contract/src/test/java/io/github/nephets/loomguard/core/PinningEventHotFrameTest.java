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
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 钉住「热点帧」的选取规则。
 *
 * <p>为什么这条规则值得单独测：pinning 的栈里绝大多数帧是 JDK 与框架的样板， 真正要改的那一行在业务包里。选错帧，整份报告就把人指向了错误的地方 ——
 * 一个指向错误位置的告警比没有告警更糟，因为它会消耗掉排查者的信任。
 */
class PinningEventHotFrameTest {

    private static final Instant NOW = Instant.parse("2026-02-03T04:05:06Z");

    private static PinningEvent event(List<String> stack) {
        return new PinningEvent(stack, 0L, 0, NOW);
    }

    @Test
    void prefersTheFirstFrameInOurOwnPackage() {
        PinningEvent event =
                event(
                        List.of(
                                "java.base/java.lang.VirtualThread#park",
                                "com.example.Framework#invoke",
                                "io.github.nephets.app.OrderService#charge",
                                "io.github.nephets.app.OrderService#submit"));
        assertThat(event.hotFrame()).isEqualTo("io.github.nephets.app.OrderService#charge");
    }

    @Test
    void fallsBackToTheTopFrameWhenNothingIsInOurPackage() {
        assertThat(
                        event(List.of("java.base/java.lang.VirtualThread#park", "other.Thing#run"))
                                .hotFrame())
                .isEqualTo("java.base/java.lang.VirtualThread#park");
    }

    @Test
    void unknownWhenThereIsNoStackTraceAtAll() {
        assertThat(event(List.of()).hotFrame()).isEqualTo("<unknown>");
    }

    @Test
    void stackTraceIsImmutable() {
        PinningEvent event = event(new ArrayList<>(List.of("a")));
        assertThat(event.stackTrace()).containsExactly("a");
    }

    @Test
    void eventsWithTheSameComponentsAreEqual() {
        PinningEvent a = event(List.of("a"));
        PinningEvent b = new PinningEvent(List.of("a"), 0L, 0, NOW);
        assertThat(a).isEqualTo(b);
        assertThat(a.hashCode()).isEqualTo(b.hashCode());
    }

    @Test
    void eventsDifferByOccurrenceIndexAndDuration() {
        assertThat(new PinningEvent(List.of("a"), 0L, 0, NOW))
                .isNotEqualTo(new PinningEvent(List.of("a"), 0L, 1, NOW));
        assertThat(new PinningEvent(List.of("a"), 0L, 0, NOW))
                .isNotEqualTo(new PinningEvent(List.of("a"), 5L, 0, NOW));
        assertThat(new PinningEvent(List.of("a"), 0L, 0, NOW))
                .isNotEqualTo(new PinningEvent(List.of("a"), 0L, 0, NOW.plusSeconds(1)));
    }

    @Test
    void toStringIsDiagnosable() {
        assertThat(new PinningEvent(List.of("a#b"), 7L, 2, NOW).toString())
                .contains("a#b")
                .contains("occurrenceIndex=2");
    }
}
