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
package org.apache.fineract.infrastructure.security.api;

import com.google.gson.Gson;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.parameters.RequestBody;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import lombok.RequiredArgsConstructor;
import org.apache.commons.lang3.StringUtils;
import org.apache.fineract.infrastructure.core.config.MoltaJwtHelper;
import org.apache.fineract.infrastructure.core.data.EnumOptionData;
import org.apache.fineract.infrastructure.core.serialization.ToApiJsonSerializer;
import org.apache.fineract.infrastructure.security.constants.TwoFactorConstants;
import org.apache.fineract.infrastructure.security.data.AuthenticatedUserData;
import org.apache.fineract.infrastructure.security.data.CustomAuthenticatedUserData;
import org.apache.fineract.infrastructure.security.service.SpringSecurityPlatformSecurityContext;
import org.apache.fineract.portfolio.client.service.ClientReadPlatformService;
import org.apache.fineract.useradministration.data.RoleData;
import org.apache.fineract.useradministration.domain.AppUser;
import org.apache.fineract.useradministration.domain.Role;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.stream.Collectors;

@Component
@ConditionalOnProperty("fineract.security.oauth.custom.enabled")
@Path("/v1/authentication")
@Tag(name = "Authentication Custom OAuth", description = "An API capability that allows client applications to verify authentication details using Custom OAuth.")
@RequiredArgsConstructor
public class CustomOAuthenticationApiResource {

    @Value("${fineract.security.2fa.enabled}")
    private boolean twoFactorEnabled;

    public static class AuthenticateRequest {
        public String username;
        public String password;
    }

    @Qualifier("customAuthenticationProvider")
    private final DaoAuthenticationProvider customAuthenticationProvider;
    private final ToApiJsonSerializer<AuthenticatedUserData> apiJsonSerializerService;
    private final SpringSecurityPlatformSecurityContext springSecurityPlatformSecurityContext;
    private final ClientReadPlatformService clientReadPlatformService;
    private final MoltaJwtHelper moltaJwtHelper;

    @POST
    @Consumes({ MediaType.APPLICATION_JSON })
    @Produces({ MediaType.APPLICATION_JSON })
    @Operation(summary = "Verify authentication", description = "Authenticates the credentials provided and returns the set roles and permissions allowed.")
    @RequestBody(required = true, content = @Content(schema = @Schema(implementation = AuthenticationApiResourceSwagger.PostAuthenticationRequest.class)))
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "OK", content = @Content(schema = @Schema(implementation = AuthenticationApiResourceSwagger.PostAuthenticationResponse.class))),
            @ApiResponse(responseCode = "400", description = "Unauthenticated. Please login") })
    public String authenticate(@Parameter(hidden = true) final String apiRequestBodyAsJson,
            @QueryParam("returnClientList") @DefaultValue("false") boolean returnClientList) {

        AuthenticateRequest request = new Gson().fromJson(apiRequestBodyAsJson, AuthenticateRequest.class);
        if (request == null) {
            throw new IllegalArgumentException("Invalid JSON in BODY of POST to /custom/authentication: " + apiRequestBodyAsJson);
        }

        if (StringUtils.isBlank(request.username)) {
            throw new IllegalArgumentException("Username is null in JSON of POST to /custom/authentication: " + "; username=" + request.username);
        }

        if (StringUtils.isBlank(request.password)) {
            throw new IllegalArgumentException("Password is null in JSON of POST to /custom/authentication: ");
        }


        final Authentication authentication = new UsernamePasswordAuthenticationToken(request.username, request.password);
        final Authentication authenticationCheck = this.customAuthenticationProvider.authenticate(authentication);

        final Collection<String> permissions = new ArrayList<>();
        CustomAuthenticatedUserData customAuthenticatedUserData = new CustomAuthenticatedUserData().setUsername(request.username).setPermissions(permissions);

        if (authenticationCheck.isAuthenticated()) {
            final Collection<GrantedAuthority> authorities = new ArrayList<>(authenticationCheck.getAuthorities());
            for (final GrantedAuthority grantedAuthority : authorities) {
                permissions.add(grantedAuthority.getAuthority());
            }

            final AppUser principal = (AppUser) authenticationCheck.getPrincipal();

            Map<String, Object> claims = new HashMap<>();
            claims.put("permissions", permissions);
            String accessToken = moltaJwtHelper.createJwtForClaims(request.username, claims);

            final Collection<RoleData> roles = new ArrayList<>();
            final Set<Role> userRoles = principal.getRoles();
            for (final Role role : userRoles) {
                roles.add(role.toData());
            }

            final Long officeId = principal.getOffice().getId();
            final String officeName = principal.getOffice().getName();

            final Long staffId = principal.getStaffId() == null ? 0L : principal.getStaffId();
            final String staffDisplayName = principal.getStaffDisplayName();

            final EnumOptionData organisationalRole = principal.organisationalRoleData();

            boolean isTwoFactorRequired = this.twoFactorEnabled
                    && !principal.hasSpecificPermissionTo(TwoFactorConstants.BYPASS_TWO_FACTOR_PERMISSION);
            Long userId = principal.getId();
            if (this.springSecurityPlatformSecurityContext.doesPasswordHasToBeRenewed(principal)) {
                customAuthenticatedUserData = new CustomAuthenticatedUserData()
                        .setUsername(request.username)
                        .setUserId(userId)
                        .setBearerToken(accessToken)
                        .setAuthenticated(true).setShouldRenewPassword(true).setTwoFactorAuthenticationRequired(isTwoFactorRequired);
            } else {
                customAuthenticatedUserData = new CustomAuthenticatedUserData().setUsername(request.username)
                        .setOfficeId(officeId)
                        .setOfficeName(officeName)
                        .setStaffId(staffId)
                        .setFirstName(principal.getFirstname())
                        .setLastName(principal.getLastname())
                        .setEmail(principal.getEmail())
                        .setStaffDisplayName(staffDisplayName)
                        .setOrganisationalRole(organisationalRole).setRoles(roles).setPermissions(permissions).setUserId(principal.getId())
                        .setAuthenticated(true)
                        .setBearerToken(accessToken)
                        .setTwoFactorAuthenticationRequired(isTwoFactorRequired)
                        .setClients(returnClientList ? clientReadPlatformService.retrieveUserClients(userId) : null);

            }

        }

        return this.apiJsonSerializerService.serialize(customAuthenticatedUserData);
    }
}
