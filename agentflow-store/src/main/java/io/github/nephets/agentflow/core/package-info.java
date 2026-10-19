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
/**
 * 运行态模型与存储：{@link io.github.nephets.agentflow.core.WorkflowInstance} 聚合根、 {@link
 * io.github.nephets.agentflow.core.RunSnapshot} 快照历史点、{@link
 * io.github.nephets.agentflow.core.RunStore} 存储 SPI 与其内存实现。
 *
 * <p>为什么聚合根与存储放在一起：实例的唯一持久化出口就是 RunStore，存储的容量上限与淘汰策略
 * 直接约束聚合根上快照链的增长，两者必须成对演进；引擎（agentflow-core）只依赖这里的接口与模型， 将来替换 JDBC / Redis 实现时引擎代码零改动。
 */
package io.github.nephets.agentflow.core;
