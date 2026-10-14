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

/** 一次任务的观测结果：是否 pinning、墙钟耗时、任务元数据、是否成功。 */
public record TaskObservation(
        PinningReport pinning,
        long wallTimeNanos,
        TaskSpec spec,
        boolean succeeded,
        String failureMessage) {

    /** 墙钟耗时（毫秒）。 */
    public long wallTimeMillis() {
        return java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(wallTimeNanos);
    }
}
