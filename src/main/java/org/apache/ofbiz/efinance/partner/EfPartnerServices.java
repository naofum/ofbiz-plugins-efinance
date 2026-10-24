/*******************************************************************************
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
 *******************************************************************************/
package org.apache.ofbiz.efinance.partner;

import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.apache.ofbiz.base.util.Debug;
import org.apache.ofbiz.base.util.UtilDateTime;
import org.apache.ofbiz.base.util.UtilProperties;
import org.apache.ofbiz.base.util.UtilValidate;
import org.apache.ofbiz.entity.Delegator;
import org.apache.ofbiz.entity.GenericEntityException;
import org.apache.ofbiz.entity.GenericValue;
import org.apache.ofbiz.efinance.security.EfRequestGuard;
import org.apache.ofbiz.service.DispatchContext;
import org.apache.ofbiz.service.ServiceUtil;

/**
 * STEP W9: partner handoff and application attribution.
 *
 * <p>The partner receives a signed, time-limited token. The token carries the
 * partner ID and an allow-listed return URL. The customer portal validates the
 * token without authentication, stores it for the browser session, and sends
 * it when creating an application draft. efinance stores the verified partner
 * ID on the draft and on the submitted application link.</p>
 */
public final class EfPartnerServices {

    private static final String MODULE = EfPartnerServices.class.getName();
    private static final String RESOURCE = "EfinanceUiLabels";
    private static final String PROPS = "efinance";
    private static final String PARTNER_PREFIX = "efinance.partner.";
    private static final String TOKEN_VERSION = "1";
    private static final String HMAC_ALGORITHM = "HmacSHA256";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final String ACTION_ISSUE = "EFAUD_PARTNER_ISSUE";
    private static final String RESULT_SUCCESS = "EFAUDRES_SUCCESS";

    private EfPartnerServices() {
    }

    // =========================================================================
    // efIssuePartnerHandoff
    // =========================================================================

    public static Map<String, Object> efIssuePartnerHandoff(DispatchContext dctx,
            Map<String, Object> context) {
        Delegator delegator = dctx.getDelegator();
        Locale locale = (Locale) context.get("locale");
        String partnerId = string(context, "partnerId");
        String partnerSecret = string(context, "partnerSecret");
        String requestedReturnUrl = string(context, "returnUrl");
        String ipAddress = string(context, "ipAddress");

        if (!isValidPartnerId(partnerId)) {
            return ServiceUtil.returnError(message(locale, "EfinancePartnerInvalidCredentials"));
        }
        PartnerConfig config = loadPartner(partnerId);
        if (!config.active || UtilValidate.isEmpty(config.secret)
                || UtilValidate.isEmpty(partnerSecret)
                || !constantTimeEquals(partnerSecret, config.secret)) {
            return ServiceUtil.returnError(message(locale, "EfinancePartnerInvalidCredentials"));
        }

        String returnUrl;
        try {
            returnUrl = resolveReturnUrl(config, requestedReturnUrl);
        } catch (IllegalArgumentException e) {
            return ServiceUtil.returnError(message(locale, "EfinancePartnerReturnUrlInvalid"));
        }
        if (UtilValidate.isEmpty(returnUrl)) {
            return ServiceUtil.returnError(message(locale, "EfinancePartnerReturnUrlInvalid"));
        }

        try {
            int limit = UtilProperties.getPropertyAsInteger(PROPS,
                    "efinance.rate.limit.partner.handoff", 30);
            int window = UtilProperties.getPropertyAsInteger(PROPS,
                    "efinance.rate.limit.partner.handoff.window.seconds", 3600);
            String rateSubject = UtilValidate.isNotEmpty(ipAddress) ? ipAddress : partnerId;
            if (!EfRequestGuard.checkRateLimit(delegator, "PARTNER_HANDOFF", rateSubject, limit, window)) {
                return ServiceUtil.returnError(message(locale, "EfinanceRateLimited"));
            }

            int expireMinutes = UtilProperties.getPropertyAsInteger(PROPS,
                    "efinance.partner.handoff.expire.minutes", 1440);
            if (expireMinutes <= 0) {
                expireMinutes = 1440;
            }
            Timestamp now = UtilDateTime.nowTimestamp();
            Timestamp expiresAt = new Timestamp(now.getTime() + expireMinutes * 60_000L);
            String token = createToken(config, returnUrl, now, expireMinutes);
            String portalUrl = UtilProperties.getPropertyValue(PROPS,
                    "efinance.portal.url", "https://localhost:8443/portal/control/main");
            String separator = portalUrl.contains("?") ? "&" : "?";
            String handoffUrl = portalUrl + separator + "partnerToken="
                    + URLEncoder.encode(token, StandardCharsets.UTF_8) + "#apply";

            writeAudit(delegator, partnerId, now);
            Map<String, Object> result = ServiceUtil.returnSuccess();
            result.put("partnerId", partnerId);
            result.put("partnerName", config.name);
            result.put("handoffToken", token);
            result.put("handoffUrl", handoffUrl);
            result.put("expiresAt", expiresAt);
            return result;
        } catch (GenericEntityException e) {
            Debug.logError(e, "efIssuePartnerHandoff failed", MODULE);
            return ServiceUtil.returnError(message(locale, "EfinancePartnerOperationFailed"));
        } catch (Exception e) {
            Debug.logError(e, "Could not create partner handoff token", MODULE);
            return ServiceUtil.returnError(message(locale, "EfinancePartnerOperationFailed"));
        }
    }

