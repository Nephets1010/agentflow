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

class AgentflowExceptionsTest {

    @Test
    void workflowNotFoundCarriesMessage() {
        WorkflowNotFoundException withoutCause = new WorkflowNotFoundException("run-1");
        WorkflowNotFoundException withCause =
                new WorkflowNotFoundException("run-1", new IllegalStateException("down"));

        assertThat(withoutCause).isInstanceOf(RuntimeException.class).hasMessage("run-1");
        assertThat(withCause.getCause()).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void approvalRequiredCarriesMessage() {
        ApprovalRequiredException withoutCause = new ApprovalRequiredException("apr-1");
        ApprovalRequiredException withCause =
                new ApprovalRequiredException("apr-1", new IllegalStateException("down"));

        assertThat(withoutCause).isInstanceOf(RuntimeException.class).hasMessage("apr-1");
        assertThat(withCause.getCause()).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void stepTimeoutCarriesStepAndThreshold() {
        StepTimeoutException exception = new StepTimeoutException("risk", Duration.ofSeconds(3));

        assertThat(exception.getStepId()).isEqualTo("risk");
        assertThat(exception.getTimeout()).isEqualTo(Duration.ofSeconds(3));
        assertThat(exception.getMessage()).contains("risk").contains("PT3S");

        StepTimeoutException generic =
                new StepTimeoutException("超时了", new IllegalStateException("down"));
        assertThat(generic.getStepId()).isNull();
        assertThat(generic.getTimeout()).isEqualTo(Duration.ZERO);
        assertThat(generic.getCause()).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void retryExhaustedCarriesStepAndAttempts() {
        RetryExhaustedException withoutCause = new RetryExhaustedException("risk", 3, "接口 500");
        RetryExhaustedException withCause =
                new RetryExhaustedException("risk", 3, "接口 500", new IllegalStateException("down"));

        assertThat(withoutCause.getStepId()).isEqualTo("risk");
        assertThat(withoutCause.getAttempts()).isEqualTo(3);
        assertThat(withoutCause.getMessage()).contains("risk").contains("尝试次数=3");
        assertThat(withCause.getCause()).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void compensationFailedCarriesStep() {
        CompensationFailedException withoutCause = new CompensationFailedException("risk", "撤销失败");
        CompensationFailedException withCause =
                new CompensationFailedException("risk", "撤销失败", new IllegalStateException("down"));

        assertThat(withoutCause.getStepId()).isEqualTo("risk");
        assertThat(withoutCause.getMessage()).contains("撤销失败");
        assertThat(withCause.getCause()).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void toolInvocationExceptionCarriesToolName() {
        ToolInvocationException withMessage = new ToolInvocationException("echo", "参数缺失");
        ToolInvocationException withCause =
                new ToolInvocationException("echo", new IllegalStateException("down"));

        assertThat(withMessage.getToolName()).isEqualTo("echo");
        assertThat(withMessage.getMessage()).contains("echo").contains("参数缺失");
        assertThat(withCause.getCause()).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void toolNotFoundCarriesToolName() {
        ToolNotFoundException exception = new ToolNotFoundException("echo");

        assertThat(exception.getToolName()).isEqualTo("echo");
        assertThat(exception.getMessage()).contains("echo");
    }
}
