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

import io.github.nephets.loomguard.core.LoomGuard;
import io.github.nephets.loomguard.core.LoomGuardConfig;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

import java.time.Clock;

/** loomguard 自动装配：注册 {@link LoomGuard} 门面、结果收集器与可选 Actuator 端点。 */
@AutoConfiguration
@ConditionalOnProperty(
        prefix = "loomguard",
        name = "enabled",
        havingValue = "true",
        matchIfMissing = true)
@EnableConfigurationProperties(LoomGuardProperties.class)
public class LoomGuardAutoConfiguration {

    private static final Logger log = LoggerFactory.getLogger(LoomGuardAutoConfiguration.class);

    /**
     * 供观测链路共用的时钟。
     *
     * <p><strong>为什么要把时钟做成 bean：</strong>巡检报告的 {@code observedAt} 与收集器的时间窗口判定
     * 必须基于<strong>同一个</strong>时钟，否则「报告的观测时刻」与「窗口的当前时刻」可能来自不同基准， 出现「报告明明是刚才的，却不在最近 5
     * 分钟之内」这类无法解释的现象。把它注册成 {@code @ConditionalOnMissingBean} 的 bean，生产环境用系统时钟，而使用方（或测试）只要自己声明一个
     * {@code Clock} bean 就能整体接管 —— 一处替换，全链路生效。
     */
    @Bean
    @ConditionalOnMissingBean
    public Clock loomGuardClock() {
        log.info("loomguard 自动装配：创建 Clock（系统时钟）");
        return Clock.systemUTC();
    }

    /** loomguard 聚合门面。 */
    @Bean
    @ConditionalOnMissingBean
    public LoomGuard loomGuard(LoomGuardProperties properties, Clock loomGuardClock) {
        log.info("loomguard 自动装配：创建 LoomGuard 门面");
        return LoomGuard.create(LoomGuardConfig.of(properties.isEnabled()), loomGuardClock);
    }

    /** 最近巡检结果收集器。 */
    @Bean
    @ConditionalOnMissingBean
    public LoomGuardCollector loomGuardCollector(Clock loomGuardClock) {
        log.info("loomguard 自动装配：创建 LoomGuardCollector");
        return new LoomGuardCollector(loomGuardClock);
    }

    /** Actuator 端点（默认开启，可经 loomguard.expose-endpoint=false 关闭）。 */
    @Bean
    @ConditionalOnProperty(
            prefix = "loomguard",
            name = "expose-endpoint",
            havingValue = "true",
            matchIfMissing = true)
    public LoomGuardEndpoint loomGuardEndpoint(
            LoomGuardProperties properties, LoomGuard loomGuard, LoomGuardCollector collector) {
        log.info("loomguard 自动装配：创建 LoomGuardEndpoint");
        return new LoomGuardEndpoint(properties, loomGuard, collector);
    }
}
