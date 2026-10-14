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

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ToolResultTest {

    @Test
    void okCarriesData() {
        ToolResult result = ToolResult.ok(Map.of("code", 200));

        assertThat(result.isOk()).isTrue();
        assertThat(result.getData()).containsEntry("code", 200);
        assertThat(result.getError()).isEmpty();
        assertThat(ToolResult.ok("k", "v").getData()).containsEntry("k", "v");
        assertThat(ToolResult.ok().getData()).isEmpty();
        assertThat(ToolResult.ok(null).getData()).isEmpty();
    }

    @Test
    void failureCarriesErrorAndDataIsCopied() {
        ToolResult result = ToolResult.failure("超时");

        assertThat(result.isOk()).isFalse();
        assertThat(result.getError()).isEqualTo("超时");
        assertThat(result.getData()).isEmpty();
        assertThat(ToolResult.failure(null).getError()).isEmpty();

        java.util.Map<String, Object> data = new java.util.LinkedHashMap<>();
        data.put("k", "v");
        ToolResult copied = ToolResult.ok(data);
        data.put("k", "changed");
        assertThat(copied.getData()).containsEntry("k", "v");
        assertThatThrownBy(() -> copied.getData().clear())
                .isInstanceOf(UnsupportedOperationException.class);
    }
}
