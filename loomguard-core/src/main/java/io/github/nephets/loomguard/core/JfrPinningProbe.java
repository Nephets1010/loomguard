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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import jdk.jfr.EventSettings;
import jdk.jfr.Recording;
import jdk.jfr.ValueDescriptor;
import jdk.jfr.consumer.RecordedEvent;
import jdk.jfr.consumer.RecordedFrame;
import jdk.jfr.consumer.RecordedMethod;
import jdk.jfr.consumer.RecordedStackTrace;
import jdk.jfr.consumer.RecordingFile;

/**
 * 基于 JFR 的 pinning 探针：在进程内录制 {@code jdk.VirtualThreadPinned} 事件，还原出每次 pinning 的发生栈（hot
 * stack），供调用方定位「哪里被钉住了」。
 *
 * <p>不需要任何特殊 JVM 参数：OpenJDK 21 的 JFR 默认可用，只要录制期间该事件被启用且带堆栈， pinning 一发生就会被捕获。这是运行时真实探测，而非静态扫描。
 *
 * <h2>为什么用 JFR 事件，而不是 {@code ThreadMXBean} 轮询 / 按 {@code Thread.State} 采样</h2>
 *
 * <p>pinning 的本质是「虚拟线程在临界区里被阻塞，导致它的载体线程没法卸载」。这件事<strong>只在阻塞发生的那一刻存在</strong>：
 * 一旦阻塞结束、虚拟线程重新挂载，从外部能观测到的状态就恢复如常了。任何基于轮询的方案（定时 {@code ThreadMXBean#dumpAllThreads}、JMX 采样、按 {@code
 * Thread.State} 过滤）都是在<strong>离散地抽样</strong>一个 持续时间可能只有几十微秒的事件 —— 采样周期与事件长度越不匹配，漏检率越高；而采样越密开销越大，
 * 还会反过来改变被测系统的时序（观察者效应）。
 *
 * <p>JFR 是 JVM 内部的<strong>事件</strong>而非采样：虚拟线程被钉住的那一刻由 JVM 自己打点，事件里直接带着当时的栈。
 * 因此它既不会漏检（不依赖采样恰好撞上），也不需要为覆盖率去堆采样频率。代价是必须开一次录制， 而录制是有开销的 ——
 * 这也是本类把「录制」做成<strong>围绕单次任务的短窗口</strong>（{@link #probe(Runnable)}） 而不是常驻录制的原因：常驻录制意味着给生产进程永久加一层
 * JFR 开销，那是观测工具不该有的重量。
 *
 * <h2>为什么用 {@code RecordingFile} 解析落盘文件，而不是直接读内存里的录制</h2>
 *
 * <p>{@link Recording} 与 {@link RecordingFile} 都能拿到事件列表，看似等价，实则决定了两件事：
 *
 * <ol>
 *   <li><strong>能否离线分析。</strong>走 {@code RecordingFile} 路径，同一份解析代码既能分析本次探测的录制， 也能分析运维从生产环境拷回来的任意
 *       {@code .jfr} 文件（见 {@link #parse(Path)}）。走内存路径的话， 「分析一个已有录制」就得再写一套，两套逻辑必然漂移，最终对同一个文件给出不同结论。
 *   <li><strong>内存占用是否有界。</strong>{@code readAllEvents} 这个名字有误导性：真正决定内存的是我们最终 保留多少栈帧，而不是录了多大的文件 ——
 *       所以本类在解析时就对单个事件的栈做截断（见 {@link #MAX_STACK_FRAMES}），让「录到了超长的栈」不会变成「一次探测吃掉几 MB」。
 * </ol>
 *
 * <h2>为什么必须在 finally 里删临时文件，而不是靠 JVM 退出钩（{@code deleteOnExit}）</h2>
 *
 * <p>{@code File#deleteOnExit()} 看起来更省事，但它有三个致命问题：
 *
 * <ol>
 *   <li><strong>它与进程同生命周期，不与探测同生命周期。</strong>loomguard 的典型用法是在长跑的服务进程里持续巡检， 每巡检一次落一个 {@code
 *       .jfr}；{@code deleteOnExit} 会把它们<strong>全部留到进程退出</strong>， 期间临时目录持续增长 ——
 *       这正是「加了清理却仍然泄漏」的经典形态。
 *   <li><strong>它的删除清单本身是无界内存。</strong>{@code deleteOnExit} 内部维护一个只会增长的集合， 且只在 JVM
 *       正常退出时消费；进程被强杀、容器被 OOM 杀掉时一次都不会执行， 而这些恰恰是最需要清理的场景。
 *   <li><strong>失败不可观测。</strong>{@code deleteOnExit} 删除失败时静默无声；在 {@code finally} 里删
 *       则能明确记录「删不掉」这一事实（{@code deleteDumpQuietly} 会 WARN 并带上路径）， 让磁盘问题能被看见，而不是变成一次无人知晓的泄漏。
 * </ol>
 *
 * <p>因此这里用 {@code try/finally}：<strong>无论探测成功、任务抛异常、还是解析失败，临时文件都在返回前被回收</strong>；
 * 删除失败只降级告警，绝不因为清理失败而改变本次探测的结论。
 */
