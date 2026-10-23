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
package org.apache.ofbiz.efinance.test

import org.apache.ofbiz.entity.GenericValue
import org.apache.ofbiz.service.ServiceUtil
import org.apache.ofbiz.service.testtools.OFBizTestCase

/**
 * STEP W2: eFinance application draft, document upload and submission tests.
 */
class EfApplicationTests extends OFBizTestCase {

    EfApplicationTests(String name) {
        super(name)
    }

    private GenericValue apiUser() {
        return from('UserLogin').where('userLoginId', 'test-ef-customer').queryOne()
    }

    private void ok(Map r) {
        assert ServiceUtil.isSuccess(r), ServiceUtil.getErrorMessage(r)
    }

    private String createCompleteLoanDraft() {
        Map create = dispatcher.runSync('efCreateDraft', [
                userLogin: apiUser(),
                productId: 'DEMO_LOANPROD1',
                applicationTypeId: 'LOAN'
        ])
        ok(create)
        String draftId = create.draftId
        dispatcher.runSync('efSaveDraft', [
                userLogin: apiUser(), draftId: draftId, step: 1,
                stepData: [principalAmount: '300000', termMonths: '24', repaymentMethod: 'EQUAL_PAYMENT']
        ])
        dispatcher.runSync('efSaveDraft', [
                userLogin: apiUser(), draftId: draftId, step: 2,
                stepData: [firstName: 'Test', lastName: 'Customer', birthDate: '1980-01-01',
                           postalCode: '100-0001', prefecture: 'Tokyo', city: 'Chiyoda',
                           address1: '1-1-1', employerName: 'Test Corp', annualIncome: '5000000',
                           residenceType: 'RENT']
        ])
        dispatcher.runSync('efSaveDraft', [
                userLogin: apiUser(), draftId: draftId, step: 3,
                stepData: [confirmTerms: 'Y', confirmPrivacy: 'Y']
        ])
        return draftId
    }

    void testGetApplicationForm() {
        Map r = dispatcher.runSync('efGetApplicationForm', [
            productId: 'DEMO_LOANPROD1',
            applicationTypeId: 'LOAN'
        ])
        ok(r)
        assert r.steps != null
        assert r.steps.size() == 3
        assert r.totalSteps == 3
    }

    void testGetApplicationFormLease() {
        Map r = dispatcher.runSync('efGetApplicationForm', [
            productId: 'DEMO_LEASEPROD1',
            applicationTypeId: 'LEASE'
        ])
        ok(r)
        assert r.steps != null
        assert r.steps.size() == 4
        assert r.totalSteps == 4
    }

    void testCreateAndSaveDraft() {
        Map create = dispatcher.runSync('efCreateDraft', [
            userLogin: apiUser(),
            productId: 'DEMO_LOANPROD1',
            applicationTypeId: 'LOAN'
        ])
        ok(create)
        String draftId = create.draftId
        assert draftId
        assert create.expiresAt != null

        Map save = dispatcher.runSync('efSaveDraft', [
            userLogin: apiUser(),
            draftId: draftId,
            step: 1,
            stepData: [principalAmount: '300000', termMonths: '24', repaymentMethod: 'EQUAL_PAYMENT']
        ])
        ok(save)
        assert save.stepValid == true
        assert save.validationErrors != null
        assert save.validationErrors.isEmpty()

        Map get = dispatcher.runSync('efGetDraft', [userLogin: apiUser(), draftId: draftId])
        ok(get)
        assert get.draft != null
        assert get.draft.draftId == draftId
        assert get.draft.applicationTypeId == 'LOAN'
        assert get.draft.stepData != null
    }

    void testCreateDraftIsIdempotent() {
        String key = 'TEST_CREATE_' + System.currentTimeMillis()
        Map first = dispatcher.runSync('efCreateDraft', [
                userLogin: apiUser(),
                productId: 'DEMO_LOANPROD1',
                applicationTypeId: 'LOAN',
                idempotencyKey: key
        ])
        ok(first)
        Map second = dispatcher.runSync('efCreateDraft', [
                userLogin: apiUser(),
                productId: 'DEMO_LOANPROD1',
                applicationTypeId: 'LOAN',
                idempotencyKey: key
        ])
        ok(second)
        assert second.draftId == first.draftId
        assert second.replayed == true
    }

    void testCreateDraftReusesActiveDraft() {
        Map first = dispatcher.runSync('efCreateDraft', [
                userLogin: apiUser(),
                productId: 'DEMO_LOANPROD1',
                applicationTypeId: 'LOAN'
        ])
        ok(first)
        Map second = dispatcher.runSync('efCreateDraft', [
                userLogin: apiUser(),
                productId: 'DEMO_LOANPROD1',
                applicationTypeId: 'LOAN'
        ])
        ok(second)
        assert second.draftId == first.draftId
        assert second.existingDraft == true
    }

    void testSaveDraftValidationError() {
        Map create = dispatcher.runSync('efCreateDraft', [
            userLogin: apiUser(),
            productId: 'DEMO_LOANPROD1',
            applicationTypeId: 'LOAN'
        ])
        ok(create)
        String draftId = create.draftId

        // Save step 1 without required fields
        Map save = dispatcher.runSync('efSaveDraft', [
            userLogin: apiUser(),
            draftId: draftId,
            step: 1,
            stepData: [repaymentMethod: 'EQUAL_PAYMENT']
        ])
        ok(save)
        assert save.stepValid == false
        assert save.validationErrors != null
        assert !save.validationErrors.isEmpty()
    }

