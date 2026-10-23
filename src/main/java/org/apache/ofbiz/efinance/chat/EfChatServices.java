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

import java.sql.Timestamp;
import java.util.ArrayList;
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
import org.apache.ofbiz.entity.util.EntityQuery;
import org.apache.ofbiz.efinance.security.EfAccessGuard;
import org.apache.ofbiz.efinance.security.EfRequestGuard;
import org.apache.ofbiz.service.DispatchContext;
import org.apache.ofbiz.service.GenericServiceException;
import org.apache.ofbiz.service.LocalDispatcher;
import org.apache.ofbiz.service.ServiceUtil;

/**
 * STEP W9: customer chat and human-support escalation.
 *
 * <p>Chats are owned by the authenticated efinance member. The default adapter
 * answers FAQ-style questions. When a customer asks for a person, the complete
 * conversation is handed to the support plugin as a support case. Staff replies
 * remain in the support case and are read back into the chat view without
 * duplicating support's message storage.</p>
 */
public final class EfChatServices {

    private static final String MODULE = EfChatServices.class.getName();
    private static final String RESOURCE = "EfinanceUiLabels";
    private static final String PROPS = "efinance";

    private static final String STATUS_AI = "EFCHAT_AI";
    private static final String STATUS_HUMAN = "EFCHAT_HUMAN";
    private static final String STATUS_CLOSED = "EFCHAT_CLOSED";

    private static final String SENDER_CUSTOMER = "EFCHAT_SND_CUSTOMER";
    private static final String SENDER_AI = "EFCHAT_SND_AI";
    private static final String SENDER_STAFF = "EFCHAT_SND_STAFF";
    private static final String SENDER_SYSTEM = "EFCHAT_SND_SYSTEM";

    private static final String ACTION_START = "EFAUD_CHAT_START";
    private static final String ACTION_MESSAGE = "EFAUD_CHAT_MESSAGE";
    private static final String ACTION_ESCALATE = "EFAUD_CHAT_ESCALATE";
    private static final String ACTION_CLOSE = "EFAUD_CHAT_CLOSE";

    private static final String RESULT_SUCCESS = "EFAUDRES_SUCCESS";
    private static final String SUPPORT_CASE_TYPE = "SUPCT_INQUIRY";

    private EfChatServices() {
    }

    // =========================================================================
    // efStartChat
    // =========================================================================

    public static Map<String, Object> efStartChat(DispatchContext dctx, Map<String, Object> context) {
        Delegator delegator = dctx.getDelegator();
        Locale locale = (Locale) context.get("locale");
        String partyId = getPartyId(context);
        if (UtilValidate.isEmpty(partyId)) {
            return ServiceUtil.returnError(message(locale, "EfinanceMemberNotFound"));
        }

        String draftId = (String) context.get("draftId");
        String agreementId = (String) context.get("agreementId");
        String agreementTypeId = (String) context.get("agreementTypeId");
        String subject = (String) context.get("subject");

        try {
            if (!EfAccessGuard.isActiveMember(delegator, (GenericValue) context.get("userLogin"))) {
                return ServiceUtil.returnError(message(locale, "EfinanceMemberNotActive"));
            }
            if (!ownsDraft(delegator, draftId, partyId)) {
                return ServiceUtil.returnError(message(locale, "EfinanceDraftNotOwned"));
            }
            if (!ownsAgreement(delegator, agreementId, agreementTypeId, partyId)) {
                return ServiceUtil.returnError(message(locale, "EfinanceContractNotFound"));
            }

            int limit = UtilProperties.getPropertyAsInteger(PROPS, "efinance.rate.limit.chat.start", 30);
            int window = UtilProperties.getPropertyAsInteger(PROPS,
                    "efinance.rate.limit.chat.start.window.seconds", 3600);
            if (!EfRequestGuard.checkRateLimit(delegator, "CHAT_START", partyId, limit, window)) {
                return ServiceUtil.returnError(message(locale, "EfinanceRateLimited"));
            }

            Timestamp now = UtilDateTime.nowTimestamp();
            String conversationId = delegator.getNextSeqId("EfChatConversation");
            GenericValue conversation = delegator.makeValue("EfChatConversation");
            conversation.set("conversationId", conversationId);
            conversation.set("memberPartyId", partyId);
            conversation.set("draftId", draftId);
            conversation.set("agreementId", agreementId);
            conversation.set("agreementTypeId", agreementTypeId);
            conversation.set("statusId", STATUS_AI);
            conversation.set("subject", truncate(UtilValidate.isNotEmpty(subject)
                    ? subject : defaultSubject(locale), 100));
            conversation.set("startedDate", now);
            conversation.set("lastMessageDate", now);
            delegator.create(conversation);

            ChatAdapter adapter = ChatAdapterFactory.getAdapter();
            createMessage(delegator, conversationId, SENDER_AI, null,
                    adapter.greeting(locale), "GREETING", null, now);
            writeAudit(delegator, partyId, ACTION_START, conversationId);

            return chatResult(delegator, dctx, conversation, locale, false);
        } catch (GenericEntityException e) {
            Debug.logError(e, "efStartChat failed", MODULE);
            return ServiceUtil.returnError(message(locale, "EfinanceChatCreateFailed"));
        }
    }

