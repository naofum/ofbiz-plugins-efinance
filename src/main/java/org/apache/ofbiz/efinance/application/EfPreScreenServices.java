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
package org.apache.ofbiz.efinance.application;

import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

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
import org.apache.ofbiz.efinance.filescan.FileScanAdapterFactory;
import org.apache.ofbiz.efinance.filescan.FileScanResult;
import org.apache.ofbiz.efinance.notification.NotificationAdapter;
import org.apache.ofbiz.efinance.notification.NotificationAdapterFactory;
import org.apache.ofbiz.efinance.security.EfAccessGuard;
import org.apache.ofbiz.service.DispatchContext;
import org.apache.ofbiz.service.LocalDispatcher;
import org.apache.ofbiz.service.ServiceUtil;

/**
 * STEP W4: Pre-screen, application status, and additional document request services.
 */
public final class EfPreScreenServices {

    private static final String MODULE = EfPreScreenServices.class.getName();
    private static final String RESOURCE = "EfinanceUiLabels";
    private static final String PROPS = "efinance";

    private static final String STATUS_EDITING = "EFDRF_EDITING";

    private static final String RESULT_LIKELY = "EFPRE_LIKELY";
    private static final String RESULT_CONSULT = "EFPRE_CONSULT";
    private static final String RESULT_UNLIKELY = "EFPRE_UNLIKELY";

    private static final String STAGE_RECEIVED = "EF_STAGE_RECEIVED";
    private static final String STAGE_DOC_REQUEST = "EF_STAGE_DOC_REQUEST";
    private static final String STAGE_UNDER_REVIEW = "EF_STAGE_REVIEWING";
    private static final String STAGE_RESULT = "EF_STAGE_RESULT";
    private static final String STAGE_OFFER = "EF_STAGE_OFFER";
    private static final String STAGE_CONTRACTED = "EF_STAGE_CONTRACTED";

    private static final String ACTION_PRESCREEN = "EFAUD_PRESCREEN";
    private static final String ACTION_DOC_REQ_CREATE = "EFAUD_DOC_REQ_CREATE";
    private static final String ACTION_DOC_REQ_SUBMIT = "EFAUD_DOC_REQ_SUBMIT";
    private static final String RESULT_SUCCESS = "EFAUDRES_SUCCESS";

    private static final int PRESCREEN_RATE_LIMIT = 5;
    private static final int PRESCREEN_RATE_WINDOW_HOURS = 1;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private EfPreScreenServices() {
    }

    // =========================================================================
    // efPreScreen
    // =========================================================================

