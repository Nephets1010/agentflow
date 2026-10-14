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
package io.github.nephets.agentflow.starter;

import io.github.nephets.agentflow.core.LlmClient;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * 本地规则版 {@link LlmClient}：不联网、不依赖任何模型 SDK，按模板把提示词里的 “键: 值” 整理成一段摘要。
 *
 * <p>只用于让流程能离线跑通；接真实模型时用自己的实现替换这个 Bean 即可。
 */
public class RuleBasedLlmClient implements LlmClient {

    private static final Logger log = LoggerFactory.getLogger(RuleBasedLlmClient.class);

    private static final int MAX_ITEMS = 5;
    private static final String PREFIX = "【本地规则摘要】";

    @Override
    public String complete(String prompt) {
        log.debug("complete 生成摘要: promptLength={}", prompt == null ? 0 : prompt.length());
        if (prompt == null || prompt.isBlank()) {
            return "（提示词为空，未生成内容）";
        }
        List<String> items = new ArrayList<>();
        for (String line : prompt.split("\\R")) {
            String trimmed = line.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            int separator = separatorIndex(trimmed);
            if (separator > 0) {
                items.add(
                        trimmed.substring(0, separator).trim()
                                + "="
                                + trimmed.substring(separator + 1).trim());
            } else {
                items.add(trimmed);
            }
        }
        if (items.isEmpty()) {
            return PREFIX + "（无可识别内容）";
        }
        StringBuilder summary = new StringBuilder(PREFIX);
        int limit = Math.min(MAX_ITEMS, items.size());
        for (int i = 0; i < limit; i++) {
            if (i > 0) {
                summary.append("；");
            }
            summary.append(items.get(i));
        }
        summary.append("。共 ")
                .append(items.size())
                .append(" 项，提示词 ")
                .append(prompt.length())
                .append(" 字。");
        return summary.toString();
    }

    private static int separatorIndex(String line) {
        int fullWidthColon = line.indexOf('：');
        if (fullWidthColon > 0) {
            return fullWidthColon;
        }
        int colon = line.indexOf(':');
        if (colon > 0) {
            return colon;
        }
        return line.indexOf('=');
    }
}
