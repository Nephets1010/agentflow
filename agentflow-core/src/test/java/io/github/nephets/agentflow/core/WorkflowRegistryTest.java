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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WorkflowRegistryTest {

    private static WorkflowDefinition definition(String id) {
        return WorkflowDefinition.builder()
                .workflowId(id)
                .addStep(StepDefinition.builder("s", ctx -> StepResult.success()).build())
                .build();
    }

    @Test
    void registersAndFindsDefinitions() {
        WorkflowRegistry registry = new WorkflowRegistry();
        registry.register(definition("wf-b"));
        registry.register(definition("wf-a"));

        assertThat(registry.size()).isEqualTo(2);
        assertThat(registry.find("wf-a")).isPresent();
        assertThat(registry.find("wf-a").orElseThrow().getWorkflowId()).isEqualTo("wf-a");
        assertThat(registry.require("wf-b")).isNotNull();
        assertThat(registry.ids()).containsExactly("wf-a", "wf-b");
    }

    @Test
    void missingLookupIsEmpty() {
        WorkflowRegistry registry = new WorkflowRegistry();

        assertThat(registry.find("nope")).isEmpty();
        assertThat(registry.find(null)).isEmpty();
        assertThatThrownBy(() -> registry.require("nope"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("工作流未注册");
    }

    @Test
    void unregistersDefinitions() {
        WorkflowRegistry registry = new WorkflowRegistry();
        registry.register(definition("wf"));

        assertThat(registry.unregister("wf")).isTrue();
        assertThat(registry.unregister("wf")).isFalse();
        assertThat(registry.unregister(null)).isFalse();
        assertThat(registry.size()).isZero();
    }

    @Test
    void capacityIsBoundedAndEvictsLeastRecentlyUsed() {
        WorkflowRegistry registry = new WorkflowRegistry(2);
        registry.register(definition("wf-a"));
        registry.register(definition("wf-b"));

        assertThat(registry.size()).isEqualTo(2);
        // 访问一次 wf-a，让它变成最近使用，下次淘汰就该轮到 wf-b。
        assertThat(registry.find("wf-a")).isPresent();
        registry.register(definition("wf-c"));

        assertThat(registry.size()).isEqualTo(2);
        assertThat(registry.ids()).containsExactly("wf-a", "wf-c");
        assertThat(registry.find("wf-b")).isEmpty();
        assertThat(registry.getMaxCapacity()).isEqualTo(2);
    }

    @Test
    void rejectsNonPositiveCapacity() {
        assertThatThrownBy(() -> new WorkflowRegistry(0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("maxCapacity");
        assertThat(new WorkflowRegistry().getMaxCapacity())
                .isEqualTo(WorkflowRegistry.DEFAULT_MAX_CAPACITY);
    }

    @Test
    void rejectsNullDefinition() {
        WorkflowRegistry registry = new WorkflowRegistry();

        assertThatThrownBy(() -> registry.register(null)).isInstanceOf(NullPointerException.class);
    }
}
