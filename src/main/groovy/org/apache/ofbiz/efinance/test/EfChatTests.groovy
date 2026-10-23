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
package org.apache.ofbiz.efinance.test

import org.apache.ofbiz.entity.GenericValue
import org.apache.ofbiz.service.ServiceUtil
import org.apache.ofbiz.service.testtools.OFBizTestCase

/**
 * STEP W9 chat support and support-case escalation.
 */
class EfChatTests extends OFBizTestCase {

    EfChatTests(String name) {
        super(name)
    }

    private GenericValue apiUser() {
        return from('UserLogin').where('userLoginId', 'test-ef-customer').queryOne()
    }

    private void ok(Map result) {
        assert ServiceUtil.isSuccess(result), ServiceUtil.getErrorMessage(result)
    }

    private Map startChat(Map extra = [:]) {
        Map context = [userLogin: apiUser(), subject: 'Chat functional test']
        context.putAll(extra)
        Map result = dispatcher.runSync('efStartChat', context)
        ok(result)
        assert result.conversation.conversationId
        assert result.conversation.statusId == 'EFCHAT_AI'
        assert result.messages.any { it.senderTypeEnumId == 'EFCHAT_SND_AI' }
        return result
    }

    private Map escalate(String conversationId) {
        Map result = dispatcher.runSync('efSendChatMessage', [
                userLogin: apiUser(),
                conversationId: conversationId,
                content: '担当者と話したいです'
        ])
        ok(result)
        assert result.escalated
        assert result.conversation.statusId == 'EFCHAT_HUMAN'
        assert result.conversation.supportCaseId
        return result
    }

    void testStartListGetAndRouteFaq() {
        Map started = startChat()
        String conversationId = started.conversation.conversationId

        Map listed = dispatcher.runSync('efListChats', [userLogin: apiUser()])
        ok(listed)
        assert listed.conversations.any { it.conversationId == conversationId }

        Map answered = dispatcher.runSync('efSendChatMessage', [
                userLogin: apiUser(),
                conversationId: conversationId,
                content: '本人確認の方法を教えてください'
        ])
        ok(answered)
        assert !answered.escalated
        assert answered.messages.any {
            it.senderTypeEnumId == 'EFCHAT_SND_AI' && it.content.contains('本人確認')
        }

        Map fetched = dispatcher.runSync('efGetChat', [
                userLogin: apiUser(), conversationId: conversationId
        ])
        ok(fetched)
        assert fetched.conversation.conversationId == conversationId
        assert fetched.messages.size() >= 3
    }

    void testHumanEscalationCreatesSupportCase() {
        Map started = startChat()
        Map escalated = escalate(started.conversation.conversationId)
        String supportCaseId = escalated.conversation.supportCaseId
        assert supportCaseId

        Map supportCase = dispatcher.runSync('efGetSupportCase', [
                userLogin: apiUser(), supportCaseId: supportCaseId
        ])
        ok(supportCase)
        assert supportCase.supportCase.supportCaseId == supportCaseId
        assert supportCase.supportCase.subject
    }

    void testHumanModeForwardsCustomerMessage() {
        Map started = startChat()
        String conversationId = started.conversation.conversationId
        Map escalated = escalate(conversationId)
        String supportCaseId = escalated.conversation.supportCaseId

        Map replied = dispatcher.runSync('efSendChatMessage', [
                userLogin: apiUser(),
                conversationId: conversationId,
                content: '追加の情報です。よろしくお願いします。'
        ])
        ok(replied)
        assert replied.conversation.statusId == 'EFCHAT_HUMAN'

        Map supportCase = dispatcher.runSync('efGetSupportCase', [
                userLogin: apiUser(), supportCaseId: supportCaseId
        ])
        ok(supportCase)
        assert supportCase.messages.any { it.content.contains('追加の情報です') }
    }

    void testExplicitEscalationAndClose() {
        Map started = startChat()
        String conversationId = started.conversation.conversationId

        Map escalated = dispatcher.runSync('efEscalateChat', [
                userLogin: apiUser(), conversationId: conversationId, reason: 'test'
        ])
        ok(escalated)
        assert escalated.conversation.statusId == 'EFCHAT_HUMAN'
        assert escalated.conversation.supportCaseId

        Map closed = dispatcher.runSync('efCloseChat', [
                userLogin: apiUser(), conversationId: conversationId
        ])
        ok(closed)
        assert closed.conversation.statusId == 'EFCHAT_CLOSED'

        Map rejected = dispatcher.runSync('efSendChatMessage', [
                userLogin: apiUser(),
                conversationId: conversationId,
                content: 'このメッセージは送れないはずです'
        ])
        assert !ServiceUtil.isSuccess(rejected)
    }

    void testUnknownConversationAndDraftOwnership() {
        Map unknown = dispatcher.runSync('efGetChat', [
                userLogin: apiUser(), conversationId: 'NONEXISTENT_CHAT'
        ])
        assert !ServiceUtil.isSuccess(unknown)

        Map invalidDraft = dispatcher.runSync('efStartChat', [
                userLogin: apiUser(), draftId: 'NONEXISTENT_DRAFT'
        ])
        assert !ServiceUtil.isSuccess(invalidDraft)
    }

    void testMessageValidation() {
        Map started = startChat()
        String conversationId = started.conversation.conversationId

        Map empty = dispatcher.runSync('efSendChatMessage', [
                userLogin: apiUser(), conversationId: conversationId, content: '   '
        ])
        assert !ServiceUtil.isSuccess(empty)

        StringBuilder longMessage = new StringBuilder()
        for (int i = 0; i < 4001; i++) {
            longMessage.append('x')
        }
        Map tooLong = dispatcher.runSync('efSendChatMessage', [
                userLogin: apiUser(), conversationId: conversationId, content: longMessage.toString()
        ])
        assert !ServiceUtil.isSuccess(tooLong)
    }
}
