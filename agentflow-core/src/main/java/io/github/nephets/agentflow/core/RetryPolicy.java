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
 * 重试与退避策略。
 *
 * <p>{@code maxAttempts} 是最多尝试次数（含第一次）；{@code backoffFor(attempt)} 返回第 {@code attempt} 次尝试失败后
 * 应该等待多久再发起下一次尝试，指数模式下按 {@code multiplier} 递增，上限 1 小时。
 */
public final class RetryPolicy {

    private static final Logger log = LoggerFactory.getLogger(RetryPolicy.class);

    private static final long MAX_BACKOFF_MILLIS = Duration.ofHours(1).toMillis();

    /** 退避模式。 */
    public enum BackoffMode {

        /** 每次等待固定时长。 */
        FIXED,

        /** 每次等待时长按倍数递增。 */
        EXPONENTIAL
    }

    private final int maxAttempts;
    private final Duration initialBackoff;
    private final BackoffMode mode;
    private final double multiplier;

    private RetryPolicy(
            int maxAttempts, Duration initialBackoff, BackoffMode mode, double multiplier) {
        this.maxAttempts = maxAttempts;
        this.initialBackoff = initialBackoff;
        this.mode = mode;
        this.multiplier = multiplier;
    }

    /** 不重试：只尝试一次。 */
    public static RetryPolicy none() {
        return new RetryPolicy(1, Duration.ZERO, BackoffMode.FIXED, 1.0d);
    }

    /**
     * 固定退避。
     *
     * @param maxAttempts 最多尝试次数
     * @param backoff 每次失败后的等待时长
     * @return 策略对象
     */
    public static RetryPolicy fixed(int maxAttempts, Duration backoff) {
        return of(maxAttempts, backoff, BackoffMode.FIXED, 1.0d);
    }

    /**
     * 指数退避。
     *
     * @param maxAttempts 最多尝试次数
     * @param initialBackoff 首次失败后的等待时长
     * @param multiplier 递增倍数
     * @return 策略对象
     */
    public static RetryPolicy exponential(
            int maxAttempts, Duration initialBackoff, double multiplier) {
        return of(maxAttempts, initialBackoff, BackoffMode.EXPONENTIAL, multiplier);
    }

    /**
     * 按全部参数构造。
     *
     * @param maxAttempts 最多尝试次数，必须 &gt;= 1
     * @param initialBackoff 首次失败后的等待时长，不可为 {@code null} 或负数
     * @param mode 退避模式，不可为 {@code null}
     * @param multiplier 递增倍数，必须 &gt;= 1.0
     * @return 策略对象
     */
    public static RetryPolicy of(
            int maxAttempts, Duration initialBackoff, BackoffMode mode, double multiplier) {
        if (maxAttempts < 1) {
            log.error("of 重试次数非法: maxAttempts={}", maxAttempts);
            throw new IllegalArgumentException("maxAttempts 必须 >= 1，当前值：" + maxAttempts);
        }
        Objects.requireNonNull(initialBackoff, "initialBackoff");
        Objects.requireNonNull(mode, "mode");
        if (initialBackoff.isNegative()) {
            log.error("of 退避时长为负: initialBackoff={}", initialBackoff);
            throw new IllegalArgumentException("initialBackoff 不可为负数：" + initialBackoff);
        }
        if (multiplier < 1.0d) {
            log.error("of 退避倍数非法: multiplier={}", multiplier);
            throw new IllegalArgumentException("multiplier 必须 >= 1.0，当前值：" + multiplier);
        }
        return new RetryPolicy(maxAttempts, initialBackoff, mode, multiplier);
    }

    /**
     * 第 {@code attempt} 次尝试失败后应等待多久。
     *
     * @param attempt 已完成的尝试序号，从 1 开始
     * @return 等待时长；{@code attempt < 1} 或策略为不重试时返回 {@link Duration#ZERO}
     */
    public Duration backoffFor(int attempt) {
        if (attempt < 1 || maxAttempts <= 1) {
            return Duration.ZERO;
        }
        if (mode == BackoffMode.FIXED) {
            return initialBackoff;
        }
        long millis = Math.round(initialBackoff.toMillis() * Math.pow(multiplier, attempt - 1));
        if (millis > MAX_BACKOFF_MILLIS) {
            millis = MAX_BACKOFF_MILLIS;
        }
        return Duration.ofMillis(millis);
    }

    /**
     * 第 {@code attempt} 次尝试之后是否还允许再试一次。
     *
     * @param attempt 已完成的尝试序号，从 1 开始
     * @return 还有预算返回 {@code true}
     */
    public boolean allows(int attempt) {
        return attempt < maxAttempts;
    }

    /** 返回最多尝试次数。 */
    public int getMaxAttempts() {
        return maxAttempts;
    }

    /** 返回首次失败后的等待时长。 */
    public Duration getInitialBackoff() {
        return initialBackoff;
    }

    /** 返回退避模式。 */
    public BackoffMode getMode() {
        return mode;
    }

    /** 返回递增倍数。 */
    public double getMultiplier() {
        return multiplier;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof RetryPolicy)) {
            return false;
        }
        RetryPolicy that = (RetryPolicy) other;
        return maxAttempts == that.maxAttempts
                && Double.compare(multiplier, that.multiplier) == 0
                && initialBackoff.equals(that.initialBackoff)
                && mode == that.mode;
    }

    @Override
    public int hashCode() {
        return Objects.hash(maxAttempts, initialBackoff, mode, multiplier);
    }

    @Override
    public String toString() {
        return "RetryPolicy{maxAttempts="
                + maxAttempts
                + ", initialBackoff="
                + initialBackoff
                + ", mode="
                + mode
                + ", multiplier="
                + multiplier
                + '}';
    }
}
