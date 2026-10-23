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
package org.apache.ofbiz.efinance.chat;

import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * STEP W9 request passed to a chat adapter.
 */
public final class ChatRequest {

    private final String conversationId;
    private final String memberPartyId;
    private final String message;
    private final List<Map<String, Object>> history;
    private final String draftId;
    private final String agreementId;
    private final String agreementTypeId;
    private final Locale locale;

    public ChatRequest(String conversationId, String memberPartyId, String message,
            List<Map<String, Object>> history, String draftId, String agreementId,
            String agreementTypeId, Locale locale) {
        this.conversationId = conversationId;
        this.memberPartyId = memberPartyId;
        this.message = message;
        this.history = history == null ? Collections.emptyList() : history;
        this.draftId = draftId;
        this.agreementId = agreementId;
        this.agreementTypeId = agreementTypeId;
        this.locale = locale;
    }

    public String getConversationId() {
        return conversationId;
    }

    public String getMemberPartyId() {
        return memberPartyId;
    }

    public String getMessage() {
        return message;
    }

    public List<Map<String, Object>> getHistory() {
        return history;
    }

    public String getDraftId() {
        return draftId;
    }

    public String getAgreementId() {
        return agreementId;
    }

    public String getAgreementTypeId() {
        return agreementTypeId;
    }

    public Locale getLocale() {
        return locale;
    }
}
