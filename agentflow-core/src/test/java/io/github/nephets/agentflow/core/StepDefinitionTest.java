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
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class StepDefinitionTest {

    @Test
    void defaultsAreFilledIn() {
        StepDefinition step =
                StepDefinition.builder("risk-check", ctx -> StepResult.success()).build();

        assertThat(step.getStepId()).isEqualTo("risk-check");
        assertThat(step.getName()).isEqualTo("risk-check");
        assertThat(step.getRetry()).isEqualTo(RetryPolicy.none());
        assertThat(step.getTimeout()).isEqualTo(TimeoutPolicy.none());
        assertThat(step.isRequiresApproval()).isFalse();
        assertThat(step.getApprovalPrompt()).isEqualTo("需要人工确认：risk-check");
        assertThat(step.getCompensator()).isNull();
        assertThat(step.hasCompensator()).isFalse();
        assertThat(step.toString()).contains("risk-check");
    }

    @Test
    void keepsConfiguredPolicies() {
        Compensator compensator = ctx -> CompensationResult.ok("done");
        StepDefinition step =
                StepDefinition.builder()
                        .stepId("risk-check")
                        .name("风控校验")
                        .handler(ctx -> StepResult.success(Map.of("risk", "LOW")))
                        .retry(RetryPolicy.fixed(3, Duration.ofMillis(10)))
                        .timeout(TimeoutPolicy.of(Duration.ofSeconds(5)))
                        .requiresApproval(true)
                        .approvalPrompt("请复核")
                        .compensator(compensator)
                        .build();

        assertThat(step.getName()).isEqualTo("风控校验");
        assertThat(step.getRetry().getMaxAttempts()).isEqualTo(3);
        assertThat(step.getTimeout().getTimeout()).isEqualTo(Duration.ofSeconds(5));
        assertThat(step.isRequiresApproval()).isTrue();
        assertThat(step.getApprovalPrompt()).isEqualTo("请复核");
        assertThat(step.hasCompensator()).isTrue();
        assertThat(step.getCompensator()).isSameAs(compensator);
    }

    @Test
    void nullPoliciesFallBackToDefaults() {
        StepDefinition step =
                StepDefinition.builder()
                        .stepId("s")
                        .handler(ctx -> StepResult.success())
                        .retry(null)
                        .timeout(null)
                        .build();

        assertThat(step.getRetry()).isEqualTo(RetryPolicy.none());
        assertThat(step.getTimeout()).isEqualTo(TimeoutPolicy.none());
    }

    @Test
    void validatesRequiredFields() {
        assertThatThrownBy(
                        () -> StepDefinition.builder().handler(ctx -> StepResult.success()).build())
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> StepDefinition.builder("  ", ctx -> StepResult.success()).build())
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> StepDefinition.builder("s", null).build())
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void trimsStepIdAndFallsBackToBlankPrompts() {
        StepDefinition step =
                StepDefinition.builder("  spaced  ", ctx -> StepResult.success())
                        .name(" ")
                        .approvalPrompt(" ")
                        .build();

        assertThat(step.getStepId()).isEqualTo("spaced");
        assertThat(step.getName()).isEqualTo("spaced");
        assertThat(step.getApprovalPrompt()).isEqualTo("需要人工确认：spaced");
    }
}
