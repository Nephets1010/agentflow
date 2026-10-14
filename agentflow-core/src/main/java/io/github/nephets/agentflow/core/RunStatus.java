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

/** 一次运行（WorkflowInstance）的整体状态。 */
public enum RunStatus {

    /** 已创建，尚未开始执行。 */
    CREATED,

    /** 正在执行步骤。 */
    RUNNING,

    /** 已挂起，等待人工确认。 */
    WAITING_APPROVAL,

    /** 所有步骤执行成功。 */
    SUCCEEDED,

    /** 有步骤失败，补偿未开始或补偿失败。 */
    FAILED,

    /** 正在对已完成步骤执行逆序补偿。 */
    COMPENSATING,

    /** 失败后补偿全部执行成功。 */
    COMPENSATED,

    /** 已按快照回退到某个历史版本。 */
    ROLLED_BACK;

    /**
     * 判断状态是否为终态（不会再被状态机改写）。
     *
     * @param status 待判断的状态，允许为 {@code null}
     * @return 终态返回 {@code true}；{@code null} 返回 {@code false}
     */
    public static boolean isTerminal(RunStatus status) {
        if (status == null) {
            return false;
        }
        return status == SUCCEEDED
                || status == FAILED
                || status == COMPENSATED
                || status == ROLLED_BACK;
    }
}
