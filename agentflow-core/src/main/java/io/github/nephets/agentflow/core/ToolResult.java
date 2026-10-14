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

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** 工具调用结果：是否成功 + 返回数据 + 错误信息。 */
public final class ToolResult {

    private final boolean ok;
    private final Map<String, Object> data;
    private final String error;

    private ToolResult(boolean ok, Map<String, Object> data, String error) {
        this.ok = ok;
        this.data = data;
        this.error = error;
    }

    /**
     * 构造成功结果。
     *
     * @param data 返回数据，为 {@code null} 时按空表处理
     * @return 结果对象
     */
    public static ToolResult ok(Map<String, Object> data) {
        return new ToolResult(
                true, data == null ? new LinkedHashMap<>() : new LinkedHashMap<>(data), "");
    }

    /**
     * 构造只有一个返回值的成功结果。
     *
     * @param key 返回键
     * @param value 返回值
     * @return 结果对象
     */
    public static ToolResult ok(String key, Object value) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put(key, value);
        return ok(data);
    }

    /** 构造不带返回数据的成功结果。 */
    public static ToolResult ok() {
        return ok(Collections.emptyMap());
    }

    /**
     * 构造失败结果。
     *
     * @param error 错误信息
     * @return 结果对象
     */
    public static ToolResult failure(String error) {
        return new ToolResult(false, new LinkedHashMap<>(), error == null ? "" : error);
    }

    /** 是否调用成功。 */
    public boolean isOk() {
        return ok;
    }

    /** 返回返回数据（不可修改的副本）。 */
    public Map<String, Object> getData() {
        return Collections.unmodifiableMap(data);
    }

    /** 返回错误信息；成功时为空串。 */
    public String getError() {
        return error;
    }

    @Override
    public String toString() {
        return "ToolResult{ok=" + ok + ", data=" + data + ", error='" + error + "'}";
    }
}
