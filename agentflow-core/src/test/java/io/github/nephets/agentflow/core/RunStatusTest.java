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

class RunStatusTest {

    @Test
    void coversAllEightStates() {
        assertThat(RunStatus.values())
                .containsExactlyInAnyOrder(
                        RunStatus.CREATED,
                        RunStatus.RUNNING,
                        RunStatus.WAITING_APPROVAL,
                        RunStatus.SUCCEEDED,
                        RunStatus.FAILED,
                        RunStatus.COMPENSATING,
                        RunStatus.COMPENSATED,
                        RunStatus.ROLLED_BACK);
        assertThat(RunStatus.valueOf("COMPENSATING")).isEqualTo(RunStatus.COMPENSATING);
    }

    @Test
    void terminalStatesAreRecognised() {
        assertThat(RunStatus.isTerminal(RunStatus.SUCCEEDED)).isTrue();
        assertThat(RunStatus.isTerminal(RunStatus.FAILED)).isTrue();
        assertThat(RunStatus.isTerminal(RunStatus.COMPENSATED)).isTrue();
        assertThat(RunStatus.isTerminal(RunStatus.ROLLED_BACK)).isTrue();
        assertThat(RunStatus.isTerminal(RunStatus.CREATED)).isFalse();
        assertThat(RunStatus.isTerminal(RunStatus.RUNNING)).isFalse();
        assertThat(RunStatus.isTerminal(RunStatus.WAITING_APPROVAL)).isFalse();
        assertThat(RunStatus.isTerminal(RunStatus.COMPENSATING)).isFalse();
        assertThat(RunStatus.isTerminal(null)).isFalse();
    }
}
