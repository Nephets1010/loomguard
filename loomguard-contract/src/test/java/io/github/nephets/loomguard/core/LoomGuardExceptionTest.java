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

/** 契约层的失败类型必须能被一个 catch 拦住，且不能丢掉根因。 */
class LoomGuardExceptionTest {

    @Test
    void isUncheckedSoItDoesNotForceBoilerplateAtCallSites() {
        assertThat(new LoomGuardException("boom")).isInstanceOf(RuntimeException.class);
    }

    @Test
    void carriesTheMessage() {
        assertThat(new LoomGuardException("boom")).hasMessage("boom");
    }

    @Test
    void carriesTheCause() {
        Throwable cause = new IllegalStateException("root");
        assertThat(new LoomGuardException("wrapped", cause)).hasCause(cause);
        assertThat(new LoomGuardException("wrapped", cause).getCause()).hasMessage("root");
    }

    @Test
    void causeIsOptional() {
        assertThat(new LoomGuardException("boom").getCause()).isNull();
    }

    @Test
    void canBeCaughtAsItsOwnType() {
        try {
            throw new LoomGuardException("boom");
        } catch (LoomGuardException e) {
            assertThat(e).hasMessage("boom");
        }
    }
}
