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
package org.apache.ofbiz.efinance.account;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

import org.apache.ofbiz.base.crypto.HashCrypt;
import org.apache.ofbiz.base.util.Debug;
import org.apache.ofbiz.base.util.UtilDateTime;
import org.apache.ofbiz.base.util.UtilMisc;
import org.apache.ofbiz.base.util.UtilProperties;
import org.apache.ofbiz.base.util.UtilValidate;
import org.apache.ofbiz.entity.Delegator;
import org.apache.ofbiz.entity.GenericEntityException;
import org.apache.ofbiz.entity.GenericValue;
import org.apache.ofbiz.entity.condition.EntityCondition;
import org.apache.ofbiz.entity.condition.EntityOperator;
import org.apache.ofbiz.entity.util.EntityQuery;
import org.apache.ofbiz.service.DispatchContext;
import org.apache.ofbiz.service.ServiceUtil;
import org.apache.ofbiz.webapp.control.JWTManager;

/**
 * STEP W0: eFinance member registration, authentication and consent services.
 *
 * <p>All state changes go through the dedicated service methods. Direct entity
 * CRUD must not change member status.
 */
public final class EfMemberServices {

    private static final String MODULE = EfMemberServices.class.getName();
    private static final String RESOURCE = "EfinanceUiLabels";

    private static final String STATUS_PENDING = "EFMBR_PENDING";
    private static final String STATUS_ACTIVE = "EFMBR_ACTIVE";
    private static final String STATUS_LOCKED = "EFMBR_LOCKED";

    private static final String PURPOSE_EMAIL_VERIFY = "EFOTP_EMAIL_VERIFY";
    private static final String PURPOSE_MFA = "EFOTP_MFA";
    private static final String PURPOSE_PWD_RESET = "EFOTP_PWD_RESET";

    private static final String ACTION_REGISTER = "EFAUD_REGISTER";
    private static final String ACTION_EMAIL_VERIFY = "EFAUD_EMAIL_VFY";
    private static final String ACTION_LOGIN = "EFAUD_LOGIN";
    private static final String ACTION_LOGIN_FAIL = "EFAUD_LOGIN_FAIL";
    private static final String ACTION_MFA_VERIFY = "EFAUD_MFA_VFY";
    private static final String ACTION_LOGOUT = "EFAUD_LOGOUT";
    private static final String ACTION_PWD_RESET = "EFAUD_PWD_RESET";
    private static final String ACTION_TOKEN_ISSUE = "EFAUD_TKN_ISSUE";
    private static final String ACTION_CONSENT = "EFAUD_CONSENT";

    private static final String RESULT_SUCCESS = "EFAUDRES_SUCCESS";
    private static final String RESULT_FAILURE = "EFAUDRES_FAILURE";
    private static final String RESULT_BLOCKED = "EFAUDRES_BLOCKED";

    private static final Pattern EMAIL_PATTERN = Pattern.compile(
            "^[a-zA-Z0-9._%+\\-]+@[a-zA-Z0-9.\\-]+\\.[a-zA-Z]{2,}$");

    private static final int DEFAULT_TOKEN_EXPIRE_MINUTES = 15;
    private static final int DEFAULT_REFRESH_EXPIRE_DAYS = 30;
    private static final int DEFAULT_OTP_EXPIRE_MINUTES = 10;
    private static final int DEFAULT_MAX_LOGIN_ATTEMPTS = 5;

    private EfMemberServices() {
    }

    // =========================================================================
    // Registration
    // =========================================================================

