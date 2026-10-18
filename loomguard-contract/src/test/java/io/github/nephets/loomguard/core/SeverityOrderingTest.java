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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 钉住严重级别的<strong>顺序</strong>契约。
 *
 * <p>{@code max} 依赖 {@code ordinal()}，而「OK 最轻、CRITICAL 最重」这条顺序是一份<strong>被外部依赖的契约</strong>： 端点会把
 * {@code severity.name()} 直接序列化出去，监控规则会按名字比较。顺序一变， 所有下游判断都会静默反转，所以这里把它钉死。
 */
class SeverityOrderingTest {

    @Test
    void declaredOrderIsOkWarnCritical() {
        assertThat(Severity.values())
                .containsExactly(Severity.OK, Severity.WARN, Severity.CRITICAL);
    }

    @Test
    void ordinalsMatchTheDeclaredOrder() {
        assertThat(Severity.OK.ordinal()).isZero();
        assertThat(Severity.WARN.ordinal()).isEqualTo(1);
        assertThat(Severity.CRITICAL.ordinal()).isEqualTo(2);
    }

    @Test
    void maxPicksTheMoreSevereOfTwo() {
        assertThat(Severity.OK.max(Severity.WARN)).isEqualTo(Severity.WARN);
        assertThat(Severity.WARN.max(Severity.OK)).isEqualTo(Severity.WARN);
        assertThat(Severity.CRITICAL.max(Severity.WARN)).isEqualTo(Severity.CRITICAL);
        assertThat(Severity.WARN.max(Severity.CRITICAL)).isEqualTo(Severity.CRITICAL);
    }

    /** 相等时返回自身：调用方拿它做累加，返回哪一边都必须是「同一个级别」。 */
    @Test
    void maxIsIdempotentForEqualInputs() {
        assertThat(Severity.WARN.max(Severity.WARN)).isEqualTo(Severity.WARN);
    }

    @Test
    void maxIsCommutative() {
        for (Severity a : Severity.values()) {
            for (Severity b : Severity.values()) {
                assertThat(a.max(b)).isEqualTo(b.max(a));
            }
        }
    }

    @Test
    void maxRejectsNull() {
        assertThatThrownBy(() -> Severity.OK.max(null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void valueOfRoundTrips() {
        for (Severity severity : Severity.values()) {
            assertThat(Severity.valueOf(severity.name())).isSameAs(severity);
        }
    }
}
