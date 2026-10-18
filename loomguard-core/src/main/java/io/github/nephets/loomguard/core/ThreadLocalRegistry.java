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

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 进程内注册表：收集被 {@link TrackedThreadLocal} 记录的线程本地变量，用于估算其内存风险。
 *
 * <p>虚拟线程数量可轻松达到百万级，每个线程都在自己的 ThreadLocal 副本里持有对象， 因此「单个 ThreadLocal × 线程数」常常是隐藏的内存放大点。本注册表把每次
 * {@code set} 的字节估值累积起来，给出保守上界估算。
 */
public final class ThreadLocalRegistry {

    private static final Logger log = LoggerFactory.getLogger(ThreadLocalRegistry.class);

    /**
     * 允许登记的不同线程本地变量个数上限。
     *
     * <p>为什么需要上限：本注册表是进程级单例（{@link #getInstance()}），两张 map 与进程同生命周期。 若调用方用「动态名字」（例如把请求 id 拼进
     * ThreadLocal 名）注册，条目会只增不减，最终表现为一条缓慢的内存泄漏。名字稳定时（正常用法）永远不会触到这个上限。
     */
    public static final int MAX_TRACKED_LOCALS = 4096;

    private static final ThreadLocalRegistry INSTANCE = new ThreadLocalRegistry();

    /** 每个线程本地变量名 -> 单次 set 估算的字节数（取最近一次）。 */
    private final Map<String, Long> bytesPerThread = new ConcurrentHashMap<>();

    /** 每个线程本地变量名 -> 累计 set 调用次数（充当「有多少线程触碰过它」的保守代理）。 */
    private final Map<String, Long> setCallCount = new ConcurrentHashMap<>();

    /** 是否已经在容量上限处告警过。仅用于把「超限」日志收敛成一次， 避免动态命名的 ThreadLocal 在热路径上逐条刷日志。 */
    private volatile boolean capacityWarned;

    /**
     * 因达到上限而被拒绝登记的新条目个数。
     *
     * <p><strong>为什么拒绝要计数而不是只打一次日志：</strong>日志是给人看的、会滚动、会被日志级别过滤掉； 而「这个进程的 ThreadLocal
     * 名字正在失控」是一个<strong>可被机器判定的事实</strong> —— 把它做成一个 accessor， 使用方就能把它接到自己的健康检查或指标里，在泄漏刚露出苗头时就发现，
     * 而不是等某天收到一条「谁把日志翻出来看了一眼才发现的」告警。
     */
    private final java.util.concurrent.atomic.AtomicLong capacityRejections =
            new java.util.concurrent.atomic.AtomicLong();

    /** 进程唯一实例。 */
    public static ThreadLocalRegistry getInstance() {
        return INSTANCE;
    }

    /**
     * 记录一次 set：更新单次字节估算，并累加 set 调用计数。
     *
     * <p>已登记的条目永远可以更新；只有「新名字」在达到 {@link #MAX_TRACKED_LOCALS} 时会被拒绝， 以此保证 map 规模有上界。
     */
    void record(String name, long bytes) {
        Objects.requireNonNull(name, "name must not be null");
        Long previous = bytesPerThread.put(name, bytes);
        if (previous == null) {
            if (bytesPerThread.size() > MAX_TRACKED_LOCALS) {
                // 超限：撤销本次登记，宁可少统计一项，也不能让进程级 map 无界增长
                bytesPerThread.remove(name);
                setCallCount.remove(name);
                capacityRejections.incrementAndGet();
                warnOnceAtCapacity(name);
                return;
            }
            log.debug(
                    "登记新的线程本地变量：name={}, bytes={}, distinctLocals={}",
                    name,
                    bytes,
                    bytesPerThread.size());
        }
        setCallCount.merge(name, 1L, Long::sum);
    }

    private void warnOnceAtCapacity(String name) {
        if (capacityWarned) {
            return;
        }
        capacityWarned = true;
        log.warn(
                "线程本地变量登记数已达上限 {}，忽略新登记项 {}；通常是 ThreadLocal 名字被动态拼接导致，请改用固定名字",
                MAX_TRACKED_LOCALS,
                name);
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
        log.debug("生成线程本地快照：distinctLocals={}", entries.size());
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

    /**
     * 因达到 {@link #MAX_TRACKED_LOCALS} 而被拒绝登记的新条目个数。
     *
     * <p>大于 0 即表示「审计视图已不完整」：有 ThreadLocal 在用动态名字，本次审计没有把它们算进去。
     */
    public long capacityRejections() {
        return capacityRejections.get();
    }

    /** 仅供测试：清空全部记录。 */
    public void reset() {
        bytesPerThread.clear();
        setCallCount.clear();
        capacityWarned = false;
        log.debug("线程本地注册表已清空");
    }
}