    public static Map<String, Object> efRegisterMember(DispatchContext dctx, Map<String, Object> context) {
        Delegator delegator = dctx.getDelegator();
        Locale locale = (Locale) context.get("locale");

        String email = (String) context.get("email");
        String password = (String) context.get("password");
        String firstName = (String) context.get("firstName");
        String lastName = (String) context.get("lastName");
        String phone = (String) context.get("phone");
        String memberType = (String) context.get("memberType");
        if (UtilValidate.isEmpty(memberType)) {
            memberType = "EFMBR_PERSONAL";
        }

        if (!EMAIL_PATTERN.matcher(email).matches()) {
            return ServiceUtil.returnError(UtilProperties.getMessage(RESOURCE, "EfinanceEmailAlreadyRegistered", locale));
        }

        try {
            // Check duplicate userLoginId (we use email as userLoginId)
            GenericValue existing = EntityQuery.use(delegator).from("UserLogin")
                    .where("userLoginId", email).queryOne();
            if (existing != null) {
                return ServiceUtil.returnError(UtilProperties.getMessage(RESOURCE, "EfinanceEmailAlreadyRegistered", locale));
            }

            // Create Party + Person
            String partyId = delegator.getNextSeqId("Party");
            GenericValue party = delegator.makeValue("Party",
                    UtilMisc.toMap("partyId", partyId, "partyTypeId", "PERSON"));
            delegator.create(party);

            GenericValue person = delegator.makeValue("Person",
                    UtilMisc.toMap("partyId", partyId, "firstName", firstName, "lastName", lastName));
            delegator.create(person);

            // Create UserLogin
            String hashedPassword = HashCrypt.cryptUTF8("SHA", null, password);
            GenericValue userLogin = delegator.makeValue("UserLogin");
            userLogin.set("userLoginId", email);
            userLogin.set("partyId", partyId);
            userLogin.set("currentPassword", hashedPassword);
            userLogin.set("enabled", "Y");
            userLogin.set("hasLoggedOut", "N");
            delegator.create(userLogin);

            // Create EfMember
            Timestamp now = UtilDateTime.nowTimestamp();
            GenericValue member = delegator.makeValue("EfMember");
            member.set("partyId", partyId);
            member.set("userLoginId", email);
            member.set("memberTypeEnumId", memberType);
            member.set("statusId", STATUS_PENDING);
            member.set("mfaMethodEnumId", "EFMFA_NONE");
            member.set("createdDate", now);
            member.set("lastModifiedDate", now);
            delegator.create(member);

            // Generate email verification OTP (24h expiry)
            int verifyExpireHours = Integer.parseInt(
                    UtilProperties.getPropertyValue("efinance", "efinance.email.verify.expire.hours", "24"));
            String[] otp = generateOtp(delegator);
            String otpId = delegator.getNextSeqId("EfOneTimeCode");
            Timestamp otpExpiry = new Timestamp(now.getTime() + (long) verifyExpireHours * 60 * 60 * 1000);
            GenericValue otpRecord = delegator.makeValue("EfOneTimeCode");
            otpRecord.set("otpId", otpId);
            otpRecord.set("partyId", partyId);
            otpRecord.set("codeHash", otp[1]);
            otpRecord.set("purposeEnumId", PURPOSE_EMAIL_VERIFY);
            otpRecord.set("expiresAt", otpExpiry);
            otpRecord.set("attemptCount", 0L);
            otpRecord.set("createdDate", now);
            delegator.create(otpRecord);

            logAudit(delegator, partyId, ACTION_REGISTER, "EfMember", partyId, RESULT_SUCCESS, null, null, null);

            Map<String, Object> result = ServiceUtil.returnSuccess(
                    UtilProperties.getMessage(RESOURCE, "EfinanceMemberRegistered", locale));
            result.put("partyId", partyId);
            result.put("userLoginId", email);
            // Never expose the OTP through the public API. It is returned only when
            // the caller is the internal system user (unit tests / operational tooling).
            if (isSystemUser(context)) {
                result.put("verificationToken", otp[0]);
            }
            return result;

        } catch (GenericEntityException e) {
            Debug.logError(e, "efRegisterMember failed", MODULE);
            return ServiceUtil.returnError(e.getMessage());
        }
    }

    // =========================================================================
    // Email verification
    // =========================================================================

    public static Map<String, Object> efVerifyEmail(DispatchContext dctx, Map<String, Object> context) {
        Delegator delegator = dctx.getDelegator();
        Locale locale = (Locale) context.get("locale");

        String token = (String) context.get("token");

        try {
            String tokenHash = sha256(token);
            Timestamp now = UtilDateTime.nowTimestamp();

            GenericValue otpRecord = EntityQuery.use(delegator).from("EfOneTimeCode")
                    .where(EntityCondition.makeCondition("codeHash", tokenHash),
                           EntityCondition.makeCondition("purposeEnumId", PURPOSE_EMAIL_VERIFY))
                    .queryFirst();

            if (otpRecord == null || otpRecord.getTimestamp("usedDate") != null) {
                return ServiceUtil.returnError(UtilProperties.getMessage(RESOURCE, "EfinanceTokenInvalid", locale));
            }
            if (now.after(otpRecord.getTimestamp("expiresAt"))) {
                return ServiceUtil.returnError(UtilProperties.getMessage(RESOURCE, "EfinanceTokenInvalid", locale));
            }

            String partyId = otpRecord.getString("partyId");
            GenericValue member = EntityQuery.use(delegator).from("EfMember")
                    .where("partyId", partyId).queryOne();
            if (member == null) {
                return ServiceUtil.returnError(UtilProperties.getMessage(RESOURCE, "EfinanceMemberNotFound", locale));
            }

            member.set("statusId", STATUS_ACTIVE);
            member.set("emailVerifiedDate", now);
            member.set("lastModifiedDate", now);
            delegator.store(member);

            otpRecord.set("usedDate", now);
            delegator.store(otpRecord);

            logAudit(delegator, partyId, ACTION_EMAIL_VERIFY, "EfMember", partyId, RESULT_SUCCESS, null, null, null);

            Map<String, Object> result = ServiceUtil.returnSuccess(
                    UtilProperties.getMessage(RESOURCE, "EfinanceEmailVerified", locale));
            result.put("success", Boolean.TRUE);
            return result;

        } catch (GenericEntityException e) {
            Debug.logError(e, "efVerifyEmail failed", MODULE);
            return ServiceUtil.returnError(e.getMessage());
        }
    }

    // =========================================================================
    // Login
    // =========================================================================