    public static Map<String, Object> efPreScreen(DispatchContext dctx, Map<String, Object> context) {
        Delegator delegator = dctx.getDelegator();
        Locale locale = (Locale) context.get("locale");

        String partyId = getPartyIdFromContext(context);
        String draftId = (String) context.get("draftId");

        try {
            GenericValue draft = EntityQuery.use(delegator).from("EfApplicationDraft")
                    .where("draftId", draftId).queryOne();
            if (draft == null) {
                return ServiceUtil.returnError(UtilProperties.getMessage(RESOURCE, "EfinanceDraftNotFound", locale));
            }
            if (!ownsDraft(draft, partyId)) {
                return ServiceUtil.returnError(UtilProperties.getMessage(RESOURCE, "EfinanceDraftNotOwned", locale));
            }
            if (!STATUS_EDITING.equals(draft.getString("statusId"))) {
                return ServiceUtil.returnError(UtilProperties.getMessage(RESOURCE, "EfinanceDraftNotEditable", locale));
            }

            // Rate limiting: if > 5 pre-screen results for this draft in last hour, return CONSULT
            Calendar rateCal = Calendar.getInstance();
            rateCal.add(Calendar.HOUR_OF_DAY, -PRESCREEN_RATE_WINDOW_HOURS);
            Timestamp rateSince = new Timestamp(rateCal.getTimeInMillis());
            long recentCount = EntityQuery.use(delegator).from("EfPreScreenResult")
                    .where(EntityCondition.makeCondition(
                            EntityCondition.makeCondition("draftId", draftId),
                            EntityOperator.AND,
                            EntityCondition.makeCondition("preScreenDate",
                                    EntityOperator.GREATER_THAN_EQUAL_TO, rateSince)))
                    .queryCount();
            if (recentCount > PRESCREEN_RATE_LIMIT) {
                Map<String, Object> result = ServiceUtil.returnSuccess();
                result.put("result", "CONSULT");
                result.put("guidance", message(locale, "EfinancePreScreenConsult"));
                result.put("disclaimer", message(locale, "EfinanceSimulationDisclaimer"));
                return result;
            }

            String applicationTypeId = draft.getString("applicationTypeId");
            String productId = draft.getString("productId");

            // Load product
            GenericValue product = EntityQuery.use(delegator).from("FinancialProduct")
                    .where("productId", productId).queryOne();

            // Parse step data
            Map<String, Object> allSteps = parseDraftJson(draft.getString("draftJson"));
            @SuppressWarnings("unchecked")
            Map<String, Object> step1 = (Map<String, Object>) allSteps.get("1");
            @SuppressWarnings("unchecked")
            Map<String, Object> step2 = (Map<String, Object>) allSteps.get("2");
            if (step1 == null) step1 = new LinkedHashMap<>();
            if (step2 == null) step2 = new LinkedHashMap<>();

            // Run rules
            String resultEnumId;
            List<String> triggeredRules = new ArrayList<>();
            String inputSummary;

            if ("LEASE".equals(applicationTypeId)) {
                Integer termMonths = getInteger(step1, "termMonths");
                inputSummary = "term=" + (termMonths != null ? termMonths : "?");

                if (product != null && termMonths != null) {
                    Long minTerm = product.getLong("minTermMonths");
                    Long maxTerm = product.getLong("maxTermMonths");
                    if ((minTerm != null && termMonths < minTerm)
                            || (maxTerm != null && termMonths > maxTerm)) {
                        triggeredRules.add("RULE_TERM_RANGE");
                    }
                }
                resultEnumId = triggeredRules.isEmpty() ? RESULT_LIKELY : RESULT_UNLIKELY;
            } else {
                // LOAN rules
                BigDecimal principalAmount = getBigDecimal(step1, "principalAmount");
                Integer termMonths = getInteger(step1, "termMonths");
                BigDecimal annualIncome = getBigDecimal(step2, "annualIncome");
                inputSummary = "amount=" + principalAmount + ",term=" + termMonths;

                // RULE_AMOUNT_RANGE
                if (product != null) {
                    BigDecimal minAmt = product.getBigDecimal("minPrincipalAmount");
                    BigDecimal maxAmt = product.getBigDecimal("maxPrincipalAmount");
                    if ((minAmt != null && principalAmount.compareTo(minAmt) < 0)
                            || (maxAmt != null && principalAmount.compareTo(maxAmt) > 0)) {
                        triggeredRules.add("RULE_AMOUNT_RANGE");
                    }
                }

                // RULE_TERM_RANGE
                if (product != null && termMonths != null) {
                    Long minTerm = product.getLong("minTermMonths");
                    Long maxTerm = product.getLong("maxTermMonths");
                    if ((minTerm != null && termMonths < minTerm)
                            || (maxTerm != null && termMonths > maxTerm)) {
                        triggeredRules.add("RULE_TERM_RANGE");
                    }
                }

                // RULE_INCOME_LOW: income < amount * 0.2
                boolean incomeLow = false;
                boolean incomeModerate = false;
                if (annualIncome != null && annualIncome.compareTo(BigDecimal.ZERO) > 0
                        && principalAmount.compareTo(BigDecimal.ZERO) > 0) {
                    BigDecimal threshold20 = principalAmount.multiply(new BigDecimal("0.2"));
                    BigDecimal threshold40 = principalAmount.multiply(new BigDecimal("0.4"));
                    if (annualIncome.compareTo(threshold20) < 0) {
                        triggeredRules.add("RULE_INCOME_LOW");
                        incomeLow = true;
                    } else if (annualIncome.compareTo(threshold40) < 0) {
                        triggeredRules.add("RULE_INCOME_MODERATE");
                        incomeModerate = true;
                    }
                }

                // Determine result: UNLIKELY if any hard rule, else CONSULT if moderate income, else LIKELY
                if (!triggeredRules.isEmpty()
                        && (triggeredRules.contains("RULE_AMOUNT_RANGE")
                                || triggeredRules.contains("RULE_TERM_RANGE")
                                || incomeLow)) {
                    resultEnumId = RESULT_UNLIKELY;
                } else if (incomeModerate) {
                    resultEnumId = RESULT_CONSULT;
                } else {
                    resultEnumId = RESULT_LIKELY;
                }
            }

            // Store result (internal only — do not return triggeredRules/score to caller)
            Timestamp now = UtilDateTime.nowTimestamp();
            String preScreenId = delegator.getNextSeqId("EfPreScreenResult");
            GenericValue psr = delegator.makeValue("EfPreScreenResult");
            psr.set("preScreenId", preScreenId);
            psr.set("draftId", draftId);
            psr.set("memberPartyId", partyId);
            psr.set("preScreenDate", now);
            psr.set("resultEnumId", resultEnumId);
            psr.set("triggeredRules", String.join(",", triggeredRules));
            psr.set("inputSummary", inputSummary.length() > 255
                    ? inputSummary.substring(0, 255) : inputSummary);
            delegator.create(psr);

            writeAuditLog(delegator, partyId, ACTION_PRESCREEN, "EfPreScreenResult", preScreenId);

            // Map enumId → code to return to caller (LIKELY/CONSULT/UNLIKELY)
            String resultCode = resultEnumId.replace("EFPRE_", "");

            // Look up guidance label key
            String guidanceKey;
            if (RESULT_LIKELY.equals(resultEnumId)) {
                guidanceKey = "EfinancePreScreenLikely";
            } else if (RESULT_CONSULT.equals(resultEnumId)) {
                guidanceKey = "EfinancePreScreenConsult";
            } else {
                guidanceKey = "EfinancePreScreenUnlikely";
            }

            Map<String, Object> result = ServiceUtil.returnSuccess();
            result.put("result", resultCode);
            result.put("guidance", message(locale, guidanceKey));
            result.put("disclaimer", message(locale, "EfinanceSimulationDisclaimer"));
            return result;

        } catch (GenericEntityException e) {
            Debug.logError(e, MODULE);
            return ServiceUtil.returnError(e.getMessage());
        }
    }

