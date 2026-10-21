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
package org.apache.ofbiz.efinance.support;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

import org.apache.ofbiz.base.util.UtilProperties;
import org.apache.ofbiz.base.util.UtilValidate;
import org.apache.ofbiz.entity.Delegator;
import org.apache.ofbiz.entity.GenericEntityException;
import org.apache.ofbiz.entity.GenericValue;
import org.apache.ofbiz.entity.util.EntityQuery;
import org.apache.ofbiz.service.DispatchContext;
import org.apache.ofbiz.service.GenericServiceException;
import org.apache.ofbiz.service.LocalDispatcher;
import org.apache.ofbiz.service.ServiceUtil;

/**
 * STEP W6/W7 integration: efinance is the customer-facing gateway for the
 * support customer API. The support REST contract stays publish="false"; these
 * services call support's services internally with the efinance system user and
 * derive customerPartyId from the authenticated efinance member.
 */
public final class EfSupportServices {

    private static final String RESOURCE = "EfinanceUiLabels";

    private EfSupportServices() {
    }

    public static Map<String, Object> efListSupportCases(DispatchContext dctx, Map<String, Object> context) {
        return callSupport(dctx, context, "listSupportCasesForCustomer", "cases");
    }

    public static Map<String, Object> efCreateSupportCase(DispatchContext dctx, Map<String, Object> context) {
        return callSupport(dctx, context, "createSupportCaseForCustomer", "case");
    }

    public static Map<String, Object> efGetSupportCase(DispatchContext dctx, Map<String, Object> context) {
        return callSupport(dctx, context, "getSupportCaseForCustomer", "case");
    }

    public static Map<String, Object> efReplySupportCase(DispatchContext dctx, Map<String, Object> context) {
        return callSupport(dctx, context, "replySupportCaseFromCustomer", "reply");
    }

    public static Map<String, Object> efCloseSupportCase(DispatchContext dctx, Map<String, Object> context) {
        return callSupport(dctx, context, "closeSupportCaseByCustomer", "close");
    }

    public static Map<String, Object> efRateSupportCase(DispatchContext dctx, Map<String, Object> context) {
        return callSupport(dctx, context, "rateSupportCase", "rating");
    }

    public static Map<String, Object> efGetSupportFormOptions(DispatchContext dctx, Map<String, Object> context) {
        return callSupport(dctx, context, "getSupportCaseFormOptions", "options");
    }

    public static Map<String, Object> efUploadSupportAttachment(DispatchContext dctx, Map<String, Object> context) {
        return callSupport(dctx, context, "uploadSupportAttachment", "attachment");
    }

    private static Map<String, Object> callSupport(DispatchContext dctx, Map<String, Object> context,
            String supportService, String operation) {
        Delegator delegator = dctx.getDelegator();
        LocalDispatcher dispatcher = dctx.getDispatcher();
        Locale locale = (Locale) context.get("locale");
        GenericValue userLogin = (GenericValue) context.get("userLogin");
        String customerPartyId = userLogin == null ? null : userLogin.getString("partyId");
        if (UtilValidate.isEmpty(customerPartyId)) {
            return ServiceUtil.returnError(message(locale, "EfinanceMemberNotFound"));
        }
        try {
            GenericValue member = EntityQuery.use(delegator).from("EfMember")
                    .where("userLoginId", userLogin.getString("userLoginId")).queryOne();
            if (member == null || !"EFMBR_ACTIVE".equals(member.getString("statusId"))) {
                return ServiceUtil.returnError(message(locale, "EfinanceMemberNotActive"));
            }
            GenericValue systemUser = EntityQuery.use(delegator).from("UserLogin")
                    .where("userLoginId", UtilProperties.getPropertyValue("efinance",
                            "efinance.system.userlogin", "efinance-system")).queryOne();
            if (systemUser == null) {
                return ServiceUtil.returnError(message(locale, "EfinanceMemberNotFound"));
            }

            Map<String, Object> supportContext = new HashMap<>(context);
            supportContext.put("userLogin", systemUser);
            // Never trust a client-supplied customerPartyId.
            supportContext.put("customerPartyId", customerPartyId);

            Map<String, Object> supportResult = dispatcher.runSync(supportService, supportContext);
            if (ServiceUtil.isError(supportResult)) {
                return ServiceUtil.returnError(message(locale, "EfinanceSupportOperationFailed"));
            }

            Map<String, Object> result = ServiceUtil.returnSuccess();
            for (Map.Entry<String, Object> entry : supportResult.entrySet()) {
                if (!"responseMessage".equals(entry.getKey())
                        && !"errorMessage".equals(entry.getKey())) {
                    result.put(entry.getKey(), entry.getValue());
                }
            }
            return result;
        } catch (GenericEntityException | GenericServiceException e) {
            return ServiceUtil.returnError(message(locale, "EfinanceSupportOperationFailed"));
        }
    }

    private static String message(Locale locale, String key) {
        return UtilProperties.getMessage(RESOURCE, key, locale);
    }
}