    public static Map<String, Object> efLogin(DispatchContext dctx, Map<String, Object> context) {
        Delegator delegator = dctx.getDelegator();
        Locale locale = (Locale) context.get("locale");

        String userLoginId = (String) context.get("userLoginId");
        String password = (String) context.get("password");
        String deviceId = (String) context.get("deviceId");
        String ipAddress = (String) context.get("ipAddress");

        int maxAttempts = Integer.parseInt(
                UtilProperties.getPropertyValue("efinance", "efinance.max.login.attempts", String.valueOf(DEFAULT_MAX_LOGIN_ATTEMPTS)));

        try {
            GenericValue userLogin = EntityQuery.use(delegator).from("UserLogin")
                    .where("userLoginId", userLoginId).queryOne();
            if (userLogin == null) {
                logAudit(delegator, null, ACTION_LOGIN_FAIL, "UserLogin", userLoginId, RESULT_FAILURE, ipAddress, deviceId, "User not found");
                return ServiceUtil.returnError(UtilProperties.getMessage(RESOURCE, "EfinanceLoginFailed", locale));
            }

            String partyId = userLogin.getString("partyId");
            GenericValue member = EntityQuery.use(delegator).from("EfMember")
                    .where("partyId", partyId).queryOne();
            if (member == null) {
                return ServiceUtil.returnError(UtilProperties.getMessage(RESOURCE, "EfinanceMemberNotFound", locale));
            }

            String statusId = member.getString("statusId");
            if (STATUS_LOCKED.equals(statusId)) {
                logAudit(delegator, partyId, ACTION_LOGIN_FAIL, "EfMember", partyId, RESULT_BLOCKED, ipAddress, deviceId, "Account locked");
                return ServiceUtil.returnError(UtilProperties.getMessage(RESOURCE, "EfinanceMemberLocked", locale));
            }
            if (STATUS_PENDING.equals(statusId)) {
                return ServiceUtil.returnError(UtilProperties.getMessage(RESOURCE, "EfinanceMemberNotActive", locale));
            }

            // Check recent failed attempts
            Timestamp since = new Timestamp(System.currentTimeMillis() - 15L * 60 * 1000);
            long failCount = EntityQuery.use(delegator).from("EfAuditLog")
                    .where(EntityCondition.makeCondition("partyId", partyId),
                           EntityCondition.makeCondition("actionEnumId", ACTION_LOGIN_FAIL),
                           EntityCondition.makeCondition("actionDate", EntityOperator.GREATER_THAN_EQUAL_TO, since))
                    .queryCount();
            if (failCount >= maxAttempts) {
                member.set("statusId", STATUS_LOCKED);
                member.set("lastModifiedDate", UtilDateTime.nowTimestamp());
                delegator.store(member);
                logAudit(delegator, partyId, ACTION_LOGIN_FAIL, "EfMember", partyId, RESULT_BLOCKED, ipAddress, deviceId, "Too many attempts");
                return ServiceUtil.returnError(UtilProperties.getMessage(RESOURCE, "EfinanceMemberLocked", locale));
            }

            // Verify password
            String storedHash = userLogin.getString("currentPassword");
            boolean passwordOk = HashCrypt.comparePassword(storedHash, "SHA", password);
            if (!passwordOk) {
                logAudit(delegator, partyId, ACTION_LOGIN_FAIL, "UserLogin", userLoginId, RESULT_FAILURE, ipAddress, deviceId, "Wrong password");
                return ServiceUtil.returnError(UtilProperties.getMessage(RESOURCE, "EfinanceLoginFailed", locale));
            }

            // Update last login
            Timestamp now = UtilDateTime.nowTimestamp();
            member.set("lastLoginDate", now);
            member.set("lastModifiedDate", now);
            delegator.store(member);

            String mfaMethod = member.getString("mfaMethodEnumId");
            if (mfaMethod != null && !"EFMFA_NONE".equals(mfaMethod)) {
                // Issue MFA temp token (5-min expiry)
                String tempTokenRaw = generateSecureToken();
                String tempTokenHash = sha256(tempTokenRaw);
                String tokenId = delegator.getNextSeqId("EfToken");
                Timestamp tempExpiry = new Timestamp(now.getTime() + 5L * 60 * 1000);
                GenericValue tempToken = delegator.makeValue("EfToken");
                tempToken.set("tokenId", tokenId);
                tempToken.set("tokenHash", tempTokenHash);
                tempToken.set("partyId", partyId);
                tempToken.set("userLoginId", userLoginId);
                tempToken.set("deviceId", deviceId);
                tempToken.set("ipAddress", ipAddress);
                tempToken.set("issuedAt", now);
                tempToken.set("expiresAt", tempExpiry);
                tempToken.set("detail", "MFA_TEMP");
                delegator.create(tempToken);

                // Generate MFA OTP
                int otpExpireMinutes = Integer.parseInt(
                        UtilProperties.getPropertyValue("efinance", "efinance.otp.expire.minutes", String.valueOf(DEFAULT_OTP_EXPIRE_MINUTES)));
                String[] mfaOtp = generateOtp(delegator);
                String otpId = delegator.getNextSeqId("EfOneTimeCode");
                Timestamp otpExpiry = new Timestamp(now.getTime() + (long) otpExpireMinutes * 60 * 1000);
                GenericValue otpRecord = delegator.makeValue("EfOneTimeCode");
                otpRecord.set("otpId", otpId);
                otpRecord.set("partyId", partyId);
                otpRecord.set("codeHash", mfaOtp[1]);
                otpRecord.set("purposeEnumId", PURPOSE_MFA);
                otpRecord.set("expiresAt", otpExpiry);
                otpRecord.set("attemptCount", 0L);
                otpRecord.set("createdDate", now);
                delegator.create(otpRecord);

                Map<String, Object> result = ServiceUtil.returnSuccess(
                        UtilProperties.getMessage(RESOURCE, "EfinanceMfaRequired", locale));
                result.put("mfaRequired", Boolean.TRUE);
                result.put("mfaTempToken", tempTokenRaw);
                result.put("memberStatus", statusId);
                return result;
            }

            // No MFA – issue tokens directly
            Map<String, String> tokens = issueTokens(delegator, partyId, userLoginId, deviceId, ipAddress, now);
            logAudit(delegator, partyId, ACTION_LOGIN, "EfMember", partyId, RESULT_SUCCESS, ipAddress, deviceId, null);
            logAudit(delegator, partyId, ACTION_TOKEN_ISSUE, "EfToken", partyId, RESULT_SUCCESS, ipAddress, deviceId, null);

            Map<String, Object> result = ServiceUtil.returnSuccess(
                    UtilProperties.getMessage(RESOURCE, "EfinanceLoginSuccess", locale));
            result.put("accessToken", tokens.get("accessToken"));
            result.put("refreshToken", tokens.get("refreshToken"));
            result.put("memberStatus", statusId);
            return result;

        } catch (GenericEntityException e) {
            Debug.logError(e, "efLogin failed", MODULE);
            return ServiceUtil.returnError(e.getMessage());
        }
    }