    // =========================================================================
    // efListChats
    // =========================================================================

    public static Map<String, Object> efListChats(DispatchContext dctx, Map<String, Object> context) {
        Delegator delegator = dctx.getDelegator();
        Locale locale = (Locale) context.get("locale");
        String partyId = getPartyId(context);
        try {
            List<GenericValue> values = EntityQuery.use(delegator).from("EfChatConversation")
                    .where("memberPartyId", partyId)
                    .orderBy("-lastMessageDate")
                    .limit(50)
                    .queryList();
            List<Map<String, Object>> conversations = new ArrayList<>();
            for (GenericValue value : values) {
                conversations.add(conversationMap(value));
            }
            Map<String, Object> result = ServiceUtil.returnSuccess();
            result.put("conversations", conversations);
            result.put("count", conversations.size());
            return result;
        } catch (GenericEntityException e) {
            Debug.logError(e, "efListChats failed", MODULE);
            return ServiceUtil.returnError(message(locale, "EfinanceChatCreateFailed"));
        }
    }

    // =========================================================================
    // efGetChat
    // =========================================================================

    public static Map<String, Object> efGetChat(DispatchContext dctx, Map<String, Object> context) {
        Delegator delegator = dctx.getDelegator();
        Locale locale = (Locale) context.get("locale");
        String partyId = getPartyId(context);
        String conversationId = (String) context.get("conversationId");
        try {
            GenericValue conversation = getOwnedConversation(delegator, conversationId, partyId);
            if (conversation == null) {
                return ServiceUtil.returnError(message(locale, "EfinanceChatNotFound"));
            }
            return chatResult(delegator, dctx, conversation, locale, false);
        } catch (GenericEntityException e) {
            Debug.logError(e, "efGetChat failed", MODULE);
            return ServiceUtil.returnError(message(locale, "EfinanceChatCreateFailed"));
        }
    }

    // =========================================================================
    // efSendChatMessage
    // =========================================================================

