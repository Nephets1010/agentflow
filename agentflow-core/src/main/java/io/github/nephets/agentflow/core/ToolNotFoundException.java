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

/** 按名字在 {@link ToolRegistry} 里找不到工具。 */
public class ToolNotFoundException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final String toolName;

    /**
     * 构造异常。
     *
     * @param toolName 工具名
     */
    public ToolNotFoundException(String toolName) {
        super("工具未注册：" + toolName);
        this.toolName = toolName;
    }

    /** 返回工具名。 */
    public String getToolName() {
        return toolName;
    }
}
