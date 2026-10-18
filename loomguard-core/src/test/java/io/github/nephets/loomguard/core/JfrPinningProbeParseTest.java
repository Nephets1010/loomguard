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

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 用<strong>确定性合成录制</strong>钉住 {@link JfrPinningProbe} 的解析契约。
 *
 * <p>为什么这些用例不能靠真实 pinning 来测：真实 pinning 是否发生由 JVM 决定，于是「空录制 / 损坏录制 / 缺栈 /
 * 超长栈」这些边界输入根本造不出来，而它们恰恰是解析代码最容易写错的地方。合成录制让每个边界都成为可复现的输入。
 */
class JfrPinningProbeParseTest {

    private static final String SYNTHETIC = SyntheticPinnedEvent.EVENT_NAME;

    private final JfrPinningProbe probe = new JfrPinningProbe(SYNTHETIC);

    private Path dir;

    @BeforeEach
    void setUp() throws IOException {
        dir = Files.createTempDirectory("loomguard-parse-test");
    }

    @AfterEach
    void tearDown() {
        deleteQuietly(dir);
    }

    @Test
    void normalRecordingYieldsEveryEvent() throws IOException {
        PinningReport report = probe.parse(SyntheticPinningRecording.writePinned(dir, 3));
        assertThat(report.pinnedCount()).isEqualTo(3);
        assertThat(report.anyPinned()).isTrue();
        assertThat(report.hotStacks()).hasSize(3);
    }

    @Test
    void occurrenceIndexMatchesParseOrder() throws IOException {
        PinningReport report = probe.parse(SyntheticPinningRecording.writePinned(dir, 3));
        assertThat(report.events())
                .extracting(PinningEvent::occurrenceIndex)
                .containsExactly(0, 1, 2);
    }

    @Test
    void everyEventCarriesAReadableStackTrace() throws IOException {
        PinningReport report = probe.parse(SyntheticPinningRecording.writePinned(dir, 2));
        assertThat(report.events()).allSatisfy(e -> assertThat(e.stackTrace()).isNotEmpty());
        assertThat(report.events().get(0).stackTrace())
                .anyMatch(frame -> frame.contains("SyntheticPinningRecording"));
    }

    @Test
    void emptyRecordingYieldsEmptyReport() throws IOException {
        PinningReport report = probe.parse(SyntheticPinningRecording.writeEmpty(dir));
        assertThat(report.anyPinned()).isFalse();
        assertThat(report.pinnedCount()).isZero();
        assertThat(report.hotStacks()).isEmpty();
    }

    /** 损坏文件必须降级成「没有 pinning」，而不是把异常抛给调用方。 */
    @Test
    void garbageRecordingDegradesToEmptyReport() throws IOException {
        PinningReport report = probe.parse(SyntheticPinningRecording.writeGarbage(dir));
        assertThat(report.anyPinned()).isFalse();
    }

    /** 截断文件同理：读到一半断了也要降级，不能让一次分析打断调用方的流程。 */
    @Test
    void truncatedRecordingDegradesToEmptyReport() throws IOException {
        PinningReport report = probe.parse(SyntheticPinningRecording.writeTruncated(dir, 3, 64));
        assertThat(report.anyPinned()).isFalse();
    }

    @Test
    void missingFileDegradesToEmptyReport() {
        PinningReport report = probe.parse(dir.resolve("does-not-exist.jfr"));
        assertThat(report.anyPinned()).isFalse();
    }

    /** 传进来一个目录而不是文件：同样是「读不了」，同样降级，不能抛异常。 */
    @Test
    void directoryDegradesToEmptyReport() {
        PinningReport report = probe.parse(dir);
        assertThat(report.anyPinned()).isFalse();
    }

    @Test
    void parseRejectsNullPath() {
        assertThatThrownBy(() -> probe.parse(null)).isInstanceOf(NullPointerException.class);
    }

    /** 栈缺失是最容易被忘记处理的降级路径 —— 没有栈的 pinning 事件什么都定位不了。 */
    @Test
    void eventsWithoutStackTraceDegradeToEmptyStack() throws IOException {
        PinningReport report =
                probe.parse(SyntheticPinningRecording.writePinnedWithoutStackTrace(dir, 2));
        assertThat(report.pinnedCount()).isEqualTo(2);
        assertThat(report.events()).allSatisfy(e -> assertThat(e.stackTrace()).isEmpty());
        assertThat(report.events().get(0).hotFrame()).isEqualTo("<unknown>");
    }

    /** 真实 jdk.VirtualThreadPinned 是瞬时事件：时长恒为 0，不能凭空编一个出来。 */
    @Test
    void instantEventsReportZeroDuration() throws IOException {
        PinningReport report = probe.parse(SyntheticPinningRecording.writePinned(dir, 2));
        assertThat(report.events()).allSatisfy(e -> assertThat(e.durationNanos()).isZero());
    }

    /** 反过来：录制里带了时长就必须如实读出来，否则阈值判定的「时长」这一维永远是死的。 */
    @Test
    void timedEventsReportTheirDuration() throws IOException {
        PinningReport report = probe.parse(SyntheticPinningRecording.writeTimedPinned(dir, 2));
        assertThat(report.events()).allSatisfy(e -> assertThat(e.durationNanos()).isPositive());
    }

