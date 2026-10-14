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

import io.github.nephets.loomguard.core.LoomGuardReport;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Objects;

/** 有界地保留最近若干次巡检结果，供 Actuator 端点展示。 */
public class LoomGuardCollector {

    private static final Logger log = LoggerFactory.getLogger(LoomGuardCollector.class);

    private final int capacity;
    private final Deque<LoomGuardReport> recent = new ArrayDeque<>();

    /** 默认保留最近 20 次。 */
    public LoomGuardCollector() {
        this(20);
    }

    /** 指定保留容量。 */
    public LoomGuardCollector(int capacity) {
        this.capacity = Math.max(1, capacity);
        log.debug("初始化 LoomGuardCollector：capacity={}", this.capacity);
    }

    /** 记录一次巡检结果。 */
    public synchronized void record(LoomGuardReport report) {
        Objects.requireNonNull(report, "report must not be null");
        recent.addLast(report);
        while (recent.size() > capacity) {
            recent.removeFirst();
        }
        log.debug("记录一次巡检结果：当前保留 {}/{} 条", recent.size(), capacity);
    }

    /** 最近的结果（从早到晚）。 */
    public synchronized List<LoomGuardReport> recent() {
        return List.copyOf(recent);
    }

    /** 最近一次结果（无则 null）。 */
    public synchronized LoomGuardReport latest() {
        return recent.peekLast();
    }
}
