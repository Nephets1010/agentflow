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

class TimeoutPolicyTest {

    @Test
    void zeroMeansUnlimited() {
        TimeoutPolicy policy = TimeoutPolicy.none();

        assertThat(policy.getTimeout()).isEqualTo(Duration.ZERO);
        assertThat(policy.isUnlimited()).isTrue();
        assertThat(policy.isEnabled()).isFalse();
    }

    @Test
    void positiveTimeoutIsEnabled() {
        TimeoutPolicy policy = TimeoutPolicy.of(Duration.ofSeconds(30));

        assertThat(policy.getTimeout()).isEqualTo(Duration.ofSeconds(30));
        assertThat(policy.isUnlimited()).isFalse();
        assertThat(policy.isEnabled()).isTrue();
    }

    @Test
    void rejectsNegativeAndNull() {
        assertThatThrownBy(() -> TimeoutPolicy.of(Duration.ofMillis(-1)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> TimeoutPolicy.of(null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void equalityAndToString() {
        TimeoutPolicy left = TimeoutPolicy.of(Duration.ofSeconds(5));
        TimeoutPolicy right = TimeoutPolicy.of(Duration.ofSeconds(5));

        assertThat(left).isEqualTo(right).hasSameHashCodeAs(right);
        assertThat(left).isNotEqualTo(TimeoutPolicy.none());
        assertThat(left).isNotEqualTo("5s");
        assertThat(left.toString()).contains("PT5S");
    }
}
