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

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 钉住 {@link Finding} 构造器的默认值与不可变性。
 *
 * <p>为什么默认值也要测：发现项会被直接渲染到 Actuator 端点与日志里， 「没设建议修复」必须是空串而不是 null —— 否则每一处渲染代码都要自己处理 null。
 */
class FindingBuilderTest {

    @Test
    void unsetFieldsDefaultToEmptyRatherThanNull() {
        Finding finding = Finding.builder(Severity.OK, "LOOM-OK").build();
        assertThat(finding.message()).isEqualTo("");
        assertThat(finding.suggestedFix()).isEqualTo("");
        assertThat(finding.evidenceStack()).isEmpty();
    }

    @Test
    void severityAndRuleIdAreRequired() {
        assertThatThrownBy(() -> Finding.builder(null, "R"))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> Finding.builder(Severity.OK, null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void builderIsFluentAndReturnsItself() {
        Finding.FindingBuilder builder = Finding.builder(Severity.WARN, "R");
        assertThat(builder.message("m")).isSameAs(builder);
        assertThat(builder.suggestedFix("f")).isSameAs(builder);
        assertThat(builder.evidenceStack(List.of())).isSameAs(builder);
    }

    /** 证据栈必须是防御性拷贝：调用方事后改自己的 list，不能改写已构造的发现项。 */
    @Test
    void evidenceStackIsCopiedDefensively() {
        List<String> stack = new ArrayList<>(List.of("a", "b"));
        Finding finding = Finding.builder(Severity.CRITICAL, "R").evidenceStack(stack).build();
        stack.clear();
        assertThat(finding.evidenceStack()).containsExactly("a", "b");
    }

    @Test
    void evidenceStackIsImmutableOnceBuilt() {
        Finding finding =
                Finding.builder(Severity.CRITICAL, "R").evidenceStack(List.of("a")).build();
        assertThatThrownBy(() -> finding.evidenceStack().add("b"))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void nullEvidenceStackIsRejected() {
        assertThatThrownBy(() -> Finding.builder(Severity.OK, "R").evidenceStack(null).build())
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void findingsWithTheSameComponentsAreEqual() {
        Finding a = Finding.builder(Severity.WARN, "R").message("m").suggestedFix("f").build();
        Finding b = Finding.builder(Severity.WARN, "R").message("m").suggestedFix("f").build();
        assertThat(a).isEqualTo(b);
        assertThat(a.hashCode()).isEqualTo(b.hashCode());
    }

    @Test
    void findingsDifferBySeverityAndRuleId() {
        assertThat(Finding.builder(Severity.OK, "R").build())
                .isNotEqualTo(Finding.builder(Severity.WARN, "R").build());
        assertThat(Finding.builder(Severity.OK, "A").build())
                .isNotEqualTo(Finding.builder(Severity.OK, "B").build());
    }

    @Test
    void toStringContainsTheRuleId() {
        assertThat(Finding.builder(Severity.CRITICAL, "LOOM-PINNED").build().toString())
                .contains("LOOM-PINNED")
                .contains("CRITICAL");
    }
}