public final class JfrPinningProbe {

    private static final Logger log = LoggerFactory.getLogger(JfrPinningProbe.class);

    /** 被探测的 JFR 事件名。 */
    public static final String PINNED_EVENT_NAME = "jdk.VirtualThreadPinned";

    /**
     * 单个 pinning 事件最多保留的栈帧数。
     *
     * <p><strong>为什么要有这个上限：</strong>一次探测可能落在任意深的调用栈上（典型 Web 应用的栈轻易上百帧）， 而每个栈帧都要拼成字符串、被 {@link
     * PinningEvent} 持有、最后还可能进 {@link Finding} 的证据列表、 被 Actuator 端点序列化出去。栈的深度对定位 pinning 几乎没有帮助 ——
     * <strong>元凶永远在最上面几帧</strong> （持有 monitor 的那个方法），越往下越是框架与容器的样板帧。因此这里设一道与 JFR 默认 {@code
     * stackdepth} 对齐的上限，把「超长栈」的内存成本钉死。
     *
     * <p><strong>为什么淘汰要可观测而不是静默截断：</strong>静默截断会让使用者误以为「栈就是这么浅」， 然后照着一份被砍过的栈去排查。截断时这里既打 WARN 也累加
     * {@link #truncatedStackTraces()}， 让「本次结论的栈不完整」成为可查询的事实。
     */
    public static final int MAX_STACK_FRAMES = 64;

    /** JFR 事件里承载「被钉时长」的字段名。真实 {@code jdk.VirtualThreadPinned} 不带此字段。 */
    public static final String DURATION_FIELD = "duration";

    private final String eventName;
    private final Clock clock;
    private final AtomicLong truncatedStackTraces = new AtomicLong();

    /** 使用真实事件名与系统时钟。 */
    public JfrPinningProbe() {
        this(PINNED_EVENT_NAME, Clock.systemUTC());
    }

    /**
     * 使用真实事件名，但由调用方提供时钟。
     *
     * <p><strong>为什么时钟要可注入：</strong>{@link PinningEvent#capturedAt()} 是这份数据唯一的「新鲜度」依据。 若探针内部直接
     * {@code Instant.now()}，则任何依赖捕获时刻的逻辑（例如「只看最近 5 分钟内发生的 pinning」）
     * 在测试里都只能断言「时刻是任意值」，时间窗口行为根本没法被确定性地钉住。把时钟提升为构造参数后， 生产用 {@link
     * Clock#systemUTC()}、测试用固定时钟，两边跑的是同一份判定代码。
     */
    public JfrPinningProbe(Clock clock) {
        this(PINNED_EVENT_NAME, clock);
    }

    /**
     * 指定要探测的事件名。
     *
     * <p>存在理由不是「可配置」本身，而是<strong>让解析逻辑可被确定性测试</strong>：真实 pinning 是否发生由 JVM 决定， 无法稳定造出「空录制 / 缺字段 /
     * 超长栈」这些边界输入。用合成事件名配合 {@code loomguard-mock-jfr}， 就能把这些输入全部变成可复现的录制文件，而解析侧跑的仍是同一条代码路径。
     */
    public JfrPinningProbe(String eventName) {
        this(eventName, Clock.systemUTC());
    }