    public static Map<String, Object> efSendChatMessage(DispatchContext dctx, Map<String, Object> context) {
        Delegator delegator = dctx.getDelegator();
        LocalDispatcher dispatcher = dctx.getDispatcher();
        Locale locale = (Locale) context.get("locale");
        String partyId = getPartyId(context);
        String conversationId = (String) context.get("conversationId");
        String content = (String) context.get("content");

        if (UtilValidate.isEmpty(content) || content.trim().isEmpty()) {
            return ServiceUtil.returnError(message(locale, "EfinanceChatMessageRequired"));
        }
        content = content.trim();
        int maxLength = UtilProperties.getPropertyAsInteger(PROPS, "efinance.chat.max.length", 4000);
        if (content.length() > maxLength) {
            return ServiceUtil.returnError(message(locale, "EfinanceChatMessageTooLong"));
        }

        try {
            GenericValue conversation = getOwnedConversation(delegator, conversationId, partyId);
            if (conversation == null) {
                return ServiceUtil.returnError(message(locale, "EfinanceChatNotFound"));
            }
            String statusId = conversation.getString("statusId");
            if (STATUS_CLOSED.equals(statusId)) {
                return ServiceUtil.returnError(message(locale, "EfinanceChatClosed"));
            }

            int limit = UtilProperties.getPropertyAsInteger(PROPS, "efinance.rate.limit.chat.message", 60);
            int window = UtilProperties.getPropertyAsInteger(PROPS,
                    "efinance.rate.limit.chat.message.window.seconds", 300);
            if (!EfRequestGuard.checkRateLimit(delegator, "CHAT_MESSAGE", partyId, limit, window)) {
                return ServiceUtil.returnError(message(locale, "EfinanceRateLimited"));
            }

            Timestamp now = UtilDateTime.nowTimestamp();
            GenericValue customerMessage = createMessage(delegator, conversationId, SENDER_CUSTOMER,
                    partyId, content, null, null, now);
            boolean escalated = false;

            if (STATUS_HUMAN.equals(statusId)) {
                // The support team owns the conversation after escalation. Mirror
                // the customer message into the support case using an idempotent
                // externalId so retries cannot create duplicate replies.
                GenericValue supportCase = getSupportCase(delegator, conversation);
                if (supportCase != null) {
                    Map<String, Object> supportResult = dispatcher.runSync("replySupportCaseFromCustomer",
                            UtilMisc.toMap("userLogin", getSystemUser(delegator),
                                    "customerPartyId", partyId,
                                    "supportCaseId", supportCase.getString("supportCaseId"),
                                    "subject", truncate(content, 100),
                                    "content", content,
                                    "externalId", customerMessage.getString("messageId"),
                                    "locale", locale));
                    if (ServiceUtil.isError(supportResult)) {
                        return supportResult;
                    }
                    customerMessage.set("communicationEventId", supportResult.get("communicationEventId"));
                    delegator.store(customerMessage);
                }
            } else {
                List<Map<String, Object>> history = messageMaps(
                        EntityQuery.use(delegator).from("EfChatMessage")
                                .where("conversationId", conversationId)
                                .orderBy("createdDate")
                                .queryList());
                ChatReply reply;
                try {
                    reply = ChatAdapterFactory.getAdapter().reply(new ChatRequest(
                            conversationId, partyId, content, history,
                            conversation.getString("draftId"), conversation.getString("agreementId"),
                            conversation.getString("agreementTypeId"), locale));
                } catch (RuntimeException e) {
                    Debug.logError(e, "Chat adapter failed; escalating", MODULE);
                    reply = new ChatReply(message(locale, "EfinanceChatAdapterFallback"), true, "HUMAN");
                }
                createMessage(delegator, conversationId, SENDER_AI, null,
                        reply.getText(), reply.getIntent(), null, UtilDateTime.nowTimestamp());
                escalated = reply.isEscalate();
                if (escalated) {
                    Map<String, Object> escalation = escalate(dctx, conversation, partyId, locale);
                    if (ServiceUtil.isError(escalation)) {
                        return escalation;
                    }
                    conversation.set("supportCaseId", escalation.get("supportCaseId"));
                    conversation.set("statusId", STATUS_HUMAN);
                    createMessage(delegator, conversationId, SENDER_SYSTEM, null,
                            message(locale, "EfinanceChatEscalated"), "ESCALATED", null,
                            UtilDateTime.nowTimestamp());
                    writeAudit(delegator, partyId, ACTION_ESCALATE, conversationId);
                }
            }

            conversation.set("lastMessageDate", UtilDateTime.nowTimestamp());
            delegator.store(conversation);
            writeAudit(delegator, partyId, ACTION_MESSAGE, conversationId);
            return chatResult(delegator, dctx, conversation, locale, escalated);
        } catch (GenericEntityException | GenericServiceException e) {
            Debug.logError(e, "efSendChatMessage failed", MODULE);
            return ServiceUtil.returnError(message(locale, "EfinanceChatCreateFailed"));
        }
    }

