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
package org.apache.ofbiz.efinance.security;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.apache.ofbiz.base.util.UtilDateTime;
import org.apache.ofbiz.base.util.UtilValidate;
import org.apache.ofbiz.entity.Delegator;
import org.apache.ofbiz.entity.GenericEntityException;
import org.apache.ofbiz.entity.GenericValue;
import org.apache.ofbiz.entity.condition.EntityCondition;
import org.apache.ofbiz.entity.condition.EntityOperator;
import org.apache.ofbiz.entity.util.EntityQuery;

/**
 * STEP W0 security guard.
 *
 * <p>The guard centralizes checks that were previously repeated in each service:
 * active member, access-token revocation, and row-level ownership. REST filters
 * and service entry points should call this class rather than re-implementing
 * the checks.</p>
 *
 * <p>Current integration status: the permission service is applied to customer
 * services, token hashes now correspond to the issued JWT, and the signing
 * service calls KYC/consent checks. A request-level filter for every REST call
 * is still to be added (see SPEC 3.4).</p>
 */
public final class EfAccessGuard {

    private EfAccessGuard() {
    }

    /** True when the user's EfMember is ACTIVE. */
    public static boolean isActiveMember(Delegator delegator, GenericValue userLogin)
            throws GenericEntityException {
        if (userLogin == null || UtilValidate.isEmpty(userLogin.getString("userLoginId"))) {
            return false;
        }
        GenericValue member = EntityQuery.use(delegator).from("EfMember")
                .where("userLoginId", userLogin.getString("userLoginId")).queryOne();
        return member != null && "EFMBR_ACTIVE".equals(member.getString("statusId"));
    }

    /**
     * True when the JWT is present in EfToken, not revoked and not expired.
     * The access token hash stored by EfMemberServices is the SHA-256 of the JWT.
     */
    public static boolean isAccessTokenActive(Delegator delegator, String jwt)
            throws GenericEntityException {
        if (UtilValidate.isEmpty(jwt)) {
            return false;
        }
        GenericValue token = EntityQuery.use(delegator).from("EfToken")
                .where("tokenHash", sha256(jwt)).queryOne();
        if (token == null || token.getTimestamp("revokedDate") != null
                || !"ACCESS".equals(token.getString("detail"))) {
            return false;
        }
        Timestamp expiresAt = token.getTimestamp("expiresAt");
        return expiresAt == null || !expiresAt.before(UtilDateTime.nowTimestamp());
    }

    /** True when the short-lived re-authentication token is still valid. */
    public static boolean isReauthTokenActive(Delegator delegator, String partyId, String rawToken)
            throws GenericEntityException {
        if (UtilValidate.isEmpty(partyId) || UtilValidate.isEmpty(rawToken)) {
            return false;
        }
        GenericValue token = EntityQuery.use(delegator).from("EfToken")
                .where("tokenHash", sha256(rawToken), "partyId", partyId, "detail", "REAUTH").queryOne();
        if (token == null || token.getTimestamp("revokedDate") != null) {
            return false;
        }
        Timestamp expiresAt = token.getTimestamp("expiresAt");
        return expiresAt != null && !expiresAt.before(UtilDateTime.nowTimestamp());
    }

    /** Validate and consume a one-time re-authentication token. */
    public static boolean consumeReauthToken(Delegator delegator, String partyId, String rawToken)
            throws GenericEntityException {
        if (!isReauthTokenActive(delegator, partyId, rawToken)) {
            return false;
        }
        GenericValue token = EntityQuery.use(delegator).from("EfToken")
                .where("tokenHash", sha256(rawToken), "partyId", partyId, "detail", "REAUTH").queryOne();
        token.set("revokedDate", UtilDateTime.nowTimestamp());
        delegator.store(token);
        return true;
    }

