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

import io.github.nephets.agentflow.core.AgentflowTenant;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;

class AgentflowTenantFilterTest {

    private final List<String> seen = new ArrayList<>();

    private final FilterChain captureTenant =
            (ServletRequest request, ServletResponse response) ->
                    seen.add(String.valueOf(AgentflowTenant.current()));

    @AfterEach
    void tearDown() {
        AgentflowTenant.clear();
    }

    @Test
    void readsTenantFromConfiguredHeader() throws Exception {
        AgentflowProperties properties = new AgentflowProperties();
        AgentflowTenantFilter filter = new AgentflowTenantFilter(properties);
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-Tenant-Id", "acme");

        filter.doFilter(request, new MockHttpServletResponse(), captureTenant);

        assertThat(seen).containsExactly("acme");
        assertThat(AgentflowTenant.current()).isNull();
    }

    @Test
    void fallsBackToDefaultTenant() throws Exception {
        AgentflowProperties properties = new AgentflowProperties();
        properties.setDefaultTenantId("anonymous");
        AgentflowTenantFilter filter = new AgentflowTenantFilter(properties);

        filter.doFilter(new MockHttpServletRequest(), new MockHttpServletResponse(), captureTenant);

        assertThat(seen).containsExactly("anonymous");

        properties.setDefaultTenantId("  ");
        filter.doFilter(new MockHttpServletRequest(), new MockHttpServletResponse(), captureTenant);
        assertThat(seen).containsExactly("anonymous", "default");
    }
}
