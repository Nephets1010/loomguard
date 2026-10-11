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

import java.util.List;

/** loomguard 聚合门面：把「探针观测 → 误用规则 → 线程本地审计」串成一次完整巡检，得到 {@link LoomGuardReport}。 */
public final class LoomGuard {

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
        return new LoomGuard(new RecommendationEngine(), ThreadLocalRegistry.getInstance(), config);
    }

    /** 运行一次完整巡检。 */
    public LoomGuardReport inspect(TaskSpec spec, Runnable task) {
        TaskObservation observation = new LoomProbe().observe(spec, task);
        List<Finding> findings = engine.recommend(observation);
        ThreadLocalReport threadLocal = new ThreadLocalAudit(registry).audit();
        return LoomGuardReport.of(observation, findings, threadLocal);
    }

    /** 仅审计线程本地变量内存风险。 */
    public ThreadLocalReport auditThreadLocals() {
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
