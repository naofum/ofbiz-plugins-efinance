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
package org.apache.ofbiz.efinance.mypage;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

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
import org.apache.ofbiz.efinance.security.EfAccessGuard;
import org.apache.ofbiz.service.DispatchContext;
import org.apache.ofbiz.service.LocalDispatcher;
import org.apache.ofbiz.service.ServiceUtil;

/**
 * STEP W6: eFinance MyPage – customer self-service portal services.
 */
public final class EfMyPageServices {

    private static final String MODULE = EfMyPageServices.class.getName();
    private static final String RESOURCE = "EfinanceUiLabels";
    private static final String PROPS = "efinance";

    private static final String AGMT_TYPE_LOAN = "LOAN";
    private static final String AGMT_TYPE_LEASE = "LEASE";

    private static final String ACTION_SVCREQ_CREATE = "EFAUD_SVCREQ_CREATE";
    private static final String ACTION_MSG_SEND = "EFAUD_MSG_SEND";
    private static final String RESULT_SUCCESS = "EFAUDRES_SUCCESS";

    private static final String STATUS_SVCREQ_RECEIVED = "EFSVCREQ_RECEIVED";

    private EfMyPageServices() {
    }

    // =========================================================================
    // efListApplications
    // =========================================================================

    public static Map<String, Object> efListApplications(DispatchContext dctx, Map<String, Object> context) {
        Delegator delegator = dctx.getDelegator();

        String partyId = getPartyIdFromContext(context);

        try {
            List<GenericValue> links = EntityQuery.use(delegator).from("EfApplicationLink")
                    .where("memberPartyId", partyId)
                    .orderBy("-linkedDate")
                    .queryList();

            List<Map<String, Object>> applications = new ArrayList<>();
            for (GenericValue link : links) {
                String financeApplicationId = link.getString("financeApplicationId");
                String financeApplicationTypeId = link.getString("financeApplicationTypeId");

                Map<String, Object> appMap = new LinkedHashMap<>();
                appMap.put("financeApplicationId", financeApplicationId);
                appMap.put("financeApplicationTypeId", financeApplicationTypeId);
                appMap.put("draftId", link.getString("draftId"));
                appMap.put("linkedDate", link.getTimestamp("linkedDate"));

                if (AGMT_TYPE_LEASE.equals(financeApplicationTypeId)) {
                    GenericValue leaseApp = EntityQuery.use(delegator).from("LeaseApplication")
                            .where("leaseApplicationId", financeApplicationId).queryOne();
                    if (leaseApp != null) {
                        appMap.put("statusId", leaseApp.getString("statusId"));
                        appMap.put("productId", leaseApp.getString("productId"));
                        appMap.put("applicationDate", leaseApp.getTimestamp("applicationDate"));
                    }
                } else {
                    GenericValue loanApp = EntityQuery.use(delegator).from("LoanApplication")
                            .where("loanApplicationId", financeApplicationId).queryOne();
                    if (loanApp != null) {
                        appMap.put("statusId", loanApp.getString("statusId"));
                        appMap.put("productId", loanApp.getString("productId"));
                        appMap.put("applicationDate", loanApp.getTimestamp("applicationDate"));
                    }
                }

                applications.add(appMap);
            }

            Map<String, Object> result = ServiceUtil.returnSuccess();
            result.put("applications", applications);
            return result;

        } catch (GenericEntityException e) {
            Debug.logError(e, MODULE);
            return ServiceUtil.returnError(e.getMessage());
        }
    }

    // =========================================================================
    // efListContracts
    // =========================================================================

