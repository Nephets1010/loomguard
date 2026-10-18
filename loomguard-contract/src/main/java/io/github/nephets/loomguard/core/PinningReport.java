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

import java.util.List;

/** 一次探测得到的全部 pinning 事件集合。 */
public record PinningReport(List<PinningEvent> events) {

    /** 构造一个报告。 */
    public static PinningReport of(List<PinningEvent> events) {
        return new PinningReport(List.copyOf(events));
    }

    /** 空报告（未发生 pinning）。 */
    public static PinningReport empty() {
        return new PinningReport(List.of());
    }

    /** pinning 次数。 */
    public int pinnedCount() {
        return events.size();
    }

    /** 是否发生了 pinning。 */
    public boolean anyPinned() {
        return !events.isEmpty();
    }

    /** 所有发生栈（hot stack）按出现顺序。 */
    public List<List<String>> hotStacks() {
        return events.stream().map(PinningEvent::stackTrace).toList();
    }
}