    // =========================================================================
    // efGetApplicationStatus
    // =========================================================================

    public static Map<String, Object> efGetApplicationStatus(DispatchContext dctx, Map<String, Object> context) {
        Delegator delegator = dctx.getDelegator();
        Locale locale = (Locale) context.get("locale");

        String partyId = getPartyIdFromContext(context);
        String draftId = (String) context.get("draftId");

        try {
            GenericValue draft = EntityQuery.use(delegator).from("EfApplicationDraft")
                    .where("draftId", draftId).queryOne();
            if (draft == null) {
                return ServiceUtil.returnError(UtilProperties.getMessage(RESOURCE, "EfinanceDraftNotFound", locale));
            }
            if (!ownsDraft(draft, partyId)) {
                return ServiceUtil.returnError(UtilProperties.getMessage(RESOURCE, "EfinanceDraftNotOwned", locale));
            }

            String financeApplicationId = draft.getString("submittedApplicationId");
            String financeApplicationTypeId = draft.getString("submittedApplicationTypeId");

            String memberPartyId = draft.getString("memberPartyId");

            // If not yet submitted, still in RECEIVED stage
            if (UtilValidate.isEmpty(financeApplicationId)) {
                return buildStageResult(delegator, locale, draftId, memberPartyId, STAGE_RECEIVED,
                        null, null, null, null);
            }

            // Check for any pending document requests
            long pendingDocCount = EntityQuery.use(delegator).from("EfDocumentRequest")
                    .where(EntityCondition.makeCondition(
                            EntityCondition.makeCondition("draftId", draftId),
                            EntityOperator.AND,
                            EntityCondition.makeCondition("statusId", "EFDOCREQ_PENDING")))
                    .queryCount();
            if (pendingDocCount > 0) {
                return buildStageResult(delegator, locale, draftId, memberPartyId, STAGE_DOC_REQUEST,
                        financeApplicationId, financeApplicationTypeId, null, null);
            }

            String stageEnumId;
            String financeStatusId = null;
            String underwritingStatusId = null;

            if ("LEASE".equals(financeApplicationTypeId)) {
                // Lease path
                GenericValue leaseApp = EntityQuery.use(delegator).from("LeaseApplication")
                        .where("leaseApplicationId", financeApplicationId).queryOne();
                financeStatusId = (leaseApp != null) ? leaseApp.getString("statusId") : null;

                GenericValue underwriting = EntityQuery.use(delegator).from("LoanUnderwriting")
                        .where(EntityCondition.makeCondition(
                                EntityCondition.makeCondition("applicationId", financeApplicationId),
                                EntityOperator.AND,
                                EntityCondition.makeCondition("applicationTypeId", "LEASE")))
                        .orderBy("-loanUnderwritingId")
                        .queryFirst();
                underwritingStatusId = (underwriting != null) ? underwriting.getString("statusId") : null;

                GenericValue leaseAgreement = EntityQuery.use(delegator).from("LeaseAgreement")
                        .where("leaseApplicationId", financeApplicationId).queryFirst();

                GenericValue leaseQuote = EntityQuery.use(delegator).from("LeaseQuote")
                        .where(EntityCondition.makeCondition(
                                EntityCondition.makeCondition("leaseApplicationId", financeApplicationId),
                                EntityOperator.AND,
                                EntityCondition.makeCondition("statusId", "LEASEQT_PRESENTED")))
                        .queryFirst();

                stageEnumId = determineLeaseStage(financeStatusId, underwritingStatusId,
                        leaseAgreement, leaseQuote);
            } else {
                // Loan path
                GenericValue loanApp = EntityQuery.use(delegator).from("LoanApplication")
                        .where("loanApplicationId", financeApplicationId).queryOne();
                financeStatusId = (loanApp != null) ? loanApp.getString("statusId") : null;

                GenericValue underwriting = EntityQuery.use(delegator).from("LoanUnderwriting")
                        .where("loanApplicationId", financeApplicationId)
                        .orderBy("-loanUnderwritingId")
                        .queryFirst();
                underwritingStatusId = (underwriting != null) ? underwriting.getString("statusId") : null;

                GenericValue loanAgreement = EntityQuery.use(delegator).from("LoanAgreement")
                        .where("loanApplicationId", financeApplicationId).queryFirst();

                GenericValue loanQuote = EntityQuery.use(delegator).from("LoanQuote")
                        .where(EntityCondition.makeCondition(
                                EntityCondition.makeCondition("loanApplicationId", financeApplicationId),
                                EntityOperator.AND,
                                EntityCondition.makeCondition("statusId", "LOANQT_PRESENTED")))
                        .queryFirst();

                stageEnumId = determineLoanStage(financeStatusId, underwritingStatusId,
                        loanAgreement, loanQuote);
            }

            return buildStageResult(delegator, locale, draftId, memberPartyId, stageEnumId,
                    financeApplicationId, financeApplicationTypeId,
                    financeStatusId, underwritingStatusId);

        } catch (GenericEntityException e) {
            Debug.logError(e, MODULE);
            return ServiceUtil.returnError(e.getMessage());
        }
    }

