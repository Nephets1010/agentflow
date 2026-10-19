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

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RunSnapshotTest {

    @Test
    void keepsAllFields() {
        Map<String, Object> variables = new LinkedHashMap<>();
        variables.put("amount", 500);
        Instant now = Instant.parse("2026-02-03T04:05:06Z");

        RunSnapshot snapshot = RunSnapshot.of(3, "run-9", 1, StepStatus.SUCCEEDED, variables, now);

        assertThat(snapshot.getVersion()).isEqualTo(3);
        assertThat(snapshot.getRunId()).isEqualTo("run-9");
        assertThat(snapshot.getStepIndex()).isEqualTo(1);
        assertThat(snapshot.getStepStatus()).isEqualTo(StepStatus.SUCCEEDED);
        assertThat(snapshot.getVariables()).containsEntry("amount", 500);
        assertThat(snapshot.getCreatedAt()).isEqualTo(now);
    }

    @Test
    void copiesVariablesDefensively() {
        Map<String, Object> variables = new LinkedHashMap<>();
        variables.put("amount", 500);
        RunSnapshot snapshot = RunSnapshot.of(1, "run-9", 0, StepStatus.PENDING, variables, null);
        variables.put("amount", 999);

        assertThat(snapshot.getVariables()).containsEntry("amount", 500);
        assertThat(snapshot.getCreatedAt()).isEqualTo(Instant.EPOCH);
        assertThatThrownBy(() -> snapshot.getVariables().clear())
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void validatesVersionAndStatus() {
        assertThatThrownBy(() -> RunSnapshot.of(0, "run-9", 0, StepStatus.PENDING, null, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> RunSnapshot.of(1, "run-9", 0, null, null, null))
                .isInstanceOf(NullPointerException.class);
        assertThat(RunSnapshot.of(1, "run-9", 0, StepStatus.PENDING, null, null).getVariables())
                .isEmpty();
    }
}