    /** True when the member has the given corporate role in an active corporate account. */
    public static boolean hasCorporateRole(Delegator delegator, String corporatePartyId, String memberPartyId,
            String roleField) throws GenericEntityException {
        if (UtilValidate.isEmpty(corporatePartyId) || UtilValidate.isEmpty(memberPartyId)
                || UtilValidate.isEmpty(roleField)) {
            return false;
        }
        GenericValue account = EntityQuery.use(delegator).from("EfCorporateAccount")
                .where("corporatePartyId", corporatePartyId, "statusId", "EFCORP_ACTIVE").queryOne();
        if (account == null) {
            return false;
        }
        Timestamp now = UtilDateTime.nowTimestamp();
        return EntityQuery.use(delegator).from("EfCorporateUser")
                .where(EntityCondition.makeCondition(EntityOperator.AND,
                        EntityCondition.makeCondition("corporatePartyId", corporatePartyId),
                        EntityCondition.makeCondition("memberPartyId", memberPartyId),
                        EntityCondition.makeCondition(roleField, "Y"),
                        EntityCondition.makeCondition("fromDate",
                                EntityOperator.LESS_THAN_EQUAL_TO, now),
                        EntityCondition.makeCondition(EntityOperator.OR,
                                EntityCondition.makeCondition("thruDate", EntityOperator.EQUALS, null),
                                EntityCondition.makeCondition("thruDate",
                                        EntityOperator.GREATER_THAN_EQUAL_TO, now))))
                .queryFirst() != null;
    }

    /**
     * Active corporate accounts in which the member has the requested role.
     * The result is used to widen contract queries to corporate contracts.
     */
    public static List<String> getCorporatePartyIds(Delegator delegator, String memberPartyId,
            String roleField) throws GenericEntityException {
        if (UtilValidate.isEmpty(memberPartyId) || UtilValidate.isEmpty(roleField)) {
            return new ArrayList<>();
        }
        Timestamp now = UtilDateTime.nowTimestamp();
        List<GenericValue> users = EntityQuery.use(delegator).from("EfCorporateUser")
                .where(EntityCondition.makeCondition(EntityOperator.AND,
                        EntityCondition.makeCondition("memberPartyId", memberPartyId),
                        EntityCondition.makeCondition(roleField, "Y"),
                        EntityCondition.makeCondition("fromDate",
                                EntityOperator.LESS_THAN_EQUAL_TO, now),
                        EntityCondition.makeCondition(EntityOperator.OR,
                                EntityCondition.makeCondition("thruDate", EntityOperator.EQUALS, null),
                                EntityCondition.makeCondition("thruDate",
                                        EntityOperator.GREATER_THAN_EQUAL_TO, now))))
                .queryList();
        Set<String> corporatePartyIds = new LinkedHashSet<>();
        for (GenericValue user : users) {
            String corporatePartyId = user.getString("corporatePartyId");
            GenericValue account = EntityQuery.use(delegator).from("EfCorporateAccount")
                    .where("corporatePartyId", corporatePartyId, "statusId", "EFCORP_ACTIVE").queryOne();
            if (account != null) {
                corporatePartyIds.add(corporatePartyId);
            }
        }
        return new ArrayList<>(corporatePartyIds);
    }

    public static boolean isCorporateAdmin(Delegator delegator, String corporatePartyId, String memberPartyId)
            throws GenericEntityException {
        return hasCorporateRole(delegator, corporatePartyId, memberPartyId, "roleAdmin");
    }

    /**
     * True when the member owns the draft personally, or has the requested
     * role on the draft's corporate account.
     */
    public static boolean isDraftAccessible(Delegator delegator, GenericValue draft, String partyId,
            String roleField) throws GenericEntityException {
        if (draft == null || UtilValidate.isEmpty(partyId)) {
            return false;
        }
        if (partyId.equals(draft.getString("memberPartyId"))) {
            return true;
        }
        String corporatePartyId = draft.getString("corporatePartyId");
        if (UtilValidate.isEmpty(corporatePartyId)) {
            return false;
        }
        return hasCorporateRole(delegator, corporatePartyId, partyId, roleField);
    }

    /** True when the draft belongs to the logged-in member or corporate account. */
    public static boolean isDraftOwned(Delegator delegator, String draftId, GenericValue userLogin)
            throws GenericEntityException {
        if (userLogin == null || UtilValidate.isEmpty(draftId)) {
            return false;
        }
        GenericValue draft = EntityQuery.use(delegator).from("EfApplicationDraft")
                .where("draftId", draftId).queryOne();
        return isDraftAccessible(delegator, draft, userLogin.getString("partyId"), "roleView");
    }

