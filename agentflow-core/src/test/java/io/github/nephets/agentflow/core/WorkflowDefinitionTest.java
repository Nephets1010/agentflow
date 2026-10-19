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

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WorkflowDefinitionTest {

    private static StepDefinition step(String id) {
        return StepDefinition.builder(id, ctx -> StepResult.success()).build();
    }

    @Test
    void keepsOrderedSteps() {
        WorkflowDefinition definition =
                WorkflowDefinition.builder()
                        .workflowId("contract-approval")
                        .name("合同审批")
                        .version(2)
                        .addStep(step("extract"))
                        .addStep(step("risk"))
                        .build();

        assertThat(definition.getWorkflowId()).isEqualTo("contract-approval");
        assertThat(definition.getName()).isEqualTo("合同审批");
        assertThat(definition.getVersion()).isEqualTo(2);
        assertThat(definition.size()).isEqualTo(2);
        assertThat(definition.getSteps())
                .extracting(StepDefinition::getStepId)
                .containsExactly("extract", "risk");
        assertThat(definition.step("risk")).isPresent();
        assertThat(definition.step("nope")).isEmpty();
        assertThat(definition.toString()).contains("contract-approval");
    }

    @Test
    void nameFallsBackToWorkflowId() {
        WorkflowDefinition definition =
                WorkflowDefinition.builder().workflowId("wf").addStep(step("a")).build();

        assertThat(definition.getName()).isEqualTo("wf");
        assertThat(definition.getVersion()).isEqualTo(1);
    }

    @Test
    void rejectsDuplicateStepIds() {
        assertThatThrownBy(
                        () ->
                                WorkflowDefinition.builder()
                                        .workflowId("wf")
                                        .addStep(step("a"))
                                        .addStep(step("a"))
                                        .build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("步骤 id 重复");
    }

    @Test
    void rejectsEmptyOrInvalidInput() {
        assertThatThrownBy(() -> WorkflowDefinition.builder().addStep(step("a")).build())
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> WorkflowDefinition.builder().workflowId("wf").build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("至少需要一个步骤");
        assertThatThrownBy(() -> WorkflowDefinition.builder().workflowId("wf").addStep(null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> WorkflowDefinition.builder().workflowId("wf").steps(null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(
                        () ->
                                WorkflowDefinition.builder()
                                        .workflowId("wf")
                                        .version(0)
                                        .addStep(step("a"))
                                        .build())
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void stepsCanBeReplacedInBulk() {
        WorkflowDefinition definition =
                WorkflowDefinition.builder()
                        .workflowId("wf")
                        .addStep(step("a"))
                        .steps(List.of(step("b"), step("c")))
                        .build();

        assertThat(definition.getSteps())
                .extracting(StepDefinition::getStepId)
                .containsExactly("b", "c");
    }
}
