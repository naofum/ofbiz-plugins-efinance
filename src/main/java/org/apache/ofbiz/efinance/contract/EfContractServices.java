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
package org.apache.ofbiz.efinance.contract;

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
 * STEP W5: eFinance electronic contract workflow and document delivery services.
 */
public final class EfContractServices {

    private static final String MODULE = EfContractServices.class.getName();
    private static final String RESOURCE = "EfinanceUiLabels";
    private static final String PROPS = "efinance";

    private static final String STATUS_EFSIG_PENDING = "EFSIG_PENDING";
    private static final String STATUS_EFSIG_SIGNED = "EFSIG_SIGNED";

    private static final String ACTION_OFFER_ACCEPT = "EFAUD_OFFER_ACCEPT";
    private static final String ACTION_SIGN_START = "EFAUD_SIGN_START";
    private static final String ACTION_SIGN_COMPLETE = "EFAUD_SIGN_COMPLETE";
    private static final String ACTION_DOC_DELIVER = "EFAUD_DOC_DELIVER";
    private static final String RESULT_SUCCESS = "EFAUDRES_SUCCESS";

    private static final String QUOTE_TYPE_LOAN = "LOAN";
    private static final String QUOTE_TYPE_LEASE = "LEASE";

    private static final int SIGNING_SESSION_EXPIRY_HOURS = 24;

    private EfContractServices() {
    }

    // =========================================================================
    // efGetOffer
    // =========================================================================

