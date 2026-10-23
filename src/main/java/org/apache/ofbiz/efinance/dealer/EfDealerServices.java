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
package org.apache.ofbiz.efinance.dealer;

import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.apache.ofbiz.base.util.Debug;
import org.apache.ofbiz.base.util.UtilDateTime;
import org.apache.ofbiz.base.util.UtilProperties;
import org.apache.ofbiz.base.util.UtilValidate;
import org.apache.ofbiz.entity.Delegator;
import org.apache.ofbiz.entity.GenericEntityException;
import org.apache.ofbiz.entity.GenericValue;
import org.apache.ofbiz.entity.condition.EntityCondition;
import org.apache.ofbiz.entity.condition.EntityOperator;
import org.apache.ofbiz.entity.util.EntityQuery;
import org.apache.ofbiz.efinance.security.EfAccessGuard;
import org.apache.ofbiz.service.DispatchContext;
import org.apache.ofbiz.service.LocalDispatcher;
import org.apache.ofbiz.service.ServiceUtil;

/**
 * W8 dealer portal services. Dealer staff can act for a customer only while an
 * active EfDealerCustomer consent exists, and only within the role assigned in
 * EfDealerUser.
 */
public final class EfDealerServices {

    private static final String MODULE = EfDealerServices.class.getName();
    private static final String RESOURCE = "EfinanceUiLabels";
    private static final String PROPS = "efinance";
    private static final String DEALER_ACTIVE = "EFDEALER_ACTIVE";
    private static final String CONSENT_ACTIVE = "EFDEALER_CONSENT_OK";
    private static final String CONSENT_REVOKED = "EFDEALER_CONSENT_NO";
    private static final String DRAFT_EDITING = "EFDRF_EDITING";
    private static final String ACTION_DEALER_CREATE = "EFAUD_DEALER_CREATE";
    private static final String ACTION_DEALER_USER = "EFAUD_DEALER_USER";
    private static final String ACTION_DEALER_CONSENT = "EFAUD_DEALER_CONSENT";

    private EfDealerServices() {
    }

    public static Map<String, Object> efGetDealerProfile(DispatchContext dctx, Map<String, Object> context) {
        Delegator delegator = dctx.getDelegator();
        String memberPartyId = getPartyId(context);
        try {
            Timestamp now = UtilDateTime.nowTimestamp();
            List<GenericValue> links = EntityQuery.use(delegator).from("EfDealerUser")
                    .where(EntityCondition.makeCondition(EntityOperator.AND,
                            EntityCondition.makeCondition("memberPartyId", memberPartyId),
                            EntityCondition.makeCondition("fromDate", EntityOperator.LESS_THAN_EQUAL_TO, now),
                            EntityCondition.makeCondition(EntityOperator.OR,
                                    EntityCondition.makeCondition("thruDate", EntityOperator.EQUALS, null),
                                    EntityCondition.makeCondition("thruDate",
                                            EntityOperator.GREATER_THAN_EQUAL_TO, now))))
                    .queryList();
            List<Map<String, Object>> dealers = new ArrayList<>();
            for (GenericValue link : links) {
                GenericValue dealer = EntityQuery.use(delegator).from("EfDealer")
                        .where("dealerPartyId", link.getString("dealerPartyId"),
                                "statusId", DEALER_ACTIVE).queryOne();
                if (dealer == null) {
                    continue;
                }
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("dealerPartyId", dealer.getString("dealerPartyId"));
                m.put("dealerCode", dealer.getString("dealerCode"));
                m.put("dealerName", dealer.getString("dealerName"));
                m.put("roleApply", link.getString("roleApply"));
                m.put("roleView", link.getString("roleView"));
                m.put("rolePayment", link.getString("rolePayment"));
                m.put("roleAdmin", link.getString("roleAdmin"));
                dealers.add(m);
            }
            Map<String, Object> result = ServiceUtil.returnSuccess();
            result.put("dealers", dealers);
            return result;
        } catch (GenericEntityException e) {
            return error(e, "efGetDealerProfile");
        }
    }

