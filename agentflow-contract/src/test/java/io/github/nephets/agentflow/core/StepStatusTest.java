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

import static org.assertj.core.api.Assertions.assertThat;

class StepStatusTest {

    @Test
    void coversAllEightStates() {
        assertThat(StepStatus.values())
                .containsExactlyInAnyOrder(
                        StepStatus.PENDING,
                        StepStatus.RUNNING,
                        StepStatus.WAITING_APPROVAL,
                        StepStatus.SUCCEEDED,
                        StepStatus.FAILED,
                        StepStatus.SKIPPED,
                        StepStatus.TIMED_OUT,
                        StepStatus.COMPENSATED);
        assertThat(StepStatus.valueOf("WAITING_APPROVAL")).isEqualTo(StepStatus.WAITING_APPROVAL);
    }

    @Test
    void terminalStatesAreRecognised() {
        assertThat(StepStatus.isTerminal(StepStatus.SUCCEEDED)).isTrue();
        assertThat(StepStatus.isTerminal(StepStatus.FAILED)).isTrue();
        assertThat(StepStatus.isTerminal(StepStatus.SKIPPED)).isTrue();
        assertThat(StepStatus.isTerminal(StepStatus.TIMED_OUT)).isTrue();
        assertThat(StepStatus.isTerminal(StepStatus.COMPENSATED)).isTrue();
        assertThat(StepStatus.isTerminal(StepStatus.PENDING)).isFalse();
        assertThat(StepStatus.isTerminal(StepStatus.RUNNING)).isFalse();
        assertThat(StepStatus.isTerminal(StepStatus.WAITING_APPROVAL)).isFalse();
        assertThat(StepStatus.isTerminal(null)).isFalse();
    }
}
