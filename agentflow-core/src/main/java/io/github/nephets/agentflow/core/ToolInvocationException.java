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

/** 工具内部抛出了异常，由 {@link ToolRegistry#invoke} 统一包装。 */
public class ToolInvocationException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final String toolName;

    /**
     * 构造异常。
     *
     * @param toolName 工具名
     * @param message 说明
     */
    public ToolInvocationException(String toolName, String message) {
        super("工具调用失败：" + toolName + "，" + message);
        this.toolName = toolName;
    }

    /**
     * 构造异常。
     *
     * @param toolName 工具名
     * @param cause 工具内部抛出的异常
     */
    public ToolInvocationException(String toolName, Throwable cause) {
        super("工具调用失败：" + toolName + "，" + cause.getMessage(), cause);
        this.toolName = toolName;
    }

    /** 返回工具名。 */
    public String getToolName() {
        return toolName;
    }
}
