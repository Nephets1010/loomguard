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
package io.github.nephets.loomguard.starter;

import io.github.nephets.loomguard.core.LoomGuardReport;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Objects;

/**
 * 有界地保留最近若干次巡检结果，供 Actuator 端点展示。
 *
 * <p><strong>为什么必须有界：</strong>本类的实例是 Spring Boot 单例 bean，与进程同生命周期。若保留全部结果，
 * 一个高频巡检的服务会在几小时内吃光堆；而它的用途只是「给排障的人看最近发生了什么」， 越旧的结果价值越低。所以这里用固定容量的 {@code Deque}：满了就丢最旧的，内存占用恒定。
 */
public class LoomGuardCollector {

    private static final Logger log = LoggerFactory.getLogger(LoomGuardCollector.class);

    /** 默认保留条数。 */
    public static final int DEFAULT_CAPACITY = 20;

    private final int capacity;
    private final Clock clock;
    private final Deque<LoomGuardReport> recent = new ArrayDeque<>();
    private long evictedCount;

    /** 默认保留最近 20 次，使用系统时钟。 */
    public LoomGuardCollector() {
        this(DEFAULT_CAPACITY, Clock.systemUTC());
    }

    /** 指定保留容量，使用系统时钟。 */
    public LoomGuardCollector(int capacity) {
        this(capacity, Clock.systemUTC());
    }

    /**
     * 指定时钟，使用默认容量。
     *
     * <p><strong>为什么时钟要可注入：</strong>{@link #recentWithin(Duration)} 问的是「最近这个窗口内的巡检」，
     * 这是一个纯粹的时间窗口判定。若这里内部取系统时间，测试就只能靠 {@code sleep} 去制造时间差 ——
     * 既慢又不稳定，而窗口边界（恰好等于窗口长度、差一毫秒）根本没法精确断言。注入固定时钟后， 边界用例是确定性的：移动时钟即可，一毫秒都不用等。
     */
    public LoomGuardCollector(Clock clock) {
        this(DEFAULT_CAPACITY, clock);
    }

    /** 指定容量与时钟。 */
    public LoomGuardCollector(int capacity, Clock clock) {
        this.capacity = Math.max(1, capacity);
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
        log.debug("初始化 LoomGuardCollector：capacity={}", this.capacity);
    }

    /** 保留容量。 */
    public int capacity() {
        return capacity;
    }

    /** 当前时刻（按注入的时钟）。 */
    public Instant now() {
        return clock.instant();
    }

    /** 记录一次巡检结果。 */
    public synchronized void record(LoomGuardReport report) {
        Objects.requireNonNull(report, "report must not be null");
        recent.addLast(report);
        while (recent.size() > capacity) {
            recent.removeFirst();
            evictedCount++;
            // 淘汰不能静默：一旦有人发现「怎么只剩最近这几条」，日志与计数里都要有据可查。
            log.warn("巡检结果保留数已达上限 {}，淘汰最旧的一条（累计淘汰 {} 条）", capacity, evictedCount);
        }
        log.debug("记录一次巡检结果：当前保留 {}/{} 条", recent.size(), capacity);
    }

    /** 最近的结果（从早到晚）。 */
    public synchronized List<LoomGuardReport> recent() {
        return List.copyOf(recent);
    }

    /**
     * 最近 {@code window} 之内的巡检结果（从早到晚）。
     *
     * <p><strong>边界口径是闭区间</strong>：{@code now - observedAt <= window} 算命中。之所以不取开区间， 是因为调用方说「我要最近 5
     * 分钟」时，恰好 5 分钟整的那一条在语义上属于「5 分钟内」； 用开区间会让它神秘地消失，而这类差一毫秒的问题最难排查 —— 边界必须落在直觉那一侧。
     *
     * @param window 时间窗口，不能为 null；{@link Duration#ZERO} 表示「只要此刻发生的」
     */
    public synchronized List<LoomGuardReport> recentWithin(Duration window) {
        Objects.requireNonNull(window, "window must not be null");
        Instant now = clock.instant();
        return recent.stream()
                .filter(report -> !report.observedAt().isBefore(now.minus(window)))
                .toList();
    }

    /** 最近一次结果（无则 null）。 */
    public synchronized LoomGuardReport latest() {
        return recent.peekLast();
    }

    /**
     * 已被淘汰的结果条数。
     *
     * <p>与 {@link #recent()} 的条数一起看，才能区分「最近真的只发生了这几条」和「旧的被扔掉了」。
     */
    public synchronized long evictedCount() {
        return evictedCount;
    }
}
