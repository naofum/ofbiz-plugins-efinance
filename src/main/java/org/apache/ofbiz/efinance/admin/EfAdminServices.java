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
package org.apache.ofbiz.efinance.admin;

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

/**
 * Staff-only operations that are not exposed as customer APIs.
 */
public final class EfAdminServices {

    private static final String MODULE = EfAdminServices.class.getName();
    private static final String RESOURCE = "EfinanceUiLabels";

    private static final String MEMBER_LOCKED = "EFMBR_LOCKED";
    private static final String MEMBER_ACTIVE = "EFMBR_ACTIVE";
    private static final String SVCREQ_COMPLETE = "EFSVCREQ_COMPLETE";
    private static final String ACTION_SVCREQ_UPDATE = "EFAUD_SVCREQ_UPDATE";
    private static final String ACTION_MEMBER_UNLOCK = "EFAUD_MEMBER_UNLOCK";
    private static final String RESULT_SUCCESS = "EFAUDRES_SUCCESS";

    private EfAdminServices() {
    }

    public static Map<String, Object> efUpdateServiceRequestStatus(DispatchContext dctx, Map<String, Object> context) {
        Delegator delegator = dctx.getDelegator();
        Locale locale = (Locale) context.get("locale");
        String serviceRequestId = (String) context.get("serviceRequestId");
        String statusId = (String) context.get("statusId");
        String assignedToPartyId = (String) context.get("assignedToPartyId");
        String staffNotes = (String) context.get("staffNotes");

        try {
            GenericValue request = EntityQuery.use(delegator).from("EfServiceRequest")
                    .where("serviceRequestId", serviceRequestId).queryOne();
            if (request == null) {
                return ServiceUtil.returnError(message(locale, "EfinanceServiceRequestNotFound"));
            }
            String currentStatus = request.getString("statusId");
            if (UtilValidate.isNotEmpty(statusId) && !statusId.equals(currentStatus)) {
                GenericValue validChange = EntityQuery.use(delegator).from("StatusValidChange")
                        .where("statusId", currentStatus, "statusIdTo", statusId).queryOne();
                if (validChange == null) {
                    return ServiceUtil.returnError(message(locale, "EfinanceInvalidStatusTransition"));
                }
                request.set("statusId", statusId);
                if (SVCREQ_COMPLETE.equals(statusId)) {
                    request.set("completedDate", UtilDateTime.nowTimestamp());
                }
            }
            if (context.containsKey("assignedToPartyId")) {
                request.set("assignedToPartyId", UtilValidate.isEmpty(assignedToPartyId) ? null : assignedToPartyId);
            }
            if (context.containsKey("staffNotes")) {
                request.set("staffNotes", staffNotes);
            }
            delegator.store(request);
            writeAudit(delegator, serviceRequestId, ACTION_SVCREQ_UPDATE,
                    "EfServiceRequest", serviceRequestId);
            Map<String, Object> result = ServiceUtil.returnSuccess(
                    message(locale, "EfinanceServiceRequestUpdated"));
            result.put("serviceRequestId", serviceRequestId);
            result.put("statusId", request.getString("statusId"));
            return result;
        } catch (GenericEntityException e) {
            Debug.logError(e, "efUpdateServiceRequestStatus failed", MODULE);
            return ServiceUtil.returnError(e.getMessage());
        }
    }

    public static Map<String, Object> efUnlockMember(DispatchContext dctx, Map<String, Object> context) {
        Delegator delegator = dctx.getDelegator();
        Locale locale = (Locale) context.get("locale");
        String partyId = (String) context.get("partyId");
        try {
            GenericValue member = EntityQuery.use(delegator).from("EfMember")
                    .where("partyId", partyId).queryOne();
            if (member == null) {
                return ServiceUtil.returnError(message(locale, "EfinanceMemberNotFound"));
            }
            if (!MEMBER_LOCKED.equals(member.getString("statusId"))) {
                return ServiceUtil.returnSuccess(message(locale, "EfinanceMemberNotLocked"));
            }
            Timestamp now = UtilDateTime.nowTimestamp();
            member.set("statusId", MEMBER_ACTIVE);
            member.set("lastModifiedDate", now);
            delegator.store(member);
            writeAudit(delegator, partyId, ACTION_MEMBER_UNLOCK, "EfMember", partyId);
            Map<String, Object> result = ServiceUtil.returnSuccess(
                    message(locale, "EfinanceMemberUnlocked"));
            result.put("partyId", partyId);
            result.put("statusId", MEMBER_ACTIVE);
            return result;
        } catch (GenericEntityException e) {
            Debug.logError(e, "efUnlockMember failed", MODULE);
            return ServiceUtil.returnError(e.getMessage());
        }
    }

    public static Map<String, Object> efListAuditLogs(DispatchContext dctx, Map<String, Object> context) {
        Delegator delegator = dctx.getDelegator();
        Locale locale = (Locale) context.get("locale");
        String partyId = (String) context.get("partyId");
        String actionEnumId = (String) context.get("actionEnumId");
        Integer viewIndex = (Integer) context.get("viewIndex");
        Integer viewSize = (Integer) context.get("viewSize");

        try {
            List<EntityCondition> conditions = new ArrayList<>();
            if (UtilValidate.isNotEmpty(partyId)) {
                conditions.add(EntityCondition.makeCondition("partyId", partyId));
            }
            if (UtilValidate.isNotEmpty(actionEnumId)) {
                conditions.add(EntityCondition.makeCondition("actionEnumId", actionEnumId));
            }

            int offset = viewIndex == null || viewIndex < 0 ? 0 : viewIndex;
            int size = viewSize == null || viewSize <= 0 ? 100 : Math.min(viewSize, 500);
            EntityQuery query = EntityQuery.use(delegator).from("EfAuditLog");
            if (!conditions.isEmpty()) {
                query.where(EntityCondition.makeCondition(conditions, EntityOperator.AND));
            }
            List<GenericValue> logs = query.orderBy("-actionDate").offset(offset * size).limit(size).queryList();

            List<Map<String, Object>> rows = new ArrayList<>();
            for (GenericValue log : logs) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("auditLogId", log.getString("auditLogId"));
                row.put("partyId", log.getString("partyId"));
                row.put("actionEnumId", log.getString("actionEnumId"));
                row.put("targetEntityName", log.getString("targetEntityName"));
                row.put("targetEntityId", log.getString("targetEntityId"));
                row.put("resultEnumId", log.getString("resultEnumId"));
                row.put("ipAddress", log.getString("ipAddress"));
                row.put("deviceId", log.getString("deviceId"));
                row.put("actionDate", log.getTimestamp("actionDate"));
                row.put("detail", log.getString("detail"));
                rows.add(row);
            }

            Map<String, Object> result = ServiceUtil.returnSuccess();
            result.put("auditLogs", rows);
            result.put("viewIndex", offset);
            result.put("viewSize", size);
            return result;
        } catch (GenericEntityException e) {
            Debug.logError(e, "efListAuditLogs failed", MODULE);
            return ServiceUtil.returnError(UtilProperties.getMessage(RESOURCE, "EfinanceAuditLogError", locale));
        }
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
            Debug.logWarning("Failed to write admin audit log: " + e.getMessage(), MODULE);
        }
    }

    private static String message(Locale locale, String key) {
        return UtilProperties.getMessage(RESOURCE, key, locale);
    }
}
