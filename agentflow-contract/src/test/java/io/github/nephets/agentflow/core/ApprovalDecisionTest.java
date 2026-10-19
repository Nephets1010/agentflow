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

/** 人工裁决枚举的取值与往返解析。 */
class ApprovalDecisionTest {

    @Test
    void keepsTwoOutcomes() {
        assertThat(ApprovalDecision.values()).hasSize(2);
        assertThat(ApprovalDecision.APPROVE.name()).isEqualTo("APPROVE");
        assertThat(ApprovalDecision.REJECT.name()).isEqualTo("REJECT");
    }

    @Test
    void parsesFromWireText() {
        // HTTP 层按名字反解析，大小写由调用方归一，这里只保证标准拼写可 round-trip。
        assertThat(ApprovalDecision.valueOf(ApprovalDecision.APPROVE.name()))
                .isEqualTo(ApprovalDecision.APPROVE);
        assertThat(ApprovalDecision.valueOf(ApprovalDecision.REJECT.name()))
                .isEqualTo(ApprovalDecision.REJECT);
    }

    @Test
    void distinctOutcomesAreNotEqual() {
        assertThat(ApprovalDecision.APPROVE).isNotEqualTo(ApprovalDecision.REJECT);
        assertThat(ApprovalDecision.APPROVE).isSameAs(ApprovalDecision.APPROVE);
    }
}