    // =========================================================================
    // efEscalateChat
    // =========================================================================

    public static Map<String, Object> efEscalateChat(DispatchContext dctx, Map<String, Object> context) {
        Delegator delegator = dctx.getDelegator();
        Locale locale = (Locale) context.get("locale");
        String partyId = getPartyId(context);
        String conversationId = (String) context.get("conversationId");
        try {
            GenericValue conversation = getOwnedConversation(delegator, conversationId, partyId);
            if (conversation == null) {
                return ServiceUtil.returnError(message(locale, "EfinanceChatNotFound"));
            }
            if (STATUS_CLOSED.equals(conversation.getString("statusId"))) {
                return ServiceUtil.returnError(message(locale, "EfinanceChatClosed"));
            }
            if (STATUS_HUMAN.equals(conversation.getString("statusId"))
                    && UtilValidate.isNotEmpty(conversation.getString("supportCaseId"))) {
                return chatResult(delegator, dctx, conversation, locale, true);
            }

            Map<String, Object> escalation = escalate(dctx, conversation, partyId, locale);
            if (ServiceUtil.isError(escalation)) {
                return escalation;
            }
            conversation.set("supportCaseId", escalation.get("supportCaseId"));
            conversation.set("statusId", STATUS_HUMAN);
            conversation.set("lastMessageDate", UtilDateTime.nowTimestamp());
            delegator.store(conversation);
            createMessage(delegator, conversationId, SENDER_SYSTEM, null,
                    message(locale, "EfinanceChatEscalated"), "ESCALATED", null,
                    UtilDateTime.nowTimestamp());
            writeAudit(delegator, partyId, ACTION_ESCALATE, conversationId);
            return chatResult(delegator, dctx, conversation, locale, true);
        } catch (GenericEntityException | GenericServiceException e) {
            Debug.logError(e, "efEscalateChat failed", MODULE);
            return ServiceUtil.returnError(message(locale, "EfinanceChatCreateFailed"));
        }
    }

    // =========================================================================
    // efCloseChat
    // =========================================================================

    public static Map<String, Object> efCloseChat(DispatchContext dctx, Map<String, Object> context) {
        Delegator delegator = dctx.getDelegator();
        Locale locale = (Locale) context.get("locale");
        String partyId = getPartyId(context);
        String conversationId = (String) context.get("conversationId");
        try {
            GenericValue conversation = getOwnedConversation(delegator, conversationId, partyId);
            if (conversation == null) {
                return ServiceUtil.returnError(message(locale, "EfinanceChatNotFound"));
            }
            if (!STATUS_CLOSED.equals(conversation.getString("statusId"))) {
                Timestamp now = UtilDateTime.nowTimestamp();
                conversation.set("statusId", STATUS_CLOSED);
                conversation.set("closedDate", now);
                conversation.set("lastMessageDate", now);
                delegator.store(conversation);
                createMessage(delegator, conversationId, SENDER_SYSTEM, null,
                        message(locale, "EfinanceChatClosedMessage"), "CLOSED", null, now);
                writeAudit(delegator, partyId, ACTION_CLOSE, conversationId);
            }
            return chatResult(delegator, dctx, conversation, locale, false);
        } catch (GenericEntityException e) {
            Debug.logError(e, "efCloseChat failed", MODULE);
            return ServiceUtil.returnError(message(locale, "EfinanceChatCreateFailed"));
        }
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private static Map<String, Object> chatResult(Delegator delegator, DispatchContext dctx,
            GenericValue conversation, Locale locale, boolean escalated) throws GenericEntityException {
        List<GenericValue> messages = EntityQuery.use(delegator).from("EfChatMessage")
                .where("conversationId", conversation.getString("conversationId"))
                .orderBy("createdDate")
                .queryList();
        Map<String, Object> result = ServiceUtil.returnSuccess();
        result.put("conversation", conversationMap(conversation));
        result.put("messages", messageMaps(messages));
        result.put("supportMessages", supportStaffMessages(dctx, conversation));
        result.put("escalated", escalated);
        return result;
    }

    private static Map<String, Object> conversationMap(GenericValue conversation) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("conversationId", conversation.getString("conversationId"));
        map.put("memberPartyId", conversation.getString("memberPartyId"));
        map.put("draftId", conversation.getString("draftId"));
        map.put("agreementId", conversation.getString("agreementId"));
        map.put("agreementTypeId", conversation.getString("agreementTypeId"));
        map.put("statusId", conversation.getString("statusId"));
        map.put("subject", conversation.getString("subject"));
        map.put("supportCaseId", conversation.getString("supportCaseId"));
        map.put("startedDate", conversation.getTimestamp("startedDate"));
        map.put("lastMessageDate", conversation.getTimestamp("lastMessageDate"));
        map.put("closedDate", conversation.getTimestamp("closedDate"));
        map.put("canEscalate", STATUS_AI.equals(conversation.getString("statusId")));
        map.put("canSend", !STATUS_CLOSED.equals(conversation.getString("statusId")));
        return map;
    }

