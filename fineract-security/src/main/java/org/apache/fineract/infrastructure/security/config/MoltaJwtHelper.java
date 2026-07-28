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

package org.apache.fineract.infrastructure.security.config;

import com.auth0.jwt.JWT;
import com.auth0.jwt.JWTCreator;
import com.auth0.jwt.algorithms.Algorithm;
import com.auth0.jwt.interfaces.DecodedJWT;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Map;

@Slf4j
@Component
@ConditionalOnProperty("fineract.security.oauth.custom.enabled")
public class MoltaJwtHelper {

    private final RSAPrivateKey privateKey;
    private final RSAPublicKey publicKey;

    @Value("${molta.jwt.hour.duration}")
    private int jwtHourDuration;

    private final JWT jwt;

    public MoltaJwtHelper(RSAPrivateKey privateKey, RSAPublicKey publicKey, JWT jwt) {
        this.privateKey = privateKey;
        this.publicKey = publicKey;
        this.jwt = jwt;
    }

    public String createJwtForClaims(String subject, Map<String, Object> claims) {
        Calendar calendar = Calendar.getInstance();
        calendar.setTimeInMillis(Instant.now().toEpochMilli());
        calendar.add(Calendar.HOUR, jwtHourDuration);

        JWTCreator.Builder jwtBuilder = JWT.create().withSubject(subject);
        for (Map.Entry<String, Object> entry : claims.entrySet()) {
            if (entry.getValue() instanceof String) {
                jwtBuilder.withClaim(entry.getKey(), (String) entry.getValue());
            } else if (entry.getValue() instanceof Integer) {
                jwtBuilder.withClaim(entry.getKey(), (Integer) entry.getValue());
            } else if (entry.getValue() instanceof Long) {
                jwtBuilder.withClaim(entry.getKey(), (Long) entry.getValue());
            } else if (entry.getValue() instanceof Boolean) {
                jwtBuilder.withClaim(entry.getKey(), (Boolean) entry.getValue());
            } else if (entry.getValue() instanceof List<?>) {
                jwtBuilder.withClaim(entry.getKey(), (List<?>) entry.getValue());
            }
        }

        return jwtBuilder
                .withNotBefore(new Date())
                .withExpiresAt(calendar.getTime())
                .sign(Algorithm.RSA256(publicKey, privateKey));
    }

    public boolean isTokenExpired(String token) {
        DecodedJWT payload = jwt.decodeJwt(token);
        LocalDateTime expiryDate = convertToLocalDateTimeViaMiliSecond(payload.getExpiresAt());
        return expiryDate.isBefore(LocalDateTime.now());
    }

    private LocalDateTime convertToLocalDateTimeViaMiliSecond(Date dateToConvert) {
        return Instant.ofEpochMilli(dateToConvert.getTime())
                .atZone(ZoneId.systemDefault())
                .toLocalDateTime();
    }
}