    public static Map<String, Object> efCreateDealer(DispatchContext dctx, Map<String, Object> context) {
        Delegator delegator = dctx.getDelegator();
        String dealerName = (String) context.get("dealerName");
        String dealerCode = (String) context.get("dealerCode");
        String adminPartyId = (String) context.get("adminPartyId");
        try {
            if (UtilValidate.isEmpty(dealerName) || UtilValidate.isEmpty(dealerCode)) {
                return ServiceUtil.returnError("Dealer name and code are required.");
            }
            if (EntityQuery.use(delegator).from("EfDealer")
                    .where("dealerCode", dealerCode).queryOne() != null) {
                return ServiceUtil.returnError("Dealer code already exists.");
            }
            Timestamp now = UtilDateTime.nowTimestamp();
            String dealerPartyId = delegator.getNextSeqId("Party");
            GenericValue party = delegator.makeValue("Party");
            party.set("partyId", dealerPartyId);
            party.set("partyTypeId", "PARTY_GROUP");
            delegator.create(party);
            GenericValue group = delegator.makeValue("PartyGroup");
            group.set("partyId", dealerPartyId);
            group.set("groupName", dealerName);
            delegator.create(group);
            GenericValue dealer = delegator.makeValue("EfDealer");
            dealer.set("dealerPartyId", dealerPartyId);
            dealer.set("dealerCode", dealerCode);
            dealer.set("dealerName", dealerName);
            dealer.set("statusId", DEALER_ACTIVE);
            dealer.set("createdDate", now);
            delegator.create(dealer);
            if (UtilValidate.isNotEmpty(adminPartyId)) {
                createOrUpdateDealerUser(delegator, dealerPartyId, adminPartyId,
                        "Y", "Y", "N", "Y", now);
            }
            writeAudit(delegator, getPartyId(context), ACTION_DEALER_CREATE,
                    "EfDealer", dealerPartyId);
            Map<String, Object> result = ServiceUtil.returnSuccess();
            result.put("dealerPartyId", dealerPartyId);
            return result;
        } catch (GenericEntityException e) {
            return error(e, "efCreateDealer");
        }
    }

    public static Map<String, Object> efAddDealerUser(DispatchContext dctx, Map<String, Object> context) {
        Delegator delegator = dctx.getDelegator();
        LocalDispatcher dispatcher = dctx.getDispatcher();
        String dealerPartyId = (String) context.get("dealerPartyId");
        String memberPartyId = (String) context.get("memberPartyId");
        String actorPartyId = getPartyId(context);
        try {
            if (!isDealerAdmin(delegator, dealerPartyId, actorPartyId)
                    && !isEfinanceAdmin(dispatcher, context)) {
                return ServiceUtil.returnError(UtilProperties.getMessage(
                        RESOURCE, "EfinanceDealerPermissionError", (Locale) context.get("locale")));
            }
            if (EntityQuery.use(delegator).from("Party")
                    .where("partyId", memberPartyId).queryOne() == null) {
                return ServiceUtil.returnError("Member party not found.");
            }
            Timestamp now = UtilDateTime.nowTimestamp();
            createOrUpdateDealerUser(delegator, dealerPartyId, memberPartyId,
                    flag(context, "roleApply"), flag(context, "roleView"),
                    flag(context, "rolePayment"), flag(context, "roleAdmin"), now);
            writeAudit(delegator, actorPartyId, ACTION_DEALER_USER,
                    "EfDealerUser", dealerPartyId + "/" + memberPartyId);
            Map<String, Object> result = ServiceUtil.returnSuccess();
            result.put("dealerPartyId", dealerPartyId);
            result.put("memberPartyId", memberPartyId);
            return result;
        } catch (GenericEntityException e) {
            return error(e, "efAddDealerUser");
        }
    }