    private static List<Map<String, Object>> messageMaps(List<GenericValue> values) {
        List<Map<String, Object>> messages = new ArrayList<>();
        for (GenericValue value : values) {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("messageId", value.getString("messageId"));
            map.put("conversationId", value.getString("conversationId"));
            map.put("senderTypeEnumId", value.getString("senderTypeEnumId"));
            map.put("senderPartyId", value.getString("senderPartyId"));
            map.put("content", value.getString("content"));
            map.put("intent", value.getString("intent"));
            map.put("createdDate", value.getTimestamp("createdDate"));
            map.put("communicationEventId", value.getString("communicationEventId"));
            messages.add(map);
        }
        return messages;
    }

    private static GenericValue createMessage(Delegator delegator, String conversationId,
            String senderTypeEnumId, String senderPartyId, String content, String intent,
            String communicationEventId, Timestamp createdDate) throws GenericEntityException {
        GenericValue message = delegator.makeValue("EfChatMessage");
        message.set("messageId", delegator.getNextSeqId("EfChatMessage"));
        message.set("conversationId", conversationId);
        message.set("senderTypeEnumId", senderTypeEnumId);
        message.set("senderPartyId", senderPartyId);
        message.set("content", content);
        message.set("intent", intent);
        message.set("createdDate", createdDate);
        message.set("communicationEventId", communicationEventId);
        delegator.create(message);
        return message;
    }

    private static GenericValue getOwnedConversation(Delegator delegator, String conversationId,
            String partyId) throws GenericEntityException {
        if (UtilValidate.isEmpty(conversationId) || UtilValidate.isEmpty(partyId)) {
            return null;
        }
        GenericValue conversation = EntityQuery.use(delegator).from("EfChatConversation")
                .where("conversationId", conversationId).queryOne();
        if (conversation == null || !partyId.equals(conversation.getString("memberPartyId"))) {
            return null;
        }
        return conversation;
    }

    private static GenericValue getSupportCase(Delegator delegator, GenericValue conversation)
            throws GenericEntityException {
        String supportCaseId = conversation.getString("supportCaseId");
        if (UtilValidate.isEmpty(supportCaseId)) {
            return null;
        }
        return EntityQuery.use(delegator).from("SupportCase")
                .where("supportCaseId", supportCaseId).queryOne();
    }

