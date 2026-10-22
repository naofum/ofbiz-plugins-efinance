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
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

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
 * STEP W2: eFinance application draft, document upload and submission services.
 */
public final class EfApplicationServices {

    private static final String MODULE = EfApplicationServices.class.getName();
    private static final String RESOURCE = "EfinanceUiLabels";
    private static final String PROPS = "efinance";

    private static final String STATUS_EDITING = "EFDRF_EDITING";
    private static final String STATUS_SUBMITTED = "EFDRF_SUBMITTED";
    private static final String STATUS_WITHDRAWN = "EFDRF_WITHDRAWN";

    private static final String FRAUD_OK = "EFFRAUD_OK";
    private static final String FRAUD_BLOCK = "EFFRAUD_BLOCK";

    private static final String ACTION_DRAFT_CREATE = "EFAUD_DRAFT_CREATE";
    private static final String ACTION_DRAFT_SAVE = "EFAUD_DRAFT_SAVE";
    private static final String ACTION_DRAFT_SUBMIT = "EFAUD_DRAFT_SUBMIT";
    private static final String ACTION_DRAFT_WITHDRAW = "EFAUD_DRAFT_WITHDRAW";
    private static final String ACTION_DOC_UPLOAD = "EFAUD_DOC_UPLOAD";
    private static final String ACTION_DOC_DELETE = "EFAUD_DOC_DELETE";

    private static final String RESULT_SUCCESS = "EFAUDRES_SUCCESS";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private EfApplicationServices() {
    }

    // =========================================================================
    // efGetApplicationForm
    // =========================================================================

    public static Map<String, Object> efGetApplicationForm(DispatchContext dctx, Map<String, Object> context) {
        String applicationTypeId = (String) context.get("applicationTypeId");

        List<Map<String, Object>> steps;
        if ("LEASE".equals(applicationTypeId)) {
            steps = buildLeaseSteps();
        } else {
            steps = buildLoanSteps();
        }

        Map<String, Object> result = ServiceUtil.returnSuccess();
        result.put("steps", steps);
        result.put("totalSteps", steps.size());
        return result;
    }

    private static List<Map<String, Object>> buildLoanSteps() {
        List<Map<String, Object>> steps = new ArrayList<>();

        // Step 1: product
        List<Map<String, Object>> fields1 = new ArrayList<>();
        fields1.add(field("productId", "hidden", false, "Product"));
        fields1.add(field("principalAmount", "number", true, "Loan amount"));
        fields1.add(field("termMonths", "number", true, "Term (months)"));
        fields1.add(field("repaymentMethod", "select", false, "Repayment method"));
        steps.add(step(1, "product", fields1));

        // Step 2: applicant
        List<Map<String, Object>> fields2 = new ArrayList<>();
        fields2.add(field("firstName", "text", true, "First name"));
        fields2.add(field("lastName", "text", true, "Last name"));
        fields2.add(field("birthDate", "date", true, "Date of birth"));
        fields2.add(field("address1", "text", true, "Address"));
        fields2.add(field("postalCode", "text", true, "Postal code"));
        fields2.add(field("prefecture", "text", true, "Prefecture"));
        fields2.add(field("city", "text", true, "City"));
        fields2.add(field("employerName", "text", true, "Employer name"));
        fields2.add(field("annualIncome", "number", true, "Annual income"));
        fields2.add(field("residenceType", "select", false, "Residence type (OWN/RENT)"));
        steps.add(step(2, "applicant", fields2));

        // Step 3: agreement
        List<Map<String, Object>> fields3 = new ArrayList<>();
        fields3.add(field("confirmTerms", "checkbox", true, "Agree to terms"));
        fields3.add(field("confirmPrivacy", "checkbox", true, "Agree to privacy policy"));
        steps.add(step(3, "agreement", fields3));

        return steps;
    }

