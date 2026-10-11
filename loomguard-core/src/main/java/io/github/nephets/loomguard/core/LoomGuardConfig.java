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

/** loomguard 运行配置。 */
public final class LoomGuardConfig {

    private final boolean enabled;

    private LoomGuardConfig(boolean enabled) {
        this.enabled = enabled;
    }

    /** 默认配置：启用。 */
    public static LoomGuardConfig defaults() {
        return new LoomGuardConfig(true);
    }

    /** 自定义是否启用。 */
    public static LoomGuardConfig of(boolean enabled) {
        return new LoomGuardConfig(enabled);
    }

    public boolean enabled() {
        return enabled;
    }
}
