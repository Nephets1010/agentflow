/*
 * Copyright 2026 The agentflow Authors.
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
package io.github.nephets.agentflow.core;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.Objects;

/**
 * 单步超时策略。
 *
 * <p>{@link Duration#ZERO} 表示不限时；超时的步骤记为 {@link StepStatus#TIMED_OUT}， 之后按 {@link RetryPolicy}
 * 决定是否再试。
 */
public final class TimeoutPolicy {

    private static final Logger log = LoggerFactory.getLogger(TimeoutPolicy.class);

    private final Duration timeout;

    private TimeoutPolicy(Duration timeout) {
        this.timeout = timeout;
    }

    /** 不限时。 */
    public static TimeoutPolicy none() {
        return new TimeoutPolicy(Duration.ZERO);
    }

    /**
     * 指定超时时长。
     *
     * @param timeout 超时时长；{@link Duration#ZERO} 表示不限时
     * @return 策略对象
     */
    public static TimeoutPolicy of(Duration timeout) {
        Objects.requireNonNull(timeout, "timeout");
        if (timeout.isNegative()) {
            log.error("of 超时时长为负: timeout={}", timeout);
            throw new IllegalArgumentException("timeout 不可为负数：" + timeout);
        }
        return new TimeoutPolicy(timeout);
    }

    /** 返回超时时长。 */
    public Duration getTimeout() {
        return timeout;
    }

    /** 是否不限时。 */
    public boolean isUnlimited() {
        return timeout.isZero();
    }

    /** 是否设置了超时（与 {@link #isUnlimited()} 相反）。 */
    public boolean isEnabled() {
        return !isUnlimited();
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof TimeoutPolicy)) {
            return false;
        }
        return timeout.equals(((TimeoutPolicy) other).timeout);
    }

    @Override
    public int hashCode() {
        return Objects.hash(timeout);
    }

    @Override
    public String toString() {
        return "TimeoutPolicy{timeout=" + timeout + '}';
    }
}
