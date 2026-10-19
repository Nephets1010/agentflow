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
 * 一次运行（WorkflowInstance）的整体状态。
 *
 * <p><b>为什么 ROLLED_BACK 既是“终态”又能被 resume 继续：</b>{@link #isTerminal} 回答的是“引擎还会不会自发 推进这个运行”——回退后不会；而
 * resume 是显式的人工指令，允许把已回退的运行重新推起来（回退的意义就在于此）。 二者不矛盾：自动路径到此为止，人工路径显式重启。
 */
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

    /**
     * 已按快照回退到某个历史版本。
     *
     * <p>对 {@link #isTerminal} 而言是终态（引擎不再自发推进），但允许被显式 resume 重新启动。
     */
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
