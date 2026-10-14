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
 * 补偿执行结果。
 *
 * <p>{@code ok == false} 时引擎会抛 {@link CompensationFailedException}，整条运行停在 {@link RunStatus#FAILED}。
 */
public final class CompensationResult {

    private final boolean ok;
    private final String message;

    private CompensationResult(boolean ok, String message) {
        this.ok = ok;
        this.message = message;
    }

    /**
     * 构造成功结果。
     *
     * @param message 说明文字
     * @return 结果对象
     */
    public static CompensationResult ok(String message) {
        return new CompensationResult(true, message == null ? "" : message);
    }

    /** 构造不带说明的成功结果。 */
    public static CompensationResult ok() {
        return new CompensationResult(true, "");
    }

    /**
     * 构造失败结果。
     *
     * @param message 失败原因
     * @return 结果对象
     */
    public static CompensationResult failed(String message) {
        return new CompensationResult(false, message == null ? "" : message);
    }

    /** 是否补偿成功。 */
    public boolean isOk() {
        return ok;
    }

    /** 返回说明文字。 */
    public String getMessage() {
        return message;
    }

    @Override
    public String toString() {
        return "CompensationResult{ok=" + ok + ", message='" + message + "'}";
    }
}
