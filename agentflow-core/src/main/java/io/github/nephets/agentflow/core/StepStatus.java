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

/** 单个步骤的状态。一次运行中每个步骤在状态机里只会落在其中一个取值上。 */
public enum StepStatus {

    /** 已创建但尚未开始执行。 */
    PENDING,

    /** 正在执行。 */
    RUNNING,

    /** 已挂起，等待人工确认。 */
    WAITING_APPROVAL,

    /** 执行成功。 */
    SUCCEEDED,

    /** 执行失败，含重试预算耗尽的情况。 */
    FAILED,

    /** 步骤自行决定跳过，不算失败。 */
    SKIPPED,

    /** 单步执行超时，且按重试策略不再继续尝试。 */
    TIMED_OUT,

    /** 该步骤的补偿动作已执行完成。 */
    COMPENSATED;

    /**
     * 判断状态是否为终态（不会再被状态机改写）。
     *
     * @param status 待判断的状态，允许为 {@code null}
     * @return 终态返回 {@code true}；{@code null} 返回 {@code false}
     */
    public static boolean isTerminal(StepStatus status) {
        if (status == null) {
            return false;
        }
        return status == SUCCEEDED
                || status == FAILED
                || status == SKIPPED
                || status == TIMED_OUT
                || status == COMPENSATED;
    }
}
