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
package io.github.nephets.loomguard.sample;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

/** 应用上下文能正常加载（loomguard 自动装配生效）。 */
@SpringBootTest
class SampleApplicationTests {

    @Test
    void contextLoads() {
        // 上下文成功启动即证明 LoomGuard / LoomGuardCollector / LoomGuardEndpoint 已装配
    }
}
