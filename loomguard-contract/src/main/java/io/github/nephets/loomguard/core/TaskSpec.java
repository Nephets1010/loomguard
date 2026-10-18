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

/** 描述一个任务「怎么跑」的元数据，供误用规则引擎判断是否为虚拟线程的不当用法。 */
public record TaskSpec(boolean blockingIo, boolean cpuBound, String name) {

    /** 任意任务。 */
    public static TaskSpec of(boolean blockingIo, boolean cpuBound, String name) {
        return new TaskSpec(blockingIo, cpuBound, name);
    }

    /** 标注「在虚拟线程上做阻塞 I/O」。 */
    public static TaskSpec blockingIo(String name) {
        return new TaskSpec(true, false, name);
    }

    /** 标注「CPU 密集任务」。 */
    public static TaskSpec cpuBound(String name) {
        return new TaskSpec(false, true, name);
    }

    /** 普通任务（无阻塞 I/O、无 CPU 密集）。 */
    public static TaskSpec plain(String name) {
        return new TaskSpec(false, false, name);
    }
}
