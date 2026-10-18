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
package io.github.nephets.loomguard.starter;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** loomguard 的配置属性，前缀 {@code loomguard}。 */
@ConfigurationProperties(prefix = "loomguard")
public class LoomGuardProperties {

    /** 是否启用 loomguard 自动装配。默认 true。 */
    private boolean enabled = true;

    /** 是否暴露 Actuator 端点 /actuator/loomguard。默认 true。 */
    private boolean exposeEndpoint = true;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public boolean isExposeEndpoint() {
        return exposeEndpoint;
    }

    public void setExposeEndpoint(boolean exposeEndpoint) {
        this.exposeEndpoint = exposeEndpoint;
    }
}
