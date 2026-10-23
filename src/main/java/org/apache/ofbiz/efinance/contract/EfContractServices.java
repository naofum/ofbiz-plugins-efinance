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

import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Timestamp;
import java.text.DecimalFormat;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Calendar;
import java.util.HashMap;
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
import org.apache.ofbiz.efinance.contract.signature.ESignatureAdapter;
import org.apache.ofbiz.efinance.contract.signature.ESignatureAdapterFactory;
import org.apache.ofbiz.efinance.contract.signature.ESignatureException;
import org.apache.ofbiz.efinance.contract.signature.SignatureRequest;
import org.apache.ofbiz.efinance.contract.signature.SignatureResult;
import org.apache.ofbiz.efinance.security.EfAccessGuard;
import org.apache.ofbiz.party.party.PartyHelper;
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
            result.put("offerAvailable", false);

            if (QUOTE_TYPE_LEASE.equals(financeApplicationTypeId)) {
                GenericValue leaseQuote = EntityQuery.use(delegator).from("LeaseQuote")
                        .where(EntityCondition.makeCondition(
                                EntityCondition.makeCondition("leaseApplicationId", financeApplicationId),
                                EntityOperator.AND,
                                EntityCondition.makeCondition("statusId", "LEASEQT_PRESENTED")))
                        .orderBy("-createdDate")
                        .queryFirst();
                if (leaseQuote == null) {
                    // Let the customer resume signing after accepting an offer, unless
                    // the accepted quote has already been finalized as an agreement.
                    leaseQuote = EntityQuery.use(delegator).from("LeaseQuote")
                            .where(EntityCondition.makeCondition(
                                    EntityCondition.makeCondition("leaseApplicationId", financeApplicationId),
                                    EntityOperator.AND,
                                    EntityCondition.makeCondition("statusId", "LEASEQT_ACCEPTED")))
                            .orderBy("-createdDate")
                            .queryFirst();
                    if (leaseQuote != null && EntityQuery.use(delegator).from("LeaseAgreement")
                            .where("leaseQuoteId", leaseQuote.getString("leaseQuoteId"))
                            .queryFirst() != null) {
                        leaseQuote = null;
                    }
                }
                if (leaseQuote == null) {
                    result.put("offer", null);
                    result.put("quoteId", null);
                    result.put("quoteTypeId", null);
                    return result;
                }
                Map<String, Object> offer = buildLeaseOffer(leaseQuote);
                result.put("offerAvailable", true);
                result.put("offer", offer);
                result.put("quoteId", leaseQuote.getString("leaseQuoteId"));
                result.put("quoteTypeId", QUOTE_TYPE_LEASE);
                result.put("quoteStatusId", leaseQuote.getString("statusId"));
            } else {
                GenericValue loanQuote = EntityQuery.use(delegator).from("LoanQuote")
                        .where(EntityCondition.makeCondition(
                                EntityCondition.makeCondition("loanApplicationId", financeApplicationId),
                                EntityOperator.AND,
                                EntityCondition.makeCondition("statusId", "LOANQT_PRESENTED")))
                        .orderBy("-createdDate")
                        .queryFirst();
                if (loanQuote == null) {
                    // Let the customer resume signing after accepting an offer, unless
                    // the accepted quote has already been finalized as an agreement.
                    loanQuote = EntityQuery.use(delegator).from("LoanQuote")
                            .where(EntityCondition.makeCondition(
                                    EntityCondition.makeCondition("loanApplicationId", financeApplicationId),
                                    EntityOperator.AND,
                                    EntityCondition.makeCondition("statusId", "LOANQT_ACCEPTED")))
                            .orderBy("-createdDate")
                            .queryFirst();
                    if (loanQuote != null && EntityQuery.use(delegator).from("LoanAgreement")
                            .where("loanQuoteId", loanQuote.getString("loanQuoteId"))
                            .queryFirst() != null) {
                        loanQuote = null;
                    }
                }
                if (loanQuote == null) {
                    result.put("offer", null);
                    result.put("quoteId", null);
                    result.put("quoteTypeId", null);
                    return result;
                }
                Map<String, Object> offer = buildLoanOffer(loanQuote);
                result.put("offerAvailable", true);
                result.put("offer", offer);
                result.put("quoteId", loanQuote.getString("loanQuoteId"));
                result.put("quoteTypeId", QUOTE_TYPE_LOAN);
                result.put("quoteStatusId", loanQuote.getString("statusId"));
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

            if (!EfAccessGuard.consumeReauthToken(delegator, partyId,
                    (String) context.get("reauthToken"))) {
                return ServiceUtil.returnError(
                        UtilProperties.getMessage(RESOURCE, "EfinanceReauthRequired", locale));
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

            ESignatureAdapter signatureAdapter = ESignatureAdapterFactory.getAdapter();
            SignatureResult signatureResult;
            try {
                signatureResult = signatureAdapter.sign(new SignatureRequest(
                        signingSessionId, partyId, draftId, quoteId, quoteTypeId,
                        Boolean.TRUE.equals(confirmContent), ipAddress, deviceId, locale));
            } catch (ESignatureException e) {
                Debug.logError(e, "Electronic signature failed for session " + signingSessionId, MODULE);
                return ServiceUtil.returnError(e.getMessage());
            }
            if (!signatureResult.isSuccess()) {
                return ServiceUtil.returnError(signatureResult.getErrorMessage());
            }
            Timestamp signedAt = signatureResult.getSignedDate() != null
                    ? signatureResult.getSignedDate() : now;

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

            // Generate the delivered contract PDF and attach it to the signing session.
            GenericValue agreement = loadAgreement(delegator, agreementTypeId, agreementId);
            String contentId = null;
            try {
                contentId = createContractDocument(delegator, dispatcher, agreement, agreementTypeId,
                        draftId, signingSessionId, signedAt);
            } catch (Exception documentException) {
                Debug.logWarning("Unable to create contract PDF for " + agreementId
                        + ": " + documentException.getMessage(), MODULE);
            }

            // Update signing session to SIGNED
            session.set("statusId", STATUS_EFSIG_SIGNED);
            session.set("consentIpAddress", ipAddress);
            session.set("consentDeviceId", deviceId);
            session.set("consentDate", signedAt);
            session.set("signatureMethodEnumId", signatureResult.getSignatureMethodEnumId());
            session.set("signatureEvidenceHash", signatureResult.getEvidenceHash());
            session.set("agreementId", agreementId);
            session.set("agreementTypeId", agreementTypeId);
            if (contentId != null) {
                session.set("signedContentId", contentId);
            }
            delegator.store(session);

            // Create EfDeliveredDocument record for the contract.
            String deliveredDocId = createDeliveredDocument(delegator, draftId, partyId,
                    "EFDELDOC_CONTRACT", contentId, now);

            writeAuditLog(delegator, partyId, ACTION_SIGN_COMPLETE, "EfSigningSession", signingSessionId);

            Map<String, Object> result = ServiceUtil.returnSuccess();
            result.put("agreementId", agreementId);
            result.put("agreementTypeId", agreementTypeId);
            result.put("deliveredDocId", deliveredDocId);
            result.put("contentId", contentId);
            result.put("signatureMethodEnumId", signatureResult.getSignatureMethodEnumId());
            result.put("signatureEvidenceHash", signatureResult.getEvidenceHash());
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
                m.put("contentId", doc.getString("contentId"));
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
            result.put("contentId", contentId);
            if (UtilValidate.isEmpty(contentId)) {
                result.put("mimeTypeId", null);
                result.put("fileName", null);
                result.put("textData", null);
                result.put("contentBase64", null);
                return result;
            }

            // Look up content data
            GenericValue contentDataView = EntityQuery.use(delegator).from("ContentDataResourceView")
                    .where("contentId", contentId).queryOne();
            if (contentDataView != null) {
                String mimeTypeId = contentDataView.getString("mimeTypeId");
                String dataResourceTypeId = contentDataView.getString("drDataResourceTypeId");
                String objectInfo = contentDataView.getString("drObjectInfo");
                String dataResourceName = contentDataView.getString("drDataResourceName");
                result.put("mimeTypeId", mimeTypeId);
                result.put("fileName", UtilValidate.isNotEmpty(dataResourceName) ? dataResourceName : objectInfo);
                result.put("contentBase64", null);
                result.put("textData", null);

                if ("LOCAL_FILE".equals(dataResourceTypeId) && UtilValidate.isNotEmpty(objectInfo)) {
                    try {
                        byte[] bytes = Files.readAllBytes(Path.of(objectInfo));
                        result.put("contentBase64", Base64.getEncoder().encodeToString(bytes));
                        if (UtilValidate.isNotEmpty(mimeTypeId) && mimeTypeId.startsWith("text/")) {
                            result.put("textData", new String(bytes, java.nio.charset.StandardCharsets.UTF_8));
                        }
                    } catch (Exception fileException) {
                        Debug.logWarning("Unable to read delivered document file " + objectInfo
                                + ": " + fileException.getMessage(), MODULE);
                    }
                } else {
                    result.put("textData", objectInfo);
                }
            } else {
                result.put("mimeTypeId", null);
                result.put("fileName", null);
                result.put("textData", null);
                result.put("contentBase64", null);
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

    private static GenericValue loadAgreement(Delegator delegator, String agreementTypeId,
            String agreementId) throws GenericEntityException {
        if (QUOTE_TYPE_LEASE.equals(agreementTypeId)) {
            return EntityQuery.use(delegator).from("LeaseAgreement")
                    .where("leaseAgreementId", agreementId).queryOne();
        }
        return EntityQuery.use(delegator).from("LoanAgreement")
                .where("loanAgreementId", agreementId).queryOne();
    }

    private static String createContractDocument(Delegator delegator, LocalDispatcher dispatcher,
            GenericValue agreement, String agreementTypeId, String draftId,
            String signingSessionId, Timestamp now) throws Exception {
        if (agreement == null) {
            return null;
        }
        List<String> lines = buildContractDocumentLines(delegator, agreement, agreementTypeId,
                signingSessionId, now);
        byte[] pdf = JasperContractDocumentRenderer.render(
                buildContractReportParameters(delegator, agreement, agreementTypeId, signingSessionId, now),
                buildContractReportRows(delegator, agreement, agreementTypeId));
        if (pdf == null) {
            pdf = ContractDocumentPdf.build(lines);
        }
        String fileName = "contract-" + agreement.getString(
                QUOTE_TYPE_LEASE.equals(agreementTypeId) ? "leaseAgreementId" : "loanAgreementId") + ".pdf";

        GenericValue systemUser = getSystemUser(delegator);
        Map<String, Object> contentCtx = new HashMap<>();
        contentCtx.put("userLogin", systemUser);
        contentCtx.put("dataResourceTypeId", "LOCAL_FILE");
        contentCtx.put("dataTemplateTypeId", "NONE");
        contentCtx.put("dataResourceName", fileName);
        contentCtx.put("mimeTypeId", "application/pdf");
        contentCtx.put("contentTypeId", "DOCUMENT");
        contentCtx.put("contentName", "Electronic contract " + fileName);
        contentCtx.put("uploadedFile", ByteBuffer.wrap(pdf));
        contentCtx.put("_uploadedFile_fileName", fileName);
        contentCtx.put("_uploadedFile_contentType", "application/pdf");

        Map<String, Object> contentResult = dispatcher.runSync("createContentFromUploadedFile", contentCtx);
        if (ServiceUtil.isSuccess(contentResult)) {
            return (String) contentResult.get("contentId");
        }
        Debug.logWarning("createContentFromUploadedFile failed for " + draftId + ": "
                + ServiceUtil.getErrorMessage(contentResult), MODULE);
        return null;
    }

    private static Map<String, Object> buildContractReportParameters(Delegator delegator,
            GenericValue agreement, String agreementTypeId, String signingSessionId, Timestamp now) {
        boolean lease = QUOTE_TYPE_LEASE.equals(agreementTypeId);
        String customerPartyId = agreement.getString(lease ? "lesseePartyId" : "borrowerPartyId");
        Map<String, Object> parameters = new LinkedHashMap<>();
        parameters.put("documentTitle", "電子契約書（eFinance）");
        parameters.put("documentNoLabel", "契約番号");
        parameters.put("documentNo", agreement.getString(lease ? "leaseAgreementId" : "loanAgreementId"));
        parameters.put("agreementTypeLabel", "契約種別");
        parameters.put("agreementType", lease ? "リース" : "ローン");
        parameters.put("agreementDateLabel", "契約日");
        parameters.put("agreementDate", agreement.getTimestamp("agreementDate"));
        parameters.put("partyLabel", lease ? "借受者" : "借入人");
        parameters.put("partyName", partyName(delegator, customerPartyId));
        parameters.put("partyId", customerPartyId);
        parameters.put("principalLabel", lease ? "取得価額" : "借入元本");
        parameters.put("principalAmount", lease
                ? agreement.getBigDecimal("acquisitionCost") : agreement.getBigDecimal("principalAmount"));
        parameters.put("rateLabel", lease ? "計算利率" : "年利");
        parameters.put("rateAmount", lease
                ? agreement.getBigDecimal("calcInterestRate") : agreement.getBigDecimal("annualInterestRate"));
        parameters.put("termLabel", lease ? "リース期間（月）" : "返済期間（月）");
        parameters.put("termMonths", toBigDecimal(agreement.get("termMonths")));
        parameters.put("firstPaymentLabel", lease ? "リース開始日" : "初回返済日");
        parameters.put("firstPaymentDate", lease
                ? agreement.getTimestamp("leaseStartDate") : agreement.getTimestamp("firstPaymentDate"));
        parameters.put("currencyLabel", "通貨");
        parameters.put("currencyUomId", agreement.getString("currencyUomId"));
        parameters.put("scheduleTitle", "返済・リース料予定表");
        parameters.put("colNo", "回");
        parameters.put("colDueDate", "期日");
        parameters.put("colPayment", "返済額/リース料");
        parameters.put("colPrincipal", "元本");
        parameters.put("colInterest", "利息");
        parameters.put("colTax", "消費税");
        parameters.put("colBalance", "残高");
        parameters.put("signatureDateLabel", "署名日時");
        parameters.put("signatureDate", now);
        parameters.put("signingSessionLabel", "署名セッション");
        parameters.put("signingSession", signingSessionId);
        parameters.put("footerText", "本書面は電子契約により交付されました。");
        return parameters;
    }

    private static List<Map<String, Object>> buildContractReportRows(Delegator delegator,
            GenericValue agreement, String agreementTypeId) throws GenericEntityException {
        boolean lease = QUOTE_TYPE_LEASE.equals(agreementTypeId);
        String agreementId = agreement.getString(lease ? "leaseAgreementId" : "loanAgreementId");
        String entityName = lease ? "LeaseAgreementPaymentSchedule" : "LoanAgreementRepaymentSchedule";
        String keyName = lease ? "leaseAgreementId" : "loanAgreementId";
        Long revision = latestRevision(delegator, entityName, keyName, agreementId);
        List<Map<String, Object>> rows = new ArrayList<>();
        if (revision == null) {
            return rows;
        }
        List<GenericValue> schedule = EntityQuery.use(delegator).from(entityName)
                .where(keyName, agreementId, "scheduleRevisionNumber", revision)
                .orderBy("installmentNumber").queryList();
        for (GenericValue line : schedule) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("installmentNumber", toBigDecimal(line.get("installmentNumber")));
            row.put("dueDate", line.getTimestamp("dueDate"));
            row.put("paymentAmount", line.getBigDecimal(lease ? "leaseAmount" : "paymentAmount"));
            row.put("principalAmount", line.getBigDecimal(lease ? "principalPortion" : "principalAmount"));
            row.put("interestAmount", line.getBigDecimal(lease ? "interestPortion" : "interestAmount"));
            row.put("taxAmount", lease ? line.getBigDecimal("taxAmount") : null);
            row.put("remainingBalance", line.getBigDecimal(lease ? "remainingPrincipal" : "remainingBalance"));
            rows.add(row);
        }
        return rows;
    }

    private static BigDecimal toBigDecimal(Object value) {
        return value == null ? null : new BigDecimal(value.toString());
    }

    private static List<String> buildContractDocumentLines(Delegator delegator, GenericValue agreement,
            String agreementTypeId, String signingSessionId, Timestamp now) throws GenericEntityException {
        List<String> lines = new ArrayList<>();
        boolean lease = QUOTE_TYPE_LEASE.equals(agreementTypeId);
        String agreementId = agreement.getString(lease ? "leaseAgreementId" : "loanAgreementId");
        String customerPartyId = agreement.getString(lease ? "lesseePartyId" : "borrowerPartyId");

        addWrapped(lines, "電子契約書（eFinance サンプル）", 80);
        addWrapped(lines, "契約番号: " + agreementId, 90);
        addWrapped(lines, "契約種別: " + (lease ? "リース" : "ローン"), 90);
        addWrapped(lines, "契約日: " + formatContractDate(agreement.getTimestamp("agreementDate")), 90);
        addWrapped(lines, "契約当事者: " + partyName(delegator, customerPartyId)
                + " (" + customerPartyId + ")", 100);
        if (lease) {
            addWrapped(lines, "取得価額: " + formatAmount(agreement.getBigDecimal("acquisitionCost"))
                    + " " + agreement.getString("currencyUomId"), 90);
            addWrapped(lines, "月額リース料: " + formatAmount(agreement.getBigDecimal("monthlyLeaseAmount"))
                    + " " + agreement.getString("currencyUomId"), 90);
            addWrapped(lines, "リース期間: " + agreement.get("termMonths") + " ヶ月", 90);
            addWrapped(lines, "リース開始日: " + formatContractDate(agreement.getTimestamp("leaseStartDate")), 90);
            addWrapped(lines, "リース終了日: " + formatContractDate(agreement.getTimestamp("leaseEndDate")), 90);
        } else {
            addWrapped(lines, "借入元本: " + formatAmount(agreement.getBigDecimal("principalAmount"))
                    + " " + agreement.getString("currencyUomId"), 90);
            addWrapped(lines, "年利: " + agreement.getBigDecimal("annualInterestRate"), 90);
            addWrapped(lines, "返済期間: " + agreement.get("termMonths") + " ヶ月", 90);
            addWrapped(lines, "初回返済日: " + formatContractDate(agreement.getTimestamp("firstPaymentDate")), 90);
        }
        addWrapped(lines, "", 80);
        addWrapped(lines, "返済・リース料予定表", 80);

        if (lease) {
            addWrapped(lines, "回 | 期日 | リース料 | 元本 | 利息 | 消費税 | 残元本", 100);
            Long revision = latestRevision(delegator, "LeaseAgreementPaymentSchedule",
                    "leaseAgreementId", agreementId);
            if (revision != null) {
                List<GenericValue> rows = EntityQuery.use(delegator)
                        .from("LeaseAgreementPaymentSchedule")
                        .where("leaseAgreementId", agreementId, "scheduleRevisionNumber", revision)
                        .orderBy("installmentNumber").queryList();
                for (GenericValue row : rows) {
                    addWrapped(lines, String.format("%s | %s | %s | %s | %s | %s | %s",
                            row.get("installmentNumber"),
                            formatContractDate(row.getTimestamp("dueDate")),
                            formatAmount(row.getBigDecimal("leaseAmount")),
                            formatAmount(row.getBigDecimal("principalPortion")),
                            formatAmount(row.getBigDecimal("interestPortion")),
                            formatAmount(row.getBigDecimal("taxAmount")),
                            formatAmount(row.getBigDecimal("remainingPrincipal"))), 120);
                }
            }
        } else {
            addWrapped(lines, "回 | 期日 | 返済額 | 元本 | 利息 | 残高", 100);
            Long revision = latestRevision(delegator, "LoanAgreementRepaymentSchedule",
                    "loanAgreementId", agreementId);
            if (revision != null) {
                List<GenericValue> rows = EntityQuery.use(delegator)
                        .from("LoanAgreementRepaymentSchedule")
                        .where("loanAgreementId", agreementId, "scheduleRevisionNumber", revision)
                        .orderBy("installmentNumber").queryList();
                for (GenericValue row : rows) {
                    addWrapped(lines, String.format("%s | %s | %s | %s | %s | %s",
                            row.get("installmentNumber"),
                            formatContractDate(row.getTimestamp("dueDate")),
                            formatAmount(row.getBigDecimal("paymentAmount")),
                            formatAmount(row.getBigDecimal("principalAmount")),
                            formatAmount(row.getBigDecimal("interestAmount")),
                            formatAmount(row.getBigDecimal("remainingBalance"))), 120);
                }
            }
        }

        addWrapped(lines, "", 80);
        addWrapped(lines, "署名日時: " + formatContractDate(now), 90);
        addWrapped(lines, "署名セッション: " + signingSessionId, 90);
        addWrapped(lines, "本書面は電子契約により交付されました。", 90);
        return lines;
    }

    private static Long latestRevision(Delegator delegator, String entityName, String keyName,
            String keyValue) throws GenericEntityException {
        List<GenericValue> rows = EntityQuery.use(delegator).from(entityName)
                .where(keyName, keyValue)
                .orderBy("-scheduleRevisionNumber").queryList();
        return rows.isEmpty() ? null : rows.get(0).getLong("scheduleRevisionNumber");
    }

    private static String partyName(Delegator delegator, String partyId) {
        if (UtilValidate.isEmpty(partyId)) {
            return "";
        }
        return PartyHelper.getPartyName(delegator, partyId, false);
    }

    private static String formatContractDate(Timestamp value) {
        if (value == null) {
            return "";
        }
        return new SimpleDateFormat("yyyy-MM-dd HH:mm").format(value);
    }

    private static String formatAmount(BigDecimal value) {
        if (value == null) {
            return "";
        }
        return new DecimalFormat("#,##0.00").format(value);
    }

    private static void addWrapped(List<String> lines, String text, int maxLength) {
        if (text == null) {
            lines.add("");
            return;
        }
        if (text.length() <= maxLength) {
            lines.add(text);
            return;
        }
        for (int start = 0; start < text.length(); start += maxLength) {
            lines.add(text.substring(start, Math.min(start + maxLength, text.length())));
        }
    }

    private static String createDeliveredDocument(Delegator delegator, String draftId,
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
            return deliveredDocId;
        } catch (GenericEntityException e) {
            Debug.logWarning("Failed to create delivered document: " + e.getMessage(), MODULE);
            return null;
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
