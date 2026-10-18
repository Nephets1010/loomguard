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

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

/** 验证自动装配在不同配置下的 bean 注册行为。 */
class LoomGuardAutoConfigurationTest {

    private final ApplicationContextRunner runner =
            new ApplicationContextRunner().withUserConfiguration(LoomGuardAutoConfiguration.class);

    @Test
    void enabledByDefaultRegistersAllBeans() {
        runner.run(
                context -> {
                    assertThat(context).hasSingleBean(LoomGuard.class);
                    assertThat(context).hasSingleBean(LoomGuardCollector.class);
                    assertThat(context).hasSingleBean(LoomGuardEndpoint.class);
                });
    }

    @Test
    void explicitEnabledRegistersAllBeans() {
        runner.withPropertyValues("loomguard.enabled=true")
                .run(
                        context -> {
                            assertThat(context).hasSingleBean(LoomGuard.class);
                            assertThat(context).hasSingleBean(LoomGuardEndpoint.class);
                        });
    }

    @Test
    void exposeEndpointFalseHidesEndpointButKeepsGuard() {
        runner.withPropertyValues("loomguard.expose-endpoint=false")
                .run(
                        context -> {
                            assertThat(context).hasSingleBean(LoomGuard.class);
                            assertThat(context).doesNotHaveBean(LoomGuardEndpoint.class);
                        });
    }

    @Test
    void disabledRegistersNothing() {
        runner.withPropertyValues("loomguard.enabled=false")
                .run(
                        context -> {
                            assertThat(context).doesNotHaveBean(LoomGuard.class);
                            assertThat(context).doesNotHaveBean(LoomGuardCollector.class);
                            assertThat(context).doesNotHaveBean(LoomGuardEndpoint.class);
                        });
    }

    @Test
    void propertiesAreBound() {
        runner.withPropertyValues("loomguard.enabled=true", "loomguard.expose-endpoint=false")
                .run(
                        context -> {
                            assertThat(context).hasSingleBean(LoomGuardProperties.class);
                            assertThat(
                                            context.getBean(LoomGuardProperties.class)
                                                    .isExposeEndpoint())
                                    .isFalse();
                        });
    }
}
