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
package org.apache.ofbiz.efinance.payment;

import java.math.BigDecimal;
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
import org.apache.ofbiz.efinance.security.EfAccessGuard;
import org.apache.ofbiz.service.DispatchContext;
import org.apache.ofbiz.service.ServiceUtil;

public final class EfPaymentServices {

    private static final String MODULE = EfPaymentServices.class.getName();
    private static final String RESOURCE = "EfinanceUiLabels";
    private static final String PROPS = "efinance";
    private static final String STATUS_REQUESTED = "EFDD_REQUESTED";
    private static final String ACTION_DD_REQUEST = "EFAUD_DD_REQUEST";
    private static final String ACTION_DD_UPDATE = "EFAUD_DD_UPDATE";
    private static final String RESULT_SUCCESS = "EFAUDRES_SUCCESS";

    private EfPaymentServices() {
    }

    public static Map<String, Object> efRequestDirectDebit(DispatchContext dctx, Map<String, Object> context) {
        Delegator delegator = dctx.getDelegator();
        Locale locale = (Locale) context.get("locale");
        String memberPartyId = getPartyIdFromContext(context);
        String agreementId = (String) context.get("agreementId");
        String agreementTypeId = (String) context.get("agreementTypeId");
        String bankName = (String) context.get("bankName");
        String branchName = (String) context.get("branchName");
        String accountTypeEnumId = (String) context.get("accountTypeEnumId");
        String accountNumber = (String) context.get("accountNumber");
        String accountHolderName = (String) context.get("accountHolderName");
        Timestamp startDate = (Timestamp) context.get("startDate");

        boolean reauthValid;
        try {
            reauthValid = EfAccessGuard.consumeReauthToken(delegator, memberPartyId,
                    (String) context.get("reauthToken"));
        } catch (GenericEntityException e) {
            Debug.logError(e, "Unable to validate direct debit re-authentication", MODULE);
            return ServiceUtil.returnError(e.getMessage());
        }
        if (!reauthValid) {
            return ServiceUtil.returnError(message(locale, "EfinanceReauthRequired"));
        }
        if (UtilValidate.isEmpty(agreementId) || UtilValidate.isEmpty(agreementTypeId)) {
            return ServiceUtil.returnError(message(locale, "EfinanceContractNotFound"));
        }
        if (UtilValidate.isEmpty(accountNumber) || !accountNumber.matches("\\d{5,12}")) {
            return ServiceUtil.returnError(message(locale, "EfinanceDirectDebitAccountInvalid"));
        }

        try {
            GenericValue agreement = loadAgreement(delegator, agreementTypeId, agreementId);
            if (!EfAccessGuard.isContractAccessible(delegator, agreement, memberPartyId, "rolePayment")) {
                return ServiceUtil.returnError(message(locale, "EfinanceContractNotFound"));
            }

            DirectDebitAdapter adapter = DirectDebitAdapterFactory.getAdapter();
            DirectDebitResult adapterResult = adapter.request(new DirectDebitRequest(
                    memberPartyId, agreementId, agreementTypeId, bankName, branchName,
                    accountTypeEnumId, accountNumber, accountHolderName, locale));
            if (!adapterResult.isSuccess()) {
                return ServiceUtil.returnError(adapterResult.getErrorMessage());
            }

            Timestamp now = UtilDateTime.nowTimestamp();
            String mandateId = delegator.getNextSeqId("EfDirectDebitMandate");
            GenericValue mandate = delegator.makeValue("EfDirectDebitMandate");
            mandate.set("mandateId", mandateId);
            mandate.set("memberPartyId", memberPartyId);
            mandate.set("agreementId", agreementId);
            mandate.set("agreementTypeId", agreementTypeId);
            mandate.set("financeContractId", agreement.getString("financeContractId"));
            mandate.set("bankName", bankName);
            mandate.set("branchName", branchName);
            mandate.set("accountTypeEnumId", accountTypeEnumId);
            mandate.set("accountNumber", accountNumber);
            mandate.set("accountNumberLast4", accountNumber.substring(accountNumber.length() - 4));
            mandate.set("accountHolderName", accountHolderName);
            mandate.set("statusId", UtilValidate.isNotEmpty(adapterResult.getStatusId())
                    ? adapterResult.getStatusId() : STATUS_REQUESTED);
            mandate.set("adapterResult", adapterResult.getAdapterResult());
            mandate.set("startDate", startDate != null ? startDate : now);
            mandate.set("requestedDate", now);
            mandate.set("createdDate", now);
            mandate.set("lastModifiedDate", now);
            delegator.create(mandate);
            writeAudit(delegator, memberPartyId, ACTION_DD_REQUEST, mandateId);

            Map<String, Object> result = ServiceUtil.returnSuccess(
                    message(locale, "EfinanceDirectDebitRequested"));
            result.put("mandateId", mandateId);
            result.put("statusId", mandate.getString("statusId"));
            result.put("accountNumberLast4", mandate.getString("accountNumberLast4"));
            return result;
        } catch (GenericEntityException e) {
            Debug.logError(e, "efRequestDirectDebit failed", MODULE);
            return ServiceUtil.returnError(e.getMessage());
        }
    }

