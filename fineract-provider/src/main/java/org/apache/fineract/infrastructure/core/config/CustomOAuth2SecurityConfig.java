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

import com.auth0.jwt.JWT;
import org.apache.fineract.infrastructure.businessdate.service.BusinessDateReadPlatformService;
import org.apache.fineract.infrastructure.cache.service.CacheWritePlatformService;
import org.apache.fineract.infrastructure.configuration.domain.ConfigurationDomainService;
import org.springframework.security.oauth2.server.resource.web.BearerTokenAuthenticationEntryPoint;
import org.apache.fineract.infrastructure.core.serialization.ToApiJsonSerializer;
import org.apache.fineract.infrastructure.security.data.FineractJwtAuthenticationToken;
import org.apache.fineract.infrastructure.security.data.PlatformRequestLog;

import org.apache.fineract.infrastructure.security.filter.BusinessDateFilter;
import org.apache.fineract.infrastructure.security.filter.MoltaTenantFilter;
import org.apache.fineract.infrastructure.security.filter.TwoFactorAuthenticationFilter;
import org.apache.fineract.infrastructure.security.service.AuthTenantDetailsService;
import org.apache.fineract.infrastructure.security.service.TenantAwareJpaPlatformUserDetailsService;
import org.apache.fineract.infrastructure.security.service.TwoFactorService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.web.ServerProperties;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.convert.converter.Converter;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.ProviderManager;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2ErrorCodes;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.www.BasicAuthenticationEntryPoint;
import org.springframework.security.web.authentication.www.BasicAuthenticationFilter;
import org.springframework.security.web.context.SecurityContextHolderFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.security.interfaces.RSAPublicKey;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.web.authentication.AuthenticationFilter;

import org.springframework.security.config.Customizer;

import static org.springframework.security.authorization.AuthenticatedAuthorizationManager.fullyAuthenticated;
import static org.springframework.security.authorization.AuthorityAuthorizationManager.hasAuthority;
import static org.springframework.security.authorization.AuthorizationManagers.allOf;
import static org.springframework.security.web.util.matcher.AntPathRequestMatcher.antMatcher;

@Configuration
@ConditionalOnProperty("fineract.security.oauth.custom.enabled")
@EnableMethodSecurity
public class CustomOAuth2SecurityConfig {

    @Autowired
    private TenantAwareJpaPlatformUserDetailsService userDetailsService;

    @Autowired
    private ServerProperties serverProperties;

    @Autowired
    private FineractProperties fineractProperties;

    @Autowired
    private AuthTenantDetailsService basicAuthTenantDetailsService;

    @Autowired
    private ToApiJsonSerializer<PlatformRequestLog> toApiJsonSerializer;

    @Autowired
    private ConfigurationDomainService configurationDomainService;

    @Autowired
    private CacheWritePlatformService cacheWritePlatformService;

    @Autowired
    private BusinessDateReadPlatformService businessDateReadPlatformService;

    @Autowired
    private ApplicationContext applicationContext;

    private static final JwtGrantedAuthoritiesConverter jwtGrantedAuthoritiesConverter = new JwtGrantedAuthoritiesConverter();

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http //
                .securityMatcher(antMatcher("/api/**")).authorizeHttpRequests((auth) -> {
                    auth.requestMatchers(antMatcher(HttpMethod.OPTIONS, "/api/**")).permitAll() //
                            .requestMatchers(antMatcher(HttpMethod.POST, "/api/*/echo")).permitAll() //
                            .requestMatchers(antMatcher(HttpMethod.POST, "/api/*/authentication")).permitAll() //
                            .requestMatchers(antMatcher(HttpMethod.POST, "/api/*/custom/authentication")).permitAll() //
                            .requestMatchers(antMatcher(HttpMethod.POST, "/api/*/self/authentication")).permitAll() //
                            .requestMatchers(antMatcher(HttpMethod.POST, "/api/*/self/registration")).permitAll() //
                            .requestMatchers(antMatcher(HttpMethod.POST, "/api/*/self/registration/user")).permitAll() //
                            .requestMatchers(antMatcher(HttpMethod.POST, "/api/*/twofactor/validate")).fullyAuthenticated() //
                            .requestMatchers(antMatcher("/api/*/twofactor")).fullyAuthenticated() //
                            .requestMatchers(antMatcher("/api/**"))
                            .access(allOf(fullyAuthenticated(), hasAuthority("TWOFACTOR_AUTHENTICATED"))); //
                }).csrf((csrf) -> csrf.disable()) // NOSONAR only creating a service that is used by non-browser clients
                .exceptionHandling((ehc) -> ehc.authenticationEntryPoint(new BearerTokenAuthenticationEntryPoint()))
                .oauth2ResourceServer(oauth2 -> oauth2.jwt(jwt -> jwt.jwtAuthenticationConverter(authenticationConverter()))
                        .authenticationEntryPoint(new BearerTokenAuthenticationEntryPoint())) //
                .sessionManagement((smc) -> smc.sessionCreationPolicy(SessionCreationPolicy.STATELESS)) //
                .cors(Customizer.withDefaults()) //
                .addFilterAfter(tenantAwareTenantIdentifierFilter(), SecurityContextHolderFilter.class)
                .addFilterAfter(businessDateFilter(), MoltaTenantFilter.class);

