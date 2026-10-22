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

import java.sql.Timestamp;
import java.util.ArrayList;
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
import org.apache.ofbiz.service.DispatchContext;
import org.apache.ofbiz.service.ServiceUtil;
import org.apache.ofbiz.efinance.security.EfAccessGuard;

/**
 * STEP W0: corporate account and role management.
 */
public final class EfCorporateServices {

    private static final String RESOURCE = "EfinanceUiLabels";
    private static final String STATUS_ACTIVE = "EFCORP_ACTIVE";
    private static final String ACTION_CORP_CREATE = "EFAUD_CORP_CREATE";
    private static final String ACTION_CORP_USER_SAVE = "EFAUD_CORP_USER_SAVE";
    private static final String RESULT_SUCCESS = "EFAUDRES_SUCCESS";

    private EfCorporateServices() {
    }

    public static Map<String, Object> efCreateCorporateAccount(DispatchContext dctx, Map<String, Object> context) {
        Delegator delegator = dctx.getDelegator();
        Locale locale = (Locale) context.get("locale");
        String corporatePartyId = getString(context, "corporatePartyId");
        String adminMemberPartyId = getString(context, "adminMemberPartyId");
        String companyName = getString(context, "companyName");
        if (UtilValidate.isEmpty(corporatePartyId) || UtilValidate.isEmpty(adminMemberPartyId)) {
            return ServiceUtil.returnError(message(locale, "EfinanceCorporateRequired"));
        }
        try {
            GenericValue member = EntityQuery.use(delegator).from("EfMember")
                    .where("partyId", adminMemberPartyId).queryOne();
            if (member == null) {
                return ServiceUtil.returnError(message(locale, "EfinanceMemberNotFound"));
            }
            GenericValue party = EntityQuery.use(delegator).from("Party")
                    .where("partyId", corporatePartyId).queryOne();
            if (party == null) {
                party = delegator.makeValue("Party");
                party.set("partyId", corporatePartyId);
                party.set("partyTypeId", "PARTY_GROUP");
                delegator.create(party);
            }
            GenericValue group = EntityQuery.use(delegator).from("PartyGroup")
                    .where("partyId", corporatePartyId).queryOne();
            if (group == null) {
                group = delegator.makeValue("PartyGroup");
                group.set("partyId", corporatePartyId);
                group.set("groupName", UtilValidate.isEmpty(companyName) ? corporatePartyId : companyName);
                delegator.create(group);
            }
            Timestamp now = UtilDateTime.nowTimestamp();
            GenericValue account = EntityQuery.use(delegator).from("EfCorporateAccount")
                    .where("corporatePartyId", corporatePartyId).queryOne();
            if (account == null) {
                account = delegator.makeValue("EfCorporateAccount");
                account.set("corporatePartyId", corporatePartyId);
                account.set("adminMemberId", adminMemberPartyId);
                account.set("statusId", STATUS_ACTIVE);
                account.set("createdDate", now);
                delegator.create(account);
            } else {
                account.set("adminMemberId", adminMemberPartyId);
                account.set("statusId", STATUS_ACTIVE);
                delegator.store(account);
            }
            upsertCorporateUser(delegator, corporatePartyId, adminMemberPartyId,
                    "Y", "Y", "Y", "Y", now, null);
            writeAudit(delegator, getPartyIdFromContext(context), ACTION_CORP_CREATE,
                    "EfCorporateAccount", corporatePartyId);
            Map<String, Object> result = ServiceUtil.returnSuccess(message(locale, "EfinanceCorporateCreated"));
            result.put("corporatePartyId", corporatePartyId);
            result.put("adminMemberPartyId", adminMemberPartyId);
            return result;
        } catch (GenericEntityException e) {
            Debug.logError(e, "efCreateCorporateAccount failed", RESOURCE);
            return ServiceUtil.returnError(e.getMessage());
        }
    }

    public static Map<String, Object> efAddCorporateUser(DispatchContext dctx, Map<String, Object> context) {
        Delegator delegator = dctx.getDelegator();
        Locale locale = (Locale) context.get("locale");
        String corporatePartyId = getString(context, "corporatePartyId");
        String memberPartyId = getString(context, "memberPartyId");
        try {
            if (!canManageCorporate(delegator, context, corporatePartyId)) {
                return ServiceUtil.returnError(message(locale, "EfinancePermissionError"));
            }
            if (EntityQuery.use(delegator).from("EfMember").where("partyId", memberPartyId).queryOne() == null) {
                return ServiceUtil.returnError(message(locale, "EfinanceMemberNotFound"));
            }
            Timestamp now = UtilDateTime.nowTimestamp();
            upsertCorporateUser(delegator, corporatePartyId, memberPartyId,
                    indicator(context, "roleApply"), indicator(context, "roleView"),
                    indicator(context, "rolePayment"), indicator(context, "roleAdmin"), now,
                    timestamp(context, "thruDate"));
            writeAudit(delegator, getPartyIdFromContext(context), ACTION_CORP_USER_SAVE,
                    "EfCorporateUser", memberPartyId);
            Map<String, Object> result = ServiceUtil.returnSuccess(message(locale, "EfinanceCorporateUserSaved"));
            result.put("corporatePartyId", corporatePartyId);
            result.put("memberPartyId", memberPartyId);
            return result;
        } catch (GenericEntityException e) {
            Debug.logError(e, "efAddCorporateUser failed", RESOURCE);
            return ServiceUtil.returnError(e.getMessage());
        }
    }

