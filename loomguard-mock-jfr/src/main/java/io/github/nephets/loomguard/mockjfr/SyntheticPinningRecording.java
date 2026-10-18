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

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.Objects;

import jdk.jfr.Recording;

/**
 * 可复现的合成 JFR 录制工厂：在受控条件下写出「内容确定」的 {@code .jfr} 文件。
 *
 * <h2>为什么需要它</h2>
 *
 * <p>直接测 {@code jdk.VirtualThreadPinned} 的解析有一个绕不过去的问题：<strong>是否发生 pinning 由 JVM 决定，
 * 不由测试决定</strong>。想触发一次真实 pinning，测试必须真的去抢一把 monitor 并在临界区里阻塞 —— 于是「解析逻辑是否正确」就被绑在了「宿主 JVM
 * 这一刻有没有打这个事件」上，在负载高的 CI 机器上会间歇性变红， 而且无法构造「空录制 / 损坏录制 / 缺字段 / 超长栈」这些边界输入，因为真实录制根本产生不出它们。
 *
 * <p>本类把这些输入全部变成<strong>可确定复现</strong>的：用 {@link Recording} 录制我们自己定义的合成事件， 再 {@code dump} 成真正的
 * {@code .jfr} 文件。产出的文件与真实录制走的是完全相同的二进制格式， 因此解析侧跑的是同一条代码路径 —— 测的是解析逻辑，不是 JVM。
 *
 * <h2>为什么不直接手写 .jfr 字节</h2>
 *
 * <p>JFR 的二进制格式（chunk / 常量池 / 压缩整数）是 JVM 的内部实现细节，没有稳定的公开规格。手写字节造出来的文件 会在 JDK
 * 小版本升级时集体失效，而且「这个文件是不是合法录制」本身就变成了被测试的对象。用官方 {@link Recording} 录制，格式兼容性由 JDK
 * 自己保证，本类只负责「发什么事件、发几个、栈多深」。
 *
 * <h2>资源管理</h2>
 *
 * <p>本类只负责<strong>产出</strong>文件，不负责删除。原因不是偷懒：实测发现 JDK 21 的 {@code RecordingFile}
 * 在遇到损坏文件时会在<strong>构造方法里</strong>抛 {@code IOException} （「Not a Flight Recorder
 * file」），此时构造尚未完成、try-with-resources 还没生效， 底层的文件句柄就泄漏了 —— 在 Windows 上表现为文件被锁住、删不掉， 放进 JUnit 的
 * {@code @TempDir} 会让「清理临时目录」这一步直接失败、把测试判红。
 * 所以调用方应在系统临时目录里生成这些文件、并对删除做<strong>尽力而为</strong>的处理，而不是假定一定能删掉。
 */
public final class SyntheticPinningRecording {

    /** 合成录制文件的后缀。 */
    public static final String DUMP_SUFFIX = ".jfr";

    /** 合成录制文件的前缀。 */
    public static final String DUMP_PREFIX = "loomguard-synthetic-";

    /** 一次合成录制里默认打的栈深度（递归层数）。 */
    public static final int DEFAULT_STACK_DEPTH = 8;

    /**
     * 「带时长」的合成事件至少要被钉住的时长（纳秒）。
     *
     * <p>为什么用自旋而不是 {@code Thread.sleep(1)}：{@code sleep} 的实际时长受系统定时器粒度影响， 在部分机器上会退化成 15 ms
     * 甚至更长，让「带时长事件的时长 > 0」这类断言变得不稳定； 用 {@code nanoTime} 自旋则保证下限、且不引入可中断语义。
     */
    public static final long MIN_TIMED_NANOS = 1_000_000L;

    /** 垃圾字节数：足够让 RecordingFile 立刻判定「这不是 JFR 文件」。 */
    private static final int GARBAGE_BYTES = 512;

    private SyntheticPinningRecording() {}

