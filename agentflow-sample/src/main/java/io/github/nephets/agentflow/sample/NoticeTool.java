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
package io.github.nephets.agentflow.sample;

import io.github.nephets.agentflow.core.Tool;
import io.github.nephets.agentflow.core.ToolResult;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 示例工具：把通知发到内存队列里，便于观察流程是否真的通知成功。
 *
 * <p>core 不提供任何工具实现，这里演示一个最小可用的 {@link Tool}。
 *
 * <p>{@code sent} 是本工具的长期存活字段，示例进程跑多久就会积累多少条通知，因此设了保留上限 {@value
 * #MAX_RETAINED}，超出时丢掉最旧的。这只影响演示用的历史列表，已发出的通知不受影响； 返回值里的 {@code total} 是 “当前保留条数”，到达上限后不再增长。
 */
public class NoticeTool implements Tool {

    private static final Logger log = LoggerFactory.getLogger(NoticeTool.class);

    /** 工具名。 */
    public static final String NAME = "send-notice";

    /** 保留的最近通知条数上限，超出时丢弃最旧的。 */
    public static final int MAX_RETAINED = 256;

    private final List<String> sent = new ArrayList<>();

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public ToolResult invoke(Map<String, Object> args) {
        Map<String, Object> safeArgs = args == null ? Map.of() : args;
        Object risk = safeArgs.get("risk");
        if (risk == null) {
            log.warn("invoke 缺少 risk 参数: args={}", safeArgs.keySet());
            return ToolResult.failure("缺少 risk 参数");
        }
        String message = "风险等级=" + risk + "，运行=" + safeArgs.get("runId");
        log.info("invoke 发送通知: message={}", message);
        retain(message);
        return ToolResult.ok(Map.of("message", message, "total", sent.size()));
    }

    /** 返回已发出的通知，用于断言与演示输出。 */
    public List<String> sentMessages() {
        return List.copyOf(sent);
    }

    /** 追加一条通知并保持列表不超过 {@link #MAX_RETAINED}，淘汰时留一条 warn 便于发现丢历史。 */
    private void retain(String message) {
        sent.add(message);
        if (sent.size() <= MAX_RETAINED) {
            return;
        }
        String dropped = sent.remove(0);
        log.warn("retain 通知历史已达上限，丢弃最旧一条: maxRetained={}, dropped={}", MAX_RETAINED, dropped);
    }
}
