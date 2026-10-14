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

class ToolRegistryTest {

    private static final Tool ECHO =
            new Tool() {
                @Override
                public String name() {
                    return "echo";
                }

                @Override
                public ToolResult invoke(Map<String, Object> args) {
                    return ToolResult.ok("value", args.get("text"));
                }
            };

    private static final Tool BROKEN =
            new Tool() {
                @Override
                public String name() {
                    return "broken";
                }

                @Override
                public ToolResult invoke(Map<String, Object> args) {
                    throw new IllegalStateException("下游不可用");
                }
            };

    private static Tool namedTool(String name) {
        return new Tool() {
            @Override
            public String name() {
                return name;
            }

            @Override
            public ToolResult invoke(Map<String, Object> args) {
                return ToolResult.ok();
            }
        };
    }

    @Test
    void registersAndFindsTools() {
        ToolRegistry registry = new ToolRegistry();
        registry.register(ECHO);

        assertThat(registry.size()).isEqualTo(1);
        assertThat(registry.find("echo")).containsSame(ECHO);
        assertThat(registry.require("echo")).isSameAs(ECHO);
        assertThat(registry.find("missing")).isEmpty();
        assertThat(registry.find(null)).isEmpty();
        assertThat(registry.names()).containsExactly("echo");
    }

    @Test
    void sameNameIsOverwritten() {
        ToolRegistry registry = new ToolRegistry();
        registry.register(ECHO);
        registry.register(ECHO);

        assertThat(registry.size()).isEqualTo(1);
    }

    @Test
    void rejectsBlankToolName() {
        ToolRegistry registry = new ToolRegistry();
        Tool blankName =
                new Tool() {
                    @Override
                    public String name() {
                        return "  ";
                    }

                    @Override
                    public ToolResult invoke(Map<String, Object> args) {
                        return ToolResult.ok();
                    }
                };

        assertThatThrownBy(() -> registry.register(blankName))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> registry.register(null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void invokesToolWithArguments() {
        ToolRegistry registry = new ToolRegistry();
        registry.register(ECHO);

        ToolResult result = registry.invoke("echo", Map.of("text", "hi"));

        assertThat(result.isOk()).isTrue();
        assertThat(result.getData()).containsEntry("value", "hi");
        assertThat(registry.invoke("echo", null).isOk()).isTrue();
    }

    @Test
    void unknownToolThrowsToolNotFound() {
        ToolRegistry registry = new ToolRegistry();

        assertThatThrownBy(() -> registry.invoke("echo", Map.of()))
                .isInstanceOf(ToolNotFoundException.class)
                .hasMessageContaining("echo");
        assertThatThrownBy(() -> registry.require("echo"))
                .isInstanceOf(ToolNotFoundException.class);
    }

    @Test
    void brokenToolIsWrapped() {
        ToolRegistry registry = new ToolRegistry();
        registry.register(BROKEN);

        assertThatThrownBy(() -> registry.invoke("broken", Map.of()))
                .isInstanceOf(ToolInvocationException.class)
                .hasMessageContaining("broken")
                .hasCauseInstanceOf(IllegalStateException.class);
    }

    @Test
    void capacityIsBoundedAndEvictsLeastRecentlyUsed() {
        ToolRegistry registry = new ToolRegistry(2);
        registry.register(ECHO);
        registry.register(BROKEN);

        assertThat(registry.size()).isEqualTo(2);
        // 访问一次 echo，让它变成最近使用，下次淘汰就该轮到 broken。
        assertThat(registry.find("echo")).isPresent();
        registry.register(namedTool("third"));

        assertThat(registry.size()).isEqualTo(2);
        assertThat(registry.names()).containsExactly("echo", "third");
        assertThat(registry.find("broken")).isEmpty();
        assertThat(registry.getMaxCapacity()).isEqualTo(2);
    }

    @Test
    void rejectsNonPositiveCapacity() {
        assertThatThrownBy(() -> new ToolRegistry(0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("maxCapacity");
        assertThat(new ToolRegistry().getMaxCapacity())
                .isEqualTo(ToolRegistry.DEFAULT_MAX_CAPACITY);
    }

    @Test
    void unregistersTools() {
        ToolRegistry registry = new ToolRegistry();
        registry.register(ECHO);

        assertThat(registry.unregister("echo")).isTrue();
        assertThat(registry.unregister("echo")).isFalse();
        assertThat(registry.unregister(null)).isFalse();
        assertThat(registry.size()).isZero();
    }
}
