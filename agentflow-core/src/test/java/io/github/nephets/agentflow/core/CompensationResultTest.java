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

class CompensationResultTest {

    @Test
    void okCarriesMessage() {
        CompensationResult result = CompensationResult.ok("已撤销风控记录");

        assertThat(result.isOk()).isTrue();
        assertThat(result.getMessage()).isEqualTo("已撤销风控记录");
        assertThat(CompensationResult.ok().getMessage()).isEmpty();
        assertThat(CompensationResult.ok(null).getMessage()).isEmpty();
    }

    @Test
    void failedIsNotOk() {
        CompensationResult result = CompensationResult.failed("风控系统不可用");

        assertThat(result.isOk()).isFalse();
        assertThat(result.getMessage()).isEqualTo("风控系统不可用");
        assertThat(CompensationResult.failed(null).getMessage()).isEmpty();
        assertThat(result.toString()).contains("ok=false");
    }
}
