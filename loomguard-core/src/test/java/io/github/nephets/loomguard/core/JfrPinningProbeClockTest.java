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

import io.github.nephets.loomguard.mockjfr.SyntheticPinnedEvent;
import io.github.nephets.loomguard.mockjfr.SyntheticPinningRecording;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 钉住「捕获时刻来自注入的时钟」这条契约：时间必须是输入，而不是环境副产品。 */
class JfrPinningProbeClockTest {

    private static final Instant FROZEN = Instant.parse("2026-01-02T03:04:05Z");
    private static final Clock FIXED = Clock.fixed(FROZEN, ZoneOffset.UTC);

    @Test
    void capturedAtComesFromTheInjectedClock() throws IOException {
        JfrPinningProbe probe = new JfrPinningProbe(SyntheticPinnedEvent.EVENT_NAME, FIXED);
        Path dump = SyntheticPinningRecording.writePinned(tmp(), 2);
        try {
            PinningReport report = probe.parse(dump);
            assertThat(report.events()).hasSize(2);
            assertThat(report.events())
                    .allSatisfy(e -> assertThat(e.capturedAt()).isEqualTo(FROZEN));
        } finally {
            deleteQuietly(dump);
        }
    }

    @Test
    void movingTheClockMovesTheCapturedInstant() throws IOException {
        Instant later = FROZEN.plusSeconds(600);
        Path dump = SyntheticPinningRecording.writePinned(tmp(), 1);
        try {
            Instant first =
                    new JfrPinningProbe(SyntheticPinnedEvent.EVENT_NAME, FIXED)
                            .parse(dump)
                            .events()
                            .get(0)
                            .capturedAt();
            Instant second =
                    new JfrPinningProbe(
                                    SyntheticPinnedEvent.EVENT_NAME,
                                    Clock.fixed(later, ZoneOffset.UTC))
                            .parse(dump)
                            .events()
                            .get(0)
                            .capturedAt();
            assertThat(second).isEqualTo(later);
            assertThat(second).isAfter(first);
        } finally {
            deleteQuietly(dump);
        }
    }

    @Test
    void eventNameAndClockAccessorsExposeWhatWasInjected() {
        JfrPinningProbe probe = new JfrPinningProbe("loomguard.Custom", FIXED);
        assertThat(probe.eventName()).isEqualTo("loomguard.Custom");
        assertThat(probe.clock()).isSameAs(FIXED);
    }

    @Test
    void defaultProbeUsesTheRealPinningEventName() {
        assertThat(new JfrPinningProbe().eventName()).isEqualTo(JfrPinningProbe.PINNED_EVENT_NAME);
    }

    @Test
    void constructorsRejectNull() {
        assertThatThrownBy(() -> new JfrPinningProbe((Clock) null))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new JfrPinningProbe((String) null))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new JfrPinningProbe("x", null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void probeConstructorSharesTheClockWithLoomProbe() {
        LoomProbe loomProbe = new LoomProbe(FIXED);
        assertThat(loomProbe).isNotNull();
        assertThatThrownBy(() -> new LoomProbe(null)).isInstanceOf(NullPointerException.class);
    }

    private static Path tmp() {
        return Path.of(System.getProperty("java.io.tmpdir"));
    }

    private static void deleteQuietly(Path file) {
        try {
            Files.deleteIfExists(file);
        } catch (IOException ignored) {
            // 尽力而为：损坏录制会泄漏句柄，Windows 上可能删不掉
        }
    }
}