    // =========================================================================
    // MFA verification
    // =========================================================================

    public static Map<String, Object> efVerifyMfa(DispatchContext dctx, Map<String, Object> context) {
        Delegator delegator = dctx.getDelegator();
        Locale locale = (Locale) context.get("locale");

        String tempTokenRaw = (String) context.get("tempToken");
        String otpCode = (String) context.get("otpCode");
        String deviceId = (String) context.get("deviceId");
        String ipAddress = (String) context.get("ipAddress");

        try {
            Timestamp now = UtilDateTime.nowTimestamp();
            String tempTokenHash = sha256(tempTokenRaw);

            GenericValue tempToken = EntityQuery.use(delegator).from("EfToken")
                    .where(EntityCondition.makeCondition("tokenHash", tempTokenHash),
                           EntityCondition.makeCondition("detail", "MFA_TEMP"))
                    .queryFirst();
            if (tempToken == null || tempToken.getTimestamp("revokedDate") != null
                    || now.after(tempToken.getTimestamp("expiresAt"))) {
                return ServiceUtil.returnError(UtilProperties.getMessage(RESOURCE, "EfinanceTokenInvalid", locale));
            }

            String partyId = tempToken.getString("partyId");
            String userLoginId = tempToken.getString("userLoginId");

            // Find MFA OTP
            String otpHash = sha256(otpCode);
            GenericValue otpRecord = EntityQuery.use(delegator).from("EfOneTimeCode")
                    .where(EntityCondition.makeCondition("partyId", partyId),
                           EntityCondition.makeCondition("purposeEnumId", PURPOSE_MFA),
                           EntityCondition.makeCondition("codeHash", otpHash))
                    .queryFirst();
            if (otpRecord == null || otpRecord.getTimestamp("usedDate") != null
                    || now.after(otpRecord.getTimestamp("expiresAt"))) {
                return ServiceUtil.returnError(UtilProperties.getMessage(RESOURCE, "EfinanceOtpInvalid", locale));
            }

            // Revoke temp token and mark OTP used
            tempToken.set("revokedDate", now);
            delegator.store(tempToken);
            otpRecord.set("usedDate", now);
            delegator.store(otpRecord);

            Map<String, String> tokens = issueTokens(delegator, partyId, userLoginId, deviceId, ipAddress, now);
            logAudit(delegator, partyId, ACTION_MFA_VERIFY, "EfToken", partyId, RESULT_SUCCESS, ipAddress, deviceId, null);
            logAudit(delegator, partyId, ACTION_TOKEN_ISSUE, "EfToken", partyId, RESULT_SUCCESS, ipAddress, deviceId, null);

            Map<String, Object> result = ServiceUtil.returnSuccess();
            result.put("accessToken", tokens.get("accessToken"));
            result.put("refreshToken", tokens.get("refreshToken"));
            return result;

        } catch (GenericEntityException e) {
            Debug.logError(e, "efVerifyMfa failed", MODULE);
            return ServiceUtil.returnError(e.getMessage());
        }
    }

    // =========================================================================
    // Refresh token
    // =========================================================================

