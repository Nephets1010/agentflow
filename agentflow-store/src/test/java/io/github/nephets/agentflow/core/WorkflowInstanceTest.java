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

class WorkflowInstanceTest {

    private static final Instant CREATED = Instant.parse("2026-04-01T00:00:00Z");

    private static WorkflowInstance instance() {
        return new WorkflowInstance("run-1", "acme", "contract-approval", 3, CREATED);
    }

    @Test
    void startsInCreatedState() {
        WorkflowInstance instance = instance();

        assertThat(instance.getRunId()).isEqualTo("run-1");
        assertThat(instance.getTenantId()).isEqualTo("acme");
        assertThat(instance.getWorkflowId()).isEqualTo("contract-approval");
        assertThat(instance.getVersion()).isEqualTo(3);
        assertThat(instance.getStatus()).isEqualTo(RunStatus.CREATED);
        assertThat(instance.getCurrentStepIndex()).isZero();
        assertThat(instance.getCreatedAt()).isEqualTo(CREATED);
        assertThat(instance.getUpdatedAt()).isEqualTo(CREATED);
        assertThat(instance.getPendingApproval()).isNull();
        assertThat(instance.getStepStatuses()).isEmpty();
        assertThat(instance.getVariables()).isEmpty();
        assertThat(instance.getSnapshots()).isEmpty();
        assertThat(instance.latestSnapshotVersion()).isZero();
        assertThat(instance.toString()).contains("run-1");
    }

    @Test
    void rejectsBlankIdentityFields() {
        assertThatThrownBy(() -> new WorkflowInstance(" ", "acme", "wf", 1, CREATED))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new WorkflowInstance("run", " ", "wf", 1, CREATED))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new WorkflowInstance("run", "acme", " ", 1, CREATED))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(new WorkflowInstance("run", "acme", "wf", 1, null).getCreatedAt())
                .isEqualTo(Instant.EPOCH);
    }

    @Test
    void recordsStepStatuses() {
        WorkflowInstance instance = instance();
        instance.recordStepStatus("extract", StepStatus.RUNNING);

        assertThat(instance.stepStatus("extract")).isEqualTo(StepStatus.RUNNING);
        assertThat(instance.stepStatus("unknown")).isEqualTo(StepStatus.PENDING);

        Map<String, StepStatus> replacement = new LinkedHashMap<>();
        replacement.put("extract", StepStatus.SUCCEEDED);
        replacement.put("risk", StepStatus.PENDING);
        instance.replaceStepStatuses(replacement);

        assertThat(instance.getStepStatuses())
                .containsEntry("extract", StepStatus.SUCCEEDED)
                .containsEntry("risk", StepStatus.PENDING);
        assertThatThrownBy(() -> instance.getStepStatuses().clear())
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> instance.recordStepStatus(" ", StepStatus.RUNNING))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void managesVariables() {
        WorkflowInstance instance = instance();
        instance.putVariable("amount", 100);
        instance.putAllVariables(Map.of("party", "acme"));

        assertThat(instance.variable("amount")).isEqualTo(100);
        assertThat(instance.variable("party")).isEqualTo("acme");
        assertThat(instance.variable("missing")).isNull();

        instance.replaceVariables(Map.of("amount", 200));
        assertThat(instance.getVariables()).containsExactly(Map.entry("amount", 200));
        instance.putAllVariables(null);
        assertThat(instance.variable("amount")).isEqualTo(200);
    }

    @Test
    void managesSnapshots() {
        WorkflowInstance instance = instance();
        for (int version = 1; version <= 4; version++) {
            instance.addSnapshot(
                    RunSnapshot.of(
                            version,
                            "run-1",
                            version,
                            StepStatus.SUCCEEDED,
                            Map.of("v", version),
                            CREATED));
        }

        assertThat(instance.getSnapshots()).hasSize(4);
        assertThat(instance.latestSnapshotVersion()).isEqualTo(4);
        assertThat(instance.snapshot(2)).isPresent();
        assertThat(instance.snapshot(2).orElseThrow().getStepIndex()).isEqualTo(2);
        assertThat(instance.snapshot(99)).isEmpty();
        assertThatThrownBy(() -> instance.getSnapshots().clear())
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void trimKeepsOnlyTheNewestSnapshots() {
        WorkflowInstance instance = instance();
        for (int version = 1; version <= 5; version++) {
            instance.addSnapshot(
                    RunSnapshot.of(version, "run-1", 0, StepStatus.SUCCEEDED, Map.of(), CREATED));
        }
        instance.trimSnapshots(2);

        assertThat(instance.getSnapshots())
                .extracting(RunSnapshot::getVersion)
                .containsExactly(4, 5);
        instance.trimSnapshots(0);
        assertThat(instance.getSnapshots()).hasSize(2);
    }

    @Test
    void dropsSnapshotsAfterGivenVersion() {
        WorkflowInstance instance = instance();
        for (int version = 1; version <= 4; version++) {
            instance.addSnapshot(
                    RunSnapshot.of(version, "run-1", 0, StepStatus.SUCCEEDED, Map.of(), CREATED));
        }
        instance.dropSnapshotsAfter(2);

        assertThat(instance.getSnapshots())
                .extracting(RunSnapshot::getVersion)
                .containsExactly(1, 2);
    }

    @Test
    void updatesStatusIndexAndTimestamp() {
        WorkflowInstance instance = instance();
        instance.setStatus(RunStatus.RUNNING);
        instance.setCurrentStepIndex(2);
        instance.touch(Instant.parse("2026-04-01T01:00:00Z"));
        instance.touch(null);

        assertThat(instance.getStatus()).isEqualTo(RunStatus.RUNNING);
        assertThat(instance.getCurrentStepIndex()).isEqualTo(2);
        assertThat(instance.getUpdatedAt()).isEqualTo(Instant.parse("2026-04-01T01:00:00Z"));
        assertThatThrownBy(() -> instance.setStatus(null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> instance.setCurrentStepIndex(-1))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