    public static Map<String, Object> efUpdateCorporateUser(DispatchContext dctx, Map<String, Object> context) {
        Delegator delegator = dctx.getDelegator();
        Locale locale = (Locale) context.get("locale");
        String corporatePartyId = getString(context, "corporatePartyId");
        String memberPartyId = getString(context, "memberPartyId");
        try {
            if (!canManageCorporate(delegator, context, corporatePartyId)) {
                return ServiceUtil.returnError(message(locale, "EfinancePermissionError"));
            }
            GenericValue user = findActiveCorporateUser(delegator, corporatePartyId, memberPartyId);
            if (user == null) {
                return ServiceUtil.returnError(message(locale, "EfinanceCorporateUserNotFound"));
            }
            if (context.containsKey("roleApply")) {
                user.set("roleApply", indicator(context, "roleApply"));
            }
            if (context.containsKey("roleView")) {
                user.set("roleView", indicator(context, "roleView"));
            }
            if (context.containsKey("rolePayment")) {
                user.set("rolePayment", indicator(context, "rolePayment"));
            }
            if (context.containsKey("roleAdmin")) {
                user.set("roleAdmin", indicator(context, "roleAdmin"));
            }
            if (context.containsKey("thruDate")) {
                user.set("thruDate", timestamp(context, "thruDate"));
            }
            delegator.store(user);
            writeAudit(delegator, getPartyIdFromContext(context), ACTION_CORP_USER_SAVE,
                    "EfCorporateUser", memberPartyId);
            Map<String, Object> result = ServiceUtil.returnSuccess(message(locale, "EfinanceCorporateUserSaved"));
            result.put("corporatePartyId", corporatePartyId);
            result.put("memberPartyId", memberPartyId);
            return result;
        } catch (GenericEntityException e) {
            Debug.logError(e, "efUpdateCorporateUser failed", RESOURCE);
            return ServiceUtil.returnError(e.getMessage());
        }
    }

