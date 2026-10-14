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

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class InMemoryRunStoreTest {

    private static final Instant NOW = Instant.parse("2026-05-01T00:00:00Z");

    private InMemoryRunStore store;

    private static WorkflowInstance instance(String tenantId, String runId, RunStatus status) {
        WorkflowInstance instance = new WorkflowInstance(runId, tenantId, "wf", 1, NOW);
        instance.setStatus(status);
        return instance;
    }

    @BeforeEach
    void setUp() {
        store = new InMemoryRunStore();
    }

    @Test
    void savesAndFindsByTenantAndRunId() {
        WorkflowInstance saved = instance("acme", "run-1", RunStatus.RUNNING);
        store.save(saved);

        assertThat(store.size()).isEqualTo(1);
        Optional<WorkflowInstance> found = store.findById("acme", "run-1");
        assertThat(found).containsSame(saved);
        assertThat(store.findById("acme", "missing")).isEmpty();
        assertThat(store.findById(null, "run-1")).isEmpty();
        assertThat(store.findById("acme", null)).isEmpty();
    }

    @Test
    void overwritesOnSecondSave() {
        store.save(instance("acme", "run-1", RunStatus.RUNNING));
        store.save(instance("acme", "run-1", RunStatus.SUCCEEDED));

        assertThat(store.size()).isEqualTo(1);
        assertThat(store.findById("acme", "run-1").orElseThrow().getStatus())
                .isEqualTo(RunStatus.SUCCEEDED);
    }

    @Test
    void findsByStatusWithinTenantOnly() {
        store.save(instance("acme", "run-1", RunStatus.RUNNING));
        store.save(instance("acme", "run-2", RunStatus.SUCCEEDED));
        store.save(instance("globex", "run-1", RunStatus.RUNNING));

        assertThat(store.findByStatus("acme", RunStatus.RUNNING)).hasSize(1);
        assertThat(store.findByStatus("acme", RunStatus.SUCCEEDED)).hasSize(1);
        assertThat(store.findByStatus("acme", RunStatus.FAILED)).isEmpty();
        assertThat(store.findByStatus(null, RunStatus.RUNNING)).isEmpty();
        assertThat(store.findByStatus("acme", null)).isEmpty();
    }

    @Test
    void listsPendingApprovalsOfTenant() {
        WorkflowInstance waiting = instance("acme", "run-1", RunStatus.WAITING_APPROVAL);
        waiting.setPendingApproval(PendingApproval.of("apr-1", "run-1", "risk", "请复核", NOW));
        store.save(waiting);
        store.save(instance("acme", "run-2", RunStatus.RUNNING));
        store.save(instance("globex", "run-3", RunStatus.RUNNING));

        assertThat(store.findPendingApprovals("acme"))
                .extracting(PendingApproval::getApprovalId)
                .containsExactly("apr-1");
        assertThat(store.findPendingApprovals("globex")).isEmpty();
        assertThat(store.findPendingApprovals(null)).isEmpty();
    }

    @Test
    void evictsOldestEntryWhenExceedingCapacity() {
        InMemoryRunStore bounded = new InMemoryRunStore(2);

        bounded.save(instance("acme", "run-1", RunStatus.RUNNING));
        bounded.save(instance("acme", "run-2", RunStatus.RUNNING));
        assertThat(bounded.size()).isEqualTo(2);

        // 超出容量，最旧的 run-1 应被淘汰
        bounded.save(instance("acme", "run-3", RunStatus.RUNNING));
        assertThat(bounded.size()).isEqualTo(2);
        assertThat(bounded.findById("acme", "run-1")).isEmpty();
        assertThat(bounded.findById("acme", "run-2")).isPresent();
        assertThat(bounded.findById("acme", "run-3")).isPresent();
    }

    @Test
    void keepsRecentlyAccessedEntryUnderLruEviction() {
        InMemoryRunStore bounded = new InMemoryRunStore(2);

        bounded.save(instance("acme", "run-1", RunStatus.RUNNING));
        bounded.save(instance("acme", "run-2", RunStatus.RUNNING));
        // 访问 run-1，使其成为“最近使用”，run-2 变为最旧
        bounded.findById("acme", "run-1");

        bounded.save(instance("acme", "run-3", RunStatus.RUNNING));
        assertThat(bounded.size()).isEqualTo(2);
        // run-2（最旧且未被访问）被淘汰，run-1 与 run-3 保留
        assertThat(bounded.findById("acme", "run-2")).isEmpty();
        assertThat(bounded.findById("acme", "run-1")).isPresent();
        assertThat(bounded.findById("acme", "run-3")).isPresent();
    }

    @Test
    void rejectsNonPositiveCapacity() {
        assertThatThrownBy(() -> new InMemoryRunStore(0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new InMemoryRunStore(-1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void listsAllAndClears() {
        store.save(instance("acme", "run-1", RunStatus.RUNNING));
        store.save(instance("acme", "run-2", RunStatus.RUNNING));
        store.save(instance("globex", "run-1", RunStatus.RUNNING));

        assertThat(store.findAll("acme")).hasSize(2);
        assertThat(store.findAll(null)).isEmpty();
        store.clear();
        assertThat(store.size()).isZero();
    }
}
