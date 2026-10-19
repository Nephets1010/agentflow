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

import io.github.nephets.agentflow.core.AgentflowTenant;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/** 按请求头把租户 id 写入 {@link AgentflowTenant}，请求结束（含异常）后清除。 */
public class AgentflowTenantFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(AgentflowTenantFilter.class);

    private final AgentflowProperties properties;

    /**
     * 构造过滤器。
     *
     * @param properties 配置属性，提供请求头名称与默认租户
     */
    public AgentflowTenantFilter(AgentflowProperties properties) {
        this.properties = properties;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String header = request.getHeader(properties.getTenantHeader());
        String tenantId = AgentflowProperties.resolveTenantId(header, properties);
        log.debug("doFilter 解析租户: requestUri={}, tenantId={}", request.getRequestURI(), tenantId);
        AgentflowTenant.set(tenantId);
        try {
            chain.doFilter(request, response);
        } catch (ServletException | IOException e) {
            // 不吞异常：原样抛出前记一条带租户的日志，便于定位是哪个租户的请求打到了坏的下游。
            log.error(
                    "doFilter 请求处理失败: requestUri={}, tenantId={}",
                    request.getRequestURI(),
                    tenantId,
                    e);
            throw e;
        } finally {
            AgentflowTenant.clear();
        }
    }
}
