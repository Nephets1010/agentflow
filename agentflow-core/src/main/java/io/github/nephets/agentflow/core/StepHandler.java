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
 * 步骤处理器：一个步骤要做的具体事情。
 *
 * <p>实现应该是幂等的——同一个步骤可能因重试被调用多次。抛异常与返回 {@link StepResult#failure} 等价， 都会计入重试预算。
 */
@FunctionalInterface
public interface StepHandler {

    /**
     * 执行一步。
     *
     * @param ctx 步骤上下文
     * @return 步骤结果；返回 {@code null} 时按失败处理
     */
    StepResult handle(StepContext ctx);
}
