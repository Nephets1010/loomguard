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

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

/** loomguard 自动装配：注册 {@link LoomGuard} 门面、结果收集器与可选 Actuator 端点。 */
@AutoConfiguration
@ConditionalOnProperty(
        prefix = "loomguard",
        name = "enabled",
        havingValue = "true",
        matchIfMissing = true)
@EnableConfigurationProperties(LoomGuardProperties.class)
public class LoomGuardAutoConfiguration {

    /** loomguard 聚合门面。 */
    @Bean
    @ConditionalOnMissingBean
    public LoomGuard loomGuard() {
        return LoomGuard.create();
    }

    /** 最近巡检结果收集器。 */
    @Bean
    @ConditionalOnMissingBean
    public LoomGuardCollector loomGuardCollector() {
        return new LoomGuardCollector();
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
        return new LoomGuardEndpoint(properties, loomGuard, collector);
    }
}