    public static Map<String, Object> efGetOffer(DispatchContext dctx, Map<String, Object> context) {
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

            if (UtilValidate.isEmpty(financeApplicationId)) {
                return ServiceUtil.returnError(
                        UtilProperties.getMessage(RESOURCE, "EfinanceNoApplicationSubmitted", locale));
            }

            Map<String, Object> result = ServiceUtil.returnSuccess();

            if (QUOTE_TYPE_LEASE.equals(financeApplicationTypeId)) {
                GenericValue leaseQuote = EntityQuery.use(delegator).from("LeaseQuote")
                        .where(EntityCondition.makeCondition(
                                EntityCondition.makeCondition("leaseApplicationId", financeApplicationId),
                                EntityOperator.AND,
                                EntityCondition.makeCondition("statusId", "LEASEQT_PRESENTED")))
                        .orderBy("-createdDate")
                        .queryFirst();
                if (leaseQuote == null) {
                    result.put("offer", null);
                    result.put("quoteId", null);
                    result.put("quoteTypeId", null);
                    return result;
                }
                Map<String, Object> offer = buildLeaseOffer(leaseQuote);
                result.put("offer", offer);
                result.put("quoteId", leaseQuote.getString("leaseQuoteId"));
                result.put("quoteTypeId", QUOTE_TYPE_LEASE);
            } else {
                GenericValue loanQuote = EntityQuery.use(delegator).from("LoanQuote")
                        .where(EntityCondition.makeCondition(
                                EntityCondition.makeCondition("loanApplicationId", financeApplicationId),
                                EntityOperator.AND,
                                EntityCondition.makeCondition("statusId", "LOANQT_PRESENTED")))
                        .orderBy("-createdDate")
                        .queryFirst();
                if (loanQuote == null) {
                    result.put("offer", null);
                    result.put("quoteId", null);
                    result.put("quoteTypeId", null);
                    return result;
                }
                Map<String, Object> offer = buildLoanOffer(loanQuote);
                result.put("offer", offer);
                result.put("quoteId", loanQuote.getString("loanQuoteId"));
                result.put("quoteTypeId", QUOTE_TYPE_LOAN);
            }

            return result;

        } catch (GenericEntityException e) {
            Debug.logError(e, MODULE);
            return ServiceUtil.returnError(e.getMessage());
        }
    }

    // =========================================================================
    // efAcceptOffer
    // =========================================================================

    public static Map<String, Object> efAcceptOffer(DispatchContext dctx, Map<String, Object> context) {
        Delegator delegator = dctx.getDelegator();
        LocalDispatcher dispatcher = dctx.getDispatcher();
        Locale locale = (Locale) context.get("locale");

        String partyId = getPartyIdFromContext(context);
        String draftId = (String) context.get("draftId");
        String quoteId = (String) context.get("quoteId");
        String quoteTypeId = (String) context.get("quoteTypeId");

        try {
            GenericValue draft = EntityQuery.use(delegator).from("EfApplicationDraft")
                    .where("draftId", draftId).queryOne();
            if (draft == null) {
                return ServiceUtil.returnError(UtilProperties.getMessage(RESOURCE, "EfinanceDraftNotFound", locale));
            }
            if (!ownsDraft(draft, partyId)) {
                return ServiceUtil.returnError(UtilProperties.getMessage(RESOURCE, "EfinanceDraftNotOwned", locale));
            }

            GenericValue systemUser = getSystemUser(delegator);

            Map<String, Object> svcCtx = new LinkedHashMap<>();
            svcCtx.put("userLogin", systemUser);

            Map<String, Object> svcResult;
            if (QUOTE_TYPE_LEASE.equals(quoteTypeId)) {
                svcCtx.put("leaseQuoteId", quoteId);
                svcCtx.put("statusId", "LEASEQT_ACCEPTED");
                svcResult = dispatcher.runSync("setLeaseQuoteStatus", svcCtx);
            } else {
                svcCtx.put("loanQuoteId", quoteId);
                svcCtx.put("statusId", "LOANQT_ACCEPTED");
                svcResult = dispatcher.runSync("setLoanQuoteStatus", svcCtx);
            }

            if (!ServiceUtil.isSuccess(svcResult)) {
                return ServiceUtil.returnError(ServiceUtil.getErrorMessage(svcResult));
            }

            writeAuditLog(delegator, partyId, ACTION_OFFER_ACCEPT, "EfApplicationDraft", draftId);

            Map<String, Object> result = ServiceUtil.returnSuccess();
            result.put("acceptedQuoteId", quoteId);
            return result;

        } catch (Exception e) {
            Debug.logError(e, MODULE);
            return ServiceUtil.returnError(e.getMessage());
        }
    }

    // =========================================================================
    // efStartSigning
    // =========================================================================

    public static Map<String, Object> efStartSigning(DispatchContext dctx, Map<String, Object> context) {
        Delegator delegator = dctx.getDelegator();
        Locale locale = (Locale) context.get("locale");

        String partyId = getPartyIdFromContext(context);
        String draftId = (String) context.get("draftId");
        String quoteId = (String) context.get("quoteId");
        String quoteTypeId = (String) context.get("quoteTypeId");

        try {
            GenericValue draft = EntityQuery.use(delegator).from("EfApplicationDraft")
                    .where("draftId", draftId).queryOne();
            if (draft == null) {
                return ServiceUtil.returnError(UtilProperties.getMessage(RESOURCE, "EfinanceDraftNotFound", locale));
            }
            if (!ownsDraft(draft, partyId)) {
                return ServiceUtil.returnError(UtilProperties.getMessage(RESOURCE, "EfinanceDraftNotOwned", locale));
            }

            // Verify quote is ACCEPTED
            if (QUOTE_TYPE_LEASE.equals(quoteTypeId)) {
                GenericValue leaseQuote = EntityQuery.use(delegator).from("LeaseQuote")
                        .where("leaseQuoteId", quoteId).queryOne();
                if (leaseQuote == null || !"LEASEQT_ACCEPTED".equals(leaseQuote.getString("statusId"))) {
                    return ServiceUtil.returnError(
                            UtilProperties.getMessage(RESOURCE, "EfinanceQuoteNotAccepted", locale));
                }
            } else {
                GenericValue loanQuote = EntityQuery.use(delegator).from("LoanQuote")
                        .where("loanQuoteId", quoteId).queryOne();
                if (loanQuote == null || !"LOANQT_ACCEPTED".equals(loanQuote.getString("statusId"))) {
                    return ServiceUtil.returnError(
                            UtilProperties.getMessage(RESOURCE, "EfinanceQuoteNotAccepted", locale));
                }
            }

            // Check no existing PENDING signing session for this draft
            GenericValue existingSession = EntityQuery.use(delegator).from("EfSigningSession")
                    .where(EntityCondition.makeCondition(
                            EntityCondition.makeCondition("draftId", draftId),
                            EntityOperator.AND,
                            EntityCondition.makeCondition("statusId", STATUS_EFSIG_PENDING)))
                    .queryFirst();
            if (existingSession != null) {
                return ServiceUtil.returnError(
                        UtilProperties.getMessage(RESOURCE, "EfinanceSigningAlreadyActive", locale));
            }

            // Create signing session
            Timestamp now = UtilDateTime.nowTimestamp();
            Calendar cal = Calendar.getInstance();
            cal.setTimeInMillis(now.getTime());
            cal.add(Calendar.HOUR_OF_DAY, SIGNING_SESSION_EXPIRY_HOURS);
            Timestamp expiresAt = new Timestamp(cal.getTimeInMillis());

            String signingSessionId = delegator.getNextSeqId("EfSigningSession");
            GenericValue session = delegator.makeValue("EfSigningSession");
            session.set("signingSessionId", signingSessionId);
            session.set("draftId", draftId);
            session.set("memberPartyId", partyId);
            session.set("quoteId", quoteId);
            session.set("quoteTypeId", quoteTypeId);
            session.set("statusId", STATUS_EFSIG_PENDING);
            session.set("expiresAt", expiresAt);
            session.set("createdDate", now);
            delegator.create(session);

            // Build contractSummary
            Map<String, Object> contractSummary;
            if (QUOTE_TYPE_LEASE.equals(quoteTypeId)) {
                GenericValue leaseQuote = EntityQuery.use(delegator).from("LeaseQuote")
                        .where("leaseQuoteId", quoteId).queryOne();
                contractSummary = buildLeaseOffer(leaseQuote);
            } else {
                GenericValue loanQuote = EntityQuery.use(delegator).from("LoanQuote")
                        .where("loanQuoteId", quoteId).queryOne();
                contractSummary = buildLoanOffer(loanQuote);
            }

            writeAuditLog(delegator, partyId, ACTION_SIGN_START, "EfSigningSession", signingSessionId);

            Map<String, Object> result = ServiceUtil.returnSuccess();
            result.put("signingSessionId", signingSessionId);
            result.put("contractSummary", contractSummary);
            return result;

        } catch (GenericEntityException e) {
            Debug.logError(e, MODULE);
            return ServiceUtil.returnError(e.getMessage());
        }
    }

    // =========================================================================
    // efCompleteSigning
    // =========================================================================

    public static Map<String, Object> efCompleteSigning(DispatchContext dctx, Map<String, Object> context) {
        Delegator delegator = dctx.getDelegator();
        LocalDispatcher dispatcher = dctx.getDispatcher();
        Locale locale = (Locale) context.get("locale");

        String partyId = getPartyIdFromContext(context);
        String signingSessionId = (String) context.get("signingSessionId");
        Boolean confirmContent = (Boolean) context.get("confirmContent");
        String ipAddress = (String) context.get("ipAddress");
        String deviceId = (String) context.get("deviceId");

        try {
            GenericValue session = EntityQuery.use(delegator).from("EfSigningSession")
                    .where("signingSessionId", signingSessionId).queryOne();
            if (session == null) {
                return ServiceUtil.returnError(
                        UtilProperties.getMessage(RESOURCE, "EfinanceSigningSessionNotFound", locale));
            }

            // Ownership check
            if (!partyId.equals(session.getString("memberPartyId"))) {
                return ServiceUtil.returnError(
                        UtilProperties.getMessage(RESOURCE, "EfinanceDraftNotOwned", locale));
            }

            // Status check
            if (!STATUS_EFSIG_PENDING.equals(session.getString("statusId"))) {
                return ServiceUtil.returnError(
                        UtilProperties.getMessage(RESOURCE, "EfinanceSigningSessionNotFound", locale));
            }

            // Expiry check
            Timestamp now = UtilDateTime.nowTimestamp();
            Timestamp expiresAt = session.getTimestamp("expiresAt");
            if (expiresAt != null && now.after(expiresAt)) {
                session.set("statusId", "EFSIG_EXPIRED");
                delegator.store(session);
                return ServiceUtil.returnError(
                        UtilProperties.getMessage(RESOURCE, "EfinanceSigningExpired", locale));
            }

            // Confirm content check
            if (!Boolean.TRUE.equals(confirmContent)) {
                return ServiceUtil.returnError(
                        UtilProperties.getMessage(RESOURCE, "EfinanceSigningNotConfirmed", locale));
            }

            // Contract prerequisites: verified KYC and electronic-contract consent.
            if (!isKycVerified(delegator, partyId)) {
                return ServiceUtil.returnError(
                        UtilProperties.getMessage(RESOURCE, "EfinanceKycRequiredForContract", locale));
            }
            if (!hasElectronicContractConsent(delegator, partyId)) {
                return ServiceUtil.returnError(
                        UtilProperties.getMessage(RESOURCE, "EfinanceConsentRequired", locale));
            }

            String quoteId = session.getString("quoteId");
            String quoteTypeId = session.getString("quoteTypeId");
            String draftId = session.getString("draftId");

            GenericValue systemUser = getSystemUser(delegator);

            // Call finance service to create agreement
            Map<String, Object> svcCtx = new LinkedHashMap<>();
            svcCtx.put("userLogin", systemUser);

            Map<String, Object> svcResult;
            String agreementId;
            String agreementTypeId;

            if (QUOTE_TYPE_LEASE.equals(quoteTypeId)) {
                svcCtx.put("leaseQuoteId", quoteId);
                svcCtx.put("organizationPartyId", "Company");
                svcResult = dispatcher.runSync("createLeaseAgreement", svcCtx);
            } else {
                svcCtx.put("loanQuoteId", quoteId);
                svcCtx.put("organizationPartyId", "Company");
                svcCtx.put("agreementDate", now);
                svcResult = dispatcher.runSync("createLoanAgreement", svcCtx);
            }

            if (!ServiceUtil.isSuccess(svcResult)) {
                Debug.logWarning("createAgreement failed for session " + signingSessionId
                        + ": " + ServiceUtil.getErrorMessage(svcResult), MODULE);
                return ServiceUtil.returnError(
                        UtilProperties.getMessage(RESOURCE, "EfinanceSigningAgreementFailed", locale));
            }

            if (QUOTE_TYPE_LEASE.equals(quoteTypeId)) {
                agreementId = (String) svcResult.get("leaseAgreementId");
                agreementTypeId = QUOTE_TYPE_LEASE;
            } else {
                agreementId = (String) svcResult.get("loanAgreementId");
                agreementTypeId = QUOTE_TYPE_LOAN;
            }

            // Update signing session to SIGNED
            session.set("statusId", STATUS_EFSIG_SIGNED);
            session.set("consentIpAddress", ipAddress);
            session.set("consentDeviceId", deviceId);
            session.set("consentDate", now);
            session.set("agreementId", agreementId);
            session.set("agreementTypeId", agreementTypeId);
            delegator.store(session);

            // Create EfDeliveredDocument record for the contract
            createDeliveredDocument(delegator, draftId, partyId, "EFDELDOC_CONTRACT", null, now);

            writeAuditLog(delegator, partyId, ACTION_SIGN_COMPLETE, "EfSigningSession", signingSessionId);

            Map<String, Object> result = ServiceUtil.returnSuccess();
            result.put("agreementId", agreementId);
            result.put("agreementTypeId", agreementTypeId);
            return result;

        } catch (Exception e) {
            Debug.logError(e, MODULE);
            return ServiceUtil.returnError(e.getMessage());
        }
    }

    // =========================================================================
    // efListDocuments
    // =========================================================================

    public static Map<String, Object> efListDocuments(DispatchContext dctx, Map<String, Object> context) {
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

            List<GenericValue> docList = EntityQuery.use(delegator).from("EfDeliveredDocument")
                    .where("draftId", draftId)
                    .orderBy("deliveredDate")
                    .queryList();

            List<Map<String, Object>> documents = new ArrayList<>();
            for (GenericValue doc : docList) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("deliveredDocId", doc.getString("deliveredDocId"));
                m.put("documentTypeEnumId", doc.getString("documentTypeEnumId"));
                m.put("deliveredDate", doc.getTimestamp("deliveredDate"));
                m.put("viewedDate", doc.getTimestamp("viewedDate"));
                m.put("hasContent", UtilValidate.isNotEmpty(doc.getString("contentId")));
                documents.add(m);
            }

            Map<String, Object> result = ServiceUtil.returnSuccess();
            result.put("documents", documents);
            return result;

        } catch (GenericEntityException e) {
            Debug.logError(e, MODULE);
            return ServiceUtil.returnError(e.getMessage());
        }
    }

    // =========================================================================
    // efDownloadDocument
    // =========================================================================

    public static Map<String, Object> efDownloadDocument(DispatchContext dctx, Map<String, Object> context) {
        Delegator delegator = dctx.getDelegator();
        Locale locale = (Locale) context.get("locale");

        String partyId = getPartyIdFromContext(context);
        String deliveredDocId = (String) context.get("deliveredDocId");

        try {
            GenericValue doc = EntityQuery.use(delegator).from("EfDeliveredDocument")
                    .where("deliveredDocId", deliveredDocId).queryOne();
            if (doc == null) {
                return ServiceUtil.returnError(
                        UtilProperties.getMessage(RESOURCE, "EfinanceDraftNotFound", locale));
            }

            // Ownership check
            if (!partyId.equals(doc.getString("memberPartyId"))) {
                return ServiceUtil.returnError(
                        UtilProperties.getMessage(RESOURCE, "EfinanceDraftNotOwned", locale));
            }

            // Update viewedDate on first view
            if (doc.getTimestamp("viewedDate") == null) {
                doc.set("viewedDate", UtilDateTime.nowTimestamp());
                delegator.store(doc);
            }

            Map<String, Object> documentMap = new LinkedHashMap<>();
            documentMap.put("deliveredDocId", doc.getString("deliveredDocId"));
            documentMap.put("draftId", doc.getString("draftId"));
            documentMap.put("documentTypeEnumId", doc.getString("documentTypeEnumId"));
            documentMap.put("deliveredDate", doc.getTimestamp("deliveredDate"));
            documentMap.put("viewedDate", doc.getTimestamp("viewedDate"));

            Map<String, Object> result = ServiceUtil.returnSuccess();
            result.put("document", documentMap);

            String contentId = doc.getString("contentId");
            if (UtilValidate.isEmpty(contentId)) {
                result.put("mimeTypeId", null);
                result.put("fileName", null);
                result.put("textData", null);
                return result;
            }

            // Look up content data
            GenericValue contentDataView = EntityQuery.use(delegator).from("ContentDataResourceView")
                    .where("contentId", contentId).queryOne();
            if (contentDataView != null) {
                result.put("mimeTypeId", contentDataView.getString("mimeTypeId"));
                result.put("fileName", contentDataView.getString("drObjectInfo"));
                String textData = contentDataView.getString("drObjectInfo");
                result.put("textData", textData);
            } else {
                result.put("mimeTypeId", null);
                result.put("fileName", null);
                result.put("textData", null);
            }

            return result;

        } catch (GenericEntityException e) {
            Debug.logError(e, MODULE);
            return ServiceUtil.returnError(e.getMessage());
        }
    }

    // =========================================================================
    // Private helpers
    // =========================================================================

    private static Map<String, Object> buildLoanOffer(GenericValue loanQuote) {
        if (loanQuote == null) return new LinkedHashMap<>();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("quoteId", loanQuote.getString("loanQuoteId"));
        m.put("quoteTypeId", QUOTE_TYPE_LOAN);
        m.put("principalAmount", loanQuote.getBigDecimal("principalAmount"));
        m.put("annualInterestRate", loanQuote.getBigDecimal("annualInterestRate"));
        m.put("termMonths", loanQuote.getLong("termMonths"));
        m.put("monthlyPaymentAmount", loanQuote.getBigDecimal("monthlyPaymentAmount"));
        m.put("totalPaymentAmount", loanQuote.getBigDecimal("totalPaymentAmount"));
        m.put("totalInterestAmount", loanQuote.getBigDecimal("totalInterestAmount"));
        m.put("currencyUomId", loanQuote.getString("currencyUomId"));
        m.put("firstPaymentDate", loanQuote.getTimestamp("firstPaymentDate"));
        return m;
    }

    private static Map<String, Object> buildLeaseOffer(GenericValue leaseQuote) {
        if (leaseQuote == null) return new LinkedHashMap<>();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("quoteId", leaseQuote.getString("leaseQuoteId"));
        m.put("quoteTypeId", QUOTE_TYPE_LEASE);
        m.put("acquisitionCost", leaseQuote.getBigDecimal("acquisitionCost"));
        m.put("termMonths", leaseQuote.getLong("termMonths"));
        m.put("monthlyLeaseAmount", leaseQuote.getBigDecimal("monthlyLeaseAmount"));
        m.put("totalLeaseAmount", leaseQuote.getBigDecimal("totalLeaseAmount"));
        m.put("totalInterestAmount", leaseQuote.getBigDecimal("totalInterestAmount"));
        m.put("totalTaxAmount", leaseQuote.getBigDecimal("totalTaxAmount"));
        m.put("calcInterestRate", leaseQuote.getBigDecimal("calcInterestRate"));
        m.put("currencyUomId", leaseQuote.getString("currencyUomId"));
        m.put("firstPaymentDate", leaseQuote.getTimestamp("firstPaymentDate"));
        return m;
    }

    private static void createDeliveredDocument(Delegator delegator, String draftId,
            String memberPartyId, String documentTypeEnumId, String contentId, Timestamp now) {
        try {
            String deliveredDocId = delegator.getNextSeqId("EfDeliveredDocument");
            GenericValue doc = delegator.makeValue("EfDeliveredDocument");
            doc.set("deliveredDocId", deliveredDocId);
            doc.set("draftId", draftId);
            doc.set("memberPartyId", memberPartyId);
            doc.set("documentTypeEnumId", documentTypeEnumId);
            doc.set("contentId", contentId);
            doc.set("deliveredDate", now);
            delegator.create(doc);
            writeAuditLog(delegator, memberPartyId, ACTION_DOC_DELIVER, "EfDeliveredDocument", deliveredDocId);
        } catch (GenericEntityException e) {
            Debug.logWarning("Failed to create delivered document: " + e.getMessage(), MODULE);
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

    private static boolean isKycVerified(Delegator delegator, String partyId) throws GenericEntityException {
        if (UtilValidate.isEmpty(partyId)) {
            return false;
        }
        return EntityQuery.use(delegator).from("EfKycCheck")
                .where(EntityCondition.makeCondition(EntityOperator.AND,
                        EntityCondition.makeCondition("memberPartyId", partyId),
                        EntityCondition.makeCondition("statusId", "EFKYC_VERIFIED")))
                .queryFirst() != null;
    }

    private static boolean hasElectronicContractConsent(Delegator delegator, String partyId)
            throws GenericEntityException {
        if (UtilValidate.isEmpty(partyId)) {
            return false;
        }
        GenericValue document = EntityQuery.use(delegator).from("EfConsentDocument")
                .where("documentTypeEnumId", "EFCONS_ELEC_CTR")
                .orderBy("-effectiveDate")
                .queryFirst();
        if (document == null) {
            return false;
        }
        return EntityQuery.use(delegator).from("EfConsent")
                .where(EntityCondition.makeCondition(EntityOperator.AND,
                        EntityCondition.makeCondition("partyId", partyId),
                        EntityCondition.makeCondition("documentId", document.getString("documentId")),
                        EntityCondition.makeCondition("revokedDate", EntityOperator.EQUALS, null)))
                .queryFirst() != null;
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