    public static Map<String, Object> efListCorporateUsers(DispatchContext dctx, Map<String, Object> context) {
        Delegator delegator = dctx.getDelegator();
        Locale locale = (Locale) context.get("locale");
        String corporatePartyId = getString(context, "corporatePartyId");
        try {
            if (!canManageCorporate(delegator, context, corporatePartyId)) {
                return ServiceUtil.returnError(message(locale, "EfinancePermissionError"));
            }
            Timestamp now = UtilDateTime.nowTimestamp();
            List<GenericValue> users = EntityQuery.use(delegator).from("EfCorporateUser")
                    .where(EntityCondition.makeCondition(EntityOperator.AND,
                            EntityCondition.makeCondition("corporatePartyId", corporatePartyId),
                            EntityCondition.makeCondition("fromDate", EntityOperator.LESS_THAN_EQUAL_TO, now),
                            EntityCondition.makeCondition(EntityOperator.OR,
                                    EntityCondition.makeCondition("thruDate", EntityOperator.EQUALS, null),
                                    EntityCondition.makeCondition("thruDate", EntityOperator.GREATER_THAN_EQUAL_TO, now))))
                    .orderBy("memberPartyId").queryList();
            List<Map<String, Object>> rows = new ArrayList<>();
            for (GenericValue user : users) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("corporatePartyId", user.getString("corporatePartyId"));
                row.put("memberPartyId", user.getString("memberPartyId"));
                row.put("roleApply", user.getString("roleApply"));
                row.put("roleView", user.getString("roleView"));
                row.put("rolePayment", user.getString("rolePayment"));
                row.put("roleAdmin", user.getString("roleAdmin"));
                row.put("fromDate", user.getTimestamp("fromDate"));
                row.put("thruDate", user.getTimestamp("thruDate"));
                rows.add(row);
            }
            Map<String, Object> result = ServiceUtil.returnSuccess();
            result.put("corporateUsers", rows);
            return result;
        } catch (GenericEntityException e) {
            Debug.logError(e, "efListCorporateUsers failed", RESOURCE);
            return ServiceUtil.returnError(e.getMessage());
        }
    }

    public static Map<String, Object> efListCorporateAccounts(DispatchContext dctx, Map<String, Object> context) {
        Delegator delegator = dctx.getDelegator();
        String partyId = getPartyIdFromContext(context);
        try {
            if (UtilValidate.isEmpty(partyId)) {
                return ServiceUtil.returnError(message((Locale) context.get("locale"), "EfinancePermissionError"));
            }
            EntityQuery userQuery = EntityQuery.use(delegator).from("EfCorporateUser");
            if (!isSystemUser(context)) {
                userQuery.where("memberPartyId", partyId);
            }
            List<GenericValue> users = userQuery.queryList();
            List<Map<String, Object>> accounts = new ArrayList<>();
            for (GenericValue user : users) {
                String corporatePartyId = user.getString("corporatePartyId");
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("corporatePartyId", corporatePartyId);
                GenericValue account = EntityQuery.use(delegator).from("EfCorporateAccount")
                        .where("corporatePartyId", corporatePartyId).queryOne();
                if (account != null) {
                    row.put("statusId", account.getString("statusId"));
                    row.put("adminMemberId", account.getString("adminMemberId"));
                }
                row.put("roleApply", user.getString("roleApply"));
                row.put("roleView", user.getString("roleView"));
                row.put("rolePayment", user.getString("rolePayment"));
                row.put("roleAdmin", user.getString("roleAdmin"));
                accounts.add(row);
            }
            Map<String, Object> result = ServiceUtil.returnSuccess();
            result.put("corporateAccounts", accounts);
            return result;
        } catch (GenericEntityException e) {
            Debug.logError(e, "efListCorporateAccounts failed", RESOURCE);
            return ServiceUtil.returnError(e.getMessage());
        }
    }

    private static void upsertCorporateUser(Delegator delegator, String corporatePartyId, String memberPartyId,
            String roleApply, String roleView, String rolePayment, String roleAdmin,
            Timestamp fromDate, Timestamp thruDate) throws GenericEntityException {
        GenericValue existing = findActiveCorporateUser(delegator, corporatePartyId, memberPartyId);
        GenericValue user = existing == null ? delegator.makeValue("EfCorporateUser") : existing;
        if (existing == null) {
            user.set("corporatePartyId", corporatePartyId);
            user.set("memberPartyId", memberPartyId);
            user.set("fromDate", fromDate);
        }
        user.set("roleApply", roleApply);
        user.set("roleView", roleView);
        user.set("rolePayment", rolePayment);
        user.set("roleAdmin", roleAdmin);
        user.set("thruDate", thruDate);
        if (existing == null) {
            delegator.create(user);
        } else {
            delegator.store(user);
        }
    }

    private static GenericValue findActiveCorporateUser(Delegator delegator, String corporatePartyId,
            String memberPartyId) throws GenericEntityException {
        Timestamp now = UtilDateTime.nowTimestamp();
        return EntityQuery.use(delegator).from("EfCorporateUser")
                .where(EntityCondition.makeCondition(EntityOperator.AND,
                        EntityCondition.makeCondition("corporatePartyId", corporatePartyId),
                        EntityCondition.makeCondition("memberPartyId", memberPartyId),
                        EntityCondition.makeCondition("fromDate", EntityOperator.LESS_THAN_EQUAL_TO, now),
                        EntityCondition.makeCondition(EntityOperator.OR,
                                EntityCondition.makeCondition("thruDate", EntityOperator.EQUALS, null),
                                EntityCondition.makeCondition("thruDate", EntityOperator.GREATER_THAN_EQUAL_TO, now))))
                .queryFirst();
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
            audit.set("resultEnumId", RESULT_SUCCESS);
            audit.set("actionDate", UtilDateTime.nowTimestamp());
            delegator.create(audit);
        } catch (GenericEntityException e) {
            Debug.logWarning("Failed to write corporate audit log: " + e.getMessage(), RESOURCE);
        }
    }

    private static boolean canManageCorporate(Delegator delegator, Map<String, Object> context,
            String corporatePartyId) throws GenericEntityException {
        if (isSystemUser(context)) {
            return true;
        }
        String partyId = getPartyIdFromContext(context);
        return EfAccessGuard.isCorporateAdmin(delegator, corporatePartyId, partyId);
    }

    private static boolean isSystemUser(Map<String, Object> context) {
        GenericValue userLogin = (GenericValue) context.get("userLogin");
        return userLogin != null && "system".equals(userLogin.getString("userLoginId"));
    }

    private static String getPartyIdFromContext(Map<String, Object> context) {
        GenericValue userLogin = (GenericValue) context.get("userLogin");
        return userLogin == null ? null : userLogin.getString("partyId");
    }

    private static String indicator(Map<String, Object> context, String field) {
        return "Y".equalsIgnoreCase(getString(context, field)) ? "Y" : "N";
    }

    private static Timestamp timestamp(Map<String, Object> context, String field) {
        Object value = context.get(field);
        if (value instanceof Timestamp) {
            return (Timestamp) value;
        }
        return null;
    }

    private static String getString(Map<String, Object> context, String field) {
        Object value = context.get(field);
        return value == null ? null : value.toString();
    }

    private static String message(Locale locale, String key) {
        return UtilProperties.getMessage(RESOURCE, key, locale);
    }
}
