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
package io.github.nephets.loomguard.mockjfr;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import jdk.jfr.consumer.RecordedEvent;
import jdk.jfr.consumer.RecordingFile;

/**
 * 验证合成录制本身可复现：本模块的产出必须每次都一样，否则 core 的解析测试就失去了确定性的前提。
 *
 * <p><strong>为什么不用 JUnit 的 {@code @TempDir}：</strong>JDK 21 的 {@code RecordingFile} 在遇到损坏文件时
 * 会在构造方法里抛 {@code IOException}，此时 try-with-resources 尚未生效，文件句柄泄漏； 在 Windows
 * 上表现为文件被锁住，{@code @TempDir} 的清理步骤会因此失败并把测试判红。 所以这里用系统临时目录下的自建目录，并对删除做尽力而为的处理（删不掉不影响断言）。
 */
class SyntheticPinningRecordingTest {

    private Path dir;

    @BeforeEach
    void setUp() throws IOException {
        dir = Files.createTempDirectory("loomguard-mockjfr-test");
    }

    @AfterEach
    void tearDown() {
        deleteRecursivelyQuietly(dir);
    }

    @Test
    void writePinnedProducesExactlyTheRequestedEventCount() throws IOException {
        Path dump = SyntheticPinningRecording.writePinned(dir, 4);
        assertThat(eventsOf(dump)).hasSize(4);
    }

    @Test
    void writePinnedAcceptsZeroEvents() throws IOException {
        Path dump = SyntheticPinningRecording.writePinned(dir, 0);
        assertThat(eventsOf(dump)).isEmpty();
    }

    @Test
    void everySyntheticEventCarriesTheExpectedEventName() throws IOException {
        Path dump = SyntheticPinningRecording.writePinned(dir, 2);
        assertThat(eventsOf(dump))
                .allSatisfy(
                        e ->
                                assertThat(e.getEventType().getName())
                                        .isEqualTo(SyntheticPinnedEvent.EVENT_NAME));
    }

    @Test
    void everySyntheticEventCarriesAStackTrace() throws IOException {
        Path dump = SyntheticPinningRecording.writePinned(dir, 2);
        assertThat(eventsOf(dump))
                .allSatisfy(
                        e -> {
                            assertThat(e.getStackTrace()).isNotNull();
                            assertThat(e.getStackTrace().getFrames()).isNotEmpty();
                        });
    }

    /**
     * 瞬时事件（只 {@code commit()}）的时长必须是 0。
     *
     * <p>注意：JFR 给每个 {@code jdk.jfr.Event} 子类都附带 {@code duration} 字段，所以它并不会「缺失」； 真实 {@code
     * jdk.VirtualThreadPinned} 录出来的值恒为 0。合成录制必须复现这一点， 否则调用方会误以为「pinning 持续了 N 纳秒」。
     */
    @Test
    void instantEventsCarryZeroDuration() throws IOException {
        Path dump = SyntheticPinningRecording.writePinned(dir, 2);
        List<RecordedEvent> events = eventsOf(dump);
        assertThat(events).hasSize(2);
        assertThat(events).allSatisfy(e -> assertThat(e.getLong("duration")).isZero());
    }

    /** 「栈缺失」是必须被显式构造出来的降级输入。 */
    @Test
    void eventsRecordedWithoutStackTraceCarryNoStackTrace() throws IOException {
        Path dump = SyntheticPinningRecording.writePinnedWithoutStackTrace(dir, 2);
        List<RecordedEvent> events = eventsOf(dump);
        assertThat(events).hasSize(2);
        assertThat(events).allSatisfy(e -> assertThat(e.getStackTrace()).isNull());
    }

    /** 反过来：begin/end 提交的定时事件必须带上 duration，否则「带时长的 pinning」这条分支无从测试。 */
    @Test
    void timedEventsCarryADurationField() throws IOException {
        Path dump = SyntheticPinningRecording.writeTimedPinned(dir, 2);
        List<RecordedEvent> events = eventsOf(dump);
        assertThat(events).hasSize(2);
        assertThat(events).allSatisfy(e -> assertThat(e.getLong("duration")).isPositive());
    }

    @Test
    void deeperRecursionProducesAtLeastAsManyFrames() throws IOException {
        int shallow = framesOf(SyntheticPinningRecording.writePinned(dir, 1, 3));
        int deep = framesOf(SyntheticPinningRecording.writePinned(dir, 1, 30));
        assertThat(shallow).isPositive();
        assertThat(deep).isGreaterThanOrEqualTo(shallow);
    }

    @Test
    void writeEmptyProducesNoEventsButAValidRecording() throws IOException {
        Path dump = SyntheticPinningRecording.writeEmpty(dir);
        assertThat(Files.size(dump)).isPositive();
        assertThat(eventsOf(dump)).isEmpty();
    }

    @Test
    void garbageFileIsNotParseable() throws IOException {
        Path dump = SyntheticPinningRecording.writeGarbage(dir);
        assertThatThrownBy(() -> RecordingFile.readAllEvents(dump)).isInstanceOf(IOException.class);
    }

    @Test
    void truncatedFileIsNotParseable() throws IOException {
        Path dump = SyntheticPinningRecording.writeTruncated(dir, 3, 64);
        assertThat(Files.size(dump)).isEqualTo(64);
        assertThatThrownBy(() -> RecordingFile.readAllEvents(dump)).isInstanceOf(IOException.class);
    }

    @Test
    void newDumpFileCreatesAnEmptyFileInTheGivenDirectory() throws IOException {
        Path file = SyntheticPinningRecording.newDumpFile(dir);
        assertThat(file.getParent()).isEqualTo(dir);
        assertThat(Files.size(file)).isZero();
        assertThat(file.getFileName().toString())
                .startsWith(SyntheticPinningRecording.DUMP_PREFIX)
                .endsWith(SyntheticPinningRecording.DUMP_SUFFIX);
    }

    @Test
    void negativeEventCountIsRejected() {
        assertThatThrownBy(() -> SyntheticPinningRecording.writePinned(dir, -1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void zeroStackDepthIsRejected() {
        assertThatThrownBy(() -> SyntheticPinningRecording.writePinned(dir, 1, 0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void nullDirectoryIsRejected() {
        assertThatThrownBy(() -> SyntheticPinningRecording.writePinned(null, 1))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> SyntheticPinningRecording.newDumpFile(null))
                .isInstanceOf(NullPointerException.class);
    }

    private static List<RecordedEvent> eventsOf(Path dump) throws IOException {
        return RecordingFile.readAllEvents(dump);
    }

    private static int framesOf(Path dump) throws IOException {
        return eventsOf(dump).get(0).getStackTrace().getFrames().size();
    }

    /**
     * 尽力而为地删除。
     *
     * <p>为什么忽略失败：损坏录制会让 {@code RecordingFile} 泄漏文件句柄（见类注释），Windows 上文件会被锁住。
     * 这些文件落在系统临时目录里，由操作系统的临时目录策略兜底回收；测试的成功与否不应该取决于能否删掉它们。
     */
    private static void deleteRecursivelyQuietly(Path root) {
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
                                    // 尽力而为：锁住的文件交给系统临时目录策略回收
                                }
                            });
        } catch (IOException ignored) {
            // 同上
        }
    }
}