    private static String determineLoanStage(String appStatusId, String uwStatusId,
            GenericValue loanAgreement, GenericValue loanQuote) {
        if (loanAgreement != null) {
            return STAGE_CONTRACTED;
        }
        if (loanQuote != null) {
            return STAGE_OFFER;
        }
        if ("LOANUW_APPROVED".equals(uwStatusId)
                || "LOANUW_REJECTED".equals(uwStatusId)
                || "LOANAPP_REJECTED".equals(appStatusId)
                || "LOANAPP_APPROVED".equals(appStatusId)) {
            return STAGE_RESULT;
        }
        if ("LOANAPP_CONTRACTED".equals(appStatusId)) {
            return STAGE_CONTRACTED;
        }
        if ("LOANUW_IN_REVIEW".equals(uwStatusId)
                || "LOANAPP_SUBMITTED".equals(appStatusId)) {
            return STAGE_UNDER_REVIEW;
        }
        return STAGE_RECEIVED;
    }

    private static String determineLeaseStage(String appStatusId, String uwStatusId,
            GenericValue leaseAgreement, GenericValue leaseQuote) {
        if (leaseAgreement != null) {
            return STAGE_CONTRACTED;
        }
        if (leaseQuote != null) {
            return STAGE_OFFER;
        }
        if ("LOANUW_APPROVED".equals(uwStatusId)
                || "LOANUW_REJECTED".equals(uwStatusId)
                || "LEASEAPP_REJECTED".equals(appStatusId)
                || "LEASEAPP_APPROVED".equals(appStatusId)) {
            return STAGE_RESULT;
        }
        if ("LEASEAPP_CONTRACTED".equals(appStatusId)) {
            return STAGE_CONTRACTED;
        }
        if ("LOANUW_IN_REVIEW".equals(uwStatusId)
                || "LEASEAPP_SUBMITTED".equals(appStatusId)) {
            return STAGE_UNDER_REVIEW;
        }
        return STAGE_RECEIVED;
    }