    public static Map<String, Object> efRefreshToken(DispatchContext dctx, Map<String, Object> context) {
        Delegator delegator = dctx.getDelegator();
        Locale locale = (Locale) context.get("locale");

        String refreshTokenRaw = (String) context.get("refreshToken");
        String deviceId = (String) context.get("deviceId");

        try {
            Timestamp now = UtilDateTime.nowTimestamp();
            String tokenHash = sha256(refreshTokenRaw);

            GenericValue oldToken = EntityQuery.use(delegator).from("EfRefreshToken")
                    .where("tokenHash", tokenHash).queryOne();
            if (oldToken == null || oldToken.getTimestamp("revokedDate") != null
                    || now.after(oldToken.getTimestamp("expiresAt"))) {
                return ServiceUtil.returnError(UtilProperties.getMessage(RESOURCE, "EfinanceTokenInvalid", locale));
            }

            String partyId = oldToken.getString("partyId");
            String userLoginId = oldToken.getString("userLoginId");

            Map<String, String> tokens = issueTokens(delegator, partyId, userLoginId, deviceId, null, now);

            // Revoke old token and record replacement
            String newRefreshId = tokens.get("refreshTokenId");
            oldToken.set("revokedDate", now);
            oldToken.set("replacedByTokenId", newRefreshId);
            delegator.store(oldToken);

            logAudit(delegator, partyId, ACTION_TOKEN_ISSUE, "EfRefreshToken", partyId, RESULT_SUCCESS, null, deviceId, "rotation");

            Map<String, Object> result = ServiceUtil.returnSuccess();
            result.put("accessToken", tokens.get("accessToken"));
            result.put("refreshToken", tokens.get("refreshToken"));
            return result;

        } catch (GenericEntityException e) {
            Debug.logError(e, "efRefreshToken failed", MODULE);
            return ServiceUtil.returnError(e.getMessage());
        }
    }

    // =========================================================================
    // Logout
    // =========================================================================

    public static Map<String, Object> efLogout(DispatchContext dctx, Map<String, Object> context) {
        Delegator delegator = dctx.getDelegator();
        String refreshTokenRaw = (String) context.get("refreshToken");
        String accessTokenRaw = (String) context.get("accessToken");
        String partyId = getPartyIdFromContext(context);

        try {
            Timestamp now = UtilDateTime.nowTimestamp();
            if (UtilValidate.isNotEmpty(refreshTokenRaw)) {
                String tokenHash = sha256(refreshTokenRaw);
                GenericValue token = EntityQuery.use(delegator).from("EfRefreshToken")
                        .where("tokenHash", tokenHash).queryOne();
                if (token != null && token.getTimestamp("revokedDate") == null) {
                    token.set("revokedDate", now);
                    delegator.store(token);
                }
            }
            if (UtilValidate.isNotEmpty(accessTokenRaw)) {
                GenericValue token = EntityQuery.use(delegator).from("EfToken")
                        .where("tokenHash", sha256(accessTokenRaw)).queryOne();
                if (token != null && token.getTimestamp("revokedDate") == null) {
                    token.set("revokedDate", now);
                    delegator.store(token);
                }
            }
            logAudit(delegator, partyId, ACTION_LOGOUT, "EfToken", partyId, RESULT_SUCCESS, null, null, null);
            return ServiceUtil.returnSuccess();

        } catch (GenericEntityException e) {
            Debug.logError(e, "efLogout failed", MODULE);
            return ServiceUtil.returnError(e.getMessage());
        }
    }

    public static Map<String, Object> efLogoutAll(DispatchContext dctx, Map<String, Object> context) {
        Delegator delegator = dctx.getDelegator();
        String partyId = getPartyIdFromContext(context);

        try {
            Timestamp now = UtilDateTime.nowTimestamp();

            List<GenericValue> refreshTokens = EntityQuery.use(delegator).from("EfRefreshToken")
                    .where(EntityCondition.makeCondition("partyId", partyId),
                           EntityCondition.makeCondition("revokedDate", EntityOperator.EQUALS, null))
                    .queryList();
            for (GenericValue t : refreshTokens) {
                t.set("revokedDate", now);
                delegator.store(t);
            }

            List<GenericValue> accessTokens = EntityQuery.use(delegator).from("EfToken")
                    .where(EntityCondition.makeCondition("partyId", partyId),
                           EntityCondition.makeCondition("revokedDate", EntityOperator.EQUALS, null))
                    .queryList();
            for (GenericValue t : accessTokens) {
                t.set("revokedDate", now);
                delegator.store(t);
            }

            logAudit(delegator, partyId, ACTION_LOGOUT, "EfToken", partyId, RESULT_SUCCESS, null, null, "all-devices");
            return ServiceUtil.returnSuccess();

        } catch (GenericEntityException e) {
            Debug.logError(e, "efLogoutAll failed", MODULE);
            return ServiceUtil.returnError(e.getMessage());
        }
    }

    // =========================================================================
    // Password reset
    // =========================================================================

    public static Map<String, Object> efRequestPasswordReset(DispatchContext dctx, Map<String, Object> context) {
        Delegator delegator = dctx.getDelegator();
        Locale locale = (Locale) context.get("locale");
        String email = (String) context.get("email");

        try {
            GenericValue userLogin = EntityQuery.use(delegator).from("UserLogin")
                    .where("userLoginId", email).queryOne();
            if (userLogin == null) {
                // Return success even if not found to prevent user enumeration
                return ServiceUtil.returnSuccess(
                        UtilProperties.getMessage(RESOURCE, "EfinancePasswordResetSent", locale));
            }
            String partyId = userLogin.getString("partyId");

            int expireMinutes = 60;
            String[] otp = generateOtp(delegator);
            Timestamp now = UtilDateTime.nowTimestamp();
            String otpId = delegator.getNextSeqId("EfOneTimeCode");
            Timestamp otpExpiry = new Timestamp(now.getTime() + (long) expireMinutes * 60 * 1000);
            GenericValue otpRecord = delegator.makeValue("EfOneTimeCode");
            otpRecord.set("otpId", otpId);
            otpRecord.set("partyId", partyId);
            otpRecord.set("codeHash", otp[1]);
            otpRecord.set("purposeEnumId", PURPOSE_PWD_RESET);
            otpRecord.set("expiresAt", otpExpiry);
            otpRecord.set("attemptCount", 0L);
            otpRecord.set("createdDate", now);
            delegator.create(otpRecord);

            logAudit(delegator, partyId, ACTION_PWD_RESET, "EfOneTimeCode", otpId, RESULT_SUCCESS, null, null, "request");

            Map<String, Object> result = ServiceUtil.returnSuccess(
                    UtilProperties.getMessage(RESOURCE, "EfinancePasswordResetSent", locale));
            // Never expose the reset token through the public API. It is returned only
            // to the internal system user for tests / operational tooling.
            if (isSystemUser(context)) {
                result.put("resetToken", otp[0]);
            }
            return result;

        } catch (GenericEntityException e) {
            Debug.logError(e, "efRequestPasswordReset failed", MODULE);
            return ServiceUtil.returnError(e.getMessage());
        }
    }

