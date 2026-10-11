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

import static org.assertj.core.api.Assertions.assertThat;

/** 验证 TrackedThreadLocal 在 set 时真的把估值登记进注册表。 */
class TrackedThreadLocalTest {

    private final ThreadLocalRegistry registry = new ThreadLocalRegistry();

    @AfterEach
    void tearDown() {
        registry.reset();
    }

    @Test
    void setNameIsExposed() {
        TrackedThreadLocal<String> local = new TrackedThreadLocal<>("session", s -> 4L, registry);
        assertThat(local.getName()).isEqualTo("session");
    }

    @Test
    void setRegistersEstimate() {
        TrackedThreadLocal<String> local =
                new TrackedThreadLocal<>("ctx", s -> s == null ? 0 : (long) s.length(), registry);
        local.set("abcdef"); // 6 字节
        assertThat(registry.snapshot())
                .anyMatch(e -> e.name().equals("ctx") && e.bytesPerThread() == 6);
    }

    @Test
    void stillBehavesAsThreadLocal() {
        TrackedThreadLocal<String> local = new TrackedThreadLocal<>("value", s -> 1L, registry);
        local.set("hello");
        assertThat(local.get()).isEqualTo("hello");
    }

    @Test
    void sizeEstimatorReceivesValue() {
        TrackedThreadLocal<Integer> local =
                new TrackedThreadLocal<>("num", x -> x == null ? 0 : x * 8L, registry);
        local.set(5);
        assertThat(registry.snapshot().get(0).bytesPerThread()).isEqualTo(40);
    }
}
