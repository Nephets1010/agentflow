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

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AgentflowTenantTest {

    @AfterEach
    void tearDown() {
        AgentflowTenant.clear();
    }

    @Test
    void setCurrentAndClear() {
        assertThat(AgentflowTenant.isSet()).isFalse();
        assertThat(AgentflowTenant.current()).isNull();

        AgentflowTenant.set("acme");

        assertThat(AgentflowTenant.isSet()).isTrue();
        assertThat(AgentflowTenant.current()).isEqualTo("acme");
        assertThat(AgentflowTenant.require()).isEqualTo("acme");

        AgentflowTenant.clear();
        assertThat(AgentflowTenant.current()).isNull();
    }

    @Test
    void rejectsBlankTenant() {
        assertThatThrownBy(() -> AgentflowTenant.set(null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> AgentflowTenant.set("  "))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void requireThrowsWhenUnset() {
        assertThatThrownBy(AgentflowTenant::require).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void runWithRestoresPreviousTenant() {
        AgentflowTenant.set("outer");
        AtomicReference<String> inside = new AtomicReference<>();

        AgentflowTenant.runWith("inner", () -> inside.set(AgentflowTenant.current()));

        assertThat(inside.get()).isEqualTo("inner");
        assertThat(AgentflowTenant.current()).isEqualTo("outer");
    }

    @Test
    void runWithRestoresEmptyTenant() {
        AtomicReference<String> inside = new AtomicReference<>();
        AgentflowTenant.runWith("inner", () -> inside.set(AgentflowTenant.current()));

        assertThat(inside.get()).isEqualTo("inner");
        assertThat(AgentflowTenant.current()).isNull();
    }

    @Test
    void callWithReturnsValueAndRestoresContext() throws Exception {
        AgentflowTenant.set("outer");

        String result = AgentflowTenant.callWith("inner", AgentflowTenant::current);

        assertThat(result).isEqualTo("inner");
        assertThat(AgentflowTenant.current()).isEqualTo("outer");
        assertThatThrownBy(
                        () ->
                                AgentflowTenant.callWith(
                                        "inner",
                                        () -> {
                                            throw new IllegalStateException("boom");
                                        }))
                .isInstanceOf(IllegalStateException.class);
        assertThat(AgentflowTenant.current()).isEqualTo("outer");
    }

    @Test
    void rejectsNullTask() {
        assertThatThrownBy(() -> AgentflowTenant.runWith("acme", (Runnable) null))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> AgentflowTenant.callWith("acme", null))
                .isInstanceOf(NullPointerException.class);
        assertThat(AgentflowTenant.current()).isNull();
    }

    @Test
    void nestedTenantIsInheritedByChildThread() throws Exception {
        AtomicReference<String> inside = new AtomicReference<>();
        AgentflowTenant.set("acme");
        Thread child = new Thread(() -> inside.set(AgentflowTenant.current()));
        child.start();
        child.join();

        assertThat(inside.get()).isEqualTo("acme");
    }
}