    private static List<Map<String, Object>> buildLeaseSteps() {
        List<Map<String, Object>> steps = new ArrayList<>();

        // Step 1: product
        List<Map<String, Object>> fields1 = new ArrayList<>();
        fields1.add(field("productId", "hidden", false, "Product"));
        fields1.add(field("termMonths", "number", true, "Term (months)"));
        steps.add(step(1, "product", fields1));

        // Step 2: asset
        List<Map<String, Object>> fields2 = new ArrayList<>();
        fields2.add(field("assetName", "text", true, "Asset name"));
        fields2.add(field("fixedAssetTypeId", "select", false, "Asset type"));
        fields2.add(field("estimatedAcquisitionCost", "number", true, "Estimated acquisition cost"));
        fields2.add(field("supplierName", "text", false, "Supplier name"));
        steps.add(step(2, "asset", fields2));

        // Step 3: applicant (corporate)
        List<Map<String, Object>> fields3 = new ArrayList<>();
        fields3.add(field("companyName", "text", true, "Company name"));
        fields3.add(field("companyAddress", "text", true, "Company address"));
        fields3.add(field("representativeName", "text", true, "Representative name"));
        fields3.add(field("representativeBirthDate", "date", true, "Representative date of birth"));
        steps.add(step(3, "applicant", fields3));

        // Step 4: agreement
        List<Map<String, Object>> fields4 = new ArrayList<>();
        fields4.add(field("confirmTerms", "checkbox", true, "Agree to terms"));
        fields4.add(field("confirmPrivacy", "checkbox", true, "Agree to privacy policy"));
        steps.add(step(4, "agreement", fields4));

        return steps;
    }

    private static Map<String, Object> step(int number, String key, List<Map<String, Object>> fields) {
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("stepNumber", number);
        s.put("stepKey", key);
        s.put("fields", fields);
        return s;
    }

    private static Map<String, Object> field(String name, String type, boolean required, String label) {
        Map<String, Object> f = new LinkedHashMap<>();
        f.put("fieldName", name);
        f.put("fieldType", type);
        f.put("required", required);
        f.put("label", label);
        return f;
    }

    // =========================================================================
    // efCreateDraft
    // =========================================================================

    public static Map<String, Object> efCreateDraft(DispatchContext dctx, Map<String, Object> context) {
        Delegator delegator = dctx.getDelegator();
        Locale locale = (Locale) context.get("locale");

        String partyId = getPartyIdFromContext(context);
        String productId = (String) context.get("productId");
        String applicationTypeId = (String) context.get("applicationTypeId");
        String sourceEnumId = (String) context.get("sourceEnumId");

        int expireDays = UtilProperties.getPropertyAsInteger(PROPS, "efinance.draft.expire.days", 30);
        int totalSteps = "LEASE".equals(applicationTypeId) ? 4 : 3;

        try {
            Timestamp now = UtilDateTime.nowTimestamp();
            Calendar cal = Calendar.getInstance();
            cal.setTimeInMillis(now.getTime());
            cal.add(Calendar.DAY_OF_YEAR, expireDays);
            Timestamp expiresAt = new Timestamp(cal.getTimeInMillis());

            String draftId = delegator.getNextSeqId("EfApplicationDraft");
            GenericValue draft = delegator.makeValue("EfApplicationDraft");
            draft.set("draftId", draftId);
            draft.set("memberPartyId", partyId);
            draft.set("productId", productId);
            draft.set("applicationTypeId", applicationTypeId);
            draft.set("currentStep", 1L);
            draft.set("totalSteps", (long) totalSteps);
            draft.set("draftJson", "{}");
            draft.set("statusId", STATUS_EDITING);
            draft.set("sourceEnumId", UtilValidate.isNotEmpty(sourceEnumId) ? sourceEnumId : "EFSRC_DIRECT");
            draft.set("expiresAt", expiresAt);
            draft.set("createdDate", now);
            draft.set("lastModifiedDate", now);
            delegator.create(draft);

            writeAuditLog(delegator, partyId, ACTION_DRAFT_CREATE, "EfApplicationDraft", draftId);

            Map<String, Object> result = ServiceUtil.returnSuccess();
            result.put("draftId", draftId);
            result.put("expiresAt", expiresAt);
            return result;
        } catch (GenericEntityException e) {
            Debug.logError(e, MODULE);
            return ServiceUtil.returnError(e.getMessage());
        }
    }

    // =========================================================================
    // efSaveDraft
    // =========================================================================