    public static Map<String, Object> efResetPassword(DispatchContext dctx, Map<String, Object> context) {
        Delegator delegator = dctx.getDelegator();
        Locale locale = (Locale) context.get("locale");
        String token = (String) context.get("token");
        String newPassword = (String) context.get("newPassword");

        try {
            Timestamp now = UtilDateTime.nowTimestamp();
            String tokenHash = sha256(token);

            GenericValue otpRecord = EntityQuery.use(delegator).from("EfOneTimeCode")
                    .where(EntityCondition.makeCondition("codeHash", tokenHash),
                           EntityCondition.makeCondition("purposeEnumId", PURPOSE_PWD_RESET))
                    .queryFirst();
            if (otpRecord == null || otpRecord.getTimestamp("usedDate") != null
                    || now.after(otpRecord.getTimestamp("expiresAt"))) {
                return ServiceUtil.returnError(UtilProperties.getMessage(RESOURCE, "EfinanceTokenInvalid", locale));
            }

            String partyId = otpRecord.getString("partyId");
            GenericValue member = EntityQuery.use(delegator).from("EfMember")
                    .where("partyId", partyId).queryOne();
            if (member == null) {
                return ServiceUtil.returnError(UtilProperties.getMessage(RESOURCE, "EfinanceMemberNotFound", locale));
            }
            String userLoginId = member.getString("userLoginId");

            // Update password
            GenericValue userLogin = EntityQuery.use(delegator).from("UserLogin")
                    .where("userLoginId", userLoginId).queryOne();
            if (userLogin != null) {
                userLogin.set("currentPassword", HashCrypt.cryptUTF8("SHA", null, newPassword));
                delegator.store(userLogin);
            }

            otpRecord.set("usedDate", now);
            delegator.store(otpRecord);

            // Revoke all tokens for security
            revokeAllTokens(delegator, partyId, now);

            logAudit(delegator, partyId, ACTION_PWD_RESET, "UserLogin", userLoginId, RESULT_SUCCESS, null, null, "completed");
            return ServiceUtil.returnSuccess(UtilProperties.getMessage(RESOURCE, "EfinancePasswordReset", locale));

        } catch (GenericEntityException e) {
            Debug.logError(e, "efResetPassword failed", MODULE);
            return ServiceUtil.returnError(e.getMessage());
        }
    }

    // =========================================================================
    // Profile
    // =========================================================================

    public static Map<String, Object> efGetProfile(DispatchContext dctx, Map<String, Object> context) {
        Delegator delegator = dctx.getDelegator();
        String partyId = getPartyIdFromContext(context);

        try {
            GenericValue member = EntityQuery.use(delegator).from("EfMember")
                    .where("partyId", partyId).queryOne();
            if (member == null) {
                return ServiceUtil.returnError("Member not found");
            }
            GenericValue person = EntityQuery.use(delegator).from("Person")
                    .where("partyId", partyId).queryOne();

            Map<String, Object> profile = new LinkedHashMap<>();
            profile.put("partyId", partyId);
            if (person != null) {
                profile.put("firstName", person.getString("firstName"));
                profile.put("lastName", person.getString("lastName"));
            }
            profile.put("email", member.getString("userLoginId"));
            profile.put("memberType", member.getString("memberTypeEnumId"));
            profile.put("status", member.getString("statusId"));
            profile.put("mfaMethod", member.getString("mfaMethodEnumId"));
            profile.put("emailVerifiedDate", member.getTimestamp("emailVerifiedDate"));

            Map<String, Object> result = ServiceUtil.returnSuccess();
            result.put("member", profile);
            return result;

        } catch (GenericEntityException e) {
            Debug.logError(e, "efGetProfile failed", MODULE);
            return ServiceUtil.returnError(e.getMessage());
        }
    }

    // =========================================================================
    // Consent
    // =========================================================================

