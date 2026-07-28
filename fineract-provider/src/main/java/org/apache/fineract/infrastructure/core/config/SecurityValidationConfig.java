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

package org.apache.fineract.infrastructure.core.config;

import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;

@Configuration
public class SecurityValidationConfig {

    @Value("${fineract.security.basicauth.enabled}")
    private Boolean basicAuthEnabled;

    @Value("${fineract.security.oauth2.enabled}")
    private Boolean oauthEnabled;

    @Value("${fineract.security.oauth.custom.enabled}")
    private Boolean oauthCustomEnabled;

    @PostConstruct
    public void validate() {
        // NOTE: avoid NPE if these values are not set
        int enabledCount = 0;
        if (Boolean.TRUE.equals(basicAuthEnabled)) enabledCount++;
        if (Boolean.TRUE.equals(oauthEnabled)) enabledCount++;
        if (Boolean.TRUE.equals(oauthCustomEnabled)) enabledCount++;

        if (enabledCount == 0) {
            throw new IllegalArgumentException(
                    "No authentication scheme selected. Please enable exactly one of: basic, OAuth2, or custom OAuth2 authentication.");
        }

        if (enabledCount > 1) {
            throw new IllegalArgumentException(
                    "Multiple authentication schemes selected. Please enable only one of: basic, OAuth2, or custom OAuth2 authentication.");
        }
    }
}
