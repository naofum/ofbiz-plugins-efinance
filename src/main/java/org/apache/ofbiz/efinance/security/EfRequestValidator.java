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
package org.apache.ofbiz.efinance.security;

import java.util.Locale;

import org.apache.ofbiz.base.util.Debug;
import org.apache.ofbiz.base.util.UtilProperties;
import org.apache.ofbiz.base.util.UtilValidate;
import org.apache.ofbiz.entity.Delegator;
import org.apache.ofbiz.entity.GenericEntityException;
import org.apache.ofbiz.entity.GenericValue;
import org.apache.ofbiz.entity.util.EntityQuery;

/**
 * Request-level validation called by the REST authorization filter.
 *
 * <p>The filter lives in the rest-api component because it needs the JAX-RS
 * API, while the actual efinance checks stay in this component. The filter
 * invokes this class through reflection so that rest-api has no compile-time
 * dependency on efinance.</p>
 */
public final class EfRequestValidator {

    private static final String MODULE = EfRequestValidator.class.getName();
    private static final String RESOURCE = "EfinanceUiLabels";

    private EfRequestValidator() {
    }

    /**
     * Validate an authenticated efinance request.
     *
     * @return {@code null} when the request is valid, otherwise a user-facing
     *         error message
     */
    public static String validateRequest(Delegator delegator, GenericValue userLogin, String jwt, Locale locale) {
        if (userLogin == null || UtilValidate.isEmpty(jwt)) {
            return message(locale, "EfinanceSessionInvalid");
        }
        try {
            if (!EfAccessGuard.isAccessTokenActive(delegator, jwt)) {
                return message(locale, "EfinanceSessionRevoked");
            }
            GenericValue member = EntityQuery.use(delegator).from("EfMember")
                    .where("userLoginId", userLogin.getString("userLoginId")).queryOne();
            if (member != null && !"EFMBR_ACTIVE".equals(member.getString("statusId"))) {
                return message(locale, "EfinanceAccountLocked");
            }
            return null;
        } catch (GenericEntityException e) {
            Debug.logError(e, "Unable to validate efinance request", MODULE);
            return message(locale, "EfinanceSessionInvalid");
        }
    }

    private static String message(Locale locale, String key) {
        return UtilProperties.getMessage(RESOURCE, key, locale);
    }
}
