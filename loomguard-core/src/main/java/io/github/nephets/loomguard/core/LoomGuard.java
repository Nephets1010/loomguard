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

import java.util.List;
import java.util.Objects;

/** loomguard 聚合门面：把「探针观测 → 误用规则 → 线程本地审计」串成一次完整巡检，得到 {@link LoomGuardReport}。 */
public final class LoomGuard {

    private static final Logger log = LoggerFactory.getLogger(LoomGuard.class);

    private final RecommendationEngine engine;
    private final ThreadLocalRegistry registry;
    private final LoomGuardConfig config;

    private LoomGuard(
            RecommendationEngine engine, ThreadLocalRegistry registry, LoomGuardConfig config) {
        this.engine = engine;
        this.registry = registry;
        this.config = config;
    }

    /** 使用默认配置创建。 */
    public static LoomGuard create() {
        return new LoomGuard(
                new RecommendationEngine(),
                ThreadLocalRegistry.getInstance(),
                LoomGuardConfig.defaults());
    }

    /** 使用指定配置创建。 */
    public static LoomGuard create(LoomGuardConfig config) {
        Objects.requireNonNull(config, "config must not be null");
        return new LoomGuard(new RecommendationEngine(), ThreadLocalRegistry.getInstance(), config);
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
        TaskObservation observation = new LoomProbe().observe(spec, task);
        List<Finding> findings = engine.recommend(observation);
        ThreadLocalReport threadLocal = new ThreadLocalAudit(registry).audit();
        LoomGuardReport report = LoomGuardReport.of(observation, findings, threadLocal);
        log.info(
                "loomguard 巡检完成：pinned={}, findings={}, threadLocalBytes={}",
                observation.pinning().anyPinned(),
                findings.size(),
                threadLocal.totalEstimatedBytes());
        return report;
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
}
