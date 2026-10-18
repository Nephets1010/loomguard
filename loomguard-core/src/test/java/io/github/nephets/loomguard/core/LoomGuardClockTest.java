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

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.locks.ReentrantLock;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 钉住门面层的时钟契约与「热点聚合属于观测路径」这条不变量。 */
class LoomGuardClockTest {

    private static final Instant FROZEN = Instant.parse("2026-03-04T05:06:07Z");
    private static final Clock FIXED = Clock.fixed(FROZEN, ZoneOffset.UTC);

    @AfterEach
    void tearDown() {
        ThreadLocalRegistry.getInstance().reset();
    }

    @Test
    void observedAtComesFromTheInjectedClock() {
        LoomGuard guard = LoomGuard.create(LoomGuardConfig.defaults(), FIXED);
        LoomGuardReport report = guard.inspect(TaskSpec.plain("x"), () -> sleep(5));
        assertThat(report.observedAt()).isEqualTo(FROZEN);
    }

    @Test
    void clockAccessorExposesTheInjectedClock() {
        assertThat(LoomGuard.create(LoomGuardConfig.defaults(), FIXED).clock()).isSameAs(FIXED);
    }

    @Test
    void movingClockMovesObservedAt() {
        Instant later = FROZEN.plusSeconds(60);
        LoomGuard first = LoomGuard.create(LoomGuardConfig.defaults(), FIXED);
        LoomGuard second =
                LoomGuard.create(LoomGuardConfig.defaults(), Clock.fixed(later, ZoneOffset.UTC));
        assertThat(second.inspect(TaskSpec.plain("x"), () -> sleep(1)).observedAt())
                .isAfter(first.inspect(TaskSpec.plain("x"), () -> sleep(1)).observedAt());
    }

    @Test
    void createRejectsNullArguments() {
        assertThatThrownBy(() -> LoomGuard.create(null, FIXED))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> LoomGuard.create(LoomGuardConfig.defaults(), null))
                .isInstanceOf(NullPointerException.class);
    }

    /** 缺省构造不能因为加了时钟注入就退化：它仍然要用系统时钟。 */
    @Test
    void defaultConstructorStillWorks() {
        LoomGuard guard = LoomGuard.create();
        assertThat(guard.clock()).isNotNull();
        assertThat(guard.inspect(TaskSpec.plain("x"), () -> sleep(1)).observedAt()).isNotNull();
    }

    @Test
    void hotspotCacheIsEmptyBeforeAnyPinnedInspection() {
        LoomGuard guard = LoomGuard.create(LoomGuardConfig.defaults(), FIXED);
        assertThat(guard.hotspotCache()).isNotNull();
        assertThat(guard.hotspotCache().size()).isZero();
    }

    /** 被钉住的巡检会把热点记进缓存：这是「跨巡检聚合」的核心不变量。 */
    @Test
    void pinnedInspectionFeedsTheHotspotCache() {
        LoomGuard guard = LoomGuard.create(LoomGuardConfig.defaults(), FIXED);
        Object lock = new Object();
        guard.inspect(
                TaskSpec.blockingIo("sync-sleep"),
                () -> {
                    synchronized (lock) {
                        sleep(30);
                    }
                });
        assertThat(guard.hotspotCache().size()).isGreaterThanOrEqualTo(1);
        assertThat(guard.hotspotCache().hotspots()).isNotEmpty();
    }

    /** 不 pinning 的巡检不该污染热点视图。 */
    @Test
    void cleanInspectionLeavesTheHotspotCacheEmpty() {
        LoomGuard guard = LoomGuard.create(LoomGuardConfig.defaults(), FIXED);
        guard.inspect(TaskSpec.blockingIo("reentrant"), () -> withLock(() -> sleep(10)));
        assertThat(guard.hotspotCache().size()).isZero();
    }

    /** 同一个热点被反复命中只算一个条目，但命中次数要累加。 */
    @Test
    void repeatedPinsAggregateIntoASingleHotspot() {
        LoomGuard guard = LoomGuard.create(LoomGuardConfig.defaults(), FIXED);
        Object lock = new Object();
        for (int i = 0; i < 3; i++) {
            guard.inspect(
                    TaskSpec.blockingIo("sync-sleep"),
                    () -> {
                        synchronized (lock) {
                            sleep(20);
                        }
                    });
        }
        assertThat(guard.hotspotCache().size()).isGreaterThanOrEqualTo(1);
        assertThat(guard.hotspotCache().hotspots().get(0).hits()).isGreaterThanOrEqualTo(1L);
    }

    /** 热点聚合失败不能影响巡检结论 —— 它是观测路径的一部分。 */
    @Test
    void hotspotAggregationFailureDoesNotBreakInspection() {
        LoomGuard guard = LoomGuard.create(LoomGuardConfig.defaults(), FIXED);
        guard.hotspotCache().clear();
        LoomGuardReport report = guard.inspect(TaskSpec.plain("x"), () -> sleep(1));
        assertThat(report.worst()).isEqualTo(Severity.OK);
    }

    private static void withLock(Runnable task) {
        ReentrantLock lock = new ReentrantLock();
        lock.lock();
        try {
            task.run();
        } finally {
            lock.unlock();
        }
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