    public static Map<String, Object> efListContracts(DispatchContext dctx, Map<String, Object> context) {
        Delegator delegator = dctx.getDelegator();

        String partyId = getPartyIdFromContext(context);

        try {
            List<Map<String, Object>> contracts = new ArrayList<>();
            List<String> ownerPartyIds = new ArrayList<>();
            ownerPartyIds.add(partyId);
            ownerPartyIds.addAll(EfAccessGuard.getCorporatePartyIds(delegator, partyId, "roleView"));

            // Personal and corporate loan agreements
            List<GenericValue> loanAgreements = EntityQuery.use(delegator).from("LoanAgreement")
                    .where(EntityCondition.makeCondition("borrowerPartyId", EntityOperator.IN, ownerPartyIds))
                    .orderBy("-agreementDate")
                    .queryList();
            for (GenericValue agr : loanAgreements) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("agreementId", agr.getString("loanAgreementId"));
                m.put("agreementTypeId", AGMT_TYPE_LOAN);
                m.put("statusId", agr.getString("statusId"));
                m.put("principalAmount", agr.getBigDecimal("principalAmount"));
                m.put("annualInterestRate", agr.getBigDecimal("annualInterestRate"));
                m.put("termMonths", agr.getLong("termMonths"));
                m.put("currencyUomId", agr.getString("currencyUomId"));
                m.put("agreementDate", agr.getTimestamp("agreementDate"));
                m.put("financeContractId", agr.getString("financeContractId"));
                contracts.add(m);
            }

            // Personal and corporate lease agreements
            List<GenericValue> leaseAgreements = EntityQuery.use(delegator).from("LeaseAgreement")
                    .where(EntityCondition.makeCondition("lesseePartyId", EntityOperator.IN, ownerPartyIds))
                    .orderBy("-agreementDate")
                    .queryList();
            for (GenericValue agr : leaseAgreements) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("agreementId", agr.getString("leaseAgreementId"));
                m.put("agreementTypeId", AGMT_TYPE_LEASE);
                m.put("statusId", agr.getString("statusId"));
                m.put("acquisitionCost", agr.getBigDecimal("acquisitionCost"));
                m.put("monthlyLeaseAmount", agr.getBigDecimal("monthlyLeaseAmount"));
                m.put("termMonths", agr.getLong("termMonths"));
                m.put("currencyUomId", agr.getString("currencyUomId"));
                m.put("agreementDate", agr.getTimestamp("agreementDate"));
                m.put("financeContractId", agr.getString("financeContractId"));
                contracts.add(m);
            }

