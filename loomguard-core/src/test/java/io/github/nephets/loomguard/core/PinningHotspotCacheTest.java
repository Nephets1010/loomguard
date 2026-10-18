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

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 钉住热点缓存的「有界 LRU + 淘汰可观测」契约。 */
class PinningHotspotCacheTest {

    private static PinningEvent event(String frame) {
        return new PinningEvent(List.of(frame), 0L, 0, Instant.now());
    }

    @Test
    void emptyInitially() {
        PinningHotspotCache cache = new PinningHotspotCache();
        assertThat(cache.size()).isZero();
        assertThat(cache.hotspots()).isEmpty();
        assertThat(cache.evictedCount()).isZero();
    }

    @Test
    void repeatedHitsAreAggregatedIntoOneHotspot() {
        PinningHotspotCache cache = new PinningHotspotCache();
        cache.record(event("com.example.Foo#bar"));
        cache.record(event("com.example.Foo#bar"));
        cache.record(event("com.example.Foo#bar"));
        assertThat(cache.size()).isEqualTo(1);
        assertThat(cache.hotspots())
                .containsExactly(new PinningHotspotCache.Hotspot("com.example.Foo#bar", 3L));
    }

    @Test
    void distinctFramesBecomeDistinctHotspots() {
        PinningHotspotCache cache = new PinningHotspotCache();
        cache.record(event("a"));
        cache.record(event("b"));
        assertThat(cache.size()).isEqualTo(2);
    }

    /** LRU 的顺序语义：最近被命中的排在最前。 */
    @Test
    void mostRecentlyHitHotspotComesFirst() {
        PinningHotspotCache cache = new PinningHotspotCache();
        cache.record(event("old"));
        cache.record(event("new"));
        assertThat(cache.hotspots()).first().extracting("frame").isEqualTo("new");
        cache.record(event("old"));
        assertThat(cache.hotspots()).first().extracting("frame").isEqualTo("old");
    }

    @Test
    void sizeNeverExceedsCapacity() {
        PinningHotspotCache cache = new PinningHotspotCache(3);
        for (int i = 0; i < 50; i++) {
            cache.record(event("frame-" + i));
        }
        assertThat(cache.size()).isEqualTo(3);
        assertThat(cache.capacity()).isEqualTo(3);
    }

    /** 淘汰必须可观测：否则使用者会把一份被裁剪过的视图当成完整事实。 */
    @Test
    void evictionIsCounted() {
        PinningHotspotCache cache = new PinningHotspotCache(2);
        cache.record(event("a"));
        cache.record(event("b"));
        assertThat(cache.evictedCount()).isZero();
        cache.record(event("c"));
        assertThat(cache.evictedCount()).isEqualTo(1);
        cache.record(event("d"));
        assertThat(cache.evictedCount()).isEqualTo(2);
    }

    /** LRU 淘汰的是「最久没再出现」的那个，而不是最早进来的那个。 */
    @Test
    void evictionDropsTheLeastRecentlyUsedRatherThanTheOldest() {
        PinningHotspotCache cache = new PinningHotspotCache(2);
        cache.record(event("a"));
        cache.record(event("b"));
        cache.record(event("a")); // a 被重新访问，b 成为最久未命中
        cache.record(event("c")); // 应当淘汰 b
        assertThat(cache.hotspots()).extracting("frame").containsExactly("c", "a");
        assertThat(cache.hotspots()).noneMatch(h -> h.frame().equals("b"));
    }

    /** 已存在条目重复命中不算写入新条目，因此不该触发淘汰。 */
    @Test
    void rehittingAnExistingHotspotDoesNotTriggerEviction() {
        PinningHotspotCache cache = new PinningHotspotCache(2);
        cache.record(event("a"));
        cache.record(event("b"));
        for (int i = 0; i < 10; i++) {
            cache.record(event("a"));
        }
        assertThat(cache.evictedCount()).isZero();
        assertThat(cache.size()).isEqualTo(2);
    }

    /** 清空热点不复位淘汰计数：淘汰发生过就是发生过，不该被一次清空抹掉。 */
    @Test
    void clearKeepsTheEvictionTally() {
        PinningHotspotCache cache = new PinningHotspotCache(1);
        cache.record(event("a"));
        cache.record(event("b"));
        assertThat(cache.evictedCount()).isEqualTo(1);
        cache.clear();
        assertThat(cache.size()).isZero();
        assertThat(cache.evictedCount()).isEqualTo(1);
    }

    @Test
    void recordAllAggregatesAWholeReport() {
        PinningHotspotCache cache = new PinningHotspotCache();
        cache.recordAll(PinningReport.of(List.of(event("a"), event("b"), event("a"))));
        assertThat(cache.size()).isEqualTo(2);
        assertThat(cache.hotspots()).filteredOn(h -> h.frame().equals("a")).hasSize(1);
        assertThat(cache.hotspots().get(0).hits()).isGreaterThanOrEqualTo(1L);
    }

    @Test
    void recordAllAcceptsAnEmptyReport() {
        PinningHotspotCache cache = new PinningHotspotCache();
        cache.recordAll(PinningReport.empty());
        assertThat(cache.size()).isZero();
    }

    @Test
    void zeroCapacityIsRejectedAtConstruction() {
        assertThatThrownBy(() -> new PinningHotspotCache(0)).isInstanceOf(LoomGuardException.class);
        assertThatThrownBy(() -> new PinningHotspotCache(-1))
                .isInstanceOf(LoomGuardException.class);
    }

    @Test
    void nullsAreRejectedAtTheBoundary() {
        PinningHotspotCache cache = new PinningHotspotCache();
        assertThatThrownBy(() -> cache.record(null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> cache.recordAll(null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void defaultCapacityIsUsedWhenNotSpecified() {
        assertThat(new PinningHotspotCache().capacity())
                .isEqualTo(PinningHotspotCache.DEFAULT_CAPACITY);
    }
}
