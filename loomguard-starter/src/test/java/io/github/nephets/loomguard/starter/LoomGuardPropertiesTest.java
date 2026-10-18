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

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;

import java.time.Clock;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 钉住配置项与自动装配的默认行为。
 *
 * <p>默认值之所以要单独测：starter 的价值就是「引进来就能用」， 一旦默认值漂移，使用方会在完全没改配置的情况下失去端点或门面。
 */
class LoomGuardPropertiesTest {

    @Test
    void defaultsAreEnabledAndExposed() {
        LoomGuardProperties properties = new LoomGuardProperties();
        assertThat(properties.isEnabled()).isTrue();
        assertThat(properties.isExposeEndpoint()).isTrue();
    }

    @Test
    void settersRoundTrip() {
        LoomGuardProperties properties = new LoomGuardProperties();
        properties.setEnabled(false);
        properties.setExposeEndpoint(false);
        assertThat(properties.isEnabled()).isFalse();
        assertThat(properties.isExposeEndpoint()).isFalse();
    }

    @Test
    void propertiesBindFromEnvironment() {
        StandardEnvironment environment = new StandardEnvironment();
        environment
                .getPropertySources()
                .addFirst(
                        new MapPropertySource(
                                "test",
                                Map.of(
                                        "loomguard.enabled", "true",
                                        "loomguard.expose-endpoint", "false")));
        LoomGuardProperties bound =
                Binder.get(environment).bind("loomguard", LoomGuardProperties.class).get();
        assertThat(bound.isEnabled()).isTrue();
        assertThat(bound.isExposeEndpoint()).isFalse();
    }

    @Test
    void propertiesDefaultWhenNothingIsConfigured() {
        StandardEnvironment environment = new StandardEnvironment();
        LoomGuardProperties bound =
                Binder.get(environment).bindOrCreate("loomguard", LoomGuardProperties.class);
        assertThat(bound.isEnabled()).isTrue();
        assertThat(bound.isExposeEndpoint()).isTrue();
    }

    /** 时钟 bean 必须存在，否则收集器与门面会各自取系统时钟、基准不一致。 */
    @Test
    void clockBeanIsRegisteredByDefault() {
        new ApplicationContextRunner()
                .withUserConfiguration(LoomGuardAutoConfiguration.class)
                .run(context -> assertThat(context).hasSingleBean(Clock.class));
    }

    /** 使用方自己声明 Clock bean 时必须能整体接管。 */
    @Test
    void userSuppliedClockWins() {
        Clock custom = Clock.fixed(java.time.Instant.EPOCH, java.time.ZoneOffset.UTC);
        new ApplicationContextRunner()
                .withUserConfiguration(LoomGuardAutoConfiguration.class)
                .withBean(Clock.class, () -> custom)
                .run(
                        context -> {
                            assertThat(context).hasSingleBean(Clock.class);
                            assertThat(context.getBean(Clock.class)).isSameAs(custom);
                        });
    }

    /** 门面与收集器必须共用同一个时钟实例。 */
    @Test
    void guardAndCollectorShareOneClock() {
        new ApplicationContextRunner()
                .withUserConfiguration(LoomGuardAutoConfiguration.class)
                .run(
                        context -> {
                            Clock clock = context.getBean(Clock.class);
                            assertThat(context.getBean(LoomGuardCollector.class).now())
                                    .isEqualTo(clock.instant());
                            assertThat(
                                            context.getBean(
                                                            io.github.nephets.loomguard.core
                                                                    .LoomGuard.class)
                                                    .clock())
                                    .isSameAs(clock);
                        });
    }

    @Test
    void propertiesBeanIsRegisteredEvenWhenEndpointIsHidden() {
        try (AnnotationConfigApplicationContext context =
                new AnnotationConfigApplicationContext()) {
            context.register(LoomGuardAutoConfiguration.class);
            context.refresh();
            assertThat(context.getBean(LoomGuardProperties.class)).isNotNull();
        }
    }
}
