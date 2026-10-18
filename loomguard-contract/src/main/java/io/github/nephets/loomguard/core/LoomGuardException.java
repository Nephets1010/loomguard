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

/**
 * loomguard 契约层违反时抛出的统一失败类型。
 *
 * <p><strong>为什么要有自己的异常类型，而不是直接用 {@link
 * IllegalArgumentException}：</strong>「参数不合法」和「契约被违反」在调用方面前是两种性质完全不同的失败。前者通常意味着调用方传错了值，改掉就好；后者意味着
 * loomguard 自己的不变量被打破（例如阈值对象被构造成了负值、判定结果缺了必填字段），属于库的缺陷或版本不匹配。给后者一个专属类型，调用方就能用一个 {@code catch
 * (LoomGuardException)} 把「loomguard 自己出问题了」这一整类失败隔离出来、降级掉，而不必去猜本次到底会抛 IAE 还是 ISE。
 *
 * <p>它继承 {@link RuntimeException} 而不是受检异常：loomguard 的典型调用点是业务热路径（每次任务观测、每次巡检）， 在那里强制调用方写 try/catch
 * 会逼着大家把受检异常翻译成 {@code catch (Exception e) {}} 吞掉，反而比 unchecked 更糟。
 */
public class LoomGuardException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /** 仅带消息的失败。 */
    public LoomGuardException(String message) {
        super(message);
    }

    /** 带消息与根因的失败。 */
    public LoomGuardException(String message, Throwable cause) {
        super(message, cause);
    }
}
