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

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class StepResultTest {

    @Test
    void successCarriesOutput() {
        Map<String, Object> output = new LinkedHashMap<>();
        output.put("amount", 1200);
        StepResult result = StepResult.success(output);

        assertThat(result.getStatus()).isEqualTo(StepStatus.SUCCEEDED);
        assertThat(result.getOutput()).containsEntry("amount", 1200);
        assertThat(result.getMessage()).isEmpty();
    }

    @Test
    void successWithoutOutputIsEmpty() {
        assertThat(StepResult.success().getOutput()).isEmpty();
        assertThat(StepResult.success(null).getOutput()).isEmpty();
    }

    @Test
    void failureCarriesMessage() {
        StepResult result = StepResult.failure("风控接口不可用");

        assertThat(result.getStatus()).isEqualTo(StepStatus.FAILED);
        assertThat(result.getMessage()).isEqualTo("风控接口不可用");
        assertThat(result.getOutput()).isEmpty();
        assertThat(StepResult.failure(null).getMessage()).isEmpty();
    }

    @Test
    void waitApprovalUsesMessageAsPrompt() {
        Map<String, Object> output = new LinkedHashMap<>();
        output.put("risk", "HIGH");
        StepResult result = StepResult.waitApproval("金额超阈值，请确认", output);

        assertThat(result.getStatus()).isEqualTo(StepStatus.WAITING_APPROVAL);
        assertThat(result.getMessage()).isEqualTo("金额超阈值，请确认");
        assertThat(result.getOutput()).containsEntry("risk", "HIGH");
        assertThat(StepResult.waitApproval(null).getMessage()).isEmpty();
    }

    @Test
    void skippedAndCustomStatesAreSupported() {
        assertThat(StepResult.skipped("无需通知").getStatus()).isEqualTo(StepStatus.SKIPPED);
        assertThat(StepResult.skipped("无需通知").getMessage()).isEqualTo("无需通知");

        StepResult compensated =
                StepResult.of(StepStatus.COMPENSATED, Map.of("revoked", true), "已撤销");
        assertThat(compensated.getStatus()).isEqualTo(StepStatus.COMPENSATED);
        assertThat(compensated.getOutput()).containsEntry("revoked", true);
    }

    @Test
    void outputIsDefensivelyCopied() {
        Map<String, Object> output = new LinkedHashMap<>();
        output.put("k", "v");
        StepResult result = StepResult.success(output);
        output.put("k", "changed");

        assertThat(result.getOutput()).containsEntry("k", "v");
        assertThatThrownBy(() -> result.getOutput().put("x", "y"))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> StepResult.of(null, null, null))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