    /** 在系统临时目录里建一个新的 {@code .jfr} 空文件。 */
    public static Path newDumpFile() throws IOException {
        return newDumpFile(Path.of(System.getProperty("java.io.tmpdir")));
    }

    /**
     * 建一个新的 {@code .jfr} 空文件。
     *
     * <p>为什么先建空文件再写内容，而不是直接写：解析侧对「文件存在但内容非法」与「文件不存在」 走的是两条不同的降级分支，测试要的是前者。
     */
    public static Path newDumpFile(Path dir) throws IOException {
        Objects.requireNonNull(dir, "dir must not be null");
        return Files.createTempFile(dir, DUMP_PREFIX, DUMP_SUFFIX);
    }

    /**
     * 录一段含 {@code eventCount} 个<strong>不带时长字段</strong>的 pinning 事件的录制。
     *
     * <p>真实 {@code jdk.VirtualThreadPinned} 正是这种形态（瞬时事件，没有 duration）， 因此它同时用于验证「字段缺失时的降级」。
     *
     * @param dir 文件落盘目录
     * @param eventCount 事件个数
     * @param stackDepth 每个事件的栈深度（递归层数）
     * @return 落盘后的 {@code .jfr} 文件路径
     */
    public static Path writePinned(Path dir, int eventCount, int stackDepth) throws IOException {
        return record(dir, eventCount, stackDepth, false, true);
    }

    /** 见 {@link #writePinned(Path, int, int)}，使用默认栈深度。 */
    public static Path writePinned(Path dir, int eventCount) throws IOException {
        return writePinned(dir, eventCount, DEFAULT_STACK_DEPTH);
    }

    /**
     * 录一段「事件不带发生栈」的录制。
     *
     * <p>为什么要有这个形态：录制配置是可以关掉堆栈的（{@code withoutStackTrace()}），
     * 而第三方录制的事件也可能压根没开栈。解析代码若默认「栈一定存在」，就会在这类录制上炸掉。 真实 {@code jdk.VirtualThreadPinned}
     * 的价值恰恰<strong>全在栈上</strong>（没有栈就不知道哪里被钉了）， 所以「栈缺失」必须是一条被明确测试过的降级路径，而不是一个没人走过的分支。
     */
    public static Path writePinnedWithoutStackTrace(Path dir, int eventCount) throws IOException {
        return record(dir, eventCount, DEFAULT_STACK_DEPTH, false, false);
    }

    /**
     * 录一段「每个事件都带 {@code duration} 字段」的录制。
     *
     * <p>这是唯一能确定性地造出「带时长 pinning 事件」的办法：{@code duration} 是 JFR 保留字段名， 在事件类里声明它会导致事件根本录不进去；只能靠
     * {@code begin()/end()} 让 JVM 自己附上。
     */
    public static Path writeTimedPinned(Path dir, int eventCount, int stackDepth)
            throws IOException {
        return record(dir, eventCount, stackDepth, true, true);
    }

    /** 见 {@link #writeTimedPinned(Path, int, int)}，使用默认栈深度。 */
    public static Path writeTimedPinned(Path dir, int eventCount) throws IOException {
        return writeTimedPinned(dir, eventCount, DEFAULT_STACK_DEPTH);
    }

    /**
     * 录一段「启用了事件但一个都没发生」的录制。
     *
     * <p>这个场景在真实世界里很常见（探针跑在一处根本没有 pinning 的代码上），却无法用真实录制稳定造出来 —— 因为「没发生」和「JVM
     * 没打点」从结果上看是一样的。合成录制能把这两者区分开。
     */
    public static Path writeEmpty(Path dir) throws IOException {
        return record(dir, 0, DEFAULT_STACK_DEPTH, false, true);
    }