    public static Map<String, Object> efAcceptConsent(DispatchContext dctx, Map<String, Object> context) {
        Delegator delegator = dctx.getDelegator();
        Locale locale = (Locale) context.get("locale");
        String partyId = getPartyIdFromContext(context);
        String documentId = (String) context.get("documentId");
        String ipAddress = (String) context.get("ipAddress");
        String deviceId = (String) context.get("deviceId");

        try {
            GenericValue doc = EntityQuery.use(delegator).from("EfConsentDocument")
                    .where("documentId", documentId).queryOne();
            if (doc == null) {
                return ServiceUtil.returnError("Consent document not found: " + documentId);
            }

            Timestamp now = UtilDateTime.nowTimestamp();
            String consentId = delegator.getNextSeqId("EfConsent");
            GenericValue consent = delegator.makeValue("EfConsent");
            consent.set("consentId", consentId);
            consent.set("partyId", partyId);
            consent.set("documentId", documentId);
            consent.set("consentDate", now);
            consent.set("ipAddress", ipAddress);
            consent.set("deviceId", deviceId);
            delegator.create(consent);

            logAudit(delegator, partyId, ACTION_CONSENT, "EfConsent", consentId, RESULT_SUCCESS, ipAddress, deviceId, documentId);
            return ServiceUtil.returnSuccess(UtilProperties.getMessage(RESOURCE, "EfinanceConsentRecorded", locale));

        } catch (GenericEntityException e) {
            Debug.logError(e, "efAcceptConsent failed", MODULE);
            return ServiceUtil.returnError(e.getMessage());
        }
    }

    public static Map<String, Object> efListConsentDocuments(DispatchContext dctx, Map<String, Object> context) {
        Delegator delegator = dctx.getDelegator();
        try {
            Timestamp now = UtilDateTime.nowTimestamp();
            List<GenericValue> rawDocs = EntityQuery.use(delegator).from("EfConsentDocument")
                    .where(EntityCondition.makeCondition(EntityOperator.AND,
                            EntityCondition.makeCondition("effectiveDate", EntityOperator.LESS_THAN_EQUAL_TO, now),
                            EntityCondition.makeCondition(EntityOperator.OR,
                                    EntityCondition.makeCondition("expiresDate", EntityOperator.EQUALS, null),
                                    EntityCondition.makeCondition("expiresDate",
                                            EntityOperator.GREATER_THAN_EQUAL_TO, now))))
                    .orderBy("documentTypeEnumId", "-effectiveDate")
                    .queryList();
            List<Map<String, Object>> documents = new ArrayList<>();
            for (GenericValue doc : rawDocs) {
                Map<String, Object> entry = new LinkedHashMap<>();
                entry.put("documentId", doc.getString("documentId"));
                entry.put("documentTypeEnumId", doc.getString("documentTypeEnumId"));
                entry.put("version", doc.getString("version"));
                entry.put("language", doc.getString("language"));
                entry.put("effectiveDate", doc.getTimestamp("effectiveDate"));
                entry.put("expiresDate", doc.getTimestamp("expiresDate"));
                documents.add(entry);
            }
            Map<String, Object> result = ServiceUtil.returnSuccess();
            result.put("documents", documents);
            return result;
        } catch (GenericEntityException e) {
            Debug.logError(e, "efListConsentDocuments failed", MODULE);
            return ServiceUtil.returnError(e.getMessage());
        }
    }

    public static Map<String, Object> efListConsents(DispatchContext dctx, Map<String, Object> context) {
        Delegator delegator = dctx.getDelegator();
        String partyId = getPartyIdFromContext(context);

        try {
            List<GenericValue> rawConsents = EntityQuery.use(delegator).from("EfConsent")
                    .where("partyId", partyId)
                    .orderBy("-consentDate")
                    .queryList();

            List<Map<String, Object>> consents = new ArrayList<>();
            for (GenericValue c : rawConsents) {
                String docId = c.getString("documentId");
                Map<String, Object> entry = new LinkedHashMap<>();
                entry.put("consentId", c.getString("consentId"));
                entry.put("documentId", docId);
                entry.put("consentDate", c.getTimestamp("consentDate"));
                entry.put("revokedDate", c.getTimestamp("revokedDate"));
                GenericValue doc = EntityQuery.use(delegator).from("EfConsentDocument")
                        .where("documentId", docId).queryOne();
                if (doc != null) {
                    entry.put("documentType", doc.getString("documentTypeEnumId"));
                    entry.put("version", doc.getString("version"));
                }
                consents.add(entry);
            }

            Map<String, Object> result = ServiceUtil.returnSuccess();
            result.put("consents", consents);
            return result;

        } catch (GenericEntityException e) {
            Debug.logError(e, "efListConsents failed", MODULE);
            return ServiceUtil.returnError(e.getMessage());
        }
    }

    // =========================================================================
    // Private helpers
    // =========================================================================

    private static String getPartyIdFromContext(Map<String, Object> context) {
        GenericValue userLogin = (GenericValue) context.get("userLogin");
        if (userLogin == null) {
            return null;
        }
        return userLogin.getString("partyId");
    }

    /** True only for the internal system user; used to avoid exposing OTPs in public API responses. */
    private static boolean isSystemUser(Map<String, Object> context) {
        GenericValue userLogin = (GenericValue) context.get("userLogin");
        return userLogin != null && "system".equals(userLogin.getString("userLoginId"));
    }

