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

import java.time.Instant;
import java.util.List;

/** 一次 pinning 事件的发生记录：发生栈（hot stack）与探测时刻。 */
public record PinningEvent(
        List<String> stackTrace, long durationNanos, int occurrenceIndex, Instant capturedAt) {

    /** 发生栈中最靠近业务代码的栈帧（去掉 JDK 内部帧后的第一个可读帧）。 */
    public String hotFrame() {
        for (String frame : stackTrace) {
            if (frame.startsWith("io.github.nephets")) {
                return frame;
            }
        }
        return stackTrace.isEmpty() ? "<unknown>" : stackTrace.get(0);
    }
}