    /** 事件名过滤：同一份录制里只有目标事件会被计入。 */
    @Test
    void eventsOfOtherTypesAreIgnored() throws IOException {
        JfrPinningProbe other = new JfrPinningProbe("loomguard.NotTheEventWeWant");
        PinningReport report = other.parse(SyntheticPinningRecording.writePinned(dir, 3));
        assertThat(report.anyPinned()).isFalse();
    }

    /** 默认探针只认真实事件：合成录制对它而言应当是空的。 */
    @Test
    void defaultProbeIgnoresSyntheticEvents() throws IOException {
        JfrPinningProbe realOnly = new JfrPinningProbe();
        assertThat(realOnly.eventName()).isEqualTo(JfrPinningProbe.PINNED_EVENT_NAME);
        assertThat(realOnly.parse(SyntheticPinningRecording.writePinned(dir, 3)).anyPinned())
                .isFalse();
    }

    /**
     * 超长栈必须被截断到上限。
     *
     * <p>断这里用 {@code isLessThanOrEqualTo} 而不是 {@code isEqualTo}：JFR 实际记录多少帧由 JVM 的 {@code
     * stackdepth} 决定（默认 64），不由本测试决定。精确的上限边界由 {@link #truncationKeepsExactlyTheLimit()}
     * 等用例直接钉在截断方法上。
     */
    @Test
    void veryLongStackTraceIsTruncatedToTheLimit() throws IOException {
        PinningReport report = probe.parse(SyntheticPinningRecording.writePinned(dir, 1, 200));
        assertThat(report.events()).hasSize(1);
        assertThat(report.events().get(0).stackTrace())
                .hasSizeLessThanOrEqualTo(JfrPinningProbe.MAX_STACK_FRAMES);
        assertThat(report.events().get(0).stackTrace()).isNotEmpty();
    }

    @Test
    void truncationKeepsExactlyTheLimit() {
        List<String> frames = frames(JfrPinningProbe.MAX_STACK_FRAMES + 40);
        List<String> truncated = probe.truncateStack(frames);
        assertThat(truncated).hasSize(JfrPinningProbe.MAX_STACK_FRAMES);
        assertThat(truncated).isEqualTo(frames.subList(0, JfrPinningProbe.MAX_STACK_FRAMES));
    }

    /** 恰好等于上限时不截断 —— 边界必须落在「不丢数据」那一侧。 */
    @Test
    void framesExactlyAtTheLimitAreNotTruncated() {
        List<String> frames = frames(JfrPinningProbe.MAX_STACK_FRAMES);
        assertThat(probe.truncateStack(frames)).hasSize(JfrPinningProbe.MAX_STACK_FRAMES);
        assertThat(probe.truncatedStackTraces()).isZero();
    }

    /** 超一帧就截断 —— 同上，边界的另一侧。 */
    @Test
    void oneFrameBeyondTheLimitIsTruncated() {
        List<String> frames = frames(JfrPinningProbe.MAX_STACK_FRAMES + 1);
        assertThat(probe.truncateStack(frames)).hasSize(JfrPinningProbe.MAX_STACK_FRAMES);
    }

    @Test
    void truncationIsCountedSoItCanBeObserved() {
        assertThat(probe.truncatedStackTraces()).isZero();
        probe.truncateStack(frames(JfrPinningProbe.MAX_STACK_FRAMES + 5));
        assertThat(probe.truncatedStackTraces()).isEqualTo(1);
        probe.truncateStack(frames(JfrPinningProbe.MAX_STACK_FRAMES + 5));
        assertThat(probe.truncatedStackTraces()).isEqualTo(2);
    }

    /** 短栈不计数：计数只应该反映「真的丢了数据」，而不是「做了次判断」。 */
    @Test
    void shortStacksAreNeverCounted() {
        probe.truncateStack(List.of());
        probe.truncateStack(frames(3));
        assertThat(probe.truncatedStackTraces()).isZero();
    }

    /** 截断返回的是不可变快照：调用方不能通过它反向改写解析中间结果。 */
    @Test
    void truncationReturnsAnImmutableSnapshot() {
        List<String> truncated = probe.truncateStack(frames(2));
        assertThatThrownBy(() -> truncated.add("x"))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    private static List<String> frames(int count) {
        List<String> frames = new java.util.ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            frames.add("com.example.Frame" + i + "#method" + i + ":" + i);
        }
        return frames;
    }

    /**
     * 尽力而为地删除。
     *
     * <p>为什么忽略失败：JDK 21 的 {@code RecordingFile} 在损坏文件上会在构造方法里抛异常、泄漏文件句柄， Windows
     * 上文件会被锁住删不掉。这些文件落在系统临时目录里，由系统策略兜底回收； 测试结论不该取决于能否删掉它们。
     */
    private static void deleteQuietly(Path root) {
        if (root == null || !Files.exists(root)) {
            return;
        }
        try (java.util.stream.Stream<Path> paths = Files.walk(root)) {
            paths.sorted(java.util.Comparator.reverseOrder())
                    .forEach(
                            p -> {
                                try {
                                    Files.deleteIfExists(p);
                                } catch (IOException ignored) {
                                    // 尽力而为
                                }
                            });
        } catch (IOException ignored) {
            // 尽力而为
        }
    }
}