    /** 指定事件名与时钟。 */
    public JfrPinningProbe(String eventName, Clock clock) {
        this.eventName = Objects.requireNonNull(eventName, "eventName must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    /** 本探针探测的事件名。 */
    public String eventName() {
        return eventName;
    }

    /** 本探针使用的时钟。 */
    public Clock clock() {
        return clock;
    }

    /** 已被截断的发生栈个数（用于判断「本次结论里的栈是否完整」）。 */
    public long truncatedStackTraces() {
        return truncatedStackTraces.get();
    }

    /** 在虚拟线程上执行任务并探测 pinning。 */
    public PinningReport probe(Runnable task) {
        log.debug("开始 JFR pinning 探测（Runnable）");
        return probeWrapped(
                () -> {
                    AtomicReference<Throwable> error = new AtomicReference<>();
                    Runnable guarded =
                            () -> {
                                try {
                                    task.run();
                                } catch (Throwable t) {
                                    error.set(t);
                                }
                            };
                    try {
                        Thread.startVirtualThread(guarded).join();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException("被探测的虚拟线程被中断", e);
                    }
                    if (error.get() != null) {
                        throw new RuntimeException("被探测任务抛出异常", error.get());
                    }
                });
    }

    /** 在虚拟线程上执行有返回值的任务并探测 pinning；异常会照常向上传播。 */
    public <T> PinningReport probe(Callable<T> task) throws Exception {
        log.debug("开始 JFR pinning 探测（Callable）");
        return probeWrapped(
                () -> {
                    AtomicReference<Throwable> error = new AtomicReference<>();
                    Callable<T> guarded =
                            () -> {
                                try {
                                    return task.call();
                                } catch (Throwable t) {
                                    error.set(t);
                                    return null;
                                }
                            };
                    try {
                        Thread.startVirtualThread(
                                        () -> {
                                            try {
                                                guarded.call();
                                            } catch (Exception e) {
                                                // guarded 内部已吞掉任务自身的 Throwable，这里只可能是
                                                // Callable 包装层的意外失败；必须留下日志再交给调用方，
                                                // 否则异常会被 AtomicReference 静默带走。
                                                log.error("被探测任务在虚拟线程执行期间抛出未预期异常", e);
                                                error.set(e);
                                            }
                                        })
                                .join();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException("被探测的虚拟线程被中断", e);
                    }
                    if (error.get() != null) {
                        throw new RuntimeException("被探测任务抛出异常", error.get());
                    }
                });
    }

    /**
     * 解析一个<strong>已经存在</strong>的 JFR 录制文件，产出 pinning 报告。
     *
     * <p><strong>为什么要把「解析」单独暴露出来：</strong>运维手里的 {@code .jfr} 往往来自一次独立的录制 （{@code jcmd
     * JFR.dump}、常驻录制、或容器崩溃前留下的文件）。若只有「边录边解析」这一条路径， 这些文件就得靠另一套工具分析，而两套解析器迟早会给出不同结论。让 {@link
     * #probe(Runnable)} 也走这个方法， 全库就只剩一份解析实现，「离线分析」与「在线探测」的结论天然一致。
     *
     * @param dump 录制文件路径，不能为 null
     * @return 解析结果；文件不存在、损坏或不可读时按空报告降级
     */
    public PinningReport parse(Path dump) {
        Objects.requireNonNull(dump, "dump must not be null");
        if (!Files.isRegularFile(dump)) {
            log.warn("JFR 录制文件不存在或不是普通文件，按空报告兜底：dump={}", dump);
            return PinningReport.empty();
        }
        List<PinningEvent> events = new ArrayList<>();
        try {
            for (RecordedEvent event : RecordingFile.readAllEvents(dump)) {
                if (!eventName.equals(event.getEventType().getName())) {
                    continue;
                }
                events.add(toPinningEvent(event, events.size()));
            }
        } catch (IOException | RuntimeException e) {
            // 降级点：录制可能是别的 JDK 版本写的、被截断过、或压根不是 JFR 文件。
            // 观测失败必须降级成「没有 pinning」，绝不能让一次分析打断调用方的流程。
            log.error("读取 JFR 录制事件失败，按空报告兜底：dump={}", dump, e);
            return PinningReport.empty();
        }
        log.debug("JFR 录制解析完成：dump={}, 捕获 {} 个 pinning 事件", dump, events.size());
        return PinningReport.of(events);
    }

    private PinningReport probeWrapped(ThrowingRunnable runnable) {
        Path dump = tempFile();
        try {
            return recordAndParse(runnable, dump);
        } finally {
            // 每次探测都会落一个临时 .jfr 文件；解析完必须回收，
            // 否则长跑进程会把临时目录撑爆（此前从未删除过）。
            deleteDumpQuietly(dump);
        }
    }

    private PinningReport recordAndParse(ThrowingRunnable runnable, Path dump) {
        // Recording 是 Closeable：用 try-with-resources 保证无论探测任务是否抛异常都释放录制资源
        try (Recording recording = new Recording()) {
            EventSettings settings = recording.enable(eventName);
            settings.withStackTrace();
            // 0 ms 阈值：捕获所有 pinning，不漏报
            settings.withThreshold(Duration.ZERO);
            recording.start();
            try {
                runnable.run();
            } finally {
                recording.stop();
                try {
                    recording.dump(dump);
                } catch (IOException e) {
                    log.error("JFR 录制转储失败，按空报告兜底：dump={}", dump, e);
                    return PinningReport.empty();
                }
            }
        }

        return parse(dump);
    }

    private static void deleteDumpQuietly(Path dump) {
        try {
            Files.deleteIfExists(dump);
        } catch (IOException e) {
            // 降级点：删除失败不影响本次探测结论，仅记录，剩余文件交由操作系统临时目录策略回收
            log.warn("JFR 临时录制文件删除失败，交由系统临时目录策略回收：dump={}", dump, e);
        }
    }

    private PinningEvent toPinningEvent(RecordedEvent event, int index) {
        List<String> stack = new ArrayList<>();
        RecordedStackTrace trace = event.getStackTrace();
        if (trace != null) {
            for (RecordedFrame frame : trace.getFrames()) {
                RecordedMethod method = frame.getMethod();
                if (method == null) {
                    continue;
                }
                String line = method.getType().getName() + "#" + method.getName();
                if (frame.getLineNumber() > 0) {
                    line += ":" + frame.getLineNumber();
                }
                stack.add(line);
            }
        }
        return new PinningEvent(truncateStack(stack), durationOf(event), index, clock.instant());
    }

    /**
     * 从事件里取「被钉住多久」。
     *
     * <p><strong>为什么先查字段是否存在再取值：</strong>{@code jdk.VirtualThreadPinned} 只有「被钉住了」这一个事实， 并不带时长字段；而不同
     * JDK 版本、不同来源的录制带的字段并不一致。直接 {@code event.getLong("duration")} 会在字段缺失时抛 {@code
     * IllegalArgumentException}，把一个「可选的元数据」升级成「整个录制解析失败」。 按「有则取、无则降级为
     * 0」处理，缺字段就只是「这一次不知道时长」，不影响「有没有被钉住」这个核心结论。
     */
    private static long durationOf(RecordedEvent event) {
        for (ValueDescriptor field : event.getFields()) {
            if (DURATION_FIELD.equals(field.getName()) && "long".equals(field.getTypeName())) {
                return event.getLong(DURATION_FIELD);
            }
        }
        return 0L;
    }

    /**
     * 把发生栈截断到 {@link #MAX_STACK_FRAMES} 帧，并让截断这件事可观测（WARN + 计数）。
     *
     * <p><strong>为什么是包级可见而不是 private：</strong>截断的边界行为（恰好等于上限、超一帧、空栈）必须能在测试里
     * 被直接钉住，而靠真实录制去构造一个确定深度的栈是不可能的 —— JFR 记录多少帧由 JVM 的 {@code stackdepth} 决定，不由测试决定。
     */
    List<String> truncateStack(List<String> frames) {
        if (frames.size() <= MAX_STACK_FRAMES) {
            return List.copyOf(frames);
        }
        truncatedStackTraces.incrementAndGet();
        log.warn("pinning 发生栈超过 {} 帧（实际 {}），已截断；定位元凶通常只需最上面几帧", MAX_STACK_FRAMES, frames.size());
        return List.copyOf(frames.subList(0, MAX_STACK_FRAMES));
    }

    private static Path tempFile() {
        try {
            return Files.createTempFile("loomguard", ".jfr");
        } catch (IOException e) {
            throw new IllegalStateException("无法创建 JFR 临时文件", e);
        }
    }

    /** 探针任务包装。 */
    @FunctionalInterface
    private interface ThrowingRunnable {
        void run();
    }
}