    private static List<Map<String, Object>> supportStaffMessages(DispatchContext dctx,
            GenericValue conversation) throws GenericEntityException {
        String supportCaseId = conversation.getString("supportCaseId");
        if (UtilValidate.isEmpty(supportCaseId)) {
            return new ArrayList<>();
        }
        List<Map<String, Object>> staffMessages = new ArrayList<>();
        try {
            GenericValue systemUser = getSystemUser(dctx.getDelegator());
            if (systemUser == null) {
                return staffMessages;
            }
            Map<String, Object> threadResult = dctx.getDispatcher().runSync("getSupportCaseThread",
                    UtilMisc.toMap("userLogin", systemUser, "supportCaseId", supportCaseId));
            if (ServiceUtil.isError(threadResult)) {
                return staffMessages;
            }
            Object thread = threadResult.get("supportCaseThread");
            if (!(thread instanceof List)) {
                return staffMessages;
            }
            for (Object item : (List<?>) thread) {
                if (!(item instanceof Map)) {
                    continue;
                }
                Map<?, ?> source = (Map<?, ?>) item;
                if ("Y".equals(source.get("isInternal"))
                        || !"SUPMSG_OUT".equals(source.get("directionEnumId"))) {
                    continue;
                }
                Map<String, Object> map = new LinkedHashMap<>();
                String eventId = source.get("communicationEventId") == null
                        ? null : source.get("communicationEventId").toString();
                map.put("messageId", "SUPPORT-" + eventId);
                map.put("conversationId", conversation.getString("conversationId"));
                map.put("senderTypeEnumId", SENDER_STAFF);
                map.put("senderPartyId", null);
                map.put("content", source.get("content"));
                map.put("intent", "SUPPORT");
                map.put("createdDate", source.get("entryDate"));
                map.put("communicationEventId", eventId);
                staffMessages.add(map);
            }
        } catch (GenericEntityException | GenericServiceException e) {
            Debug.logWarning(e, "Could not load support staff messages for chat "
                    + conversation.getString("conversationId"), MODULE);
        }
        return staffMessages;
    }

    private static Map<String, Object> escalate(DispatchContext dctx, GenericValue conversation,
            String partyId, Locale locale) throws GenericEntityException, GenericServiceException {
        if (UtilValidate.isNotEmpty(conversation.getString("supportCaseId"))) {
            Map<String, Object> result = ServiceUtil.returnSuccess();
            result.put("supportCaseId", conversation.getString("supportCaseId"));
            return result;
        }
        Delegator delegator = dctx.getDelegator();
        LocalDispatcher dispatcher = dctx.getDispatcher();
        List<GenericValue> messages = EntityQuery.use(delegator).from("EfChatMessage")
                .where("conversationId", conversation.getString("conversationId"))
                .orderBy("createdDate")
                .queryList();
        String subject = conversation.getString("subject");
        String firstCustomerMessage = null;
        for (GenericValue message : messages) {
            if (SENDER_CUSTOMER.equals(message.getString("senderTypeEnumId"))) {
                firstCustomerMessage = message.getString("content");
                break;
            }
        }
        if (UtilValidate.isNotEmpty(firstCustomerMessage)) {
            subject = truncate(firstCustomerMessage.replaceAll("\\s+", " "), 80);
        }
        String transcript = transcript(messages, locale);
        Map<String, Object> supportContext = new HashMap<>();
        supportContext.put("userLogin", getSystemUser(delegator));
        supportContext.put("customerPartyId", partyId);
        supportContext.put("caseTypeEnumId", SUPPORT_CASE_TYPE);
        supportContext.put("subject", truncate(subject, 100));
        // SupportCase.description is a short-varchar column; keep the most recent
        // part of the transcript and leave the full conversation in EfChatMessage.
        supportContext.put("description", truncateTail(transcript, 240));
        supportContext.put("externalId", "EFCHAT-" + conversation.getString("conversationId"));
        supportContext.put("locale", locale);
        Map<String, Object> supportResult = dispatcher.runSync("createSupportCaseForCustomer", supportContext);
        if (ServiceUtil.isError(supportResult)) {
            return ServiceUtil.returnError(message(locale, "EfinanceChatSupportFailed"));
        }
        String supportCaseId = (String) supportResult.get("supportCaseId");
        // Keep the complete chat transcript as an internal support note. The
        // support customer API filters internal notes, so this does not expose
        // staff context to the customer.
        try {
            Map<String, Object> noteResult = dispatcher.runSync("addSupportCaseInternalNote",
                    UtilMisc.toMap("userLogin", getSystemUser(delegator),
                            "supportCaseId", supportCaseId,
                            "subject", "Chat transcript",
                            "content", transcript,
                            "locale", locale));
            if (ServiceUtil.isError(noteResult)) {
                Debug.logWarning("Could not add chat transcript note to support case "
                        + supportCaseId + ": " + ServiceUtil.getErrorMessage(noteResult), MODULE);
            }
        } catch (GenericServiceException e) {
            Debug.logWarning(e, "Could not add chat transcript note to support case " + supportCaseId, MODULE);
        }
        Map<String, Object> result = ServiceUtil.returnSuccess();
        result.put("supportCaseId", supportCaseId);
        return result;
    }