    public static Map<String, Object> efGetDirectDebit(DispatchContext dctx, Map<String, Object> context) {
        Delegator delegator = dctx.getDelegator();
        String memberPartyId = getPartyIdFromContext(context);
        String agreementId = (String) context.get("agreementId");
        try {
            List<EntityCondition> conditions = new ArrayList<>();
            conditions.add(EntityCondition.makeCondition("memberPartyId", memberPartyId));
            if (UtilValidate.isNotEmpty(agreementId)) {
                conditions.add(EntityCondition.makeCondition("agreementId", agreementId));
            }
            List<GenericValue> mandates = EntityQuery.use(delegator).from("EfDirectDebitMandate")
                    .where(EntityCondition.makeCondition(conditions, EntityOperator.AND))
                    .orderBy("-requestedDate").queryList();
            List<Map<String, Object>> rows = new ArrayList<>();
            for (GenericValue mandate : mandates) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("mandateId", mandate.getString("mandateId"));
                row.put("agreementId", mandate.getString("agreementId"));
                row.put("agreementTypeId", mandate.getString("agreementTypeId"));
                row.put("bankName", mandate.getString("bankName"));
                row.put("branchName", mandate.getString("branchName"));
                row.put("accountTypeEnumId", mandate.getString("accountTypeEnumId"));
                row.put("accountNumberLast4", mandate.getString("accountNumberLast4"));
                row.put("accountHolderName", mandate.getString("accountHolderName"));
                row.put("statusId", mandate.getString("statusId"));
                row.put("adapterResult", mandate.getString("adapterResult"));
                row.put("startDate", mandate.getTimestamp("startDate"));
                row.put("requestedDate", mandate.getTimestamp("requestedDate"));
                rows.add(row);
            }
            Map<String, Object> result = ServiceUtil.returnSuccess();
            result.put("mandates", rows);
            return result;
        } catch (GenericEntityException e) {
            Debug.logError(e, "efGetDirectDebit failed", MODULE);
            return ServiceUtil.returnError(e.getMessage());
        }
    }

    public static Map<String, Object> efGetPaymentInstructions(DispatchContext dctx, Map<String, Object> context) {
        Delegator delegator = dctx.getDelegator();
        Locale locale = (Locale) context.get("locale");
        String memberPartyId = getPartyIdFromContext(context);
        String agreementId = (String) context.get("agreementId");
        String agreementTypeId = (String) context.get("agreementTypeId");
        try {
            GenericValue agreement = loadAgreement(delegator, agreementTypeId, agreementId);
            if (!EfAccessGuard.isContractAccessible(delegator, agreement, memberPartyId, "roleView")) {
                return ServiceUtil.returnError(message(locale, "EfinanceContractNotFound"));
            }
            boolean lease = "LEASE".equals(agreementTypeId);
            List<Map<String, Object>> instructions = new ArrayList<>();
            if (lease) {
                Long revision = latestRevision(delegator, "LeaseAgreementPaymentSchedule",
                        "leaseAgreementId", agreementId);
                if (revision != null) {
                    List<GenericValue> rows = EntityQuery.use(delegator)
                            .from("LeaseAgreementPaymentSchedule")
                            .where("leaseAgreementId", agreementId, "scheduleRevisionNumber", revision)
                            .orderBy("installmentNumber").queryList();
                    for (GenericValue row : rows) {
                        Map<String, Object> m = new LinkedHashMap<>();
                        m.put("installmentNumber", row.getLong("installmentNumber"));
                        m.put("dueDate", row.getTimestamp("dueDate"));
                        m.put("amount", row.getBigDecimal("leaseAmount").add(
                                nvl(row.getBigDecimal("taxAmount"))));
                        m.put("principalAmount", row.getBigDecimal("principalPortion"));
                        m.put("interestAmount", row.getBigDecimal("interestPortion"));
                        m.put("taxAmount", row.getBigDecimal("taxAmount"));
                        m.put("remainingBalance", row.getBigDecimal("remainingPrincipal"));
                        instructions.add(m);
                    }
                }
            } else {
                Long revision = latestRevision(delegator, "LoanAgreementRepaymentSchedule",
                        "loanAgreementId", agreementId);
                if (revision != null) {
                    List<GenericValue> rows = EntityQuery.use(delegator)
                            .from("LoanAgreementRepaymentSchedule")
                            .where("loanAgreementId", agreementId, "scheduleRevisionNumber", revision)
                            .orderBy("installmentNumber").queryList();
                    for (GenericValue row : rows) {
                        Map<String, Object> m = new LinkedHashMap<>();
                        m.put("installmentNumber", row.getLong("installmentNumber"));
                        m.put("dueDate", row.getTimestamp("dueDate"));
                        m.put("amount", row.getBigDecimal("paymentAmount"));
                        m.put("principalAmount", row.getBigDecimal("principalAmount"));
                        m.put("interestAmount", row.getBigDecimal("interestAmount"));
                        m.put("taxAmount", null);
                        m.put("remainingBalance", row.getBigDecimal("remainingBalance"));
                        instructions.add(m);
                    }
                }
            }

            Map<String, Object> bank = new LinkedHashMap<>();
            bank.put("bankName", property("efinance.payment.bank.name"));
            bank.put("branchName", property("efinance.payment.bank.branch"));
            bank.put("accountType", property("efinance.payment.bank.accountType"));
            bank.put("accountNumber", property("efinance.payment.bank.accountNumber"));
            bank.put("accountHolderName", property("efinance.payment.bank.accountHolder"));

            Map<String, Object> result = ServiceUtil.returnSuccess();
            result.put("agreementId", agreementId);
            result.put("agreementTypeId", agreementTypeId);
            result.put("contractNo", agreementId);
            result.put("bank", bank);
            result.put("instructions", instructions);
            return result;
        } catch (GenericEntityException e) {
            Debug.logError(e, "efGetPaymentInstructions failed", MODULE);
            return ServiceUtil.returnError(e.getMessage());
        }
    }

    public static Map<String, Object> efUpdateDirectDebitStatus(DispatchContext dctx, Map<String, Object> context) {
        Delegator delegator = dctx.getDelegator();
        Locale locale = (Locale) context.get("locale");
        String mandateId = (String) context.get("mandateId");
        String statusId = (String) context.get("statusId");
        String staffNote = (String) context.get("staffNote");
        try {
            GenericValue mandate = EntityQuery.use(delegator).from("EfDirectDebitMandate")
                    .where("mandateId", mandateId).queryOne();
            if (mandate == null) {
                return ServiceUtil.returnError(message(locale, "EfinanceDirectDebitNotFound"));
            }
            if (UtilValidate.isNotEmpty(statusId) && !statusId.equals(mandate.getString("statusId"))) {
                GenericValue valid = EntityQuery.use(delegator).from("StatusValidChange")
                        .where("statusId", mandate.getString("statusId"), "statusIdTo", statusId).queryOne();
                if (valid == null) {
                    return ServiceUtil.returnError(message(locale, "EfinanceInvalidStatusTransition"));
                }
                mandate.set("statusId", statusId);
            }
            if (UtilValidate.isNotEmpty(staffNote)) {
                mandate.set("adapterResult", staffNote);
            }
            mandate.set("lastModifiedDate", UtilDateTime.nowTimestamp());
            delegator.store(mandate);
            writeAudit(delegator, mandate.getString("memberPartyId"), ACTION_DD_UPDATE, mandateId);
            Map<String, Object> result = ServiceUtil.returnSuccess();
            result.put("mandateId", mandateId);
            result.put("statusId", mandate.getString("statusId"));
            return result;
        } catch (GenericEntityException e) {
            Debug.logError(e, "efUpdateDirectDebitStatus failed", MODULE);
            return ServiceUtil.returnError(e.getMessage());
        }
    }

    private static GenericValue loadAgreement(Delegator delegator, String agreementTypeId, String agreementId)
            throws GenericEntityException {
        if ("LEASE".equals(agreementTypeId)) {
            return EntityQuery.use(delegator).from("LeaseAgreement")
                    .where("leaseAgreementId", agreementId).queryOne();
        }
        return EntityQuery.use(delegator).from("LoanAgreement")
                .where("loanAgreementId", agreementId).queryOne();
    }

    private static Long latestRevision(Delegator delegator, String entity, String field, String id)
            throws GenericEntityException {
        List<GenericValue> rows = EntityQuery.use(delegator).from(entity).where(field, id)
                .orderBy("-scheduleRevisionNumber").queryList();
        return rows.isEmpty() ? null : rows.get(0).getLong("scheduleRevisionNumber");
    }

    private static BigDecimal nvl(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    private static String getPartyIdFromContext(Map<String, Object> context) {
        GenericValue userLogin = (GenericValue) context.get("userLogin");
        return userLogin == null ? null : userLogin.getString("partyId");
    }

    private static String property(String key) {
        return UtilProperties.getPropertyValue(PROPS, key, "");
    }

    private static String message(Locale locale, String key) {
        return UtilProperties.getMessage(RESOURCE, key, locale);
    }

    private static void writeAudit(Delegator delegator, String partyId, String action, String mandateId) {
        try {
            GenericValue audit = delegator.makeValue("EfAuditLog");
            audit.set("auditLogId", delegator.getNextSeqId("EfAuditLog"));
            audit.set("partyId", partyId);
            audit.set("actionEnumId", action);
            audit.set("targetEntityName", "EfDirectDebitMandate");
            audit.set("targetEntityId", mandateId);
            audit.set("resultEnumId", RESULT_SUCCESS);
            audit.set("actionDate", UtilDateTime.nowTimestamp());
            delegator.create(audit);
        } catch (GenericEntityException e) {
            Debug.logWarning("Failed to write direct debit audit log: " + e.getMessage(), MODULE);
        }
    }
}
