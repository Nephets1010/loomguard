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
 * loomguard 运行配置（不可变值对象）。
 *
 * <p><strong>为什么显式实现 equals / hashCode / toString：</strong>配置是要被比较的 ——
 * 使用方需要判断「这次拿到的配置是不是上次那套」，测试需要断言「门面拿到的是我传进去的那份」。 没有值语义的配置对象会把这些判断全部退化成比较引用，逼着调用方逐个字段比对，
 * 而一旦以后加了字段，漏改一处就会产生难以察觉的错误结论。
 */
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

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof LoomGuardConfig that)) {
            return false;
        }
        return enabled == that.enabled;
    }

    @Override
    public int hashCode() {
        return Objects.hash(enabled);
    }

    @Override
    public String toString() {
        return "LoomGuardConfig[enabled=" + enabled + "]";
    }
}
