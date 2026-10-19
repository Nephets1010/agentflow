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

import java.util.Map;

/**
 * 工具（SPI）：步骤可以调用的外部能力，比如查库、发消息、调接口。
 *
 * <p>core 不提供任何具体实现，由使用方实现后注册进 {@link ToolRegistry}。
 */
public interface Tool {

    /**
     * 工具名，注册表内唯一。
     *
     * @return 工具名
     */
    String name();

    /**
     * 调用工具。
     *
     * @param args 入参
     * @return 调用结果
     * @throws RuntimeException 调用失败时抛出，会被 {@link ToolRegistry} 包成 {@link ToolInvocationException}
     */
    ToolResult invoke(Map<String, Object> args);
}