            Map<String, Object> result = ServiceUtil.returnSuccess();
            result.put("contracts", contracts);
            return result;

        } catch (GenericEntityException e) {
            Debug.logError(e, MODULE);
            return ServiceUtil.returnError(e.getMessage());
        }
    }

    // =========================================================================
    // efGetContract
    // =========================================================================

    public static Map<String, Object> efGetContract(DispatchContext dctx, Map<String, Object> context) {
        Delegator delegator = dctx.getDelegator();
        LocalDispatcher dispatcher = dctx.getDispatcher();
        Locale locale = (Locale) context.get("locale");

        String partyId = getPartyIdFromContext(context);
        String agreementId = (String) context.get("agreementId");
        String agreementTypeId = (String) context.get("agreementTypeId");

        try {
            Map<String, Object> contractMap = new LinkedHashMap<>();
            List<Map<String, Object>> schedule = new ArrayList<>();
            Map<String, Object> balance = new LinkedHashMap<>();

            if (AGMT_TYPE_LEASE.equals(agreementTypeId)) {
                GenericValue agr = EntityQuery.use(delegator).from("LeaseAgreement")
                        .where("leaseAgreementId", agreementId).queryOne();
                if (!EfAccessGuard.isContractAccessible(delegator, agr, partyId, "roleView")) {
                    return ServiceUtil.returnError(
                            UtilProperties.getMessage(RESOURCE, "EfinanceContractNotFound", locale));
                }

                contractMap.put("agreementId", agr.getString("leaseAgreementId"));
                contractMap.put("agreementTypeId", AGMT_TYPE_LEASE);
                contractMap.put("statusId", agr.getString("statusId"));
                contractMap.put("acquisitionCost", agr.getBigDecimal("acquisitionCost"));
                contractMap.put("monthlyLeaseAmount", agr.getBigDecimal("monthlyLeaseAmount"));
                contractMap.put("termMonths", agr.getLong("termMonths"));
                contractMap.put("currencyUomId", agr.getString("currencyUomId"));
                contractMap.put("agreementDate", agr.getTimestamp("agreementDate"));
                contractMap.put("leaseStartDate", agr.getTimestamp("leaseStartDate"));
                contractMap.put("leaseEndDate", agr.getTimestamp("leaseEndDate"));
                contractMap.put("financeContractId", agr.getString("financeContractId"));

                // Lease repayment schedule – use LeaseAgreementPaymentSchedule
                Long maxRevision = getMaxLeaseScheduleRevision(delegator, agreementId);
                if (maxRevision != null) {
                    List<GenericValue> rows = EntityQuery.use(delegator)
                            .from("LeaseAgreementPaymentSchedule")
                            .where("leaseAgreementId", agreementId, "scheduleRevisionNumber", maxRevision)
                            .orderBy("installmentNumber")
                            .queryList();
                    for (GenericValue row : rows) {
                        Map<String, Object> s = new LinkedHashMap<>();
                        s.put("installmentNumber", row.getLong("installmentNumber"));
                        s.put("dueDate", row.getTimestamp("dueDate"));
                        s.put("leaseAmount", row.getBigDecimal("leaseAmount"));
                        s.put("principalPortion", row.getBigDecimal("principalPortion"));
                        s.put("interestPortion", row.getBigDecimal("interestPortion"));
                        s.put("taxAmount", row.getBigDecimal("taxAmount"));
                        s.put("remainingPrincipal", row.getBigDecimal("remainingPrincipal"));
                        schedule.add(s);
                    }

                    // Balance from last schedule row
                    if (!rows.isEmpty()) {
                        GenericValue lastRow = rows.get(rows.size() - 1);
                        balance.put("outstandingPrincipal", lastRow.getBigDecimal("remainingPrincipal"));
                    }
                }

                // Try to get live balance from finance service
                try {
                    GenericValue systemUser = getSystemUser(delegator);
                    Map<String, Object> balCtx = new LinkedHashMap<>();
                    balCtx.put("userLogin", systemUser);
                    balCtx.put("leaseAgreementId", agreementId);
                    Map<String, Object> balResult = dispatcher.runSync("getLeaseAgreementBalance", balCtx);
                    if (ServiceUtil.isSuccess(balResult)) {
                        balance.put("outstandingPrincipal", balResult.get("outstandingPrincipal"));
                        balance.putAll(filterBalanceFields(balResult));
                    }
                } catch (Exception ex) {
                    Debug.logWarning("getLeaseAgreementBalance not available: " + ex.getMessage(), MODULE);
                }

            } else {
                // LOAN
                GenericValue agr = EntityQuery.use(delegator).from("LoanAgreement")
                        .where("loanAgreementId", agreementId).queryOne();
                if (!EfAccessGuard.isContractAccessible(delegator, agr, partyId, "roleView")) {
                    return ServiceUtil.returnError(
                            UtilProperties.getMessage(RESOURCE, "EfinanceContractNotFound", locale));
                }

                contractMap.put("agreementId", agr.getString("loanAgreementId"));
                contractMap.put("agreementTypeId", AGMT_TYPE_LOAN);
                contractMap.put("statusId", agr.getString("statusId"));
                contractMap.put("principalAmount", agr.getBigDecimal("principalAmount"));
                contractMap.put("annualInterestRate", agr.getBigDecimal("annualInterestRate"));
                contractMap.put("termMonths", agr.getLong("termMonths"));
                contractMap.put("currencyUomId", agr.getString("currencyUomId"));
                contractMap.put("agreementDate", agr.getTimestamp("agreementDate"));
                contractMap.put("firstPaymentDate", agr.getTimestamp("firstPaymentDate"));
                contractMap.put("financeContractId", agr.getString("financeContractId"));

                // Loan repayment schedule – use max revision
                Long maxRevision = getMaxLoanScheduleRevision(delegator, agreementId);
                BigDecimal outstanding = agr.getBigDecimal("principalAmount");

                if (maxRevision != null) {
                    List<GenericValue> rows = EntityQuery.use(delegator)
                            .from("LoanAgreementRepaymentSchedule")
                            .where("loanAgreementId", agreementId, "scheduleRevisionNumber", maxRevision)
                            .orderBy("installmentNumber")
                            .queryList();
                    for (GenericValue row : rows) {
                        Map<String, Object> s = new LinkedHashMap<>();
                        s.put("installmentNumber", row.getLong("installmentNumber"));
                        s.put("dueDate", row.getTimestamp("dueDate"));
                        s.put("paymentAmount", row.getBigDecimal("paymentAmount"));
                        s.put("principalAmount", row.getBigDecimal("principalAmount"));
                        s.put("interestAmount", row.getBigDecimal("interestAmount"));
                        s.put("remainingBalance", row.getBigDecimal("remainingBalance"));
                        schedule.add(s);
                    }
                    if (!rows.isEmpty()) {
                        BigDecimal lastBalance = rows.get(rows.size() - 1).getBigDecimal("remainingBalance");
                        if (lastBalance != null) {
                            outstanding = lastBalance;
                        }
                    }
                }

                balance.put("outstandingPrincipal", outstanding);
            }

            Map<String, Object> result = ServiceUtil.returnSuccess();
            result.put("contract", contractMap);
            result.put("schedule", schedule);
            result.put("balance", balance);
            return result;

        } catch (GenericEntityException e) {
            Debug.logError(e, MODULE);
            return ServiceUtil.returnError(e.getMessage());
        }
    }

    // =========================================================================
    // efSimulatePrepayment
    // =========================================================================

    public static Map<String, Object> efSimulatePrepayment(DispatchContext dctx, Map<String, Object> context) {
        Delegator delegator = dctx.getDelegator();
        Locale locale = (Locale) context.get("locale");

        String partyId = getPartyIdFromContext(context);
        String agreementId = (String) context.get("agreementId");
        BigDecimal prepaymentAmount = (BigDecimal) context.get("prepaymentAmount");

        try {
            GenericValue agr = EntityQuery.use(delegator).from("LoanAgreement")
                    .where("loanAgreementId", agreementId).queryOne();
            if (!EfAccessGuard.isContractAccessible(delegator, agr, partyId, "roleView")) {
                return ServiceUtil.returnError(
                        UtilProperties.getMessage(RESOURCE, "EfinanceContractNotFound", locale));
            }

            // Get outstanding principal from schedule, fall back to principalAmount
            BigDecimal outstanding = agr.getBigDecimal("principalAmount");
            Long maxRevision = getMaxLoanScheduleRevision(delegator, agreementId);
            if (maxRevision != null) {
                List<GenericValue> allRows = EntityQuery.use(delegator)
                        .from("LoanAgreementRepaymentSchedule")
                        .where("loanAgreementId", agreementId, "scheduleRevisionNumber", maxRevision)
                        .orderBy("installmentNumber")
                        .queryList();
                if (!allRows.isEmpty()) {
                    BigDecimal lastBalance = allRows.get(allRows.size() - 1).getBigDecimal("remainingBalance");
                    if (lastBalance != null) {
                        outstanding = lastBalance;
                    }
                }
            }

            if (prepaymentAmount == null || prepaymentAmount.compareTo(BigDecimal.ZERO) <= 0) {
                prepaymentAmount = outstanding;
            }

            // Get prepayment fee rate from FinancialProduct via LoanApplication
            BigDecimal penaltyRate = BigDecimal.ZERO;
            String loanApplicationId = agr.getString("loanApplicationId");
            if (UtilValidate.isNotEmpty(loanApplicationId)) {
                GenericValue loanApp = EntityQuery.use(delegator).from("LoanApplication")
                        .where("loanApplicationId", loanApplicationId).queryOne();
                if (loanApp != null && UtilValidate.isNotEmpty(loanApp.getString("productId"))) {
                    GenericValue product = EntityQuery.use(delegator).from("FinancialProduct")
                            .where("productId", loanApp.getString("productId")).queryOne();
                    if (product != null && product.getBigDecimal("prepaymentFeeRate") != null) {
                        penaltyRate = product.getBigDecimal("prepaymentFeeRate");
                    }
                }
            }

            BigDecimal penaltyAmount = outstanding.multiply(penaltyRate).setScale(0, RoundingMode.HALF_UP);
            BigDecimal totalSettlement = outstanding.add(penaltyAmount);

            Map<String, Object> result = ServiceUtil.returnSuccess();
            result.put("outstandingPrincipal", outstanding);
            result.put("penaltyRate", penaltyRate);
            result.put("penaltyAmount", penaltyAmount);
            result.put("totalSettlementAmount", totalSettlement);
            return result;

        } catch (GenericEntityException e) {
            Debug.logError(e, MODULE);
            return ServiceUtil.returnError(e.getMessage());
        }
    }

    // =========================================================================
    // efRequestPrepayment
    // =========================================================================

    public static Map<String, Object> efRequestPrepayment(DispatchContext dctx, Map<String, Object> context) {
        Delegator delegator = dctx.getDelegator();
        Locale locale = (Locale) context.get("locale");

        String partyId = getPartyIdFromContext(context);
        String agreementId = (String) context.get("agreementId");
        BigDecimal prepaymentAmount = (BigDecimal) context.get("prepaymentAmount");
        String prepaymentTypeEnumId = (String) context.get("prepaymentTypeEnumId");

        if (!consumeReauth(delegator, partyId, context)) {
            return ServiceUtil.returnError(
                    UtilProperties.getMessage(RESOURCE, "EfinanceReauthRequired", locale));
        }

        try {
            GenericValue agr = EntityQuery.use(delegator).from("LoanAgreement")
                    .where("loanAgreementId", agreementId).queryOne();
            if (!EfAccessGuard.isContractAccessible(delegator, agr, partyId, "rolePayment")) {
                return ServiceUtil.returnError(
                        UtilProperties.getMessage(RESOURCE, "EfinanceContractNotFound", locale));
            }

            // Build requestData JSON
            StringBuilder requestData = new StringBuilder("{");
            if (prepaymentAmount != null) {
                requestData.append("\"prepaymentAmount\":\"").append(prepaymentAmount).append("\"");
            }
            if (UtilValidate.isNotEmpty(prepaymentTypeEnumId)) {
                if (prepaymentAmount != null) requestData.append(",");
                requestData.append("\"prepaymentTypeEnumId\":\"").append(prepaymentTypeEnumId).append("\"");
            }
            requestData.append("}");

            String serviceRequestId = createServiceRequest(delegator, partyId, agreementId,
                    AGMT_TYPE_LOAN, "EFSVCREQ_PREPAY", "Prepayment request", requestData.toString());

            writeAuditLog(delegator, partyId, ACTION_SVCREQ_CREATE, "EfServiceRequest", serviceRequestId);

            Map<String, Object> result = ServiceUtil.returnSuccess(
                    UtilProperties.getMessage(RESOURCE, "EfinanceServiceRequestCreated", locale));
            result.put("serviceRequestId", serviceRequestId);
            return result;

        } catch (GenericEntityException e) {
            Debug.logError(e, MODULE);
            return ServiceUtil.returnError(e.getMessage());
        }
    }

    // =========================================================================
    // efRequestModification
    // =========================================================================

    public static Map<String, Object> efRequestModification(DispatchContext dctx, Map<String, Object> context) {
        Delegator delegator = dctx.getDelegator();
        Locale locale = (Locale) context.get("locale");

        String partyId = getPartyIdFromContext(context);
        String agreementId = (String) context.get("agreementId");
        String agreementTypeId = (String) context.get("agreementTypeId");
        String requestContent = (String) context.get("requestContent");

        if (!consumeReauth(delegator, partyId, context)) {
            return ServiceUtil.returnError(
                    UtilProperties.getMessage(RESOURCE, "EfinanceReauthRequired", locale));
        }

        try {
            if (!ownsAgreement(delegator, agreementId, agreementTypeId, partyId)) {
                return ServiceUtil.returnError(
                        UtilProperties.getMessage(RESOURCE, "EfinanceContractNotFound", locale));
            }

            String requestData = "{\"requestContent\":\"" + escapeJson(requestContent) + "\"}";

            String serviceRequestId = createServiceRequest(delegator, partyId, agreementId,
                    agreementTypeId, "EFSVCREQ_MODIFY", "Condition change request", requestData);

            writeAuditLog(delegator, partyId, ACTION_SVCREQ_CREATE, "EfServiceRequest", serviceRequestId);

            Map<String, Object> result = ServiceUtil.returnSuccess(
                    UtilProperties.getMessage(RESOURCE, "EfinanceServiceRequestCreated", locale));
            result.put("serviceRequestId", serviceRequestId);
            return result;

        } catch (GenericEntityException e) {
            Debug.logError(e, MODULE);
            return ServiceUtil.returnError(e.getMessage());
        }
    }

    // =========================================================================
    // efRequestExpiryOption
    // =========================================================================

    public static Map<String, Object> efRequestExpiryOption(DispatchContext dctx, Map<String, Object> context) {
        Delegator delegator = dctx.getDelegator();
        Locale locale = (Locale) context.get("locale");

        String partyId = getPartyIdFromContext(context);
        String agreementId = (String) context.get("agreementId");
        String expiryOptionEnumId = (String) context.get("expiryOptionEnumId");

        if (!consumeReauth(delegator, partyId, context)) {
            return ServiceUtil.returnError(
                    UtilProperties.getMessage(RESOURCE, "EfinanceReauthRequired", locale));
        }

        try {
            GenericValue agr = EntityQuery.use(delegator).from("LeaseAgreement")
                    .where("leaseAgreementId", agreementId).queryOne();
            if (!EfAccessGuard.isContractAccessible(delegator, agr, partyId, "roleView")) {
                return ServiceUtil.returnError(
                        UtilProperties.getMessage(RESOURCE, "EfinanceContractNotFound", locale));
            }

            String requestData = "{\"expiryOptionEnumId\":\"" + escapeJson(expiryOptionEnumId) + "\"}";

            String serviceRequestId = createServiceRequest(delegator, partyId, agreementId,
                    AGMT_TYPE_LEASE, "EFSVCREQ_EXPIRY", "Expiry option request", requestData);

            writeAuditLog(delegator, partyId, ACTION_SVCREQ_CREATE, "EfServiceRequest", serviceRequestId);

            Map<String, Object> result = ServiceUtil.returnSuccess(
                    UtilProperties.getMessage(RESOURCE, "EfinanceServiceRequestCreated", locale));
            result.put("serviceRequestId", serviceRequestId);
            return result;

        } catch (GenericEntityException e) {
            Debug.logError(e, MODULE);
            return ServiceUtil.returnError(e.getMessage());
        }
    }

    // =========================================================================
    // efRequestProfileChange
    // =========================================================================

    public static Map<String, Object> efRequestProfileChange(DispatchContext dctx, Map<String, Object> context) {
        Delegator delegator = dctx.getDelegator();
        Locale locale = (Locale) context.get("locale");

        String partyId = getPartyIdFromContext(context);
        String changeType = (String) context.get("changeType");
        @SuppressWarnings("unchecked")
        Map<String, Object> changeData = (Map<String, Object>) context.get("changeData");

        if (!consumeReauth(delegator, partyId, context)) {
            return ServiceUtil.returnError(
                    UtilProperties.getMessage(RESOURCE, "EfinanceReauthRequired", locale));
        }

        try {
            StringBuilder requestData = new StringBuilder("{\"changeType\":\"");
            requestData.append(escapeJson(changeType));
            requestData.append("\",\"changeData\":{");
            if (changeData != null) {
                boolean first = true;
                for (Map.Entry<String, Object> entry : changeData.entrySet()) {
                    if (!first) requestData.append(",");
                    requestData.append("\"").append(escapeJson(entry.getKey())).append("\":\"");
                    requestData.append(escapeJson(String.valueOf(entry.getValue()))).append("\"");
                    first = false;
                }
            }
            requestData.append("}}");

            String serviceRequestId = createServiceRequest(delegator, partyId, null,
                    null, "EFSVCREQ_PROFILE", "Profile change request", requestData.toString());

            writeAuditLog(delegator, partyId, ACTION_SVCREQ_CREATE, "EfServiceRequest", serviceRequestId);

            Map<String, Object> result = ServiceUtil.returnSuccess(
                    UtilProperties.getMessage(RESOURCE, "EfinanceServiceRequestCreated", locale));
            result.put("serviceRequestId", serviceRequestId);
            return result;

        } catch (GenericEntityException e) {
            Debug.logError(e, MODULE);
            return ServiceUtil.returnError(e.getMessage());
        }
    }

    // =========================================================================
    // efRequestCertificate
    // =========================================================================

    public static Map<String, Object> efRequestCertificate(DispatchContext dctx, Map<String, Object> context) {
        Delegator delegator = dctx.getDelegator();
        Locale locale = (Locale) context.get("locale");

        String partyId = getPartyIdFromContext(context);
        String agreementId = (String) context.get("agreementId");
        String agreementTypeId = (String) context.get("agreementTypeId");
        String certificateTypeEnumId = (String) context.get("certificateTypeEnumId");

        try {
            if (!ownsAgreement(delegator, agreementId, agreementTypeId, partyId)) {
                return ServiceUtil.returnError(
                        UtilProperties.getMessage(RESOURCE, "EfinanceContractNotFound", locale));
            }

            String certType = UtilValidate.isNotEmpty(certificateTypeEnumId) ? certificateTypeEnumId : "BALANCE";
            String requestData = "{\"certificateTypeEnumId\":\"" + escapeJson(certType) + "\"}";

            String serviceRequestId = createServiceRequest(delegator, partyId, agreementId,
                    agreementTypeId, "EFSVCREQ_CERT", "Balance certificate request", requestData);

            writeAuditLog(delegator, partyId, ACTION_SVCREQ_CREATE, "EfServiceRequest", serviceRequestId);

            Map<String, Object> result = ServiceUtil.returnSuccess(
                    UtilProperties.getMessage(RESOURCE, "EfinanceServiceRequestCreated", locale));
            result.put("serviceRequestId", serviceRequestId);
            return result;

        } catch (GenericEntityException e) {
            Debug.logError(e, MODULE);
            return ServiceUtil.returnError(e.getMessage());
        }
    }

    // =========================================================================
    // efListMessages
    // =========================================================================

    public static Map<String, Object> efListMessages(DispatchContext dctx, Map<String, Object> context) {
        Delegator delegator = dctx.getDelegator();

        String partyId = getPartyIdFromContext(context);

        try {
            List<GenericValue> events = EntityQuery.use(delegator).from("CommunicationEvent")
                    .where(EntityCondition.makeCondition(
                            EntityCondition.makeCondition("partyIdFrom", partyId),
                            EntityOperator.OR,
                            EntityCondition.makeCondition("partyIdTo", partyId)))
                    .orderBy("-entryDate")
                    .queryList();

            List<Map<String, Object>> messages = new ArrayList<>();
            for (GenericValue event : events) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("communicationEventId", event.getString("communicationEventId"));
                m.put("subject", event.getString("subject"));
                m.put("entryDate", event.getTimestamp("entryDate"));
                m.put("partyIdFrom", event.getString("partyIdFrom"));
                m.put("partyIdTo", event.getString("partyIdTo"));
                m.put("statusId", event.getString("statusId"));

                // Truncate content for list view
                String content = event.getString("content");
                if (UtilValidate.isNotEmpty(content) && content.length() > 200) {
                    m.put("contentPreview", content.substring(0, 200));
                    m.put("hasMore", Boolean.TRUE);
                } else {
                    m.put("contentPreview", content);
                    m.put("hasMore", Boolean.FALSE);
                }
                messages.add(m);
            }

            Map<String, Object> result = ServiceUtil.returnSuccess();
            result.put("messages", messages);
            return result;

        } catch (GenericEntityException e) {
            Debug.logError(e, MODULE);
            return ServiceUtil.returnError(e.getMessage());
        }
    }

    // =========================================================================
    // efSendMessage
    // =========================================================================

    public static Map<String, Object> efSendMessage(DispatchContext dctx, Map<String, Object> context) {
        Delegator delegator = dctx.getDelegator();
        Locale locale = (Locale) context.get("locale");

        String partyId = getPartyIdFromContext(context);
        String subject = (String) context.get("subject");
        String content = (String) context.get("content");

        try {
            Timestamp now = UtilDateTime.nowTimestamp();

            // Determine support party id
            String supportPartyId = UtilProperties.getPropertyValue(PROPS, "efinance.inbound.partyId", "EFINANCE_SYSTEM");

            String communicationEventId = delegator.getNextSeqId("CommunicationEvent");
            GenericValue event = delegator.makeValue("CommunicationEvent");
            event.set("communicationEventId", communicationEventId);
            event.set("communicationEventTypeId", "WEB_SITE_COMMUNICATI");
            event.set("statusId", "COM_COMPLETE");
            event.set("partyIdFrom", partyId);
            event.set("partyIdTo", supportPartyId);
            event.set("subject", UtilValidate.isNotEmpty(subject) ? subject : "Portal message");
            event.set("content", content);
            event.set("entryDate", now);
            event.set("roleTypeIdFrom", "_NA_");
            event.set("roleTypeIdTo", "_NA_");
            delegator.create(event);

            writeAuditLog(delegator, partyId, ACTION_MSG_SEND, "CommunicationEvent", communicationEventId);

            Map<String, Object> result = ServiceUtil.returnSuccess(
                    UtilProperties.getMessage(RESOURCE, "EfinanceMessageSent", locale));
            result.put("communicationEventId", communicationEventId);
            return result;

        } catch (GenericEntityException e) {
            Debug.logError(e, MODULE);
            return ServiceUtil.returnError(e.getMessage());
        }
    }

    // =========================================================================
    // efGetNotificationSettings
    // =========================================================================

    public static Map<String, Object> efGetNotificationSettings(DispatchContext dctx, Map<String, Object> context) {
        Delegator delegator = dctx.getDelegator();

        String partyId = getPartyIdFromContext(context);

        try {
            // Get all EFNOTIF_TYPE enumerations
            List<GenericValue> notifTypes = EntityQuery.use(delegator).from("Enumeration")
                    .where("enumTypeId", "EFNOTIF_TYPE")
                    .orderBy("sequenceId")
                    .queryList();

            List<Map<String, Object>> settings = new ArrayList<>();
            for (GenericValue enumVal : notifTypes) {
                String notifTypeEnumId = enumVal.getString("enumId");

                // Look up existing setting
                GenericValue setting = EntityQuery.use(delegator).from("EfNotificationSetting")
                        .where("memberPartyId", partyId, "notificationTypeEnumId", notifTypeEnumId)
                        .queryOne();

                Map<String, Object> m = new LinkedHashMap<>();
                m.put("notificationTypeEnumId", notifTypeEnumId);
                m.put("description", enumVal.getString("description"));
                if (setting != null) {
                    m.put("emailEnabled", setting.getString("emailEnabled"));
                    m.put("smsEnabled", setting.getString("smsEnabled"));
                    m.put("pushEnabled", setting.getString("pushEnabled"));
                } else {
                    // Defaults: email on, sms/push off
                    m.put("emailEnabled", "Y");
                    m.put("smsEnabled", "N");
                    m.put("pushEnabled", "N");
                }
                settings.add(m);
            }

            Map<String, Object> result = ServiceUtil.returnSuccess();
            result.put("settings", settings);
            return result;

        } catch (GenericEntityException e) {
            Debug.logError(e, MODULE);
            return ServiceUtil.returnError(e.getMessage());
        }
    }

    // =========================================================================
    // efUpdateNotificationSettings
    // =========================================================================

    public static Map<String, Object> efUpdateNotificationSettings(DispatchContext dctx, Map<String, Object> context) {
        Delegator delegator = dctx.getDelegator();
        Locale locale = (Locale) context.get("locale");

        String partyId = getPartyIdFromContext(context);
        String notificationTypeEnumId = (String) context.get("notificationTypeEnumId");
        Boolean emailEnabled = (Boolean) context.get("emailEnabled");
        Boolean smsEnabled = (Boolean) context.get("smsEnabled");
        Boolean pushEnabled = (Boolean) context.get("pushEnabled");

        try {
            GenericValue setting = EntityQuery.use(delegator).from("EfNotificationSetting")
                    .where("memberPartyId", partyId, "notificationTypeEnumId", notificationTypeEnumId)
                    .queryOne();

            Timestamp now = UtilDateTime.nowTimestamp();

            if (setting == null) {
                setting = delegator.makeValue("EfNotificationSetting");
                setting.set("memberPartyId", partyId);
                setting.set("notificationTypeEnumId", notificationTypeEnumId);
                setting.set("emailEnabled", "Y");
                setting.set("smsEnabled", "N");
                setting.set("pushEnabled", "N");
            }

            if (emailEnabled != null) {
                setting.set("emailEnabled", emailEnabled ? "Y" : "N");
            }
            if (smsEnabled != null) {
                setting.set("smsEnabled", smsEnabled ? "Y" : "N");
            }
            if (pushEnabled != null) {
                setting.set("pushEnabled", pushEnabled ? "Y" : "N");
            }
            setting.set("lastModifiedDate", now);

            delegator.createOrStore(setting);

            return ServiceUtil.returnSuccess(
                    UtilProperties.getMessage(RESOURCE, "EfinanceNotificationSettingsUpdated", locale));

        } catch (GenericEntityException e) {
            Debug.logError(e, MODULE);
            return ServiceUtil.returnError(e.getMessage());
        }
    }

    // =========================================================================
    // Private helpers
    // =========================================================================

    private static String getPartyIdFromContext(Map<String, Object> context) {
        GenericValue userLogin = (GenericValue) context.get("userLogin");
        if (userLogin == null) return null;
        return userLogin.getString("partyId");
    }

    private static GenericValue getSystemUser(Delegator delegator) throws GenericEntityException {
        String sysLoginId = UtilProperties.getPropertyValue(PROPS, "efinance.system.userlogin", "efinance-system");
        return EntityQuery.use(delegator).from("UserLogin").where("userLoginId", sysLoginId).queryOne();
    }

    private static boolean ownsAgreement(Delegator delegator, String agreementId,
            String agreementTypeId, String partyId) throws GenericEntityException {
        GenericValue agreement;
        if (AGMT_TYPE_LEASE.equals(agreementTypeId)) {
            agreement = EntityQuery.use(delegator).from("LeaseAgreement")
                    .where("leaseAgreementId", agreementId).queryOne();
        } else {
            agreement = EntityQuery.use(delegator).from("LoanAgreement")
                    .where("loanAgreementId", agreementId).queryOne();
        }
        return EfAccessGuard.isContractAccessible(delegator, agreement, partyId, "roleView");
    }

    private static Long getMaxLoanScheduleRevision(Delegator delegator, String loanAgreementId)
            throws GenericEntityException {
        List<GenericValue> rows = EntityQuery.use(delegator).from("LoanAgreementRepaymentSchedule")
                .where("loanAgreementId", loanAgreementId)
                .orderBy("-scheduleRevisionNumber")
                .queryList();
        if (rows.isEmpty()) return null;
        return rows.get(0).getLong("scheduleRevisionNumber");
    }

    private static Long getMaxLeaseScheduleRevision(Delegator delegator, String leaseAgreementId)
            throws GenericEntityException {
        List<GenericValue> rows = EntityQuery.use(delegator).from("LeaseAgreementPaymentSchedule")
                .where("leaseAgreementId", leaseAgreementId)
                .orderBy("-scheduleRevisionNumber")
                .queryList();
        if (rows.isEmpty()) return null;
        return rows.get(0).getLong("scheduleRevisionNumber");
    }

    private static String createServiceRequest(Delegator delegator, String memberPartyId,
            String agreementId, String agreementTypeId, String requestTypeEnumId,
            String subject, String requestData) throws GenericEntityException {
        String serviceRequestId = delegator.getNextSeqId("EfServiceRequest");
        GenericValue req = delegator.makeValue("EfServiceRequest");
        req.set("serviceRequestId", serviceRequestId);
        req.set("memberPartyId", memberPartyId);
        req.set("agreementId", agreementId);
        req.set("agreementTypeId", agreementTypeId);
        req.set("requestTypeEnumId", requestTypeEnumId);
        req.set("statusId", STATUS_SVCREQ_RECEIVED);
        req.set("subject", subject);
        req.set("requestData", requestData);
        req.set("requestedByPartyId", memberPartyId);
        req.set("requestedDate", UtilDateTime.nowTimestamp());
        delegator.create(req);
        return serviceRequestId;
    }

    private static boolean consumeReauth(Delegator delegator, String partyId, Map<String, Object> context) {
        try {
            return EfAccessGuard.consumeReauthToken(delegator, partyId,
                    (String) context.get("reauthToken"));
        } catch (GenericEntityException e) {
            Debug.logError(e, "Unable to validate re-authentication token", MODULE);
            return false;
        }
    }

    private static void writeAuditLog(Delegator delegator, String partyId, String action,
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
            Debug.logWarning("Failed to write audit log: " + e.getMessage(), MODULE);
        }
    }

    private static String escapeJson(String value) {
        if (value == null) return "";
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    /** Extract standard balance fields from a service result map, excluding serviceResult metadata. */
    private static Map<String, Object> filterBalanceFields(Map<String, Object> serviceResult) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (Map.Entry<String, Object> e : serviceResult.entrySet()) {
            String k = e.getKey();
            if (!"responseMessage".equals(k) && !"successMessage".equals(k)
                    && !"errorMessageList".equals(k) && !"errorMessage".equals(k)) {
                out.put(k, e.getValue());
            }
        }
        return out;
    }
}