    /**
     * 写一段确定性的垃圾字节，模拟「录制文件被别的进程覆盖 / 写坏」的场景。
     *
     * <p><strong>为什么用确定性字节而不是 {@link java.util.Random}：</strong>损坏文件必须每次都能触发解析失败。 随机字节有极小概率恰好撞上 JFR
     * 的魔数、写出一个合法 chunk 头，让测试间歇性变绿； 用固定序列则「这个文件一定坏」这件事本身就是断言的一部分。
     */
    public static Path writeGarbage(Path dir) throws IOException {
        Path file = newDumpFile(dir);
        byte[] garbage = new byte[GARBAGE_BYTES];
        for (int i = 0; i < garbage.length; i++) {
            garbage[i] = (byte) (0x5A ^ (i & 0x7F));
        }
        Files.write(file, garbage);
        return file;
    }

    /**
     * 先录一段正常录制，再把它截断成前 {@code keepBytes} 字节，模拟磁盘写满 / 进程被强杀的场景。
     *
     * <p>之所以保留<strong>头部</strong>：截断头部意味着 JFR 连 chunk 头都读不到，退化成与垃圾文件同质的输入，
     * 测不出「读着读着断了」这条路径；保留头部、砍掉尾部才能复现「录制声明了自己有多少事件， 但数据区根本不够长」这种更真实的损坏。
     */
    public static Path writeTruncated(Path dir, int eventCount, long keepBytes) throws IOException {
        Path source = writePinned(dir, eventCount);
        Path target = newDumpFile(dir);
        byte[] head = new byte[(int) Math.min(keepBytes, Files.size(source))];
        try (FileChannel channel = FileChannel.open(source, StandardOpenOption.READ)) {
            channel.read(ByteBuffer.wrap(head));
        }
        Files.write(target, head);
        Files.deleteIfExists(source);
        return target;
    }

    private static Path record(
            Path dir, int eventCount, int stackDepth, boolean timed, boolean withStackTrace)
            throws IOException {
        Objects.requireNonNull(dir, "dir must not be null");
        if (eventCount < 0) {
            throw new IllegalArgumentException("eventCount must not be negative: " + eventCount);
        }
        if (stackDepth < 1) {
            throw new IllegalArgumentException("stackDepth must be at least 1: " + stackDepth);
        }
        // 必须先让事件类完成初始化：JFR 在类加载时把事件类型注册进元数据仓库，
        // 之后 Recording#enable(name) 才能按名字找到它，否则会抛 IllegalArgumentException。
        ensureRegistered();

        Path dump = newDumpFile(dir);
        try (Recording recording = new Recording()) {
            jdk.jfr.EventSettings settings =
                    recording.enable(SyntheticPinnedEvent.EVENT_NAME).withThreshold(Duration.ZERO);
            if (withStackTrace) {
                settings.withStackTrace();
            } else {
                settings.withoutStackTrace();
            }
            recording.start();
            for (int i = 0; i < eventCount; i++) {
                emitAtDepth(stackDepth, timed);
            }
            recording.stop();
            recording.dump(dump);
        }
        return dump;
    }

    private static void ensureRegistered() {
        try {
            SyntheticPinnedEvent.class.getDeclaredConstructor().newInstance();
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(
                    "无法初始化合成 JFR 事件类型：" + SyntheticPinnedEvent.EVENT_NAME, e);
        }
    }

    /** 递归到指定深度后再提交事件：栈深度完全由入参决定，因此「栈有多深」是测试输入而非环境副产品。 */
    private static void emitAtDepth(int depth, boolean timed) {
        if (depth <= 1) {
            SyntheticPinnedEvent event = new SyntheticPinnedEvent();
            if (timed) {
                event.begin();
                spinAtLeast(MIN_TIMED_NANOS);
                event.end();
            }
            event.commit();
            return;
        }
        emitAtDepth(depth - 1, timed);
    }

    private static void spinAtLeast(long nanos) {
        long start = System.nanoTime();
        while (System.nanoTime() - start < nanos) {
            Thread.onSpinWait();
        }
    }
}
