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

import java.util.Locale;

public final class SignatureRequest {

    private final String signingSessionId;
    private final String partyId;
    private final String draftId;
    private final String quoteId;
    private final String quoteTypeId;
    private final boolean confirmed;
    private final String ipAddress;
    private final String deviceId;
    private final Locale locale;

    public SignatureRequest(String signingSessionId, String partyId, String draftId, String quoteId,
            String quoteTypeId, boolean confirmed, String ipAddress, String deviceId, Locale locale) {
        this.signingSessionId = signingSessionId;
        this.partyId = partyId;
        this.draftId = draftId;
        this.quoteId = quoteId;
        this.quoteTypeId = quoteTypeId;
        this.confirmed = confirmed;
        this.ipAddress = ipAddress;
        this.deviceId = deviceId;
        this.locale = locale;
    }

    public String getSigningSessionId() {
        return signingSessionId;
    }

    public String getPartyId() {
        return partyId;
    }

    public String getDraftId() {
        return draftId;
    }

    public String getQuoteId() {
        return quoteId;
    }

    public String getQuoteTypeId() {
        return quoteTypeId;
    }

    public boolean isConfirmed() {
        return confirmed;
    }

    public String getIpAddress() {
        return ipAddress;
    }

    public String getDeviceId() {
        return deviceId;
    }

    public Locale getLocale() {
        return locale;
    }
}
