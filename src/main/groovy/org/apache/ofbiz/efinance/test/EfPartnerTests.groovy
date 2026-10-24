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

import org.apache.ofbiz.base.util.UtilProperties
import org.apache.ofbiz.entity.GenericValue
import org.apache.ofbiz.service.ServiceUtil
import org.apache.ofbiz.service.testtools.OFBizTestCase

/**
 * STEP W9 partner handoff and application attribution.
 */
class EfPartnerTests extends OFBizTestCase {

    EfPartnerTests(String name) {
        super(name)
    }

    private GenericValue apiUser() {
        return from('UserLogin').where('userLoginId', 'test-ef-customer').queryOne()
    }

    private String partnerSecret() {
        return UtilProperties.getPropertyValue('efinance',
                'efinance.partner.DEMO_PARTNER.secret')
    }

    private void ok(Map result) {
        assert ServiceUtil.isSuccess(result), ServiceUtil.getErrorMessage(result)
    }

    private Map issue(Map extra = [:]) {
        Map context = [
                partnerId: 'DEMO_PARTNER',
                partnerSecret: partnerSecret(),
                returnUrl: 'https://partner.example/complete',
                ipAddress: '127.0.0.1'
        ]
        context.putAll(extra)
        return dispatcher.runSync('efIssuePartnerHandoff', context)
    }

    void testIssueAndValidateHandoff() {
        Map issued = issue()
        ok(issued)
        assert issued.handoffToken
        assert issued.handoffUrl.contains('partnerToken=')
        assert issued.partnerName == 'Demo Partner'

        Map validated = dispatcher.runSync('efValidatePartnerHandoff', [
                handoffToken: issued.handoffToken
        ])
        ok(validated)
        assert validated.partnerId == 'DEMO_PARTNER'
        assert validated.partnerName == 'Demo Partner'
        assert validated.returnUrl == 'https://partner.example/complete'
        assert validated.expiresAt
    }

    void testInvalidCredentialsRejected() {
        Map issued = issue([partnerSecret: 'wrong-secret'])
        assert !ServiceUtil.isSuccess(issued)
    }

    void testTamperedTokenRejected() {
        Map issued = issue()
        ok(issued)
        String token = issued.handoffToken
        String tampered = (token.startsWith('x') ? 'y' : 'x') + token.substring(1)
        Map validated = dispatcher.runSync('efValidatePartnerHandoff', [
                handoffToken: tampered
        ])
        assert !ServiceUtil.isSuccess(validated)
    }

    void testReturnUrlAllowlist() {
        Map issued = issue([returnUrl: 'https://evil.example/complete'])
        assert !ServiceUtil.isSuccess(issued)
    }

    void testCreateDraftWithVerifiedPartner() {
        Map issued = issue()
        ok(issued)
        Map created = dispatcher.runSync('efCreateDraft', [
                userLogin: apiUser(),
                productId: 'DEMO_LOANPROD1',
                applicationTypeId: 'LOAN',
                partnerToken: issued.handoffToken,
                idempotencyKey: 'TEST_PARTNER_DRAFT_' + System.currentTimeMillis()
        ])
        ok(created)
        assert created.draftId
        assert created.partnerId == 'DEMO_PARTNER'

        GenericValue draft = from('EfApplicationDraft')
                .where('draftId', created.draftId).queryOne()
        assert draft != null
        assert draft.partnerId == 'DEMO_PARTNER'
        assert draft.sourceEnumId == 'EFSRC_PARTNER'
    }

    void testCreateDraftRejectsInvalidPartnerToken() {
        Map created = dispatcher.runSync('efCreateDraft', [
                userLogin: apiUser(),
                productId: 'DEMO_LOANPROD1',
                applicationTypeId: 'LOAN',
                partnerToken: 'invalid.partner.token',
                idempotencyKey: 'TEST_PARTNER_INVALID_' + System.currentTimeMillis()
        ])
        assert !ServiceUtil.isSuccess(created)
    }
}
