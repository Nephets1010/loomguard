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
package io.github.nephets.loomguard.mockjfr;

import jdk.jfr.Enabled;
import jdk.jfr.Event;
import jdk.jfr.Label;
import jdk.jfr.Name;
import jdk.jfr.StackTrace;

/**
 * 合成的「虚拟线程被钉住」事件：形状刻意对齐 {@code jdk.VirtualThreadPinned}（一个瞬时事件 + 发生栈）， 用于在受控条件下造出内容确定的 JFR 录制。
 *
 * <p><strong>为什么不用 {@code jdk.VirtualThreadPinned} 这个名字：</strong>{@code jdk.*} 是 JDK 保留的事件命名空间，
 * 第三方在这里注册事件既可能被 JVM 拒绝，也会污染使用者的事件元数据。合成事件用自己的 {@code loomguard.*} 前缀，既能被 {@code RecordingFile}
 * 正常解析，又与真实事件互不干扰。
 *
 * <p><strong>为什么 {@code @Enabled(false)}：</strong>默认关闭，只有显式 {@code Recording#enable(...)} 才会录制。
 * 否则任何引入本模块的进程都会被无条件地多录一份事件，而本模块的使用场景（测试、样本生成） 恰恰不希望影响宿主进程的 JFR 配置。
 *
 * <p><strong>为什么这个类<strong>不</strong>声明 {@code duration} 字段：</strong>{@code duration} 是 JFR 的保留字段名
 * —— 它由 {@link Event#begin()} / {@link Event#end()} 这对方法隐式使用。实测（JDK 21）表明： 一旦事件类里声明了名为 {@code
 * duration} 的字段（无论是否加 {@code @Timespan}）， 该事件就再也录不进任何录制（{@code readAllEvents} 恒返回 0 条），且不报任何错。
 * 想要「带时长」的合成事件，正确做法是<strong>不声明字段</strong>，改用 {@code begin()/end()} 让 JVM 自己附上时长 （见 {@link
 * SyntheticPinningRecording#writeTimedPinned(java.nio.file.Path, int, int)}）。
 */
@Name(SyntheticPinnedEvent.EVENT_NAME)
@Label("Synthetic Virtual Thread Pinned")
@StackTrace
@Enabled(false)
public class SyntheticPinnedEvent extends Event {

    /** 合成事件名：与真实事件同形但不同名，避免占用 jdk.* 命名空间。 */
    public static final String EVENT_NAME = "loomguard.SyntheticVirtualThreadPinned";
}