    void testWithdrawDraft() {
        Map create = dispatcher.runSync('efCreateDraft', [
            userLogin: apiUser(),
            productId: 'DEMO_LOANPROD1',
            applicationTypeId: 'LOAN'
        ])
        ok(create)
        String draftId = create.draftId

        Map withdraw = dispatcher.runSync('efWithdrawDraft', [
            userLogin: apiUser(), draftId: draftId
        ])
        ok(withdraw)

        GenericValue draft = from('EfApplicationDraft').where('draftId', draftId).queryOne()
        assert draft.statusId == 'EFDRF_WITHDRAWN'
    }

    void testWithdrawAlreadyWithdrawnFails() {
        Map create = dispatcher.runSync('efCreateDraft', [
            userLogin: apiUser(),
            productId: 'DEMO_LOANPROD1',
            applicationTypeId: 'LOAN'
        ])
        ok(create)
        String draftId = create.draftId

        dispatcher.runSync('efWithdrawDraft', [userLogin: apiUser(), draftId: draftId])

        Map withdraw2 = dispatcher.runSync('efWithdrawDraft', [userLogin: apiUser(), draftId: draftId])
        assert !ServiceUtil.isSuccess(withdraw2), 'Expected failure withdrawing already-withdrawn draft'
    }

    void testSubmitLoanApplication() {
        // Create draft
        Map create = dispatcher.runSync('efCreateDraft', [
            userLogin: apiUser(),
            productId: 'DEMO_LOANPROD1',
            applicationTypeId: 'LOAN'
        ])
        ok(create)
        String draftId = create.draftId

        // Save all required steps
        dispatcher.runSync('efSaveDraft', [
            userLogin: apiUser(), draftId: draftId, step: 1,
            stepData: [principalAmount: '300000', termMonths: '24', repaymentMethod: 'EQUAL_PAYMENT']
        ])
        dispatcher.runSync('efSaveDraft', [
            userLogin: apiUser(), draftId: draftId, step: 2,
            stepData: [firstName: 'Test', lastName: 'Customer', birthDate: '1980-01-01',
                       postalCode: '100-0001', prefecture: '東京都', city: '千代田区',
                       address1: '1-1-1', employerName: 'Test Corp', annualIncome: '5000000',
                       residenceType: 'RENT']
        ])
        dispatcher.runSync('efSaveDraft', [
            userLogin: apiUser(), draftId: draftId, step: 3,
            stepData: [confirmTerms: 'Y', confirmPrivacy: 'Y']
        ])

        // Submit
        String idempotencyKey = 'TEST_SUBMIT_' + System.currentTimeMillis()
        Map submit = dispatcher.runSync('efSubmitApplication', [
            userLogin: apiUser(), draftId: draftId,
            ipAddress: '127.0.0.1', deviceId: 'test-device',
            idempotencyKey: idempotencyKey
        ])
        ok(submit)
        assert submit.financeApplicationId
        assert submit.financeApplicationTypeId == 'LOAN'
        assert submit.fraudResult == 'EFFRAUD_OK'

        // Retrying the same request must return the original finance application.
        Map replay = dispatcher.runSync('efSubmitApplication', [
            userLogin: apiUser(), draftId: draftId,
            ipAddress: '127.0.0.1', deviceId: 'test-device',
            idempotencyKey: idempotencyKey
        ])
        ok(replay)
        assert replay.financeApplicationId == submit.financeApplicationId
        assert replay.replayed == true

        // Check LoanApplication was created
        GenericValue loanApp = from('LoanApplication').where('loanApplicationId', submit.financeApplicationId).queryOne()
        assert loanApp != null
        assert loanApp.statusId == 'LOANAPP_RECEIVED'

        // Check EfApplicationLink
        GenericValue link = from('EfApplicationLink')
            .where('financeApplicationId', submit.financeApplicationId).queryOne()
        assert link != null
        assert link.memberPartyId == 'TEST_EF_CUST'

        // Check draft is now SUBMITTED
        GenericValue draft = from('EfApplicationDraft').where('draftId', draftId).queryOne()
        assert draft.statusId == 'EFDRF_SUBMITTED'
    }

    void testSubmitRequiresCurrentConsents() {
        String draftId = createCompleteLoanDraft()
        GenericValue terms = from('EfConsent')
                .where('partyId', 'TEST_EF_CUST', 'documentId', 'EFCDOC_TERMS_V1').queryOne()
        assert terms != null
        def originalRevokedDate = terms.revokedDate
        try {
            terms.revokedDate = new java.sql.Timestamp(System.currentTimeMillis())
            terms.store()

            Map submit = dispatcher.runSync('efSubmitApplication', [
                    userLogin: apiUser(), draftId: draftId,
                    ipAddress: '127.0.0.1', deviceId: 'test-device'
            ])
            assert !ServiceUtil.isSuccess(submit), 'Expected missing-consent failure'
        } finally {
            terms.revokedDate = originalRevokedDate
            terms.store()
        }
    }

    void testSubmitFailsIfRequiredFieldsMissing() {
        Map create = dispatcher.runSync('efCreateDraft', [
            userLogin: apiUser(),
            productId: 'DEMO_LOANPROD1',
            applicationTypeId: 'LOAN'
        ])
        ok(create)
        String draftId = create.draftId

        // Only save step 1 (missing step 2 firstName/lastName and step 3 confirmations)
        dispatcher.runSync('efSaveDraft', [
            userLogin: apiUser(), draftId: draftId, step: 1,
            stepData: [principalAmount: '300000', termMonths: '24']
        ])

        Map submit = dispatcher.runSync('efSubmitApplication', [
            userLogin: apiUser(), draftId: draftId,
            ipAddress: '127.0.0.1', deviceId: 'test-device'
        ])
        assert !ServiceUtil.isSuccess(submit), 'Expected validation failure'
    }

    void testListDrafts() {
        Map r = dispatcher.runSync('efListDrafts', [userLogin: apiUser()])
        ok(r)
        assert r.drafts != null
    }
}
