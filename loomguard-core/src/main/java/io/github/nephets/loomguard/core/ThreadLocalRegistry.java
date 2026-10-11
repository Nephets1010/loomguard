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

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 进程内注册表：收集被 {@link TrackedThreadLocal} 记录的线程本地变量，用于估算其内存风险。
 *
 * <p>虚拟线程数量可轻松达到百万级，每个线程都在自己的 ThreadLocal 副本里持有对象， 因此「单个 ThreadLocal × 线程数」常常是隐藏的内存放大点。本注册表把每次
 * {@code set} 的字节估值累积起来，给出保守上界估算。
 */
public final class ThreadLocalRegistry {

    private static final ThreadLocalRegistry INSTANCE = new ThreadLocalRegistry();

    /** 每个线程本地变量名 -> 单次 set 估算的字节数（取最近一次）。 */
    private final Map<String, Long> bytesPerThread = new ConcurrentHashMap<>();

    /** 每个线程本地变量名 -> 累计 set 调用次数（充当「有多少线程触碰过它」的保守代理）。 */
    private final Map<String, Long> setCallCount = new ConcurrentHashMap<>();

    /** 进程唯一实例。 */
    public static ThreadLocalRegistry getInstance() {
        return INSTANCE;
    }

    /** 记录一次 set：更新单次字节估算，并累加 set 调用计数。 */
    void record(String name, long bytes) {
        bytesPerThread.put(name, bytes);
        setCallCount.merge(name, 1L, Long::sum);
    }

    /** 当前各线程本地变量的快照（按估算字节降序）。 */
    public List<ThreadLocalEntry> snapshot() {
        List<ThreadLocalEntry> entries = new ArrayList<>();
        for (Map.Entry<String, Long> e : bytesPerThread.entrySet()) {
            long bytes = e.getValue();
            long calls = setCallCount.getOrDefault(e.getKey(), 0L);
            entries.add(new ThreadLocalEntry(e.getKey(), bytes, calls, bytes * calls));
        }
        entries.sort((a, b) -> Long.compare(b.estimatedBytes(), a.estimatedBytes()));
        return List.copyOf(entries);
    }

    /** 全部线程本地变量按「保守上界」估算的总字节数。 */
    public long totalEstimatedBytes() {
        long total = 0;
        for (ThreadLocalEntry entry : snapshot()) {
            total += entry.estimatedBytes();
        }
        return total;
    }

    /** 见过的不同线程本地变量个数。 */
    public int distinctLocals() {
        return bytesPerThread.size();
    }

    /** 仅供测试：清空全部记录。 */
    public void reset() {
        bytesPerThread.clear();
        setCallCount.clear();
    }
}
