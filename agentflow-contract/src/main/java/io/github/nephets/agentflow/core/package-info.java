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
/**
 * 公共契约：异常类型、运行与步骤状态枚举、人工裁决枚举。
 *
 * <p>为什么单独成模块且零依赖：这些类型被 core、store、starter 同时引用，是“状态机取值与异常签名”的唯一出处。 它们被引用的面最广，却最不该有传递依赖——连 slf4j
 * 都不引入，避免一个纯数据契约把日志门面 塞进每个使用方的依赖树。core 与 store 沿用同一包名 {@code io.github.nephets.agentflow.core}，
 * 对使用方保持“一个包”的观感，public 签名与包名不因拆分而变化。
 */
package io.github.nephets.agentflow.core;
