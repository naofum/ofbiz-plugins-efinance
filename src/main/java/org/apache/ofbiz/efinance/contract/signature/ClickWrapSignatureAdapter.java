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
package org.apache.ofbiz.efinance.contract.signature;

import java.util.Base64;
import java.util.HexFormat;

import org.apache.ofbiz.base.util.UtilDateTime;
import org.apache.ofbiz.base.util.UtilProperties;
import org.apache.ofbiz.base.util.UtilValidate;
import org.apache.ofbiz.efinance.security.EfAccessGuard;

/**
 * Default e-signature implementation: click-wrap consent.
 *
 * <p>The evidence hash covers the signing session, member, quote, timestamp,
 * IP address and device id. A future external-signature adapter can return a
 * provider reference or a separately signed content id through the same
 * result object.</p>
 */
public class ClickWrapSignatureAdapter implements ESignatureAdapter {

    private static final String RESOURCE = "EfinanceUiLabels";
    private static final String METHOD = "EFSIG_METHOD_CLICK";

    @Override
    public SignatureResult sign(SignatureRequest request) throws ESignatureException {
        if (request == null || UtilValidate.isEmpty(request.getSigningSessionId())) {
            throw new ESignatureException("Signing session is required.");
        }
        if (!request.isConfirmed()) {
            String message = UtilProperties.getMessage(RESOURCE, "EfinanceSigningNotConfirmed",
                    request.getLocale());
            return SignatureResult.failure(message);
        }

        java.sql.Timestamp signedDate = UtilDateTime.nowTimestamp();
        String evidence = String.join("|",
                METHOD,
                request.getSigningSessionId(),
                nullToEmpty(request.getPartyId()),
                nullToEmpty(request.getDraftId()),
                nullToEmpty(request.getQuoteId()),
                nullToEmpty(request.getQuoteTypeId()),
                signedDate.toString(),
                nullToEmpty(request.getIpAddress()),
                nullToEmpty(request.getDeviceId()));
        // Store a compact 43-character SHA-256 representation in the
        // short-varchar(60) evidence column.
        String hex = EfAccessGuard.sha256(evidence);
        String evidenceHash = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(HexFormat.of().parseHex(hex));
        return SignatureResult.success(METHOD, evidenceHash, signedDate);
    }

    private String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
