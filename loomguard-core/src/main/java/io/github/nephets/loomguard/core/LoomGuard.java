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

import java.time.Clock;
import java.util.List;
import java.util.Objects;

/** loomguard 聚合门面：把「探针观测 → 误用规则 → 线程本地审计」串成一次完整巡检，得到 {@link LoomGuardReport}。 */
public final class LoomGuard {

    private static final Logger log = LoggerFactory.getLogger(LoomGuard.class);

    private final RecommendationEngine engine;
    private final ThreadLocalRegistry registry;
    private final LoomGuardConfig config;
    private final Clock clock;
    private final PinningHotspotCache hotspotCache;
    private final LoomProbe probe;

    private LoomGuard(
            RecommendationEngine engine,
            ThreadLocalRegistry registry,
            LoomGuardConfig config,
            Clock clock) {
        this.engine = engine;
        this.registry = registry;
        this.config = config;
        this.clock = clock;
        this.hotspotCache = new PinningHotspotCache();
        // 探针实例的生命周期与门面一致（而不是每次巡检新建）：这样热点缓存才有意义 ——
        // 每次新建探针等于每次都从零开始，跨巡检的聚合就退化成单次快照了。
        this.probe = new LoomProbe(clock);
    }

    /** 使用默认配置与系统时钟创建。 */
    public static LoomGuard create() {
        return new LoomGuard(
                new RecommendationEngine(),
                ThreadLocalRegistry.getInstance(),
                LoomGuardConfig.defaults(),
                Clock.systemUTC());
    }

    /** 使用指定配置创建（时钟为系统时钟）。 */
    public static LoomGuard create(LoomGuardConfig config) {
        Objects.requireNonNull(config, "config must not be null");
        return new LoomGuard(
                new RecommendationEngine(),
                ThreadLocalRegistry.getInstance(),
                config,
                Clock.systemUTC());
    }

    /**
     * 使用指定配置与时钟创建。
     *
     * <p><strong>为什么时钟要可注入：</strong>巡检报告的 {@code observedAt} 决定了「这份报告有多旧」， 所有基于时间的下游判定（保留最近 N
     * 分钟的巡检、判断某次 pinning 是否发生在窗口内）都以它为基准。 若门面内部直接取系统时间，这些判定在测试里就只能用「任意值」去断言，时间窗口行为等于没测。
     */
    public static LoomGuard create(LoomGuardConfig config, Clock clock) {
        Objects.requireNonNull(config, "config must not be null");
        Objects.requireNonNull(clock, "clock must not be null");
        return new LoomGuard(
                new RecommendationEngine(), ThreadLocalRegistry.getInstance(), config, clock);
    }

    /**
     * 运行一次完整巡检。
     *
     * @param spec 任务元数据，不能为 null
     * @param task 被观测的任务，不能为 null
     * @throws NullPointerException 任一参数为 null 时立即抛出，避免把空指针推迟到虚拟线程内部变成更难定位的失败
     */
    public LoomGuardReport inspect(TaskSpec spec, Runnable task) {
        Objects.requireNonNull(spec, "spec must not be null");
        Objects.requireNonNull(task, "task must not be null");
        log.info(
                "loomguard 开始巡检：task={}, spec={}",
                task == null ? "null" : task.getClass().getSimpleName(),
                spec);
        TaskObservation observation = probe.observe(spec, task);
        List<Finding> findings = engine.recommend(observation);
        ThreadLocalReport threadLocal = new ThreadLocalAudit(registry).audit();
        // 热点聚合是「观测路径」的一部分：它服务于排障视图，绝不能因为聚合失败就让巡检失败。
        recordHotspotsQuietly(observation);
        LoomGuardReport report =
                LoomGuardReport.of(observation, findings, threadLocal, clock.instant());
        log.info(
                "loomguard 巡检完成：pinned={}, findings={}, threadLocalBytes={}",
                observation.pinning().anyPinned(),
                findings.size(),
                threadLocal.totalEstimatedBytes());
        return report;
    }

    private void recordHotspotsQuietly(TaskObservation observation) {
        try {
            hotspotCache.recordAll(observation.pinning());
        } catch (RuntimeException e) {
            // 降级点：热点缓存只服务于排障视图，它出任何问题都不该影响本次巡检结论。
            log.warn("pinning 热点聚合失败，本次巡检不纳入热点视图", e);
        }
    }

    /** 仅审计线程本地变量内存风险。 */
    public ThreadLocalReport auditThreadLocals() {
        log.debug("loomguard 审计线程本地变量内存风险");
        return new ThreadLocalAudit(registry).audit();
    }

    /** 误用规则引擎。 */
    public RecommendationEngine engine() {
        return engine;
    }

    /** 线程本地注册表。 */
    public ThreadLocalRegistry registry() {
        return registry;
    }

    /** 运行配置。 */
    public LoomGuardConfig config() {
        return config;
    }

    /** 本门面使用的时钟。 */
    public Clock clock() {
        return clock;
    }

    /** 跨巡检聚合的 pinning 热点缓存（有界 LRU，淘汰会被计数）。 */
    public PinningHotspotCache hotspotCache() {
        return hotspotCache;
    }
}
