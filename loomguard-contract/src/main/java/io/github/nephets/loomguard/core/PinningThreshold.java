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

import java.util.Objects;

/**
 * pinning 的判定阈值（不可变值对象）：回答「探测到多少次 / 多长的 pinning 才值得判定为严重」。
 *
 * <h2>为什么阈值要单独成为类型，而不是散落在规则引擎里的两个 {@code int} 常量</h2>
 *
 * <p>阈值是<strong>判定口径</strong>的一部分，而口径必须能被整体传递、整体比较、整体序列化。散成两个裸参数后，「默认口径是什么」
 * 只能靠读代码的人去拼；而一旦调用方想给某个租户单独放宽口径，就又得再加一个重载。把它做成一个不可变值对象， 既让 {@code PinningThreshold.DEFAULT}
 * 成为一个可被引用、可被 equals 比较的命名常量，也让未来把它挂到配置属性上时不需要改任何方法签名。
 *
 * <h2>为什么默认值是「次数 ≥ 1、时长 ≥ 0」</h2>
 *
 * <p>真实的 {@code jdk.VirtualThreadPinned} 事件<strong>不带 duration 字段</strong>（JFR 只在虚拟线程被钉住的一刻打点，
 * 不记录它被钉了多久），所以 {@link PinningEvent#durationNanos()} 对真实事件恒为 0。如果在这里给一个正的时长阈值， 全部真实事件都会因为 {@code 0
 * >= threshold} 不成立而被判为「不值得关注」—— 那是一次静默的、全域的漏报， 比误报危险得多：误报会有人来查，漏报不会。因此默认时长阈值取 0（即「不启用时长这一维」），
 * 只由次数这一维来判定。
 *
 * <p>次数阈值取 1 同理：只要录制窗口里出现过一次 pinning，就说明那段代码路径确实会把载体线程钉住， 这与「钉了多久」无关，是<strong>结构性缺陷</strong>而非程度问题。
 */
public final class PinningThreshold {

    /** 默认判定口径：出现 1 次即判定为严重，不对时长设限。 */
    public static final PinningThreshold DEFAULT = new PinningThreshold(1, 0L);

    private final int minPinnedCount;
    private final long minDurationNanos;

    private PinningThreshold(int minPinnedCount, long minDurationNanos) {
        this.minPinnedCount = minPinnedCount;
        this.minDurationNanos = minDurationNanos;
    }

    /**
     * 构造一个判定阈值。
     *
     * @param minPinnedCount 判定为严重所需的最小 pinning 次数，必须是非负数
     * @param minDurationNanos 判定为严重所需的最小持续时长（纳秒），必须是非负数
     * @throws LoomGuardException 任一参数为负时抛出
     */
    public static PinningThreshold of(int minPinnedCount, long minDurationNanos) {
        if (minPinnedCount < 0) {
            throw new LoomGuardException("minPinnedCount must not be negative: " + minPinnedCount);
        }
        if (minDurationNanos < 0) {
            throw new LoomGuardException(
                    "minDurationNanos must not be negative: " + minDurationNanos);
        }
        return new PinningThreshold(minPinnedCount, minDurationNanos);
    }

    /** 判定为严重所需的最小 pinning 次数。 */
    public int minPinnedCount() {
        return minPinnedCount;
    }

    /** 判定为严重所需的最小持续时长（纳秒）。 */
    public long minDurationNanos() {
        return minDurationNanos;
    }

    /** 该阈值是否把「什么都没发生」也算作命中（次数阈值为 0 时恒真）。 */
    public boolean matchesNothing() {
        return minPinnedCount == 0;
    }

    /**
     * 一次 pinning 是否达到「值得判定为严重」的口径。
     *
     * <p>两个维度是<strong>与</strong>关系：次数与时长都达标才算命中。之所以不是「或」，是因为次数少但时长长
     * （例如一次天文数字级别的长钉）虽然也值得看，但它的归因方式与「反复被钉」完全不同，混进同一个判定里会让 后续的建议文本自相矛盾；这里保持口径单一，宁可让长钉先落到 {@link
     * Severity#WARN}。
     */
    public boolean isCritical(int pinnedCount, long durationNanos) {
        return pinnedCount >= minPinnedCount && durationNanos >= minDurationNanos;
    }

    /**
     * 给出一次探测在<strong>本阈值</strong>下的判定结果。
     *
     * <p>三态而非二态：{@code 0 次} 是「没发生」，{@code 有但未达阈值} 是「发生了但还够不上严重」，
     * 这两者在使用方面前的处置方式完全不同（前者无需动作，后者值得继续观察），压成二态会丢掉这个区分。
     */
    public Severity severityFor(int pinnedCount, long durationNanos) {
        if (pinnedCount <= 0) {
            return Severity.OK;
        }
        return isCritical(pinnedCount, durationNanos) ? Severity.CRITICAL : Severity.WARN;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof PinningThreshold that)) {
            return false;
        }
        return minPinnedCount == that.minPinnedCount && minDurationNanos == that.minDurationNanos;
    }

    @Override
    public int hashCode() {
        return Objects.hash(minPinnedCount, minDurationNanos);
    }

    @Override
    public String toString() {
        return "PinningThreshold[minPinnedCount="
                + minPinnedCount
                + ", minDurationNanos="
                + minDurationNanos
                + "]";
    }
}