    public static Map<String, Object> efSaveDraft(DispatchContext dctx, Map<String, Object> context) {
        Delegator delegator = dctx.getDelegator();
        Locale locale = (Locale) context.get("locale");

        String partyId = getPartyIdFromContext(context);
        String draftId = (String) context.get("draftId");
        Integer step = (Integer) context.get("step");
        @SuppressWarnings("unchecked")
        Map<String, Object> stepData = (Map<String, Object>) context.get("stepData");

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
            Timestamp expiresAt = draft.getTimestamp("expiresAt");
            if (expiresAt != null && expiresAt.before(UtilDateTime.nowTimestamp())) {
                return ServiceUtil.returnError(UtilProperties.getMessage(RESOURCE, "EfinanceDraftExpired", locale));
            }

            // Load existing JSON, merge step
            Map<String, Object> allSteps = parseDraftJson(draft.getString("draftJson"));
            allSteps.put(String.valueOf(step), stepData);

            // Validate the step
            String applicationTypeId = draft.getString("applicationTypeId");
            List<String> errors = validateStep(applicationTypeId, step, stepData);
            boolean stepValid = errors.isEmpty();

            // Update currentStep if advancing
            Long currentStep = draft.getLong("currentStep");
            if (currentStep == null || step > currentStep.intValue()) {
                draft.set("currentStep", (long) step);
            }
            draft.set("draftJson", serializeDraftJson(allSteps));
            draft.set("lastModifiedDate", UtilDateTime.nowTimestamp());
            delegator.store(draft);

            writeAuditLog(delegator, partyId, ACTION_DRAFT_SAVE, "EfApplicationDraft", draftId);

            Map<String, Object> result = ServiceUtil.returnSuccess();
            result.put("stepValid", stepValid);
            result.put("validationErrors", errors);
            return result;
        } catch (GenericEntityException e) {
            Debug.logError(e, MODULE);
            return ServiceUtil.returnError(e.getMessage());
        }
    }

    // =========================================================================
    // efGetDraft
    // =========================================================================

    public static Map<String, Object> efGetDraft(DispatchContext dctx, Map<String, Object> context) {
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

            Map<String, Object> stepData = parseDraftJson(draft.getString("draftJson"));

            List<GenericValue> docList = EntityQuery.use(delegator).from("EfDraftDocument")
                    .where("draftId", draftId).queryList();
            List<Map<String, Object>> documents = new ArrayList<>();
            for (GenericValue doc : docList) {
                Map<String, Object> d = new LinkedHashMap<>();
                d.put("draftDocumentId", doc.getString("draftDocumentId"));
                d.put("documentTypeEnumId", doc.getString("documentTypeEnumId"));
                d.put("fileName", doc.getString("fileName"));
                d.put("mimeTypeId", doc.getString("mimeTypeId"));
                d.put("scanStatus", doc.getString("scanStatus"));
                d.put("uploadedDate", doc.getTimestamp("uploadedDate"));
                documents.add(d);
            }

            Map<String, Object> draftMap = new LinkedHashMap<>();
            draftMap.put("draftId", draft.getString("draftId"));
            draftMap.put("productId", draft.getString("productId"));
            draftMap.put("applicationTypeId", draft.getString("applicationTypeId"));
            draftMap.put("statusId", draft.getString("statusId"));
            draftMap.put("currentStep", draft.getLong("currentStep"));
            draftMap.put("totalSteps", draft.getLong("totalSteps"));
            draftMap.put("expiresAt", draft.getTimestamp("expiresAt"));
            draftMap.put("stepData", stepData);
            draftMap.put("documents", documents);

            Map<String, Object> result = ServiceUtil.returnSuccess();
            result.put("draft", draftMap);
            return result;
        } catch (GenericEntityException e) {
            Debug.logError(e, MODULE);
            return ServiceUtil.returnError(e.getMessage());
        }
    }

    // =========================================================================
    // efListDrafts
    // =========================================================================

    public static Map<String, Object> efListDrafts(DispatchContext dctx, Map<String, Object> context) {
        Delegator delegator = dctx.getDelegator();

        String partyId = getPartyIdFromContext(context);

        try {
            List<GenericValue> draftList = EntityQuery.use(delegator).from("EfApplicationDraft")
                    .where(EntityCondition.makeCondition(
                            EntityCondition.makeCondition("memberPartyId", partyId),
                            EntityOperator.AND,
                            EntityCondition.makeCondition("statusId", STATUS_EDITING)))
                    .orderBy("createdDate DESC")
                    .queryList();

            List<Map<String, Object>> drafts = new ArrayList<>();
            for (GenericValue d : draftList) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("draftId", d.getString("draftId"));
                m.put("productId", d.getString("productId"));
                m.put("applicationTypeId", d.getString("applicationTypeId"));
                m.put("statusId", d.getString("statusId"));
                m.put("currentStep", d.getLong("currentStep"));
                m.put("totalSteps", d.getLong("totalSteps"));
                m.put("expiresAt", d.getTimestamp("expiresAt"));
                m.put("createdDate", d.getTimestamp("createdDate"));
                m.put("lastModifiedDate", d.getTimestamp("lastModifiedDate"));
                drafts.add(m);
            }

            Map<String, Object> result = ServiceUtil.returnSuccess();
            result.put("drafts", drafts);
            return result;
        } catch (GenericEntityException e) {
            Debug.logError(e, MODULE);
            return ServiceUtil.returnError(e.getMessage());
        }
    }

    // =========================================================================
    // efWithdrawDraft
    // =========================================================================

    public static Map<String, Object> efWithdrawDraft(DispatchContext dctx, Map<String, Object> context) {
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

            draft.set("statusId", STATUS_WITHDRAWN);
            draft.set("lastModifiedDate", UtilDateTime.nowTimestamp());
            delegator.store(draft);

            writeAuditLog(delegator, partyId, ACTION_DRAFT_WITHDRAW, "EfApplicationDraft", draftId);

            return ServiceUtil.returnSuccess();
        } catch (GenericEntityException e) {
            Debug.logError(e, MODULE);
            return ServiceUtil.returnError(e.getMessage());
        }
    }

    // =========================================================================
    // efUploadDraftDocument
    // =========================================================================

    public static Map<String, Object> efUploadDraftDocument(DispatchContext dctx, Map<String, Object> context) {
        Delegator delegator = dctx.getDelegator();
        LocalDispatcher dispatcher = dctx.getDispatcher();
        Locale locale = (Locale) context.get("locale");

        String partyId = getPartyIdFromContext(context);
        String draftId = (String) context.get("draftId");
        String documentTypeEnumId = (String) context.get("documentTypeEnumId");
        String fileName = (String) context.get("fileName");
        String contentType = (String) context.get("contentType");
        ByteBuffer uploadedFile = (ByteBuffer) context.get("uploadedFile");

        long maxBytes = UtilProperties.getPropertyAsLong(PROPS, "efinance.upload.max.bytes", 10485760L);

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

            if (uploadedFile != null && uploadedFile.limit() > maxBytes) {
                return ServiceUtil.returnError(UtilProperties.getMessage(RESOURCE, "EfinanceFileTooLarge", locale));
            }

            // Create content via createContentFromUploadedFile service using system user
            GenericValue systemUser = getSystemUser(delegator);
            Map<String, Object> contentCtx = new HashMap<>();
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
            String contentId = null;
            if (ServiceUtil.isSuccess(contentResult)) {
                contentId = (String) contentResult.get("contentId");
            } else {
                Debug.logWarning("createContentFromUploadedFile failed: " + ServiceUtil.getErrorMessage(contentResult), MODULE);
            }

            Timestamp now = UtilDateTime.nowTimestamp();
            String draftDocumentId = delegator.getNextSeqId("EfDraftDocument");
            GenericValue doc = delegator.makeValue("EfDraftDocument");
            doc.set("draftDocumentId", draftDocumentId);
            doc.set("draftId", draftId);
            doc.set("contentId", contentId);
            doc.set("documentTypeEnumId", documentTypeEnumId);
            doc.set("fileName", fileName);
            doc.set("mimeTypeId", contentType);
            doc.set("fileSizeBytes", uploadedFile != null ? (long) uploadedFile.limit() : 0L);
            doc.set("scanStatus", "EFSCAN_PENDING");
            doc.set("uploadedDate", now);
            delegator.create(doc);

            writeAuditLog(delegator, partyId, ACTION_DOC_UPLOAD, "EfDraftDocument", draftDocumentId);

            Map<String, Object> result = ServiceUtil.returnSuccess();
            result.put("draftDocumentId", draftDocumentId);
            return result;
        } catch (Exception e) {
            Debug.logError(e, MODULE);
            return ServiceUtil.returnError(e.getMessage());
        }
    }

    // =========================================================================
    // efDeleteDraftDocument
    // =========================================================================

    public static Map<String, Object> efDeleteDraftDocument(DispatchContext dctx, Map<String, Object> context) {
        Delegator delegator = dctx.getDelegator();
        Locale locale = (Locale) context.get("locale");

        String partyId = getPartyIdFromContext(context);
        String draftDocumentId = (String) context.get("draftDocumentId");

        try {
            GenericValue doc = EntityQuery.use(delegator).from("EfDraftDocument")
                    .where("draftDocumentId", draftDocumentId).queryOne();
            if (doc == null) {
                return ServiceUtil.returnError("Document not found: " + draftDocumentId);
            }

            String draftId = doc.getString("draftId");
            GenericValue draft = EntityQuery.use(delegator).from("EfApplicationDraft")
                    .where("draftId", draftId).queryOne();
            if (draft == null || !ownsDraft(draft, partyId)) {
                return ServiceUtil.returnError(UtilProperties.getMessage(RESOURCE, "EfinanceDraftNotOwned", locale));
            }

            delegator.removeValue(doc);
            writeAuditLog(delegator, partyId, ACTION_DOC_DELETE, "EfDraftDocument", draftDocumentId);

            return ServiceUtil.returnSuccess();
        } catch (GenericEntityException e) {
            Debug.logError(e, MODULE);
            return ServiceUtil.returnError(e.getMessage());
        }
    }

    // =========================================================================
    // efSubmitApplication
    // =========================================================================

    public static Map<String, Object> efSubmitApplication(DispatchContext dctx, Map<String, Object> context) {
        Delegator delegator = dctx.getDelegator();
        LocalDispatcher dispatcher = dctx.getDispatcher();
        Locale locale = (Locale) context.get("locale");

        String partyId = getPartyIdFromContext(context);
        String draftId = (String) context.get("draftId");
        String ipAddress = (String) context.get("ipAddress");
        String deviceId = (String) context.get("deviceId");

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
            Timestamp expiresAt = draft.getTimestamp("expiresAt");
            if (expiresAt != null && expiresAt.before(UtilDateTime.nowTimestamp())) {
                return ServiceUtil.returnError(UtilProperties.getMessage(RESOURCE, "EfinanceDraftExpired", locale));
            }

            String applicationTypeId = draft.getString("applicationTypeId");
            Map<String, Object> allSteps = parseDraftJson(draft.getString("draftJson"));

            // Validate all steps
            int totalSteps = "LEASE".equals(applicationTypeId) ? 4 : 3;
            List<String> allErrors = new ArrayList<>();
            for (int s = 1; s <= totalSteps; s++) {
                @SuppressWarnings("unchecked")
                Map<String, Object> sd = (Map<String, Object>) allSteps.get(String.valueOf(s));
                if (sd == null) {
                    sd = new HashMap<>();
                }
                allErrors.addAll(validateStep(applicationTypeId, s, sd));
            }
            if (!allErrors.isEmpty()) {
                Map<String, Object> err = ServiceUtil.returnError(
                        UtilProperties.getMessage(RESOURCE, "EfinanceValidationFailed", locale));
                err.put("validationErrors", allErrors);
                return err;
            }

            String consentError = validateRequiredConsents(delegator, partyId, locale);
            if (consentError != null) {
                return ServiceUtil.returnError(consentError);
            }

            // Fraud check
            String fraudResult = performFraudCheck(delegator, draftId, partyId, ipAddress, deviceId);
            if (FRAUD_BLOCK.equals(fraudResult)) {
                return ServiceUtil.returnError(
                        UtilProperties.getMessage(RESOURCE, "EfinanceSubmissionBlocked", locale));
            }

            // Get system user for finance service calls
            GenericValue systemUser = getSystemUser(delegator);
            if (systemUser == null) {
                return ServiceUtil.returnError("System user not found");
            }

            String productId = draft.getString("productId");
            @SuppressWarnings("unchecked")
            Map<String, Object> step1 = (Map<String, Object>) allSteps.get("1");
            if (step1 == null) step1 = new HashMap<>();

            String financeApplicationId;
            if ("LEASE".equals(applicationTypeId)) {
                financeApplicationId = submitLeaseApplication(dispatcher, systemUser, productId,
                        partyId, step1, allSteps);
            } else {
                financeApplicationId = submitLoanApplication(dispatcher, systemUser, productId,
                        partyId, step1);
            }

            if (financeApplicationId == null) {
                return ServiceUtil.returnError("Failed to create finance application");
            }

            // Update draft status
            draft.set("statusId", STATUS_SUBMITTED);
            draft.set("submittedApplicationId", financeApplicationId);
            draft.set("submittedApplicationTypeId", applicationTypeId);
            draft.set("lastModifiedDate", UtilDateTime.nowTimestamp());
            delegator.store(draft);

            // Create link record
            String linkId = delegator.getNextSeqId("EfApplicationLink");
            GenericValue link = delegator.makeValue("EfApplicationLink");
            link.set("linkId", linkId);
            link.set("draftId", draftId);
            link.set("memberPartyId", partyId);
            link.set("financeApplicationId", financeApplicationId);
            link.set("financeApplicationTypeId", applicationTypeId);
            link.set("linkedDate", UtilDateTime.nowTimestamp());
            delegator.create(link);

            writeAuditLog(delegator, partyId, ACTION_DRAFT_SUBMIT, "EfApplicationDraft", draftId);

            Map<String, Object> result = ServiceUtil.returnSuccess(
                    UtilProperties.getMessage(RESOURCE, "EfinanceApplicationSubmitted", locale));
            result.put("financeApplicationId", financeApplicationId);
            result.put("financeApplicationTypeId", applicationTypeId);
            result.put("fraudResult", fraudResult);
            return result;
        } catch (Exception e) {
            Debug.logError(e, MODULE);
            return ServiceUtil.returnError(e.getMessage());
        }
    }

    // =========================================================================
    // Private helpers
    // =========================================================================

    private static String submitLoanApplication(LocalDispatcher dispatcher, GenericValue systemUser,
            String productId, String memberPartyId, Map<String, Object> step1) throws Exception {
        Map<String, Object> ctx = new HashMap<>();
        ctx.put("userLogin", systemUser);
        ctx.put("productId", productId);
        ctx.put("applicantPartyId", memberPartyId);
        ctx.put("requestedPrincipalAmount", toBigDecimal(step1.get("principalAmount")));
        ctx.put("requestedTermMonths", toLong(step1.get("termMonths")));
        ctx.put("applicationDate", UtilDateTime.nowTimestamp());
        ctx.put("currencyUomId", "JPY");
        Object purpose = step1.get("purpose");
        if (purpose != null) {
            ctx.put("purpose", purpose.toString());
        }

        Map<String, Object> r = dispatcher.runSync("createLoanApplication", ctx);
        if (!ServiceUtil.isSuccess(r)) {
            Debug.logError("createLoanApplication failed: " + ServiceUtil.getErrorMessage(r), MODULE);
            return null;
        }
        return (String) r.get("loanApplicationId");
    }

    private static String submitLeaseApplication(LocalDispatcher dispatcher, GenericValue systemUser,
            String productId, String memberPartyId,
            Map<String, Object> step1, Map<String, Object> allSteps) throws Exception {
        Map<String, Object> ctx = new HashMap<>();
        ctx.put("userLogin", systemUser);
        ctx.put("productId", productId);
        ctx.put("applicantPartyId", memberPartyId);
        ctx.put("requestedTermMonths", toLong(step1.get("termMonths")));
        ctx.put("applicationDate", UtilDateTime.nowTimestamp());
        ctx.put("currencyUomId", "JPY");

        Map<String, Object> r = dispatcher.runSync("createLeaseApplication", ctx);
        if (!ServiceUtil.isSuccess(r)) {
            Debug.logError("createLeaseApplication failed: " + ServiceUtil.getErrorMessage(r), MODULE);
            return null;
        }
        String leaseApplicationId = (String) r.get("leaseApplicationId");

        // Add asset from step 2
        @SuppressWarnings("unchecked")
        Map<String, Object> step2 = (Map<String, Object>) allSteps.get("2");
        if (step2 != null) {
            Map<String, Object> assetCtx = new HashMap<>();
            assetCtx.put("userLogin", systemUser);
            assetCtx.put("leaseApplicationId", leaseApplicationId);
            assetCtx.put("assetName", step2.get("assetName"));
            assetCtx.put("estimatedAcquisitionCost", toBigDecimal(step2.get("estimatedAcquisitionCost")));
            Object fat = step2.get("fixedAssetTypeId");
            assetCtx.put("fixedAssetTypeId", (fat != null && !fat.toString().isEmpty()) ? fat.toString() : "EQUIPMENT");
            Object supplier = step2.get("supplierName");
            if (supplier != null) {
                assetCtx.put("supplierName", supplier.toString());
            }
            Map<String, Object> ar = dispatcher.runSync("createLeaseApplicationAsset", assetCtx);
            if (!ServiceUtil.isSuccess(ar)) {
                Debug.logWarning("createLeaseApplicationAsset failed: " + ServiceUtil.getErrorMessage(ar), MODULE);
            }
        }

        return leaseApplicationId;
    }

    private static String performFraudCheck(Delegator delegator, String draftId,
            String memberPartyId, String ipAddress, String deviceId) {
        String resultEnumId = FRAUD_OK;
        String triggeredRules = "";

        try {
            // Count submissions in last 24 hours
            Calendar cal = Calendar.getInstance();
            cal.add(Calendar.HOUR_OF_DAY, -24);
            Timestamp since = new Timestamp(cal.getTimeInMillis());

            long recentCount = EntityQuery.use(delegator).from("EfApplicationLink")
                    .where(EntityCondition.makeCondition(
                            EntityCondition.makeCondition("memberPartyId", memberPartyId),
                            EntityOperator.AND,
                            EntityCondition.makeCondition("linkedDate", EntityOperator.GREATER_THAN_EQUAL_TO, since)))
                    .queryCount();

            if (recentCount > 3) {
                resultEnumId = FRAUD_BLOCK;
                triggeredRules = "RATE_LIMIT_24H";
            }

            // Record fraud check
            String fraudCheckId = delegator.getNextSeqId("EfFraudCheck");
            GenericValue fc = delegator.makeValue("EfFraudCheck");
            fc.set("fraudCheckId", fraudCheckId);
            fc.set("draftId", draftId);
            fc.set("memberPartyId", memberPartyId);
            fc.set("checkDate", UtilDateTime.nowTimestamp());
            fc.set("resultEnumId", resultEnumId);
            fc.set("triggeredRules", triggeredRules);
            fc.set("ipAddress", ipAddress);
            fc.set("deviceId", deviceId);
            delegator.create(fc);
        } catch (GenericEntityException e) {
            Debug.logError(e, MODULE);
        }
        return resultEnumId;
    }

    private static List<String> validateStep(String applicationTypeId, int step,
            Map<String, Object> stepData) {
        List<String> errors = new ArrayList<>();
        if ("LEASE".equals(applicationTypeId)) {
            validateLeaseStep(step, stepData, errors);
        } else {
            validateLoanStep(step, stepData, errors);
        }
        return errors;
    }

    private static void validateLoanStep(int step, Map<String, Object> data, List<String> errors) {
        if (step == 1) {
            requireField(data, "principalAmount", "principalAmount", errors);
            requireField(data, "termMonths", "termMonths", errors);
        } else if (step == 2) {
            requireField(data, "firstName", "firstName", errors);
            requireField(data, "lastName", "lastName", errors);
        } else if (step == 3) {
            requireY(data, "confirmTerms", errors);
            requireY(data, "confirmPrivacy", errors);
        }
    }

    private static void validateLeaseStep(int step, Map<String, Object> data, List<String> errors) {
        if (step == 1) {
            requireField(data, "termMonths", "termMonths", errors);
        } else if (step == 2) {
            requireField(data, "assetName", "assetName", errors);
            requireField(data, "estimatedAcquisitionCost", "estimatedAcquisitionCost", errors);
        } else if (step == 3) {
            requireField(data, "companyName", "companyName", errors);
            requireField(data, "representativeName", "representativeName", errors);
        } else if (step == 4) {
            requireY(data, "confirmTerms", errors);
            requireY(data, "confirmPrivacy", errors);
        }
    }

    private static void requireField(Map<String, Object> data, String key, String label, List<String> errors) {
        Object v = data.get(key);
        if (v == null || v.toString().trim().isEmpty()) {
            errors.add(label + " is required");
        }
    }

    private static void requireY(Map<String, Object> data, String key, List<String> errors) {
        Object v = data.get(key);
        if (!"Y".equals(v)) {
            errors.add(key + " must be confirmed (Y)");
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

    private static Map<String, Object> parseDraftJson(String json) {
        if (UtilValidate.isEmpty(json)) return new LinkedHashMap<>();
        try {
            return MAPPER.readValue(json, new TypeReference<Map<String, Object>>() {});
        } catch (Exception e) {
            Debug.logWarning("Failed to parse draftJson: " + e.getMessage(), MODULE);
            return new LinkedHashMap<>();
        }
    }

    private static String serializeDraftJson(Map<String, Object> data) {
        try {
            return MAPPER.writeValueAsString(data);
        } catch (Exception e) {
            Debug.logError(e, MODULE);
            return "{}";
        }
    }

    private static GenericValue getSystemUser(Delegator delegator) throws GenericEntityException {
        String sysLoginId = UtilProperties.getPropertyValue(PROPS, "efinance.system.userlogin", "efinance-system");
        return EntityQuery.use(delegator).from("UserLogin").where("userLoginId", sysLoginId).queryOne();
    }

    /**
     * Require an active consent to the latest effective version of each
     * configured consent document type. The default set covers the terms,
     * privacy policy and credit-information consent needed to submit.
     */
    private static String validateRequiredConsents(Delegator delegator, String partyId, Locale locale)
            throws GenericEntityException {
        String requiredTypes = UtilProperties.getPropertyValue(PROPS,
                "efinance.consent.required", "EFCONS_TERMS,EFCONS_PRIVACY,EFCONS_CREDIT");
        if (UtilValidate.isEmpty(requiredTypes)) {
            return null;
        }
        Timestamp now = UtilDateTime.nowTimestamp();
        for (String rawType : requiredTypes.split(",")) {
            String documentType = rawType.trim();
            if (UtilValidate.isEmpty(documentType)) {
                continue;
            }
            GenericValue document = EntityQuery.use(delegator).from("EfConsentDocument")
                    .where(EntityCondition.makeCondition(EntityOperator.AND,
                            EntityCondition.makeCondition("documentTypeEnumId", documentType),
                            EntityCondition.makeCondition("effectiveDate",
                                    EntityOperator.LESS_THAN_EQUAL_TO, now),
                            EntityCondition.makeCondition(EntityOperator.OR,
                                    EntityCondition.makeCondition("expiresDate", EntityOperator.EQUALS, null),
                                    EntityCondition.makeCondition("expiresDate",
                                            EntityOperator.GREATER_THAN_EQUAL_TO, now))))
                    .orderBy("-effectiveDate")
                    .queryFirst();
            if (document == null) {
                return UtilProperties.getMessage(RESOURCE, "EfinanceConsentDocumentMissing",
                        new Object[] {documentType}, locale);
            }
            GenericValue consent = EntityQuery.use(delegator).from("EfConsent")
                    .where(EntityCondition.makeCondition(EntityOperator.AND,
                            EntityCondition.makeCondition("partyId", partyId),
                            EntityCondition.makeCondition("documentId", document.getString("documentId")),
                            EntityCondition.makeCondition("revokedDate", EntityOperator.EQUALS, null)))
                    .queryFirst();
            if (consent == null) {
                return consentRequiredMessage(delegator, document, documentType, locale);
            }
            Timestamp consentDate = consent.getTimestamp("consentDate");
            Timestamp effectiveDate = document.getTimestamp("effectiveDate");
            if (consentDate != null && effectiveDate != null && consentDate.before(effectiveDate)) {
                return consentRequiredMessage(delegator, document, documentType, locale);
            }
        }
        return null;
    }

    private static String consentRequiredMessage(Delegator delegator, GenericValue document,
            String documentType, Locale locale) throws GenericEntityException {
        String label = documentType;
        GenericValue enumeration = EntityQuery.use(delegator).from("Enumeration")
                .where("enumId", documentType).queryOne();
        if (enumeration != null && UtilValidate.isNotEmpty(enumeration.getString("description"))) {
            label = enumeration.getString("description");
        }
        return UtilProperties.getMessage(RESOURCE, "EfinanceConsentDocumentRequired",
                new Object[] {label, document.getString("version")}, locale);
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

    private static BigDecimal toBigDecimal(Object v) {
        if (v == null) return BigDecimal.ZERO;
        if (v instanceof BigDecimal) return (BigDecimal) v;
        try {
            return new BigDecimal(v.toString());
        } catch (NumberFormatException e) {
            return BigDecimal.ZERO;
        }
    }

    private static Long toLong(Object v) {
        if (v == null) return 0L;
        if (v instanceof Long) return (Long) v;
        if (v instanceof Integer) return ((Integer) v).longValue();
        try {
            return Long.parseLong(v.toString());
        } catch (NumberFormatException e) {
            return 0L;
        }
    }
}
