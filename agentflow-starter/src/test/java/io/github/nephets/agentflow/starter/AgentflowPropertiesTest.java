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

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class AgentflowPropertiesTest {

    @Test
    void defaultsAreReadyToUse() {
        AgentflowProperties properties = new AgentflowProperties();

        assertThat(properties.getTenantHeader()).isEqualTo("X-Tenant-Id");
        assertThat(properties.getDefaultTenantId()).isEqualTo("default");
        assertThat(properties.getDefaultStepTimeout()).isEqualTo(Duration.ofSeconds(30));
        assertThat(properties.getDefaultMaxAttempts()).isEqualTo(3);
        assertThat(properties.getSnapshotLimit()).isEqualTo(50);
    }

    @Test
    void valuesCanBeOverridden() {
        AgentflowProperties properties = new AgentflowProperties();
        properties.setTenantHeader("X-Org-Id");
        properties.setDefaultTenantId("anonymous");
        properties.setDefaultStepTimeout(Duration.ofSeconds(5));
        properties.setDefaultMaxAttempts(5);
        properties.setSnapshotLimit(10);

        assertThat(properties.getTenantHeader()).isEqualTo("X-Org-Id");
        assertThat(properties.getDefaultTenantId()).isEqualTo("anonymous");
        assertThat(properties.getDefaultStepTimeout()).isEqualTo(Duration.ofSeconds(5));
        assertThat(properties.getDefaultMaxAttempts()).isEqualTo(5);
        assertThat(properties.getSnapshotLimit()).isEqualTo(10);
    }

    @Test
    void invalidValuesFallBackToSafeDefaults() {
        AgentflowProperties properties = new AgentflowProperties();
        properties.setTenantHeader("  ");
        properties.setDefaultStepTimeout(null);
        properties.setDefaultMaxAttempts(0);
        properties.setSnapshotLimit(-1);

        assertThat(properties.getTenantHeader())
                .isEqualTo(AgentflowProperties.DEFAULT_TENANT_HEADER);
        assertThat(properties.getDefaultStepTimeout())
                .isEqualTo(AgentflowProperties.DEFAULT_STEP_TIMEOUT);
        assertThat(properties.getDefaultMaxAttempts())
                .isEqualTo(AgentflowProperties.DEFAULT_MAX_ATTEMPTS);
        assertThat(properties.getSnapshotLimit())
                .isEqualTo(AgentflowProperties.DEFAULT_SNAPSHOT_LIMIT);
    }

    @Test
    void resolveTenantIdPrefersExplicitValueThenConfiguredDefault() {
        AgentflowProperties properties = new AgentflowProperties();
        properties.setDefaultTenantId("acme");

        assertThat(AgentflowProperties.resolveTenantId(" globex ", properties)).isEqualTo("globex");
        assertThat(AgentflowProperties.resolveTenantId(null, properties)).isEqualTo("acme");
        assertThat(AgentflowProperties.resolveTenantId("  ", properties)).isEqualTo("acme");

        properties.setDefaultTenantId("  ");
        assertThat(AgentflowProperties.resolveTenantId(null, properties))
                .isEqualTo(AgentflowProperties.DEFAULT_TENANT_ID);
        assertThat(AgentflowProperties.resolveTenantId(null, null))
                .isEqualTo(AgentflowProperties.DEFAULT_TENANT_ID);
    }
}
