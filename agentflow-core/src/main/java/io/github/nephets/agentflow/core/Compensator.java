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

/**
 * 补偿器：某个步骤成功后留下的副作用要怎么撤销。
 *
 * <p>引擎在流程失败时，按步骤定义的逆序对状态为 {@link StepStatus#SUCCEEDED} 且带补偿器的步骤调用它。
 */
@FunctionalInterface
public interface Compensator {

    /**
     * 执行补偿。
     *
     * @param ctx 补偿上下文
     * @return 补偿结果；返回 {@code null} 或 {@code ok == false} 都会让流程失败
     */
    CompensationResult compensate(StepContext ctx);
}