    private static String transcript(List<GenericValue> messages, Locale locale) {
        StringBuilder builder = new StringBuilder();
        int start = Math.max(0, messages.size() - 10);
        for (int i = start; i < messages.size(); i++) {
            GenericValue message = messages.get(i);
            String sender = message.getString("senderTypeEnumId");
            String label;
            if (SENDER_CUSTOMER.equals(sender)) {
                label = "Customer";
            } else if (SENDER_AI.equals(sender)) {
                label = "AI";
            } else {
                label = "System";
            }
            if (builder.length() > 0) {
                builder.append('\n');
            }
            builder.append(label).append(": ").append(message.getString("content"));
        }
        if (builder.length() == 0) {
            builder.append(message(locale, "EfinanceChatDefaultSubject"));
        }
        return truncate(builder.toString(), 4000);
    }

    private static GenericValue getSystemUser(Delegator delegator) throws GenericEntityException {
        String userLoginId = UtilProperties.getPropertyValue(PROPS,
                "efinance.system.userlogin", "efinance-system");
        return EntityQuery.use(delegator).from("UserLogin")
                .where("userLoginId", userLoginId).queryOne();
    }

    private static boolean ownsDraft(Delegator delegator, String draftId, String partyId)
            throws GenericEntityException {
        if (UtilValidate.isEmpty(draftId)) {
            return true;
        }
        GenericValue draft = EntityQuery.use(delegator).from("EfApplicationDraft")
                .where("draftId", draftId).queryOne();
        return draft != null && partyId.equals(draft.getString("memberPartyId"));
    }

    private static boolean ownsAgreement(Delegator delegator, String agreementId,
            String agreementTypeId, String partyId) throws GenericEntityException {
        if (UtilValidate.isEmpty(agreementId)) {
            return true;
        }
        GenericValue agreement;
        if ("LEASE".equals(agreementTypeId)) {
            agreement = EntityQuery.use(delegator).from("LeaseAgreement")
                    .where("leaseAgreementId", agreementId).queryOne();
        } else {
            agreement = EntityQuery.use(delegator).from("LoanAgreement")
                    .where("loanAgreementId", agreementId).queryOne();
        }
        return EfAccessGuard.isContractAccessible(delegator, agreement, partyId, "roleView");
    }

    private static String getPartyId(Map<String, Object> context) {
        GenericValue userLogin = (GenericValue) context.get("userLogin");
        return userLogin == null ? null : userLogin.getString("partyId");
    }

    private static String defaultSubject(Locale locale) {
        return message(locale, "EfinanceChatDefaultSubject");
    }

    private static String truncate(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength);
    }

    private static String truncateTail(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value;
        }
        return "..." + value.substring(value.length() - (maxLength - 3));
    }

    private static String message(Locale locale, String key) {
        return UtilProperties.getMessage(RESOURCE, key, locale);
    }

    private static void writeAudit(Delegator delegator, String partyId, String action, String conversationId) {
        try {
            GenericValue audit = delegator.makeValue("EfAuditLog");
            audit.set("auditLogId", delegator.getNextSeqId("EfAuditLog"));
            audit.set("partyId", partyId);
            audit.set("actionEnumId", action);
            audit.set("targetEntityName", "EfChatConversation");
            audit.set("targetEntityId", conversationId);
            audit.set("resultEnumId", RESULT_SUCCESS);
            audit.set("actionDate", UtilDateTime.nowTimestamp());
            delegator.create(audit);
        } catch (GenericEntityException e) {
            Debug.logWarning("Failed to write chat audit log: " + e.getMessage(), MODULE);
        }
    }
}