    public static Map<String, Object> efListDealerUsers(DispatchContext dctx, Map<String, Object> context) {
        Delegator delegator = dctx.getDelegator();
        String dealerPartyId = (String) context.get("dealerPartyId");
        try {
            if (!isDealerAdmin(delegator, dealerPartyId, getPartyId(context))) {
                return ServiceUtil.returnError(UtilProperties.getMessage(
                        RESOURCE, "EfinanceDealerPermissionError", (Locale) context.get("locale")));
            }
            List<GenericValue> rows = EntityQuery.use(delegator).from("EfDealerUser")
                    .where("dealerPartyId", dealerPartyId).orderBy("memberPartyId").queryList();
            List<Map<String, Object>> users = new ArrayList<>();
            for (GenericValue row : rows) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("memberPartyId", row.getString("memberPartyId"));
                m.put("memberName", partyName(delegator, row.getString("memberPartyId")));
                m.put("roleApply", row.getString("roleApply"));
                m.put("roleView", row.getString("roleView"));
                m.put("rolePayment", row.getString("rolePayment"));
                m.put("roleAdmin", row.getString("roleAdmin"));
                m.put("fromDate", row.getTimestamp("fromDate"));
                m.put("thruDate", row.getTimestamp("thruDate"));
                users.add(m);
            }
            Map<String, Object> result = ServiceUtil.returnSuccess();
            result.put("users", users);
            return result;
        } catch (GenericEntityException e) {
            return error(e, "efListDealerUsers");
        }
    }

    public static Map<String, Object> efRegisterDealerCustomer(DispatchContext dctx, Map<String, Object> context) {
        Delegator delegator = dctx.getDelegator();
        String dealerPartyId = (String) context.get("dealerPartyId");
        String customerPartyId = (String) context.get("customerPartyId");
        String consentReference = (String) context.get("consentReference");
        try {
            if (!EfAccessGuard.hasDealerRole(delegator, dealerPartyId, getPartyId(context), "roleApply")) {
                return ServiceUtil.returnError(UtilProperties.getMessage(
                        RESOURCE, "EfinanceDealerPermissionError", (Locale) context.get("locale")));
            }
            if (UtilValidate.isEmpty(consentReference)) {
                return ServiceUtil.returnError("A customer consent reference is required.");
            }
            if (EntityQuery.use(delegator).from("Party")
                    .where("partyId", customerPartyId).queryOne() == null) {
                return ServiceUtil.returnError("Customer party not found.");
            }
            Timestamp now = UtilDateTime.nowTimestamp();
            GenericValue consent = EntityQuery.use(delegator).from("EfDealerCustomer")
                    .where("dealerPartyId", dealerPartyId, "customerPartyId", customerPartyId)
                    .queryOne();
            boolean newConsent = consent == null;
            if (newConsent) {
                consent = delegator.makeValue("EfDealerCustomer");
                consent.set("dealerPartyId", dealerPartyId);
                consent.set("customerPartyId", customerPartyId);
                consent.set("fromDate", now);
                consent.set("createdByPartyId", getPartyId(context));
                consent.set("createdDate", now);
            }
            consent.set("statusId", CONSENT_ACTIVE);
            consent.set("consentReference", consentReference);
            consent.set("consentDate", now);
            consent.set("thruDate", null);
            if (newConsent) {
                delegator.create(consent);
            } else {
                delegator.store(consent);
            }
            writeAudit(delegator, getPartyId(context), ACTION_DEALER_CONSENT,
                    "EfDealerCustomer", dealerPartyId + "/" + customerPartyId);
            Map<String, Object> result = ServiceUtil.returnSuccess();
            result.put("dealerPartyId", dealerPartyId);
            result.put("customerPartyId", customerPartyId);
            result.put("statusId", CONSENT_ACTIVE);
            return result;
        } catch (GenericEntityException e) {
            return error(e, "efRegisterDealerCustomer");
        }
    }

    public static Map<String, Object> efRevokeDealerCustomer(DispatchContext dctx, Map<String, Object> context) {
        Delegator delegator = dctx.getDelegator();
        String dealerPartyId = (String) context.get("dealerPartyId");
        String customerPartyId = (String) context.get("customerPartyId");
        try {
            if (!isDealerAdmin(delegator, dealerPartyId, getPartyId(context))) {
                return ServiceUtil.returnError(UtilProperties.getMessage(
                        RESOURCE, "EfinanceDealerPermissionError", (Locale) context.get("locale")));
            }
            GenericValue consent = EntityQuery.use(delegator).from("EfDealerCustomer")
                    .where("dealerPartyId", dealerPartyId, "customerPartyId", customerPartyId).queryOne();
            if (consent == null) {
                return ServiceUtil.returnError("Dealer customer consent not found.");
            }
            consent.set("statusId", CONSENT_REVOKED);
            consent.set("thruDate", UtilDateTime.nowTimestamp());
            delegator.store(consent);
            return ServiceUtil.returnSuccess();
        } catch (GenericEntityException e) {
            return error(e, "efRevokeDealerCustomer");
        }
    }

    public static Map<String, Object> efListDealerCustomers(DispatchContext dctx, Map<String, Object> context) {
        Delegator delegator = dctx.getDelegator();
        String dealerPartyId = (String) context.get("dealerPartyId");
        try {
            if (!EfAccessGuard.hasDealerRole(delegator, dealerPartyId, getPartyId(context), "roleView")) {
                return ServiceUtil.returnError(UtilProperties.getMessage(
                        RESOURCE, "EfinanceDealerPermissionError", (Locale) context.get("locale")));
            }
            List<GenericValue> rows = EntityQuery.use(delegator).from("EfDealerCustomer")
                    .where("dealerPartyId", dealerPartyId).orderBy("customerPartyId").queryList();
            List<Map<String, Object>> customers = new ArrayList<>();
            for (GenericValue row : rows) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("customerPartyId", row.getString("customerPartyId"));
                m.put("customerName", partyName(delegator, row.getString("customerPartyId")));
                m.put("statusId", row.getString("statusId"));
                m.put("consentReference", row.getString("consentReference"));
                m.put("consentDate", row.getTimestamp("consentDate"));
                m.put("fromDate", row.getTimestamp("fromDate"));
                m.put("thruDate", row.getTimestamp("thruDate"));
                customers.add(m);
            }
            Map<String, Object> result = ServiceUtil.returnSuccess();
            result.put("customers", customers);
            return result;
        } catch (GenericEntityException e) {
            return error(e, "efListDealerCustomers");
        }
    }

    public static Map<String, Object> efCreateDealerDraft(DispatchContext dctx, Map<String, Object> context) {
        Delegator delegator = dctx.getDelegator();
        String dealerPartyId = (String) context.get("dealerPartyId");
        String customerPartyId = (String) context.get("customerPartyId");
        String productId = (String) context.get("productId");
        String applicationTypeId = (String) context.get("applicationTypeId");
        try {
            if (!EfAccessGuard.hasDealerRole(delegator, dealerPartyId, getPartyId(context), "roleApply")
                    || !EfAccessGuard.isDealerCustomer(delegator, dealerPartyId, customerPartyId)) {
                return ServiceUtil.returnError(UtilProperties.getMessage(
                        RESOURCE, "EfinanceDealerPermissionError", (Locale) context.get("locale")));
            }
            Timestamp now = UtilDateTime.nowTimestamp();
            GenericValue existing = EntityQuery.use(delegator).from("EfApplicationDraft")
                    .where(EntityCondition.makeCondition(EntityOperator.AND,
                            EntityCondition.makeCondition("memberPartyId", customerPartyId),
                            EntityCondition.makeCondition("dealerPartyId", dealerPartyId),
                            EntityCondition.makeCondition("productId", productId),
                            EntityCondition.makeCondition("applicationTypeId", applicationTypeId),
                            EntityCondition.makeCondition("statusId", DRAFT_EDITING),
                            EntityCondition.makeCondition("draftJson", "{}"),
                            EntityCondition.makeCondition("expiresAt", EntityOperator.GREATER_THAN, now)))
                    .orderBy("-createdDate").queryFirst();
            if (existing != null) {
                Map<String, Object> result = ServiceUtil.returnSuccess();
                result.put("draftId", existing.getString("draftId"));
                result.put("expiresAt", existing.getTimestamp("expiresAt"));
                result.put("existingDraft", true);
                return result;
            }
            int expireDays = UtilProperties.getPropertyAsInteger(PROPS, "efinance.draft.expire.days", 30);
            Calendar cal = Calendar.getInstance();
            cal.setTimeInMillis(now.getTime());
            cal.add(Calendar.DAY_OF_YEAR, expireDays);
            Timestamp expiresAt = new Timestamp(cal.getTimeInMillis());
            String draftId = delegator.getNextSeqId("EfApplicationDraft");
            GenericValue draft = delegator.makeValue("EfApplicationDraft");
            draft.set("draftId", draftId);
            draft.set("memberPartyId", customerPartyId);
            draft.set("dealerPartyId", dealerPartyId);
            draft.set("productId", productId);
            draft.set("applicationTypeId", applicationTypeId);
            draft.set("currentStep", 1L);
            draft.set("totalSteps", (long) ("LEASE".equals(applicationTypeId) ? 4 : 3));
            draft.set("draftJson", "{}");
            draft.set("statusId", DRAFT_EDITING);
            draft.set("sourceEnumId", "EFSRC_DEALER");
            draft.set("expiresAt", expiresAt);
            draft.set("createdDate", now);
            draft.set("lastModifiedDate", now);
            delegator.create(draft);
            Map<String, Object> result = ServiceUtil.returnSuccess();
            result.put("draftId", draftId);
            result.put("expiresAt", expiresAt);
            result.put("existingDraft", false);
            return result;
        } catch (GenericEntityException e) {
            return error(e, "efCreateDealerDraft");
        }
    }

    public static Map<String, Object> efListDealerDrafts(DispatchContext dctx, Map<String, Object> context) {
        Delegator delegator = dctx.getDelegator();
        String dealerPartyId = (String) context.get("dealerPartyId");
        try {
            if (!EfAccessGuard.hasDealerRole(delegator, dealerPartyId, getPartyId(context), "roleView")) {
                return ServiceUtil.returnError(UtilProperties.getMessage(
                        RESOURCE, "EfinanceDealerPermissionError", (Locale) context.get("locale")));
            }
            List<GenericValue> rows = EntityQuery.use(delegator).from("EfApplicationDraft")
                    .where("dealerPartyId", dealerPartyId, "statusId", DRAFT_EDITING)
                    .orderBy("-lastModifiedDate").queryList();
            List<Map<String, Object>> drafts = new ArrayList<>();
            for (GenericValue row : rows) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("draftId", row.getString("draftId"));
                m.put("customerPartyId", row.getString("memberPartyId"));
                m.put("customerName", partyName(delegator, row.getString("memberPartyId")));
                m.put("productId", row.getString("productId"));
                m.put("applicationTypeId", row.getString("applicationTypeId"));
                m.put("statusId", row.getString("statusId"));
                m.put("currentStep", row.getLong("currentStep"));
                m.put("totalSteps", row.getLong("totalSteps"));
                m.put("expiresAt", row.getTimestamp("expiresAt"));
                drafts.add(m);
            }
            Map<String, Object> result = ServiceUtil.returnSuccess();
            result.put("drafts", drafts);
            return result;
        } catch (GenericEntityException e) {
            return error(e, "efListDealerDrafts");
        }
    }

    public static Map<String, Object> efListDealerApplications(DispatchContext dctx, Map<String, Object> context) {
        Delegator delegator = dctx.getDelegator();
        String dealerPartyId = (String) context.get("dealerPartyId");
        try {
            if (!EfAccessGuard.hasDealerRole(delegator, dealerPartyId, getPartyId(context), "roleView")) {
                return ServiceUtil.returnError(UtilProperties.getMessage(
                        RESOURCE, "EfinanceDealerPermissionError", (Locale) context.get("locale")));
            }
            List<String> customerIds = activeCustomerIds(delegator, dealerPartyId);
            List<Map<String, Object>> applications = new ArrayList<>();
            if (!customerIds.isEmpty()) {
                List<GenericValue> links = EntityQuery.use(delegator).from("EfApplicationLink")
                        .where(EntityCondition.makeCondition("memberPartyId", EntityOperator.IN, customerIds))
                        .orderBy("-linkedDate").queryList();
                for (GenericValue link : links) {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("linkId", link.getString("linkId"));
                    m.put("customerPartyId", link.getString("memberPartyId"));
                    m.put("customerName", partyName(delegator, link.getString("memberPartyId")));
                    m.put("financeApplicationId", link.getString("financeApplicationId"));
                    m.put("financeApplicationTypeId", link.getString("financeApplicationTypeId"));
                    m.put("dealerPartyId", link.getString("dealerPartyId"));
                    m.put("linkedDate", link.getTimestamp("linkedDate"));
                    applications.add(m);
                }
            }
            Map<String, Object> result = ServiceUtil.returnSuccess();
            result.put("applications", applications);
            return result;
        } catch (GenericEntityException e) {
            return error(e, "efListDealerApplications");
        }
    }

    public static Map<String, Object> efListDealerContracts(DispatchContext dctx, Map<String, Object> context) {
        Delegator delegator = dctx.getDelegator();
        String dealerPartyId = (String) context.get("dealerPartyId");
        try {
            if (!EfAccessGuard.hasDealerRole(delegator, dealerPartyId, getPartyId(context), "roleView")) {
                return ServiceUtil.returnError(UtilProperties.getMessage(
                        RESOURCE, "EfinanceDealerPermissionError", (Locale) context.get("locale")));
            }
            List<String> customerIds = activeCustomerIds(delegator, dealerPartyId);
            List<Map<String, Object>> contracts = new ArrayList<>();
            if (!customerIds.isEmpty()) {
                List<GenericValue> loans = EntityQuery.use(delegator).from("LoanAgreement")
                        .where(EntityCondition.makeCondition("borrowerPartyId", EntityOperator.IN, customerIds))
                        .orderBy("-agreementDate").queryList();
                for (GenericValue agreement : loans) {
                    contracts.add(contractMap(agreement, "LOAN", "loanAgreementId",
                            "borrowerPartyId"));
                }
                List<GenericValue> leases = EntityQuery.use(delegator).from("LeaseAgreement")
                        .where(EntityCondition.makeCondition("lesseePartyId", EntityOperator.IN, customerIds))
                        .orderBy("-agreementDate").queryList();
                for (GenericValue agreement : leases) {
                    contracts.add(contractMap(agreement, "LEASE", "leaseAgreementId",
                            "lesseePartyId"));
                }
            }
            Map<String, Object> result = ServiceUtil.returnSuccess();
            result.put("contracts", contracts);
            return result;
        } catch (GenericEntityException e) {
            return error(e, "efListDealerContracts");
        }
    }

    private static void createOrUpdateDealerUser(Delegator delegator, String dealerPartyId,
            String memberPartyId, String apply, String view, String payment, String admin,
            Timestamp now) throws GenericEntityException {
        GenericValue existing = EntityQuery.use(delegator).from("EfDealerUser")
                .where(EntityCondition.makeCondition(EntityOperator.AND,
                        EntityCondition.makeCondition("dealerPartyId", dealerPartyId),
                        EntityCondition.makeCondition("memberPartyId", memberPartyId),
                        EntityCondition.makeCondition(EntityOperator.OR,
                                EntityCondition.makeCondition("thruDate", EntityOperator.EQUALS, null),
                                EntityCondition.makeCondition("thruDate",
                                        EntityOperator.GREATER_THAN_EQUAL_TO, now))))
                .queryFirst();
        GenericValue user = existing != null ? existing : delegator.makeValue("EfDealerUser");
        user.set("dealerPartyId", dealerPartyId);
        user.set("memberPartyId", memberPartyId);
        user.set("fromDate", existing != null ? existing.getTimestamp("fromDate") : now);
        user.set("roleApply", apply);
        user.set("roleView", view);
        user.set("rolePayment", payment);
        user.set("roleAdmin", admin);
        user.set("thruDate", null);
        if (existing != null) {
            delegator.store(user);
        } else {
            delegator.create(user);
        }
    }

    private static boolean isDealerAdmin(Delegator delegator, String dealerPartyId, String memberPartyId)
            throws GenericEntityException {
        return EfAccessGuard.hasDealerRole(delegator, dealerPartyId, memberPartyId, "roleAdmin");
    }

    private static boolean isEfinanceAdmin(LocalDispatcher dispatcher, Map<String, Object> context) {
        try {
            Map<String, Object> result = dispatcher.runSync("efinanceAdminPermission",
                    Map.of("userLogin", context.get("userLogin")));
            return ServiceUtil.isSuccess(result) && Boolean.TRUE.equals(result.get("hasPermission"));
        } catch (Exception e) {
            Debug.logWarning("Unable to check efinance admin permission: " + e.getMessage(), MODULE);
            return false;
        }
    }

    private static String flag(Map<String, Object> context, String name) {
        Object value = context.get(name);
        return Boolean.TRUE.equals(value) || "Y".equals(value) || "true".equalsIgnoreCase(String.valueOf(value))
                ? "Y" : "N";
    }

    private static List<String> activeCustomerIds(Delegator delegator, String dealerPartyId)
            throws GenericEntityException {
        Timestamp now = UtilDateTime.nowTimestamp();
        List<GenericValue> rows = EntityQuery.use(delegator).from("EfDealerCustomer")
                .where(EntityCondition.makeCondition(EntityOperator.AND,
                        EntityCondition.makeCondition("dealerPartyId", dealerPartyId),
                        EntityCondition.makeCondition("statusId", CONSENT_ACTIVE),
                        EntityCondition.makeCondition(EntityOperator.OR,
                                EntityCondition.makeCondition("thruDate", EntityOperator.EQUALS, null),
                                EntityCondition.makeCondition("thruDate",
                                        EntityOperator.GREATER_THAN_EQUAL_TO, now))))
                .queryList();
        List<String> ids = new ArrayList<>();
        for (GenericValue row : rows) {
            ids.add(row.getString("customerPartyId"));
        }
        return ids;
    }

    private static Map<String, Object> contractMap(GenericValue agreement, String type,
            String idField, String ownerField) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("agreementId", agreement.getString(idField));
        m.put("agreementTypeId", type);
        m.put("customerPartyId", agreement.getString(ownerField));
        m.put("statusId", agreement.getString("statusId"));
        m.put("principalAmount", "LOAN".equals(type)
                ? agreement.getBigDecimal("principalAmount")
                : agreement.getBigDecimal("acquisitionCost"));
        m.put("currencyUomId", agreement.getString("currencyUomId"));
        m.put("agreementDate", agreement.getTimestamp("agreementDate"));
        return m;
    }

    private static String partyName(Delegator delegator, String partyId) throws GenericEntityException {
        GenericValue person = EntityQuery.use(delegator).from("Person")
                .where("partyId", partyId).queryOne();
        if (person != null) {
            String first = person.getString("firstName");
            String last = person.getString("lastName");
            return (UtilValidate.isNotEmpty(first) ? first : "") + " "
                    + (UtilValidate.isNotEmpty(last) ? last : "");
        }
        GenericValue group = EntityQuery.use(delegator).from("PartyGroup")
                .where("partyId", partyId).queryOne();
        return group == null ? partyId : group.getString("groupName");
    }

    private static String getPartyId(Map<String, Object> context) {
        GenericValue userLogin = (GenericValue) context.get("userLogin");
        return userLogin == null ? null : userLogin.getString("partyId");
    }

    private static Map<String, Object> error(GenericEntityException e, String method) {
        Debug.logError(e, method, MODULE);
        return ServiceUtil.returnError(e.getMessage());
    }

    private static void writeAudit(Delegator delegator, String partyId, String action,
            String entityName, String entityId) {
        try {
            GenericValue audit = delegator.makeValue("EfAuditLog");
            audit.set("auditLogId", delegator.getNextSeqId("EfAuditLog"));
            audit.set("partyId", partyId);
            audit.set("actionEnumId", action);
            audit.set("targetEntityName", entityName);
            audit.set("targetEntityId", entityId);
            audit.set("resultEnumId", "EFAUDRES_SUCCESS");
            audit.set("actionDate", UtilDateTime.nowTimestamp());
            delegator.create(audit);
        } catch (GenericEntityException e) {
            Debug.logWarning("Unable to write dealer audit: " + e.getMessage(), MODULE);
        }
    }
}
