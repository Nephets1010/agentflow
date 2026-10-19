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

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RetryPolicyTest {

    @Test
    void noneNeverRetries() {
        RetryPolicy policy = RetryPolicy.none();

        assertThat(policy.getMaxAttempts()).isEqualTo(1);
        assertThat(policy.allows(1)).isFalse();
        assertThat(policy.allows(2)).isFalse();
        assertThat(policy.backoffFor(1)).isEqualTo(Duration.ZERO);
    }

    @Test
    void fixedBackoffIsConstant() {
        RetryPolicy policy = RetryPolicy.fixed(3, Duration.ofMillis(200));

        assertThat(policy.getMode()).isEqualTo(RetryPolicy.BackoffMode.FIXED);
        assertThat(policy.allows(1)).isTrue();
        assertThat(policy.allows(2)).isTrue();
        assertThat(policy.allows(3)).isFalse();
        assertThat(policy.backoffFor(1)).isEqualTo(Duration.ofMillis(200));
        assertThat(policy.backoffFor(2)).isEqualTo(Duration.ofMillis(200));
    }

    @Test
    void exponentialBackoffGrowsWithMultiplier() {
        RetryPolicy policy = RetryPolicy.exponential(4, Duration.ofMillis(100), 2.0d);

        assertThat(policy.getMode()).isEqualTo(RetryPolicy.BackoffMode.EXPONENTIAL);
        assertThat(policy.getMultiplier()).isEqualTo(2.0d);
        assertThat(policy.backoffFor(1)).isEqualTo(Duration.ofMillis(100));
        assertThat(policy.backoffFor(2)).isEqualTo(Duration.ofMillis(200));
        assertThat(policy.backoffFor(3)).isEqualTo(Duration.ofMillis(400));
        assertThat(policy.backoffFor(0)).isEqualTo(Duration.ZERO);
    }

    @Test
    void exponentialBackoffIsCappedAtOneHour() {
        RetryPolicy policy = RetryPolicy.exponential(20, Duration.ofMillis(100), 10.0d);

        assertThat(policy.backoffFor(12)).isEqualTo(Duration.ofHours(1));
    }

    @Test
    void rejectsInvalidArguments() {
        assertThatThrownBy(() -> RetryPolicy.fixed(0, Duration.ofMillis(1)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(
                        () ->
                                RetryPolicy.of(
                                        2,
                                        Duration.ofMillis(-1),
                                        RetryPolicy.BackoffMode.FIXED,
                                        1.0d))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> RetryPolicy.of(2, Duration.ofMillis(1), null, 1.0d))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> RetryPolicy.exponential(2, Duration.ofMillis(1), 0.5d))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void equalityAndToString() {
        RetryPolicy left = RetryPolicy.fixed(2, Duration.ofMillis(50));
        RetryPolicy right = RetryPolicy.fixed(2, Duration.ofMillis(50));

        assertThat(left).isEqualTo(right).hasSameHashCodeAs(right);
        assertThat(left).isNotEqualTo(RetryPolicy.fixed(3, Duration.ofMillis(50)));
        assertThat(left).isNotEqualTo("not a policy");
        assertThat(left.toString()).contains("maxAttempts=2");
    }

    @Test
    void exposesDeclaredValues() {
        RetryPolicy policy =
                RetryPolicy.of(5, Duration.ofSeconds(2), RetryPolicy.BackoffMode.EXPONENTIAL, 3.0d);

        assertThat(policy.getMaxAttempts()).isEqualTo(5);
        assertThat(policy.getInitialBackoff()).isEqualTo(Duration.ofSeconds(2));
        assertThat(policy.getMode()).isEqualTo(RetryPolicy.BackoffMode.EXPONENTIAL);
        assertThat(RetryPolicy.BackoffMode.values()).hasSize(2);
        assertThat(RetryPolicy.BackoffMode.valueOf("EXPONENTIAL"))
                .isEqualTo(RetryPolicy.BackoffMode.EXPONENTIAL);
    }
}