        if (fineractProperties.getSecurity().getTwoFactor().isEnabled()) {
            http.addFilterAfter(twoFactorAuthenticationFilter(), BasicAuthenticationFilter.class);
        } else {
            http.addFilterAfter((request, response, chain) -> {
                var ctx = org.springframework.security.core.context.SecurityContextHolder.getContext();
                var auth = ctx == null ? null : ctx.getAuthentication();
                if (auth != null && auth.isAuthenticated()) {
                    List<org.springframework.security.core.GrantedAuthority> updated = new ArrayList<>(auth.getAuthorities());
                    updated.add(new SimpleGrantedAuthority("TWOFACTOR_AUTHENTICATED"));
                    ctx.setAuthentication(new org.springframework.security.authentication.UsernamePasswordAuthenticationToken(
                            auth.getPrincipal(), auth.getCredentials(), updated));
                }
                chain.doFilter(request, response);
            }, BasicAuthenticationFilter.class);
        }

        if (serverProperties.getSsl().isEnabled()) {
            http.requiresChannel(channel -> channel.requestMatchers(antMatcher("/api/**")).requiresSecure());
        }

        return http.build();
    }

    public MoltaTenantFilter tenantAwareTenantIdentifierFilter() {
        return new MoltaTenantFilter(new org.springframework.security.oauth2.server.resource.web.DefaultBearerTokenResolver(), basicAuthTenantDetailsService);
    }

    public BusinessDateFilter businessDateFilter() {
        return new BusinessDateFilter(businessDateReadPlatformService);
    }

    public TwoFactorAuthenticationFilter twoFactorAuthenticationFilter() {
        TwoFactorService twoFactorService = applicationContext.getBean(TwoFactorService.class);
        return new TwoFactorAuthenticationFilter(twoFactorService);
    }



    @Bean
    public BasicAuthenticationEntryPoint basicAuthenticationEntryPoint() {
        BasicAuthenticationEntryPoint basicAuthenticationEntryPoint = new BasicAuthenticationEntryPoint();
        basicAuthenticationEntryPoint.setRealmName("Fineract Platform API");
        return basicAuthenticationEntryPoint;
    }

    @Bean(name = "customAuthenticationProvider")
    public DaoAuthenticationProvider authProvider() {
        DaoAuthenticationProvider authProvider = new DaoAuthenticationProvider();
        authProvider.setUserDetailsService(userDetailsService);
        authProvider.setPasswordEncoder(passwordEncoder());
        return authProvider;
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return PasswordEncoderFactories.createDelegatingPasswordEncoder();
    }

    private Converter<Jwt, FineractJwtAuthenticationToken> authenticationConverter() {
        return jwt -> {
            try {
                UserDetails user = userDetailsService.loadUserByUsername(jwt.getSubject());
                jwtGrantedAuthoritiesConverter.setAuthorityPrefix("");
                jwtGrantedAuthoritiesConverter.setAuthoritiesClaimName("permissions");
                Collection<GrantedAuthority> authorities = jwtGrantedAuthoritiesConverter.convert(jwt);
                return new FineractJwtAuthenticationToken(jwt, authorities, user);
            } catch (UsernameNotFoundException ex) {
                throw new OAuth2AuthenticationException(new OAuth2Error(OAuth2ErrorCodes.INVALID_TOKEN), ex);
            }
        };
    }

    @Bean
    public AuthenticationManager authenticationManagerBean() throws Exception {
        ProviderManager providerManager = new ProviderManager(authProvider());
        providerManager.setEraseCredentialsAfterAuthentication(false);
        return providerManager;
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration configuration = new CorsConfiguration();
        configuration.setAllowedOriginPatterns(List.of("*"));
        configuration.setAllowedMethods(List.of("*"));
        configuration.setAllowCredentials(true);
        configuration.setAllowedHeaders(List.of("*"));
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", configuration);
        return source;
    }

    @Bean
    public JWT jwt() {
        return new JWT();
    }

    @Bean
    public JwtDecoder jwtDecoder(RSAPublicKey rsaPublicKey) {
        return NimbusJwtDecoder.withPublicKey(rsaPublicKey).build();
    }

}
