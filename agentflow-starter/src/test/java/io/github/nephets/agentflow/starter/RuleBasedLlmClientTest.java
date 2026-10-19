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
package io.github.nephets.agentflow.starter;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class RuleBasedLlmClientTest {

    private final RuleBasedLlmClient client = new RuleBasedLlmClient();

    @Test
    void summarisesKeyValueLines() {
        String result = client.complete("甲方：ACME\n乙方：Globex\n金额=1500000");

        assertThat(result).startsWith("【本地规则摘要】");
        assertThat(result).contains("甲方=ACME").contains("乙方=Globex").contains("金额=1500000");
        assertThat(result).contains("共 3 项");
        assertThat(client.complete("甲方：ACME\n乙方：Globex\n金额=1500000")).isEqualTo(result);
    }

    @Test
    void handlesBlankAndUnstructuredPrompts() {
        assertThat(client.complete(null)).isEqualTo("（提示词为空，未生成内容）");
        assertThat(client.complete("   ")).isEqualTo("（提示词为空，未生成内容）");
        assertThat(client.complete("审核这份合同")).contains("审核这份合同").contains("共 1 项");
    }
}
