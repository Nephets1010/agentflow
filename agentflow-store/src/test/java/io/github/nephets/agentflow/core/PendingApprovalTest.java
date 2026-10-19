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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PendingApprovalTest {

    @Test
    void keepsAllFields() {
        Instant now = Instant.parse("2026-03-01T10:00:00Z");
        PendingApproval approval = PendingApproval.of("apr-1", "run-1", "risk-check", "金额超阈值", now);

        assertThat(approval.getApprovalId()).isEqualTo("apr-1");
        assertThat(approval.getRunId()).isEqualTo("run-1");
        assertThat(approval.getStepId()).isEqualTo("risk-check");
        assertThat(approval.getPrompt()).isEqualTo("金额超阈值");
        assertThat(approval.getCreatedAt()).isEqualTo(now);
        assertThat(approval.toString()).contains("apr-1");
    }

    @Test
    void toleratesNullPromptAndTime() {
        PendingApproval approval = PendingApproval.of("apr-1", "run-1", "risk-check", null, null);

        assertThat(approval.getPrompt()).isEmpty();
        assertThat(approval.getCreatedAt()).isEqualTo(Instant.EPOCH);
        assertThatThrownBy(() -> PendingApproval.of(null, "run-1", "s", "p", null))
                .isInstanceOf(NullPointerException.class);
    }
}
