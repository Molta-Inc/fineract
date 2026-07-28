/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements. See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership. The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.fineract.infrastructure.security.filter;

import com.nimbusds.jwt.JWTParser;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import lombok.RequiredArgsConstructor;
import org.apache.fineract.infrastructure.core.service.ThreadLocalContextUtil;
import org.apache.fineract.infrastructure.security.service.AuthTenantDetailsService;
import org.springframework.lang.NonNull;
import org.springframework.security.oauth2.server.resource.web.BearerTokenResolver;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Molta-owned tenant resolution filter. Reads tenant from (in order):
 * 1. JWT {@code tenant} claim in Bearer token
 * 2. {@code Fineract-Platform-TenantId} header (standard Fineract header, required for login)
 * 3. {@code tenantIdentifier} query parameter
 * 4. {@code tenantId} query parameter
 */
@RequiredArgsConstructor
public class MoltaTenantFilter extends OncePerRequestFilter {

    private final BearerTokenResolver resolver;
    private final AuthTenantDetailsService tenantDetailsService;

    @Override
    protected void doFilterInternal(@NonNull HttpServletRequest request, @NonNull HttpServletResponse response,
            @NonNull FilterChain filterChain) throws ServletException, IOException {
        try {
            String tenantId = resolveTenantId(request);
            ThreadLocalContextUtil.setTenant(tenantDetailsService.loadTenantById(tenantId, false));
            filterChain.doFilter(request, response);
        } catch (Exception e) {
            filterChain.doFilter(request, response);
        } finally {
            ThreadLocalContextUtil.reset();
        }
    }

    private String resolveTenantId(HttpServletRequest request) throws Exception {
        String token = resolver.resolve(request);
        if (token != null) {
            var claims = JWTParser.parse(token).getJWTClaimsSet();
            String tenantId = (String) claims.getClaim("tenant");
            if (tenantId != null) {
                return tenantId;
            }
        }
        String tenantId = request.getHeader("Fineract-Platform-TenantId");
        if (tenantId != null) {
            return tenantId;
        }
        tenantId = request.getParameter("tenantIdentifier");
        if (tenantId != null) {
            return tenantId;
        }
        return request.getParameter("tenantId");
    }
}