    private static Map<String, Object> buildStageResult(Delegator delegator, Locale locale,
            String draftId, String memberPartyId, String stageEnumId,
            String financeApplicationId, String financeApplicationTypeId,
            String financeStatusId, String underwritingStatusId) throws GenericEntityException {

        // Look up the stage description from Enumeration
        String stageDescription = stageEnumId;
        GenericValue stageEnum = EntityQuery.use(delegator).from("Enumeration")
                .where("enumId", stageEnumId).queryOne();
        if (stageEnum != null && UtilValidate.isNotEmpty(stageEnum.getString("description"))) {
            stageDescription = stageEnum.getString("description");
        }

        recordStageEvent(delegator, draftId, memberPartyId, stageEnumId, stageDescription, locale);

        // Build nextAction message
        String nextAction = buildNextAction(locale, stageEnumId);

        // Build applicationStatus map
        Map<String, Object> applicationStatus = new LinkedHashMap<>();
        if (UtilValidate.isNotEmpty(financeApplicationId)) {
            applicationStatus.put("financeApplicationId", financeApplicationId);
            applicationStatus.put("financeApplicationTypeId", financeApplicationTypeId);
            applicationStatus.put("applicationStatusId", financeStatusId);
            applicationStatus.put("underwritingStatusId", underwritingStatusId);
        }

        Map<String, Object> result = ServiceUtil.returnSuccess();
        result.put("stageEnumId", stageEnumId);
        result.put("stageDescription", stageDescription);
        result.put("nextAction", nextAction);
        result.put("applicationStatus", applicationStatus);
        return result;
    }

