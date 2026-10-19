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
 * 大模型调用接口（零依赖）。
 *
 * <p>core 只定义这一个方法，不绑定任何厂商 SDK；starter 默认注册一个基于规则的本地实现， 生产环境可换成真实模型客户端。
 */
public interface LlmClient {

    /**
     * 对一段提示词生成回复。
     *
     * @param prompt 提示词
     * @return 回复文本
     */
    String complete(String prompt);
}