    // =========================================================================
    // efValidatePartnerHandoff
    // =========================================================================

    public static Map<String, Object> efValidatePartnerHandoff(DispatchContext dctx,
            Map<String, Object> context) {
        Delegator delegator = dctx.getDelegator();
        Locale locale = (Locale) context.get("locale");
        String token = string(context, "handoffToken");
        Map<String, Object> validation = validateHandoffToken(delegator, token, locale);
        if (ServiceUtil.isError(validation)) {
            return validation;
        }
        Map<String, Object> result = ServiceUtil.returnSuccess();
        result.put("partnerId", validation.get("partnerId"));
        result.put("partnerName", validation.get("partnerName"));
        result.put("returnUrl", validation.get("returnUrl"));
        result.put("expiresAt", validation.get("expiresAt"));
        return result;
    }

    /**
     * Validate a handoff token for the portal and application service.
     * Returns a success map with partnerId/partnerName/returnUrl/expiresAt,
     * or an error map. Never accept a partner ID supplied separately by the
     * client; callers must use the verified partner ID from this method.
     */
    public static Map<String, Object> validateHandoffToken(Delegator delegator, String token,
            Locale locale) {
        if (UtilValidate.isEmpty(token)) {
            return ServiceUtil.returnError(message(locale, "EfinancePartnerInvalidToken"));
        }
        String[] parts = token.split("\\.", -1);
        if (parts.length != 2) {
            return ServiceUtil.returnError(message(locale, "EfinancePartnerInvalidToken"));
        }
        try {
            byte[] payloadBytes = Base64.getUrlDecoder().decode(parts[0]);
            Map<String, Object> payload = MAPPER.readValue(payloadBytes,
                    new TypeReference<Map<String, Object>>() { });
            String partnerId = payload.get("pid") == null ? null : payload.get("pid").toString();
            if (!isValidPartnerId(partnerId)) {
                return ServiceUtil.returnError(message(locale, "EfinancePartnerInvalidToken"));
            }
            PartnerConfig config = loadPartner(partnerId);
            if (!config.active || UtilValidate.isEmpty(config.secret)) {
                return ServiceUtil.returnError(message(locale, "EfinancePartnerInvalidToken"));
            }
            String expected = sign(parts[0], config.secret);
            if (!MessageDigest.isEqual(parts[1].getBytes(StandardCharsets.US_ASCII),
                    expected.getBytes(StandardCharsets.US_ASCII))) {
                return ServiceUtil.returnError(message(locale, "EfinancePartnerInvalidToken"));
            }
            if (!TOKEN_VERSION.equals(String.valueOf(payload.get("v")))) {
                return ServiceUtil.returnError(message(locale, "EfinancePartnerInvalidToken"));
            }
            Number expires = (Number) payload.get("exp");
            if (expires == null || expires.longValue() * 1000L < System.currentTimeMillis()) {
                return ServiceUtil.returnError(message(locale, "EfinancePartnerInvalidToken"));
            }
            String returnUrl = payload.get("ret") == null ? null : payload.get("ret").toString();
            if (!isAllowedReturnUrl(config, returnUrl)) {
                return ServiceUtil.returnError(message(locale, "EfinancePartnerReturnUrlInvalid"));
            }
            Map<String, Object> result = ServiceUtil.returnSuccess();
            result.put("partnerId", config.partnerId);
            result.put("partnerName", config.name);
            result.put("returnUrl", returnUrl);
            result.put("expiresAt", new Timestamp(expires.longValue() * 1000L));
            return result;
        } catch (Exception e) {
            Debug.logWarning("Invalid partner handoff token: " + e.getMessage(), MODULE);
            return ServiceUtil.returnError(message(locale, "EfinancePartnerInvalidToken"));
        }
    }

    // =========================================================================
    // Token helpers
    // =========================================================================

