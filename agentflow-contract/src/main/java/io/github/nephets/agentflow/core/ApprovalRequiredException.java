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

/** 运行已挂起等待人工确认，此时不能直接恢复执行，必须先给出裁决。 */
public class ApprovalRequiredException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /**
     * 构造异常。
     *
     * @param message 说明，通常是审批单 id
     */
    public ApprovalRequiredException(String message) {
        super(message);
    }

    /**
     * 构造异常。
     *
     * @param message 说明
     * @param cause 原因
     */
    public ApprovalRequiredException(String message, Throwable cause) {
        super(message, cause);
    }
}
