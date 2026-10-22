/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.ofbiz.efinance.contract.signature;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.apache.ofbiz.base.util.Debug;
import org.apache.ofbiz.base.util.UtilDateTime;
import org.apache.ofbiz.base.util.UtilProperties;
import org.apache.ofbiz.base.util.UtilValidate;
import org.apache.ofbiz.efinance.security.EfAccessGuard;

/**
 * HTTP adapter for an external electronic-signature provider.
 *
 * <p>The provider receives a JSON signing request and returns
 * {@code {"status":"SIGNED","signedAt":"...","evidenceHash":"..."}} with a 2xx
 * status. The endpoint and API key are configured with the
 * {@code esignature.external.*} properties. This class is intentionally
 * independent of any specific provider.</p>
 */
public class ExternalSignatureAdapter implements ESignatureAdapter {

    private static final String MODULE = ExternalSignatureAdapter.class.getName();
    private static final String RESOURCE = "efinance";
    private static final String METHOD = "EFSIG_METHOD_EXT";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final String endpoint;
    private final String apiKey;
    private final int timeoutSeconds;

    public ExternalSignatureAdapter() {
        this(UtilProperties.getPropertyValue(RESOURCE, "esignature.external.endpoint", ""),
                UtilProperties.getPropertyValue(RESOURCE, "esignature.external.apiKey", ""),
                UtilProperties.getPropertyAsInteger(RESOURCE, "esignature.external.timeout.seconds", 10));
    }

    public ExternalSignatureAdapter(String endpoint, String apiKey, int timeoutSeconds) {
        this.endpoint = endpoint;
        this.apiKey = apiKey;
        this.timeoutSeconds = timeoutSeconds > 0 ? timeoutSeconds : 10;
    }

    @Override
    public SignatureResult sign(SignatureRequest request) throws ESignatureException {
        if (request == null || UtilValidate.isEmpty(request.getSigningSessionId())) {
            throw new ESignatureException("Signing session is required.");
        }
        if (!request.isConfirmed()) {
            return SignatureResult.failure(UtilProperties.getMessage("EfinanceUiLabels",
                    "EfinanceSigningNotConfirmed", request.getLocale()));
        }
        if (UtilValidate.isEmpty(endpoint)) {
            return SignatureResult.failure("External signature endpoint is not configured.");
        }

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("signingSessionId", request.getSigningSessionId());
        payload.put("partyId", request.getPartyId());
        payload.put("draftId", request.getDraftId());
        payload.put("quoteId", request.getQuoteId());
        payload.put("quoteTypeId", request.getQuoteTypeId());
        payload.put("confirmed", true);
        payload.put("ipAddress", request.getIpAddress());
        payload.put("deviceId", request.getDeviceId());

        try {
            String body = MAPPER.writeValueAsString(payload);
            HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(endpoint))
                    .timeout(Duration.ofSeconds(timeoutSeconds))
                    .header("Content-Type", "application/json")
                    .header("Accept", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
            if (UtilValidate.isNotEmpty(apiKey)) {
                builder.header("X-API-Key", apiKey);
            }

            HttpClient client = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(timeoutSeconds))
                    .build();
            HttpResponse<String> response = client.send(builder.build(),
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                return SignatureResult.failure("External signature provider returned HTTP "
                        + response.statusCode() + ".");
            }

            JsonNode node = MAPPER.readTree(response.body());
            String status = node.path("status").asText("");
            if (!"SIGNED".equalsIgnoreCase(status) && !node.path("success").asBoolean(false)) {
                return SignatureResult.failure("External signature provider did not sign the document.");
            }

            Timestamp signedAt = UtilDateTime.nowTimestamp();
            String signedAtText = node.path("signedAt").asText("");
            if (UtilValidate.isNotEmpty(signedAtText)) {
                try {
                    signedAt = Timestamp.from(Instant.parse(signedAtText));
                } catch (RuntimeException e) {
                    Debug.logWarning("Invalid signedAt from signature provider: " + signedAtText, MODULE);
                }
            }
            String providerEvidence = node.path("evidenceHash").asText("");
            if (UtilValidate.isEmpty(providerEvidence)) {
                providerEvidence = body + "|" + response.body();
            }
            String evidence = Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(HexFormat.of().parseHex(EfAccessGuard.sha256(providerEvidence)));
            return SignatureResult.success(METHOD, evidence, signedAt);
        } catch (Exception e) {
            throw new ESignatureException("External signature provider is unavailable: " + e.getMessage(), e);
        }
    }
}
