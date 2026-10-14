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
import java.util.Objects;

/** 一条「虚拟线程使用不当」的发现，含严重级别、规则 ID、说明与建议修复。 */
public record Finding(
        Severity severity,
        String ruleId,
        String message,
        String suggestedFix,
        List<String> evidenceStack) {

    /** 构造器入口。 */
    public static FindingBuilder builder(Severity severity, String ruleId) {
        Objects.requireNonNull(severity, "severity must not be null");
        Objects.requireNonNull(ruleId, "ruleId must not be null");
        return new FindingBuilder(severity, ruleId);
    }

    /** 构造器：避免 5 参数重载难以阅读。 */
    public static final class FindingBuilder {
        private final Severity severity;
        private final String ruleId;
        private String message = "";
        private String suggestedFix = "";
        private List<String> evidenceStack = List.of();

        private FindingBuilder(Severity severity, String ruleId) {
            this.severity = severity;
            this.ruleId = ruleId;
        }

        public FindingBuilder message(String value) {
            this.message = value;
            return this;
        }

        public FindingBuilder suggestedFix(String value) {
            this.suggestedFix = value;
            return this;
        }

        public FindingBuilder evidenceStack(List<String> value) {
            this.evidenceStack = value;
            return this;
        }

        public Finding build() {
            return new Finding(severity, ruleId, message, suggestedFix, List.copyOf(evidenceStack));
        }
    }
}