    private static String createToken(PartnerConfig config, String returnUrl, Timestamp now,
            int expireMinutes) throws Exception {
        long issuedAt = now.getTime() / 1000L;
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("v", TOKEN_VERSION);
        payload.put("pid", config.partnerId);
        payload.put("ret", returnUrl);
        payload.put("iat", issuedAt);
        payload.put("exp", issuedAt + expireMinutes * 60L);
        payload.put("nonce", UUID.randomUUID().toString());
        String encoded = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(MAPPER.writeValueAsBytes(payload));
        return encoded + "." + sign(encoded, config.secret);
    }

    private static String sign(String encodedPayload, String secret) throws Exception {
        Mac mac = Mac.getInstance(HMAC_ALGORITHM);
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), HMAC_ALGORITHM));
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(mac.doFinal(encodedPayload.getBytes(StandardCharsets.US_ASCII)));
    }

    private static boolean constantTimeEquals(String left, String right) {
        if (left == null || right == null) {
            return false;
        }
        return MessageDigest.isEqual(left.getBytes(StandardCharsets.UTF_8),
                right.getBytes(StandardCharsets.UTF_8));
    }

    private static PartnerConfig loadPartner(String partnerId) {
        String key = PARTNER_PREFIX + partnerId;
        PartnerConfig config = new PartnerConfig();
        config.partnerId = partnerId;
        config.secret = UtilProperties.getPropertyValue(PROPS, key + ".secret", "");
        config.name = UtilProperties.getPropertyValue(PROPS, key + ".name", partnerId);
        config.status = UtilProperties.getPropertyValue(PROPS, key + ".status", "ACTIVE");
        config.defaultReturnUrl = UtilProperties.getPropertyValue(PROPS, key + ".returnUrl", "");
        config.returnUrlPrefixes = UtilProperties.getPropertyValue(PROPS,
                key + ".returnUrlPrefixes", "");
        config.active = "ACTIVE".equalsIgnoreCase(config.status)
                && UtilValidate.isNotEmpty(config.secret);
        return config;
    }

    private static String resolveReturnUrl(PartnerConfig config, String requested) {
        if (UtilValidate.isEmpty(requested)) {
            if (UtilValidate.isEmpty(config.defaultReturnUrl)) {
                return null;
            }
            validateReturnUrlSyntax(config.defaultReturnUrl);
            return config.defaultReturnUrl;
        }
        String returnUrl = requested.trim();
        validateReturnUrlSyntax(returnUrl);
        if (returnUrl.equals(config.defaultReturnUrl)) {
            return returnUrl;
        }
        if (UtilValidate.isNotEmpty(config.returnUrlPrefixes)) {
            for (String prefix : config.returnUrlPrefixes.split(",")) {
                String allowedPrefix = prefix.trim();
                if (UtilValidate.isNotEmpty(allowedPrefix) && returnUrl.startsWith(allowedPrefix)) {
                    return returnUrl;
                }
            }
        }
        return null;
    }

    private static void validateReturnUrlSyntax(String returnUrl) {
        try {
            URI uri = new URI(returnUrl);
            String scheme = uri.getScheme();
            if (!("https".equalsIgnoreCase(scheme) || "http".equalsIgnoreCase(scheme))
                    || UtilValidate.isEmpty(uri.getHost())) {
                throw new IllegalArgumentException("Return URL must be an absolute http(s) URL");
            }
        } catch (URISyntaxException e) {
            throw new IllegalArgumentException("Invalid return URL", e);
        }
    }

    private static boolean isAllowedReturnUrl(PartnerConfig config, String returnUrl) {
        try {
            return UtilValidate.isNotEmpty(returnUrl) && resolveReturnUrl(config, returnUrl) != null;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private static boolean isValidPartnerId(String partnerId) {
        return UtilValidate.isNotEmpty(partnerId)
                && partnerId.length() <= 20
                && partnerId.matches("[A-Za-z0-9_-]+");
    }

    private static void writeAudit(Delegator delegator, String partnerId, Timestamp now) {
        try {
            GenericValue audit = delegator.makeValue("EfAuditLog");
            audit.set("auditLogId", delegator.getNextSeqId("EfAuditLog"));
            audit.set("actionEnumId", ACTION_ISSUE);
            audit.set("targetEntityName", "EfPartner");
            audit.set("targetEntityId", partnerId);
            audit.set("resultEnumId", RESULT_SUCCESS);
            audit.set("actionDate", now);
            delegator.create(audit);
        } catch (GenericEntityException e) {
            Debug.logWarning("Could not write partner handoff audit log: " + e.getMessage(), MODULE);
        }
    }

    private static String string(Map<String, Object> context, String key) {
        Object value = context.get(key);
        return value == null ? null : value.toString();
    }

    private static String message(Locale locale, String key) {
        return UtilProperties.getMessage(RESOURCE, key, locale);
    }

    private static final class PartnerConfig {
        private String partnerId;
        private String secret;
        private String name;
        private String status;
        private String defaultReturnUrl;
        private String returnUrlPrefixes;
        private boolean active;
    }
}
