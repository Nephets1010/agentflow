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
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class StepContextTest {

    private static StepContext ctx() {
        return new StepContext(
                "run-1",
                "acme",
                "risk",
                2,
                new LinkedHashMap<>(),
                Instant.parse("2026-01-01T00:00:00Z"));
    }

    @Test
    void exposesIdentityFields() {
        StepContext ctx = ctx();

        assertThat(ctx.getRunId()).isEqualTo("run-1");
        assertThat(ctx.getTenantId()).isEqualTo("acme");
        assertThat(ctx.getStepId()).isEqualTo("risk");
        assertThat(ctx.getAttempt()).isEqualTo(2);
        assertThat(ctx.getStartedAt()).isEqualTo(Instant.parse("2026-01-01T00:00:00Z"));
    }

    @Test
    void variablesAreReadableAndWritable() {
        StepContext ctx = ctx();
        ctx.put("amount", 1000);

        assertThat(ctx.get("amount")).isEqualTo(1000);
        assertThat(ctx.getVariables()).containsEntry("amount", 1000);
        assertThat(ctx.get("amount", Integer.class)).isEqualTo(1000);
        assertThat(ctx.get("amount", String.class)).isNull();
        assertThat(ctx.get("missing")).isNull();
        assertThat(ctx.put("other", "x")).isSameAs(ctx);
    }

    @Test
    void validatesConstructorArguments() {
        Map<String, Object> variables = new LinkedHashMap<>();

        assertThatThrownBy(() -> new StepContext(null, "acme", "s", 1, variables, Instant.EPOCH))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new StepContext("r", "acme", null, 1, variables, Instant.EPOCH))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new StepContext("r", "acme", "s", 0, variables, Instant.EPOCH))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(new StepContext("r", "acme", "s", 1, variables, null).getStartedAt())
                .isEqualTo(Instant.EPOCH);
    }
}