    /**
     * True when the member may access a contract personally, or through an
     * active corporate account with the requested role.
     */
    public static boolean isContractAccessible(Delegator delegator, GenericValue agreement,
            String partyId, String roleField) throws GenericEntityException {
        if (agreement == null || UtilValidate.isEmpty(partyId)) {
            return false;
        }
        String ownerPartyId = "LeaseAgreement".equals(agreement.getEntityName())
                ? agreement.getString("lesseePartyId")
                : agreement.getString("borrowerPartyId");
        if (UtilValidate.isEmpty(ownerPartyId)) {
            return false;
        }
        if (partyId.equals(ownerPartyId)) {
            return true;
        }
        return hasCorporateRole(delegator, ownerPartyId, partyId, roleField);
    }

    /** True when the loan or lease agreement belongs to the logged-in member. */
    public static boolean isContractOwned(Delegator delegator, String agreementId, String agreementTypeId,
            GenericValue userLogin) throws GenericEntityException {
        if (userLogin == null || UtilValidate.isEmpty(agreementId)) {
            return false;
        }
        String partyId = userLogin.getString("partyId");
        GenericValue agreement;
        if ("LEASE".equals(agreementTypeId)) {
            agreement = EntityQuery.use(delegator).from("LeaseAgreement")
                    .where("leaseAgreementId", agreementId).queryOne();
        } else {
            agreement = EntityQuery.use(delegator).from("LoanAgreement")
                    .where("loanAgreementId", agreementId).queryOne();
        }
        return isContractAccessible(delegator, agreement, partyId, "roleView");
    }

    /** True when the KYC check belongs to the logged-in member. */
    public static boolean isKycOwned(Delegator delegator, String kycCheckId, GenericValue userLogin)
            throws GenericEntityException {
        if (userLogin == null || UtilValidate.isEmpty(kycCheckId)) {
            return false;
        }
        GenericValue check = EntityQuery.use(delegator).from("EfKycCheck")
                .where("kycCheckId", kycCheckId).queryOne();
        return check != null && userLogin.getString("partyId").equals(check.getString("memberPartyId"));
    }

    /** True when the service request belongs to the logged-in member. */
    public static boolean isServiceRequestOwned(Delegator delegator, String serviceRequestId,
            GenericValue userLogin) throws GenericEntityException {
        if (userLogin == null || UtilValidate.isEmpty(serviceRequestId)) {
            return false;
        }
        GenericValue request = EntityQuery.use(delegator).from("EfServiceRequest")
                .where("serviceRequestId", serviceRequestId).queryOne();
        return request != null && userLogin.getString("partyId").equals(request.getString("memberPartyId"));
    }

    /** True when the delivered document belongs to the logged-in member. */
    public static boolean isDeliveredDocumentOwned(Delegator delegator, String deliveredDocId,
            GenericValue userLogin) throws GenericEntityException {
        if (userLogin == null || UtilValidate.isEmpty(deliveredDocId)) {
            return false;
        }
        GenericValue document = EntityQuery.use(delegator).from("EfDeliveredDocument")
                .where("deliveredDocId", deliveredDocId).queryOne();
        return document != null && userLogin.getString("partyId").equals(document.getString("memberPartyId"));
    }

    /** True when the document request belongs to the logged-in member. */
    public static boolean isDocumentRequestOwned(Delegator delegator, String docRequestId,
            GenericValue userLogin) throws GenericEntityException {
        if (userLogin == null || UtilValidate.isEmpty(docRequestId)) {
            return false;
        }
        GenericValue request = EntityQuery.use(delegator).from("EfDocumentRequest")
                .where("docRequestId", docRequestId).queryOne();
        return request != null && userLogin.getString("partyId").equals(request.getString("memberPartyId"));
    }

    /** SHA-256 hex digest. */
    public static String sha256(String value) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}
