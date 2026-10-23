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
package org.apache.ofbiz.efinance.kyc;

import java.nio.ByteBuffer;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.HashMap;
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
import org.apache.ofbiz.efinance.filescan.FileScanAdapterFactory;
import org.apache.ofbiz.efinance.filescan.FileScanResult;
import org.apache.ofbiz.service.DispatchContext;
import org.apache.ofbiz.service.LocalDispatcher;
import org.apache.ofbiz.service.ServiceUtil;

/**
 * STEP W3: eFinance KYC (identity verification) services.
 */
public final class EfKycServices {

    private static final String MODULE = EfKycServices.class.getName();
    private static final String RESOURCE = "EfinanceUiLabels";
    private static final String PROPS = "efinance";

    private static final String STATUS_PENDING   = "EFKYC_PENDING";
    private static final String STATUS_SUBMITTED = "EFKYC_SUBMITTED";
    private static final String STATUS_VERIFIED  = "EFKYC_VERIFIED";
    private static final String STATUS_RESUBMIT  = "EFKYC_RESUBMIT";
    private static final String STATUS_REJECTED  = "EFKYC_REJECTED";

    private static final String ACTION_KYC_START  = "EFAUD_KYC_START";
    private static final String ACTION_KYC_SUBMIT = "EFAUD_KYC_SUBMIT";
    private static final String ACTION_KYC_REVIEW = "EFAUD_KYC_REVIEW";

    private static final String RESULT_SUCCESS = "EFAUDRES_SUCCESS";

    private static final List<String> REVIEW_STATUSES =
            Arrays.asList(STATUS_VERIFIED, STATUS_RESUBMIT, STATUS_REJECTED);

    private EfKycServices() {
    }

    // =========================================================================
    // efStartKyc
    // =========================================================================

    public static Map<String, Object> efStartKyc(DispatchContext dctx, Map<String, Object> context) {
        Delegator delegator = dctx.getDelegator();
        Locale locale = (Locale) context.get("locale");

        String memberPartyId = getPartyIdFromContext(context);
        String kycMethodEnumId = (String) context.get("kycMethodEnumId");
        String draftId = (String) context.get("draftId");

        if (UtilValidate.isEmpty(memberPartyId)) {
            return ServiceUtil.returnError("User not authenticated");
        }
        if (UtilValidate.isEmpty(kycMethodEnumId)) {
            return ServiceUtil.returnError("kycMethodEnumId is required");
        }

        try {
            Timestamp now = UtilDateTime.nowTimestamp();
            int retentionYears = UtilProperties.getPropertyAsInteger(PROPS, "efinance.kyc.retention.years", 7);
            Calendar cal = Calendar.getInstance();
            cal.setTimeInMillis(now.getTime());
            cal.add(Calendar.YEAR, retentionYears);
            Timestamp expiresDate = new Timestamp(cal.getTimeInMillis());

            String kycCheckId = delegator.getNextSeqId("EfKycCheck");
            GenericValue kycCheck = delegator.makeValue("EfKycCheck");
            kycCheck.set("kycCheckId", kycCheckId);
            kycCheck.set("memberPartyId", memberPartyId);
            kycCheck.set("kycSubjectPartyId", memberPartyId);
            kycCheck.set("kycMethodEnumId", kycMethodEnumId);
            kycCheck.set("statusId", STATUS_PENDING);
            kycCheck.set("expiresDate", expiresDate);
            kycCheck.set("createdDate", now);
            kycCheck.set("lastModifiedDate", now);
            if (UtilValidate.isNotEmpty(draftId)) {
                kycCheck.set("draftId", draftId);
            }
            delegator.create(kycCheck);

            writeAuditLog(delegator, memberPartyId, ACTION_KYC_START, "EfKycCheck", kycCheckId);

            Map<String, Object> result = ServiceUtil.returnSuccess();
            result.put("kycCheckId", kycCheckId);
            return result;
        } catch (GenericEntityException e) {
            Debug.logError(e, MODULE);
            return ServiceUtil.returnError(e.getMessage());
        }
    }

    // =========================================================================
    // efUploadKycImage
    // =========================================================================

