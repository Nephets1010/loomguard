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
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * pinning 热点缓存：跨多次巡检聚合「哪些栈位置反复把虚拟线程钉住」，并把这个集合严格限制在有界范围内。
 *
 * <h2>为什么需要它</h2>
 *
 * <p>单次 {@link PinningReport} 只能回答「这一次任务有没有被钉住」。但在真实服务里，同一个 {@code synchronized}
 * 热点会被成千上万次请求反复命中，每次巡检都产出一份「同样内容的 CRITICAL」。调用方要看的不是第 N 次重复， 而是「到底有哪几个地方在被钉住」——
 * 这是一个<strong>跨时间的去重视图</strong>，正是本类提供的东西。
 *
 * <h2>为什么必须是有界的 LRU，而不是一个普通 {@code HashMap}</h2>
 *
 * <p>本类的实例与宿主进程同生命周期（由 {@link LoomGuard} 持有，而 {@code LoomGuard} 在 Spring Boot 下是单例 bean）。
 * 热点的键是发生栈里的业务帧 —— 只要调用方的栈里出现了<strong>带标识的部分</strong>（动态代理类名、lambda 序号、 反射生成的类名），键就会不断产生新值。一个无界的
 * map 在这里不是「理论上可能泄漏」，而是「用久了必然泄漏」， 而且泄漏速度与使用量成正比，正好在系统最忙的时候爆掉。
 *
 * <p>用 {@code LinkedHashMap(accessOrder=true)} 而不是「满了就拒绝新条目」，是因为两者的失败形态完全不同：
 * 固定槽位在满了之后<strong>拒绝新写入</strong>，于是真正的新热点永远进不来，缓存会稳定地停留在 「开机头几分钟看到的那一批热点」上，变成一个说谎的视图；LRU
 * 则是<strong>挤掉最久没再出现的热点</strong>， 保留的始终是「最近还在犯」的那些 —— 对「现在哪里在被钉住」这个问题，后者才是正确答案。
 *
 * <h2>为什么淘汰必须可观测</h2>
 *
 * <p>淘汰意味着<strong>丢数据</strong>。静默淘汰的后果是：使用者看到的列表完整、自洽、且是错的 —— 他以为「只有这 3 个热点」，实际系统里有 500
 * 个，只是其余的被悄悄扔了。这里每次淘汰都打 WARN 并累加 {@link #evictedCount()}，让「这份视图已经被裁剪过」成为调用方能查到的事实，而不是一个隐藏的降级。
 */
public final class PinningHotspotCache {

    private static final Logger log = LoggerFactory.getLogger(PinningHotspotCache.class);

    /** 默认容量：512 个不同热点。正常系统里真正的 pinning 热点是个位数，这里留出两个数量级的余量。 */
    public static final int DEFAULT_CAPACITY = 512;

    private final int capacity;
    private final Map<String, Long> hotspots;
    private long evictedCount;

    /** 使用默认容量。 */
    public PinningHotspotCache() {
        this(DEFAULT_CAPACITY);
    }

    /**
     * 指定容量。
     *
     * @param capacity 最多保留多少个不同热点，必须 ≥ 1
     * @throws LoomGuardException 容量小于 1 时抛出：0 容量的缓存是一份永远为空的视图，属于构造错误， 而不是一个可以被静默容忍的入参
     */
    public PinningHotspotCache(int capacity) {
        if (capacity < 1) {
            throw new LoomGuardException("capacity must be at least 1: " + capacity);
        }
        this.capacity = capacity;
        this.hotspots =
                new LinkedHashMap<String, Long>(16, 0.75f, true) {
                    @Override
                    protected boolean removeEldestEntry(Map.Entry<String, Long> eldest) {
                        if (size() <= capacity) {
                            return false;
                        }
                        evictedCount++;
                        log.warn(
                                "pinning 热点缓存已达容量 {}，淘汰最久未命中的热点：{}（累计淘汰 {} 个，当前视图已不完整）",
                                capacity,
                                eldest.getKey(),
                                evictedCount);
                        return true;
                    }
                };
    }

    /** 记录一次 pinning 事件对应的热点。 */
    public synchronized void record(PinningEvent event) {
        Objects.requireNonNull(event, "event must not be null");
        String key = event.hotFrame();
        Long seen = hotspots.get(key);
        hotspots.put(key, seen == null ? 1L : seen + 1L);
    }

    /** 记录一整份报告里的全部 pinning 事件。 */
    public synchronized void recordAll(PinningReport report) {
        Objects.requireNonNull(report, "report must not be null");
        for (PinningEvent event : report.events()) {
            record(event);
        }
    }

    /**
     * 当前热点列表：最近被命中的排在最前。
     *
     * <p>{@code LinkedHashMap(accessOrder=true)} 的迭代顺序是「最久未访问者在最前」，与使用方想看的 「最热在最前」正好相反，所以这里显式反转一次
     * —— 顺序排反了的排行榜比没有排行榜更糟。
     */
    public synchronized List<Hotspot> hotspots() {
        List<Hotspot> result = new ArrayList<>(hotspots.size());
        for (Map.Entry<String, Long> entry : hotspots.entrySet()) {
            result.add(new Hotspot(entry.getKey(), entry.getValue()));
        }
        Collections.reverse(result);
        return List.copyOf(result);
    }

    /** 当前保留的热点个数。 */
    public synchronized int size() {
        return hotspots.size();
    }

    /** 容量上限。 */
    public int capacity() {
        return capacity;
    }

    /**
     * 已被淘汰的热点个数。
     *
     * <p>这个数大于 0 就意味着当前视图<strong>不完整</strong>：真实热点数曾经超过容量，最旧的那些已被丢弃。
     */
    public synchronized long evictedCount() {
        return evictedCount;
    }

    /** 清空全部热点（不复位淘汰计数：淘汰发生过就是发生过，不该被一次清空抹掉）。 */
    public synchronized void clear() {
        hotspots.clear();
        log.debug("pinning 热点缓存已清空");
    }

    /** 一个热点：栈位置 + 被命中的次数。 */
    public record Hotspot(String frame, long hits) {}
}
