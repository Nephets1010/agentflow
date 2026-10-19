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

import io.github.nephets.agentflow.core.InMemoryRunStore;
import io.github.nephets.agentflow.core.LlmClient;
import io.github.nephets.agentflow.core.RunStore;
import io.github.nephets.agentflow.core.ToolRegistry;
import io.github.nephets.agentflow.core.WorkflowEngine;
import io.github.nephets.agentflow.core.WorkflowRegistry;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThat;

class AgentflowAutoConfigurationTest {

    private final ApplicationContextRunner runner =
            new ApplicationContextRunner()
                    .withConfiguration(AutoConfigurations.of(AgentflowAutoConfiguration.class));

    @Test
    void registersCoreBeans() {
        runner.run(
                context -> {
                    assertThat(context).hasSingleBean(WorkflowEngine.class);
                    assertThat(context).hasSingleBean(RunStore.class);
                    assertThat(context).hasSingleBean(ToolRegistry.class);
                    assertThat(context).hasSingleBean(WorkflowRegistry.class);
                    assertThat(context).hasSingleBean(LlmClient.class);
                    assertThat(context.getBean(RunStore.class))
                            .isInstanceOf(InMemoryRunStore.class);
                    assertThat(context.getBean(LlmClient.class))
                            .isInstanceOf(RuleBasedLlmClient.class);
                    assertThat(context.getBean(AgentflowProperties.class).getSnapshotLimit())
                            .isEqualTo(50);
                });
    }

    @Test
    void userBeansWinOverAutoConfiguredOnes() {
        runner.withUserConfiguration(CustomBeans.class)
                .run(
                        context -> {
                            assertThat(context.getBean(RunStore.class)).isSameAs(CustomBeans.STORE);
                            assertThat(context.getBean(LlmClient.class)).isSameAs(CustomBeans.LLM);
                        });
    }

    @Test
    void webBeansAreRegisteredInServletApplications() {
        new WebApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(AgentflowAutoConfiguration.class))
                .run(
                        context -> {
                            assertThat(context).hasSingleBean(WorkflowController.class);
                            assertThat(context).hasSingleBean(AgentflowTenantFilter.class);
                        });
    }

    @Test
    void webBeansAreAbsentWithoutWebApplication() {
        runner.run(
                context -> {
                    assertThat(context).doesNotHaveBean(WorkflowController.class);
                    assertThat(context).doesNotHaveBean(AgentflowTenantFilter.class);
                });
    }

    /** 用户自定义 Bean，用于验证 {@code @ConditionalOnMissingBean}。 */
    @Configuration(proxyBeanMethods = false)
    static class CustomBeans {

        static final RunStore STORE = new InMemoryRunStore();

        static final LlmClient LLM = prompt -> "stub";

        @Bean
        RunStore customRunStore() {
            return STORE;
        }

        @Bean
        LlmClient customLlmClient() {
            return LLM;
        }
    }
}