    public static Map<String, Object> efListStageEvents(DispatchContext dctx, Map<String, Object> context) {
        Delegator delegator = dctx.getDelegator();
        Locale locale = (Locale) context.get("locale");
        String partyId = getPartyIdFromContext(context);
        String draftId = (String) context.get("draftId");
        try {
            GenericValue draft = EntityQuery.use(delegator).from("EfApplicationDraft")
                    .where("draftId", draftId).queryOne();
            if (draft == null) {
                return ServiceUtil.returnError(UtilProperties.getMessage(RESOURCE, "EfinanceDraftNotFound", locale));
            }
            if (!ownsDraft(draft, partyId)) {
                return ServiceUtil.returnError(UtilProperties.getMessage(RESOURCE, "EfinanceDraftNotOwned", locale));
            }
            List<GenericValue> events = EntityQuery.use(delegator).from("EfStageEvent")
                    .where("draftId", draftId)
                    .orderBy("-eventDate")
                    .queryList();
            List<Map<String, Object>> rows = new ArrayList<>();
            for (GenericValue event : events) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("stageEventId", event.getString("stageEventId"));
                row.put("fromStageEnumId", event.getString("fromStageEnumId"));
                row.put("toStageEnumId", event.getString("toStageEnumId"));
                row.put("eventDate", event.getTimestamp("eventDate"));
                row.put("notified", event.getString("notified"));
                rows.add(row);
            }
            Map<String, Object> result = ServiceUtil.returnSuccess();
            result.put("stageEvents", rows);
            return result;
        } catch (GenericEntityException e) {
            Debug.logError(e, MODULE);
            return ServiceUtil.returnError(e.getMessage());
        }
    }

    private static void recordStageEvent(Delegator delegator, String draftId, String memberPartyId,
            String stageEnumId, String stageDescription, Locale locale) throws GenericEntityException {
        if (UtilValidate.isEmpty(draftId) || UtilValidate.isEmpty(memberPartyId)
                || UtilValidate.isEmpty(stageEnumId)) {
            return;
        }
        GenericValue latest = EntityQuery.use(delegator).from("EfStageEvent")
                .where("draftId", draftId)
                .orderBy("-eventDate")
                .queryFirst();
        if (latest != null && stageEnumId.equals(latest.getString("toStageEnumId"))) {
            if (!"Y".equals(latest.getString("notified"))) {
                notifyStageEvent(delegator, latest, memberPartyId, draftId, stageEnumId, stageDescription, locale);
            }
            return;
        }

        GenericValue event = delegator.makeValue("EfStageEvent");
        event.set("stageEventId", delegator.getNextSeqId("EfStageEvent"));
        event.set("draftId", draftId);
        event.set("memberPartyId", memberPartyId);
        event.set("fromStageEnumId", latest == null ? null : latest.getString("toStageEnumId"));
        event.set("toStageEnumId", stageEnumId);
        event.set("eventDate", UtilDateTime.nowTimestamp());
        event.set("notified", "N");
        delegator.create(event);
        notifyStageEvent(delegator, event, memberPartyId, draftId, stageEnumId, stageDescription, locale);
    }

    private static void notifyStageEvent(Delegator delegator, GenericValue event, String memberPartyId,
            String draftId, String stageEnumId, String stageDescription, Locale locale) {
        try {
            NotificationAdapter adapter = NotificationAdapterFactory.getAdapter();
            if (adapter.sendStageChange(delegator, memberPartyId, draftId, stageEnumId, stageDescription, locale)) {
                event.set("notified", "Y");
                delegator.store(event);
            }
        } catch (Exception e) {
            Debug.logError(e, "Unable to notify stage change for draft " + draftId, MODULE);
        }
    }

    private static String buildNextAction(Locale locale, String stageEnumId) {
        switch (stageEnumId) {
            case STAGE_RECEIVED:
                return null;
            case STAGE_DOC_REQUEST:
                return message(locale, "EfinanceStagePendingDocRequest");
            case STAGE_UNDER_REVIEW:
                return message(locale, "EfinanceStageUnderReview");
            case STAGE_RESULT:
                return message(locale, "EfinanceStageResult");
            case STAGE_OFFER:
                return message(locale, "EfinanceStageOffer");
            case STAGE_CONTRACTED:
                return message(locale, "EfinanceStageContracted");
            default:
                return null;
        }
    }

    // =========================================================================
    // efCreateDocumentRequest
    // =========================================================================

    public static Map<String, Object> efCreateDocumentRequest(DispatchContext dctx,
            Map<String, Object> context) {
        Delegator delegator = dctx.getDelegator();
        Locale locale = (Locale) context.get("locale");

        String staffPartyId = getPartyIdFromContext(context);
        String draftId = (String) context.get("draftId");
        String documentTypeEnumId = (String) context.get("documentTypeEnumId");
        String reason = (String) context.get("reason");
        Timestamp dueDate = (Timestamp) context.get("dueDate");

        try {
            GenericValue draft = EntityQuery.use(delegator).from("EfApplicationDraft")
                    .where("draftId", draftId).queryOne();
            if (draft == null) {
                return ServiceUtil.returnError(UtilProperties.getMessage(RESOURCE, "EfinanceDraftNotFound", locale));
            }

            String memberPartyId = draft.getString("memberPartyId");
            Timestamp now = UtilDateTime.nowTimestamp();

            String docRequestId = delegator.getNextSeqId("EfDocumentRequest");
            GenericValue req = delegator.makeValue("EfDocumentRequest");
            req.set("docRequestId", docRequestId);
            req.set("draftId", draftId);
            req.set("memberPartyId", memberPartyId);
            req.set("documentTypeEnumId", documentTypeEnumId);
            req.set("reason", reason);
            req.set("dueDate", dueDate);
            req.set("statusId", "EFDOCREQ_PENDING");
            req.set("requestedByPartyId", staffPartyId);
            req.set("requestedDate", now);
            delegator.create(req);

            writeAuditLog(delegator, staffPartyId, ACTION_DOC_REQ_CREATE, "EfDocumentRequest", docRequestId);

            Map<String, Object> result = ServiceUtil.returnSuccess();
            result.put("docRequestId", docRequestId);
            return result;

        } catch (GenericEntityException e) {
            Debug.logError(e, MODULE);
            return ServiceUtil.returnError(e.getMessage());
        }
    }

    // =========================================================================
    // efListDocumentRequests
    // =========================================================================

    public static Map<String, Object> efListDocumentRequests(DispatchContext dctx,
            Map<String, Object> context) {
        Delegator delegator = dctx.getDelegator();
        Locale locale = (Locale) context.get("locale");

        String partyId = getPartyIdFromContext(context);
        String draftId = (String) context.get("draftId");

        try {
            GenericValue draft = EntityQuery.use(delegator).from("EfApplicationDraft")
                    .where("draftId", draftId).queryOne();
            if (draft == null) {
                return ServiceUtil.returnError(UtilProperties.getMessage(RESOURCE, "EfinanceDraftNotFound", locale));
            }
            if (!ownsDraft(draft, partyId)) {
                return ServiceUtil.returnError(UtilProperties.getMessage(RESOURCE, "EfinanceDraftNotOwned", locale));
            }

            List<GenericValue> reqList = EntityQuery.use(delegator).from("EfDocumentRequest")
                    .where("draftId", draftId)
                    .orderBy("requestedDate")
                    .queryList();

            List<Map<String, Object>> docRequests = new ArrayList<>();
            for (GenericValue req : reqList) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("docRequestId", req.getString("docRequestId"));
                m.put("documentTypeEnumId", req.getString("documentTypeEnumId"));
                m.put("reason", req.getString("reason"));
                m.put("dueDate", req.getTimestamp("dueDate"));
                m.put("statusId", req.getString("statusId"));
                m.put("requestedDate", req.getTimestamp("requestedDate"));
                m.put("submittedDate", req.getTimestamp("submittedDate"));
                docRequests.add(m);
            }

            Map<String, Object> result = ServiceUtil.returnSuccess();
            result.put("docRequests", docRequests);
            return result;

        } catch (GenericEntityException e) {
            Debug.logError(e, MODULE);
            return ServiceUtil.returnError(e.getMessage());
        }
    }

    // =========================================================================
    // efSubmitRequestedDocument
    // =========================================================================

    public static Map<String, Object> efSubmitRequestedDocument(DispatchContext dctx,
            Map<String, Object> context) {
        Delegator delegator = dctx.getDelegator();
        LocalDispatcher dispatcher = dctx.getDispatcher();
        Locale locale = (Locale) context.get("locale");

        String partyId = getPartyIdFromContext(context);
        String docRequestId = (String) context.get("docRequestId");
        String fileName = (String) context.get("fileName");
        String contentType = (String) context.get("contentType");
        ByteBuffer uploadedFile = (ByteBuffer) context.get("uploadedFile");

        try {
            GenericValue req = EntityQuery.use(delegator).from("EfDocumentRequest")
                    .where("docRequestId", docRequestId).queryOne();
            if (req == null) {
                return ServiceUtil.returnError(UtilProperties.getMessage(RESOURCE, "EfinanceDocRequestNotFound", locale));
            }

            // Ownership check
            if (!partyId.equals(req.getString("memberPartyId"))) {
                return ServiceUtil.returnError(UtilProperties.getMessage(RESOURCE, "EfinanceDraftNotOwned", locale));
            }

            // Must be PENDING to submit
            if (!"EFDOCREQ_PENDING".equals(req.getString("statusId"))) {
                return ServiceUtil.returnError(UtilProperties.getMessage(RESOURCE, "EfinanceDocRequestNotPending", locale));
            }
            FileScanResult scanResult = FileScanAdapterFactory.scan(fileName, contentType, uploadedFile);
            if (scanResult.isInfected()) {
                return ServiceUtil.returnError(UtilProperties.getMessage(
                        RESOURCE, "EfinanceFileInfected", locale));
            }

            // Upload file using createContentFromUploadedFile
            String contentId = null;
            GenericValue systemUser = getSystemUser(delegator);
            if (systemUser != null && uploadedFile != null) {
                java.util.Map<String, Object> contentCtx = new java.util.HashMap<>();
                contentCtx.put("userLogin", systemUser);
                contentCtx.put("dataResourceTypeId", "LOCAL_FILE");
                contentCtx.put("dataTemplateTypeId", "NONE");
                contentCtx.put("dataResourceName", fileName);
                contentCtx.put("mimeTypeId", contentType);
                contentCtx.put("contentTypeId", "DOCUMENT");
                contentCtx.put("uploadedFile", uploadedFile);
                contentCtx.put("_uploadedFile_fileName", fileName);
                contentCtx.put("_uploadedFile_contentType", contentType);

                Map<String, Object> contentResult = dispatcher.runSync("createContentFromUploadedFile", contentCtx);
                if (ServiceUtil.isSuccess(contentResult)) {
                    contentId = (String) contentResult.get("contentId");
                } else {
                    Debug.logWarning("createContentFromUploadedFile failed: "
                            + ServiceUtil.getErrorMessage(contentResult), MODULE);
                }
            }

            // Update document request
            Timestamp now = UtilDateTime.nowTimestamp();
            req.set("statusId", "EFDOCREQ_SUBMITTED");
            req.set("submittedContentId", contentId);
            req.set("submittedDate", now);
            req.set("scanStatus", scanResult.getStatus());
            delegator.store(req);

            writeAuditLog(delegator, partyId, ACTION_DOC_REQ_SUBMIT, "EfDocumentRequest", docRequestId);

            Map<String, Object> result = ServiceUtil.returnSuccess();
            result.put("scanStatus", scanResult.getStatus());
            return result;

        } catch (Exception e) {
            Debug.logError(e, MODULE);
            return ServiceUtil.returnError(e.getMessage());
        }
    }

    // =========================================================================
    // Private helpers
    // =========================================================================

    private static Map<String, Object> parseDraftJson(String json) {
        if (UtilValidate.isEmpty(json)) return new LinkedHashMap<>();
        try {
            return MAPPER.readValue(json, new TypeReference<Map<String, Object>>() {});
        } catch (Exception e) {
            Debug.logWarning("Failed to parse draftJson: " + e.getMessage(), MODULE);
            return new LinkedHashMap<>();
        }
    }

    private static String getPartyIdFromContext(Map<String, Object> context) {
        GenericValue userLogin = (GenericValue) context.get("userLogin");
        if (userLogin == null) return null;
        return userLogin.getString("partyId");
    }

    private static boolean ownsDraft(GenericValue draft, String partyId) {
        if (partyId == null) return false;
        try {
            return EfAccessGuard.isDraftAccessible(draft.getDelegator(), draft, partyId, "roleApply");
        } catch (GenericEntityException e) {
            Debug.logError(e, "Unable to check draft ownership", MODULE);
            return false;
        }
    }

    private static BigDecimal getBigDecimal(Map<String, Object> data, String key) {
        if (data == null) return null;
        Object v = data.get(key);
        if (v == null || v.toString().trim().isEmpty()) return null;
        if (v instanceof BigDecimal) return (BigDecimal) v;
        try {
            return new BigDecimal(v.toString().trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static Integer getInteger(Map<String, Object> data, String key) {
        if (data == null) return null;
        Object v = data.get(key);
        if (v == null || v.toString().trim().isEmpty()) return null;
        if (v instanceof Integer) return (Integer) v;
        if (v instanceof Long) return ((Long) v).intValue();
        if (v instanceof Number) return ((Number) v).intValue();
        try {
            return Integer.parseInt(v.toString().trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String message(Locale locale, String key) {
        if (locale == null) locale = Locale.getDefault();
        return UtilProperties.getMessage(RESOURCE, key, locale);
    }

    private static GenericValue getSystemUser(Delegator delegator) throws GenericEntityException {
        String sysLoginId = UtilProperties.getPropertyValue(PROPS, "efinance.system.userlogin", "efinance-system");
        return EntityQuery.use(delegator).from("UserLogin").where("userLoginId", sysLoginId).queryOne();
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
}