    public static Map<String, Object> efUploadKycImage(DispatchContext dctx, Map<String, Object> context) {
        Delegator delegator = dctx.getDelegator();
        LocalDispatcher dispatcher = dctx.getDispatcher();
        Locale locale = (Locale) context.get("locale");

        String memberPartyId = getPartyIdFromContext(context);
        String kycCheckId   = (String) context.get("kycCheckId");
        String imageTypeEnumId = (String) context.get("imageTypeEnumId");
        String fileName     = (String) context.get("fileName");
        String contentType  = (String) context.get("contentType");
        ByteBuffer uploadedFile = (ByteBuffer) context.get("uploadedFile");

        try {
            GenericValue kycCheck = EntityQuery.use(delegator).from("EfKycCheck")
                    .where("kycCheckId", kycCheckId).queryOne();
            if (kycCheck == null) {
                return ServiceUtil.returnError(
                        UtilProperties.getMessage(RESOURCE, "EfinanceKycCheckNotFound", locale));
            }
            if (!memberPartyId.equals(kycCheck.getString("memberPartyId"))) {
                return ServiceUtil.returnError(
                        UtilProperties.getMessage(RESOURCE, "EfinanceKycNotOwned", locale));
            }
            if (!STATUS_PENDING.equals(kycCheck.getString("statusId"))) {
                return ServiceUtil.returnError(
                        UtilProperties.getMessage(RESOURCE, "EfinanceKycNotSubmittable", locale));
            }
            FileScanResult scanResult = FileScanAdapterFactory.scan(fileName, contentType, uploadedFile);
            if (scanResult.isInfected()) {
                return ServiceUtil.returnError(UtilProperties.getMessage(
                        RESOURCE, "EfinanceFileInfected", locale));
            }

            // Upload content via core service using system user
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
                Debug.logWarning("createContentFromUploadedFile failed: "
                        + ServiceUtil.getErrorMessage(contentResult), MODULE);
            }

            Timestamp now = UtilDateTime.nowTimestamp();
            String kycImageId = delegator.getNextSeqId("EfKycImage");
            GenericValue kycImage = delegator.makeValue("EfKycImage");
            kycImage.set("kycImageId", kycImageId);
            kycImage.set("kycCheckId", kycCheckId);
            kycImage.set("contentId", contentId);
            kycImage.set("imageTypeEnumId", imageTypeEnumId);
            kycImage.set("scanStatus", scanResult.getStatus());
            kycImage.set("uploadedDate", now);
            delegator.create(kycImage);

            Map<String, Object> result = ServiceUtil.returnSuccess();
            result.put("kycImageId", kycImageId);
            result.put("scanStatus", scanResult.getStatus());
            return result;
        } catch (Exception e) {
            Debug.logError(e, MODULE);
            return ServiceUtil.returnError(e.getMessage());
        }
    }

    // =========================================================================
    // efSubmitKyc
    // =========================================================================

    public static Map<String, Object> efSubmitKyc(DispatchContext dctx, Map<String, Object> context) {
        Delegator delegator = dctx.getDelegator();
        Locale locale = (Locale) context.get("locale");

        String memberPartyId = getPartyIdFromContext(context);
        String kycCheckId = (String) context.get("kycCheckId");

        try {
            GenericValue kycCheck = EntityQuery.use(delegator).from("EfKycCheck")
                    .where("kycCheckId", kycCheckId).queryOne();
            if (kycCheck == null) {
                return ServiceUtil.returnError(
                        UtilProperties.getMessage(RESOURCE, "EfinanceKycCheckNotFound", locale));
            }
            if (!memberPartyId.equals(kycCheck.getString("memberPartyId"))) {
                return ServiceUtil.returnError(
                        UtilProperties.getMessage(RESOURCE, "EfinanceKycNotOwned", locale));
            }
            if (!STATUS_PENDING.equals(kycCheck.getString("statusId"))) {
                return ServiceUtil.returnError(
                        UtilProperties.getMessage(RESOURCE, "EfinanceKycNotSubmittable", locale));
            }

            // Check at least one image exists
            long imageCount = EntityQuery.use(delegator).from("EfKycImage")
                    .where("kycCheckId", kycCheckId).queryCount();
            if (imageCount == 0) {
                return ServiceUtil.returnError(
                        UtilProperties.getMessage(RESOURCE, "EfinanceKycNoImages", locale));
            }

            // ManualEkycAdapter: set adapter result and transition to SUBMITTED
            Timestamp now = UtilDateTime.nowTimestamp();
            kycCheck.set("statusId", STATUS_SUBMITTED);
            kycCheck.set("adapterResultCode", "PENDING_MANUAL_REVIEW");
            kycCheck.set("lastModifiedDate", now);
            delegator.store(kycCheck);

            writeAuditLog(delegator, memberPartyId, ACTION_KYC_SUBMIT, "EfKycCheck", kycCheckId);

            return ServiceUtil.returnSuccess(
                    UtilProperties.getMessage(RESOURCE, "EfinanceKycSubmitted", locale));
        } catch (GenericEntityException e) {
            Debug.logError(e, MODULE);
            return ServiceUtil.returnError(e.getMessage());
        }
    }

    // =========================================================================
    // efGetKycStatus
    // =========================================================================

    public static Map<String, Object> efGetKycStatus(DispatchContext dctx, Map<String, Object> context) {
        Delegator delegator = dctx.getDelegator();
        Locale locale = (Locale) context.get("locale");

        String memberPartyId = getPartyIdFromContext(context);
        String kycCheckId = (String) context.get("kycCheckId");
        String draftId    = (String) context.get("draftId");

        try {
            GenericValue kycCheck = null;

            if (UtilValidate.isNotEmpty(kycCheckId)) {
                kycCheck = EntityQuery.use(delegator).from("EfKycCheck")
                        .where("kycCheckId", kycCheckId).queryOne();
                if (kycCheck == null) {
                    return ServiceUtil.returnError(
                            UtilProperties.getMessage(RESOURCE, "EfinanceKycCheckNotFound", locale));
                }
                if (!memberPartyId.equals(kycCheck.getString("memberPartyId"))) {
                    return ServiceUtil.returnError(
                            UtilProperties.getMessage(RESOURCE, "EfinanceKycNotOwned", locale));
                }
            } else if (UtilValidate.isNotEmpty(draftId)) {
                // Find latest KYC check for this member and draft
                List<GenericValue> checks = EntityQuery.use(delegator).from("EfKycCheck")
                        .where(EntityCondition.makeCondition(
                                EntityCondition.makeCondition("memberPartyId", memberPartyId),
                                EntityOperator.AND,
                                EntityCondition.makeCondition("draftId", draftId)))
                        .orderBy("-createdDate")
                        .queryList();
                if (!checks.isEmpty()) {
                    kycCheck = checks.get(0);
                }
            }

            if (kycCheck == null) {
                Map<String, Object> result = ServiceUtil.returnSuccess();
                result.put("kycCheck", null);
                result.put("images", new ArrayList<>());
                return result;
            }

            Map<String, Object> checkMap = new LinkedHashMap<>();
            checkMap.put("kycCheckId", kycCheck.getString("kycCheckId"));
            checkMap.put("statusId", kycCheck.getString("statusId"));
            checkMap.put("kycMethodEnumId", kycCheck.getString("kycMethodEnumId"));
            checkMap.put("confirmedDate", kycCheck.getTimestamp("confirmedDate"));
            checkMap.put("adapterResultCode", kycCheck.getString("adapterResultCode"));
            checkMap.put("createdDate", kycCheck.getTimestamp("createdDate"));

            List<GenericValue> imgList = EntityQuery.use(delegator).from("EfKycImage")
                    .where("kycCheckId", kycCheck.getString("kycCheckId"))
                    .orderBy("uploadedDate")
                    .queryList();
            List<Map<String, Object>> images = new ArrayList<>();
            for (GenericValue img : imgList) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("kycImageId", img.getString("kycImageId"));
                m.put("imageTypeEnumId", img.getString("imageTypeEnumId"));
                m.put("uploadedDate", img.getTimestamp("uploadedDate"));
                images.add(m);
            }

            Map<String, Object> result = ServiceUtil.returnSuccess();
            result.put("kycCheck", checkMap);
            result.put("images", images);
            return result;
        } catch (GenericEntityException e) {
            Debug.logError(e, MODULE);
            return ServiceUtil.returnError(e.getMessage());
        }
    }

    // =========================================================================
    // reviewEfKycCheck  (staff service)
    // =========================================================================

    public static Map<String, Object> reviewEfKycCheck(DispatchContext dctx, Map<String, Object> context) {
        Delegator delegator = dctx.getDelegator();
        Locale locale = (Locale) context.get("locale");

        String kycCheckId    = (String) context.get("kycCheckId");
        String newStatusId   = (String) context.get("statusId");
        String confirmerNote = (String) context.get("confirmerNote");

        // Get reviewer party from userLogin
        String confirmerPartyId = getPartyIdFromContext(context);

        // Validate target status
        if (!REVIEW_STATUSES.contains(newStatusId)) {
            return ServiceUtil.returnError(
                    UtilProperties.getMessage(RESOURCE, "EfinanceKycReviewInvalidStatus", locale));
        }

        try {
            GenericValue kycCheck = EntityQuery.use(delegator).from("EfKycCheck")
                    .where("kycCheckId", kycCheckId).queryOne();
            if (kycCheck == null) {
                return ServiceUtil.returnError(
                        UtilProperties.getMessage(RESOURCE, "EfinanceKycCheckNotFound", locale));
            }
            if (!STATUS_SUBMITTED.equals(kycCheck.getString("statusId"))) {
                return ServiceUtil.returnError(
                        UtilProperties.getMessage(RESOURCE, "EfinanceKycNotSubmitted", locale));
            }

            Timestamp now = UtilDateTime.nowTimestamp();
            kycCheck.set("statusId", newStatusId);
            kycCheck.set("confirmerPartyId", confirmerPartyId);
            kycCheck.set("confirmedDate", now);
            kycCheck.set("lastModifiedDate", now);
            if (UtilValidate.isNotEmpty(confirmerNote)) {
                kycCheck.set("confirmerNote", confirmerNote);
            }
            delegator.store(kycCheck);

            if (STATUS_VERIFIED.equals(newStatusId)) {
                recordKycToUnderwriting(delegator, kycCheck);
            }

            writeAuditLog(delegator, confirmerPartyId, ACTION_KYC_REVIEW, "EfKycCheck", kycCheckId);

            return ServiceUtil.returnSuccess();
        } catch (GenericEntityException e) {
            Debug.logError(e, MODULE);
            return ServiceUtil.returnError(e.getMessage());
        }
    }

    // =========================================================================
    // efIsKycVerified
    // =========================================================================

    public static Map<String, Object> efIsKycVerified(DispatchContext dctx, Map<String, Object> context) {
        Delegator delegator = dctx.getDelegator();

        String memberPartyId  = getPartyIdFromContext(context);
        String subjectPartyId = (String) context.get("subjectPartyId");
        String draftId        = (String) context.get("draftId");

        // Use subjectPartyId if provided, otherwise use logged-in member
        String queryPartyId = UtilValidate.isNotEmpty(subjectPartyId) ? subjectPartyId : memberPartyId;

        try {
            EntityCondition cond = EntityCondition.makeCondition(
                    EntityCondition.makeCondition("memberPartyId", queryPartyId),
                    EntityOperator.AND,
                    EntityCondition.makeCondition("statusId", STATUS_VERIFIED));

            if (UtilValidate.isNotEmpty(draftId)) {
                cond = EntityCondition.makeCondition(
                        cond,
                        EntityOperator.AND,
                        EntityCondition.makeCondition("draftId", draftId));
            }

            List<GenericValue> checks = EntityQuery.use(delegator).from("EfKycCheck")
                    .where(cond)
                    .orderBy("-confirmedDate")
                    .queryList();

            Map<String, Object> result = ServiceUtil.returnSuccess();
            if (!checks.isEmpty()) {
                result.put("verified", Boolean.TRUE);
                result.put("kycCheckId", checks.get(0).getString("kycCheckId"));
            } else {
                result.put("verified", Boolean.FALSE);
                result.put("kycCheckId", null);
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

    /**
     * Record KYC verified result into underwriting external info.
     * W3: FinUwExternalInfo (finance U6) is not yet implemented.
     * The linkage will be added when finance Step U6 is available.
     * TODO: When finance U6 is available, find LoanUnderwriting for this applicant
     *       and create FinUwExternalInfo with infoTypeId="IDENTITY", linked to loanUnderwritingId.
     */
    private static void recordKycToUnderwriting(Delegator delegator, GenericValue kycCheck)
            throws GenericEntityException {
        Debug.logInfo("KYC verified for kycCheckId=" + kycCheck.getString("kycCheckId")
                + "; finance U6 FinUwExternalInfo linkage not yet implemented.", MODULE);
    }

    private static String getPartyIdFromContext(Map<String, Object> context) {
        GenericValue userLogin = (GenericValue) context.get("userLogin");
        if (userLogin == null) return null;
        return userLogin.getString("partyId");
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
