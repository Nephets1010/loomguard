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

import java.util.Objects;
import java.util.function.ToLongFunction;

/**
 * 可审计的 {@link ThreadLocal}：每次 {@code set} 都会把本次写入对象的字节估值登记进 {@link ThreadLocalRegistry}，从而让
 * loomguard 估算「该 ThreadLocal × 虚拟线程数」的内存放大。
 *
 * <p>用法与 {@code ThreadLocal} 完全一致，只是构造时额外传入一个大小估算器：
 *
 * <pre>{@code
 * TrackedThreadLocal<String> user =
 *     new TrackedThreadLocal<>("currentUser", s -> s == null ? 0 : s.length() * 2L, registry);
 * }</pre>
 */
public class TrackedThreadLocal<T> extends ThreadLocal<T> {

    private final String name;
    private final ToLongFunction<? super T> sizeEstimator;
    private final ThreadLocalRegistry registry;

    /** 使用指定注册表。 */
    public TrackedThreadLocal(
            String name, ToLongFunction<? super T> sizeEstimator, ThreadLocalRegistry registry) {
        // 三个入参缺一不可：name 决定注册表条目，estimator 决定字节估算，registry 决定登记到哪里。
        // 在构造期就拒绝 null，避免 set() 时才炸、且现场已脱离调用方代码。
        this.name = Objects.requireNonNull(name, "name must not be null");
        this.sizeEstimator =
                Objects.requireNonNull(sizeEstimator, "sizeEstimator must not be null");
        this.registry = Objects.requireNonNull(registry, "registry must not be null");
    }

    /** 使用进程唯一注册表（{@link ThreadLocalRegistry#getInstance()}）。 */
    public TrackedThreadLocal(String name, ToLongFunction<? super T> sizeEstimator) {
        this(name, sizeEstimator, ThreadLocalRegistry.getInstance());
    }

    @Override
    public void set(T value) {
        super.set(value);
        long bytes = (value == null) ? 0L : sizeEstimator.applyAsLong(value);
        registry.record(name, bytes);
    }

    /** 该线程本地变量的注册名。 */
    public String getName() {
        return name;
    }
}
