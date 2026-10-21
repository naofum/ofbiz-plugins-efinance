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

import java.nio.ByteBuffer
import org.apache.ofbiz.entity.GenericValue
import org.apache.ofbiz.service.ServiceUtil
import org.apache.ofbiz.service.testtools.OFBizTestCase

class EfPreScreenTests extends OFBizTestCase {

    EfPreScreenTests(String name) { super(name) }

    private GenericValue apiUser() {
        return from('UserLogin').where('userLoginId', 'test-ef-customer').queryOne()
    }

    private GenericValue adminUser() {
        return from('UserLogin').where('userLoginId', 'system').queryOne()
    }

    private void ok(Map r) {
        assert ServiceUtil.isSuccess(r), ServiceUtil.getErrorMessage(r)
    }

    private String createLoanDraft(BigDecimal amount = 300000, int term = 24) {
        Map create = dispatcher.runSync('efCreateDraft', [
            userLogin: apiUser(), productId: 'DEMO_LOANPROD1', applicationTypeId: 'LOAN'
        ])
        ok(create)
        String draftId = create.draftId
        dispatcher.runSync('efSaveDraft', [
            userLogin: apiUser(), draftId: draftId, step: 1,
            stepData: [principalAmount: amount.toString(), termMonths: term.toString(),
                       repaymentMethod: 'EQUAL_PAYMENT']
        ])
        dispatcher.runSync('efSaveDraft', [
            userLogin: apiUser(), draftId: draftId, step: 2,
            stepData: [firstName: 'Test', lastName: 'User', birthDate: '1985-01-01',
                       annualIncome: '5000000', postalCode: '100-0001', prefecture: '東京都',
                       city: '千代田区', address1: '1-1', employerName: 'Test', residenceType: 'RENT']
        ])
        dispatcher.runSync('efSaveDraft', [
            userLogin: apiUser(), draftId: draftId, step: 3,
            stepData: [confirmTerms: 'Y', confirmPrivacy: 'Y']
        ])
        return draftId
    }

    void testPreScreenLikely() {
        // income 5M >> 300K * 0.4 = 120K  →  LIKELY
        String draftId = createLoanDraft(300000, 24)
        Map result = dispatcher.runSync('efPreScreen', [userLogin: apiUser(), draftId: draftId])
        ok(result)
        assert result.result in ['LIKELY', 'CONSULT', 'UNLIKELY']
        assert result.guidance != null
        assert result.disclaimer != null
        assert result.result == 'LIKELY'
    }

    void testPreScreenConsult() {
        // 400K loan, income 100K:  100K < 400K * 0.4 = 160K  →  CONSULT
        String draftId = createLoanDraft(400000, 12)
        dispatcher.runSync('efSaveDraft', [
            userLogin: apiUser(), draftId: draftId, step: 2,
            stepData: [firstName: 'Test', lastName: 'User', birthDate: '1985-01-01',
                       annualIncome: '100000',
                       postalCode: '100-0001', prefecture: '東京都', city: '千代田区',
                       address1: '1-1', employerName: 'Test', residenceType: 'RENT']
        ])
        Map result = dispatcher.runSync('efPreScreen', [userLogin: apiUser(), draftId: draftId])
        ok(result)
        assert result.result == 'CONSULT'
    }

    void testPreScreenUnlikely() {
        // termMonths = 100 > maxTermMonths of DEMO_LOANPROD1 (60)  →  UNLIKELY
        String draftId = createLoanDraft(300000, 100)
        Map result = dispatcher.runSync('efPreScreen', [userLogin: apiUser(), draftId: draftId])
        ok(result)
        assert result.result == 'UNLIKELY'
    }

    void testGetApplicationStatusDraftNotSubmitted() {
        String draftId = createLoanDraft()
        Map result = dispatcher.runSync('efGetApplicationStatus', [userLogin: apiUser(), draftId: draftId])
        ok(result)
        assert result.stageEnumId != null
        assert result.stageEnumId == 'EF_STAGE_RECEIVED'
    }

    void testGetApplicationStatusAfterSubmit() {
        String draftId = createLoanDraft()
        Map submit = dispatcher.runSync('efSubmitApplication', [
            userLogin: apiUser(), draftId: draftId, ipAddress: '127.0.0.1', deviceId: 'test'
        ])
        ok(submit)

        Map status = dispatcher.runSync('efGetApplicationStatus', [userLogin: apiUser(), draftId: draftId])
        ok(status)
        assert status.stageEnumId != null
        assert status.stageEnumId in [
            'EF_STAGE_RECEIVED', 'EF_STAGE_REVIEWING', 'EF_STAGE_RESULT'
        ]
    }

    void testCreateAndListDocumentRequest() {
        String draftId = createLoanDraft()

        Map createReq = dispatcher.runSync('efCreateDocumentRequest', [
            userLogin: adminUser(),
            draftId: draftId,
            documentTypeEnumId: 'EFDOC_INCOME',
            reason: 'Income verification needed'
        ])
        ok(createReq)
        assert createReq.docRequestId

        Map list = dispatcher.runSync('efListDocumentRequests', [
            userLogin: apiUser(), draftId: draftId
        ])
        ok(list)
        assert list.docRequests != null
        assert list.docRequests.size() >= 1
        assert list.docRequests.any { it.statusId == 'EFDOCREQ_PENDING' }
    }

    void testSubmitRequestedDocument() {
        String draftId = createLoanDraft()

        Map createReq = dispatcher.runSync('efCreateDocumentRequest', [
            userLogin: adminUser(), draftId: draftId,
            documentTypeEnumId: 'EFDOC_INCOME',
            reason: 'Please provide income proof'
        ])
        ok(createReq)
        String docRequestId = createReq.docRequestId

        Map submit = dispatcher.runSync('efSubmitRequestedDocument', [
            userLogin: apiUser(),
            docRequestId: docRequestId,
            fileName: 'income_proof.pdf',
            contentType: 'application/pdf',
            uploadedFile: ByteBuffer.wrap('income-proof-data'.getBytes('UTF-8'))
        ])
        ok(submit)

        GenericValue req = from('EfDocumentRequest').where('docRequestId', docRequestId).queryOne()
        assert req.statusId == 'EFDOCREQ_SUBMITTED'
        assert req.submittedContentId != null
    }
}