    /** Issue a new (accessToken, refreshToken) pair and persist them. Returns a map with accessToken, refreshToken, refreshTokenId. */
    private static Map<String, String> issueTokens(Delegator delegator, String partyId, String userLoginId,
            String deviceId, String ipAddress, Timestamp now) throws GenericEntityException {

        int expireMinutes = Integer.parseInt(
                UtilProperties.getPropertyValue("efinance", "efinance.token.expire.minutes",
                        String.valueOf(DEFAULT_TOKEN_EXPIRE_MINUTES)));
        int refreshExpireDays = Integer.parseInt(
                UtilProperties.getPropertyValue("efinance", "efinance.refresh.expire.days",
                        String.valueOf(DEFAULT_REFRESH_EXPIRE_DAYS)));

        // Access token: create JWT with a jti claim so the issued token can be
        // looked up and revoked later. The hash of the actual JWT is stored.
        String tokenId = delegator.getNextSeqId("EfToken");
        Map<String, String> claims = new HashMap<>();
        claims.put("userLoginId", userLoginId);
        claims.put("partyId", partyId);
        claims.put("jti", tokenId);
        String accessJwt = JWTManager.createJwt(delegator, claims, expireMinutes * 60);

        String accessHash = sha256(accessJwt);
        Timestamp accessExpiry = new Timestamp(now.getTime() + (long) expireMinutes * 60 * 1000);
        GenericValue accessToken = delegator.makeValue("EfToken");
        accessToken.set("tokenId", tokenId);
        accessToken.set("tokenHash", accessHash);
        accessToken.set("partyId", partyId);
        accessToken.set("userLoginId", userLoginId);
        accessToken.set("deviceId", deviceId);
        accessToken.set("ipAddress", ipAddress);
        accessToken.set("issuedAt", now);
        accessToken.set("expiresAt", accessExpiry);
        accessToken.set("detail", "ACCESS");
        delegator.create(accessToken);

        // Refresh token
        String rawRefresh = generateSecureToken();
        String refreshHash = sha256(rawRefresh);
        String refreshTokenId = delegator.getNextSeqId("EfRefreshToken");
        Timestamp refreshExpiry = new Timestamp(now.getTime() + (long) refreshExpireDays * 24 * 60 * 60 * 1000);
        GenericValue refreshToken = delegator.makeValue("EfRefreshToken");
        refreshToken.set("refreshTokenId", refreshTokenId);
        refreshToken.set("tokenHash", refreshHash);
        refreshToken.set("partyId", partyId);
        refreshToken.set("userLoginId", userLoginId);
        refreshToken.set("deviceId", deviceId);
        refreshToken.set("issuedAt", now);
        refreshToken.set("expiresAt", refreshExpiry);
        delegator.create(refreshToken);

        Map<String, String> result = new HashMap<>();
        result.put("accessToken", accessJwt);
        result.put("refreshToken", rawRefresh);
        result.put("refreshTokenId", refreshTokenId);
        return result;
    }

    private static void revokeAllTokens(Delegator delegator, String partyId, Timestamp now)
            throws GenericEntityException {
        List<GenericValue> refreshTokens = EntityQuery.use(delegator).from("EfRefreshToken")
                .where(EntityCondition.makeCondition("partyId", partyId),
                       EntityCondition.makeCondition("revokedDate", EntityOperator.EQUALS, null))
                .queryList();
        for (GenericValue t : refreshTokens) {
            t.set("revokedDate", now);
            delegator.store(t);
        }
        List<GenericValue> accessTokens = EntityQuery.use(delegator).from("EfToken")
                .where(EntityCondition.makeCondition("partyId", partyId),
                       EntityCondition.makeCondition("revokedDate", EntityOperator.EQUALS, null))
                .queryList();
        for (GenericValue t : accessTokens) {
            t.set("revokedDate", now);
            delegator.store(t);
        }
    }

    /** Generate a 6-digit numeric OTP. Returns [plainCode, sha256Hash]. */
    private static String[] generateOtp(Delegator delegator) {
        SecureRandom rng = new SecureRandom();
        String plain = String.format("%06d", rng.nextInt(1_000_000));
        return new String[]{plain, sha256(plain)};
    }

    /** Generate a random URL-safe token string (UUID hex). */
    private static String generateSecureToken() {
        byte[] bytes = new byte[32];
        new SecureRandom().nextBytes(bytes);
        StringBuilder sb = new StringBuilder(64);
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    /** Return the hex-encoded SHA-256 hash of the input string (UTF-8). */
    static String sha256(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(64);
            for (byte b : hash) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 not available", e);
        }
    }

    private static void logAudit(Delegator delegator, String partyId, String actionEnumId,
            String targetEntityName, String targetEntityId, String resultEnumId,
            String ipAddress, String deviceId, String detail) {
        try {
            String auditLogId = delegator.getNextSeqId("EfAuditLog");
            GenericValue log = delegator.makeValue("EfAuditLog");
            log.set("auditLogId", auditLogId);
            log.set("partyId", partyId);
            log.set("actionEnumId", actionEnumId);
            log.set("targetEntityName", targetEntityName);
            log.set("targetEntityId", targetEntityId);
            log.set("resultEnumId", resultEnumId);
            log.set("ipAddress", ipAddress);
            log.set("deviceId", deviceId);
            log.set("actionDate", UtilDateTime.nowTimestamp());
            log.set("detail", detail);
            delegator.create(log);
        } catch (GenericEntityException e) {
            Debug.logError(e, "logAudit failed for action " + actionEnumId, MODULE);
        }
    }
}
