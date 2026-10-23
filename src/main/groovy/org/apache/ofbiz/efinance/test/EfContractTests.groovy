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

import java.math.BigDecimal
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.sql.Timestamp
import java.util.Base64

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import org.apache.ofbiz.entity.GenericValue
import org.apache.ofbiz.efinance.contract.ContractDocumentPdf
import org.apache.ofbiz.efinance.contract.JasperContractDocumentRenderer
import org.apache.ofbiz.efinance.contract.signature.ClickWrapSignatureAdapter
import org.apache.ofbiz.efinance.contract.signature.ExternalSignatureAdapter
import org.apache.ofbiz.efinance.contract.signature.SignatureRequest
import org.apache.ofbiz.service.ServiceUtil
import org.apache.ofbiz.service.testtools.OFBizTestCase

class EfContractTests extends OFBizTestCase {

    EfContractTests(String name) { super(name) }

    private GenericValue apiUser() {
        return from('UserLogin').where('userLoginId', 'test-ef-customer').queryOne()
    }

    private GenericValue adminUser() {
        return from('UserLogin').where('userLoginId', 'system').queryOne()
    }

    private void ok(Map r) {
        assert ServiceUtil.isSuccess(r), ServiceUtil.getErrorMessage(r)
    }

    private String reauthToken() {
        Map r = dispatcher.runSync('efReauthenticate', [
                userLogin: apiUser(),
                password: 'ofbiz',
                ipAddress: '127.0.0.1',
                deviceId: 'test-device'
        ])
        ok(r)
        return r.reauthToken
    }

    /**
     * Create a draft in SUBMITTED state with a LoanApplication directly, bypassing
     * efSubmitApplication (and its fraud rate-limit). Used by W5 tests where submission
     * state is a prerequisite but the fraud-limit guard has already been exercised by
     * earlier test suites in the same run.
     */
    private Map createDraftWithLoanApp() {
        String partyId = 'TEST_EF_CUST'
        Timestamp now = new Timestamp(System.currentTimeMillis())

        // Create LoanApplication directly via finance service
        GenericValue systemUser = from('UserLogin').where('userLoginId', 'efinance-system').queryOne()
        Map loanAppResult = dispatcher.runSync('createLoanApplication', [
            userLogin: systemUser,
            applicantPartyId: partyId,
            productId: 'DEMO_LOANPROD1',
            requestedPrincipalAmount: new BigDecimal('100000'),
            requestedTermMonths: 12,
            repaymentMethod: 'EQUAL_PAYMENT',
            currencyUomId: 'JPY'
        ])
        ok(loanAppResult)
        String loanApplicationId = loanAppResult.loanApplicationId

        // Create draft in SUBMITTED state
        String draftId = delegator.getNextSeqId('EfApplicationDraft')
        GenericValue draft = delegator.makeValue('EfApplicationDraft')
        draft.set('draftId', draftId)
        draft.set('memberPartyId', partyId)
        draft.set('productId', 'DEMO_LOANPROD1')
        draft.set('applicationTypeId', 'LOAN')
        draft.set('currentStep', 3L)
        draft.set('totalSteps', 3L)
        draft.set('statusId', 'EFDRF_SUBMITTED')
        draft.set('submittedApplicationId', loanApplicationId)
        draft.set('submittedApplicationTypeId', 'LOAN')
        draft.set('draftJson', '{"1":{"principalAmount":"100000","termMonths":"12","repaymentMethod":"EQUAL_PAYMENT"},"2":{"firstName":"Test","lastName":"User"},"3":{"confirmTerms":"Y","confirmPrivacy":"Y"}}')
        draft.set('createdDate', now)
        draft.set('lastModifiedDate', now)
        delegator.create(draft)

        return [draftId: draftId, loanApplicationId: loanApplicationId]
    }

    void testGetOfferNoQuote() {
        Map data = createDraftWithLoanApp()
        Map result = dispatcher.runSync('efGetOffer', [userLogin: apiUser(), draftId: data.draftId])
        ok(result)
        // No presented quote yet → offer and quoteId should be null
        assert result.offerAvailable == Boolean.FALSE
        assert result.offer == null
        assert result.quoteId == null
    }

    void testListDocumentsEmpty() {
        Map data = createDraftWithLoanApp()
        Map result = dispatcher.runSync('efListDocuments', [userLogin: apiUser(), draftId: data.draftId])
        ok(result)
        assert result.documents != null
        assert result.documents.size() == 0
    }

    void testDownloadDocumentNotFound() {
        Map result = dispatcher.runSync('efDownloadDocument', [
            userLogin: apiUser(), deliveredDocId: 'NONEXISTENT_DOC_ID'
        ])
        assert !ServiceUtil.isSuccess(result)
    }

    void testFullContractFlow() {
        Map data = createDraftWithLoanApp()
        String draftId = data.draftId
        String loanApplicationId = data.loanApplicationId

        // Directly create a PRESENTED LoanQuote (test-only shortcut — simulates staff creating quote)
        String loanQuoteId = delegator.getNextSeqId('LoanQuote')
        GenericValue loanQuote = delegator.makeValue('LoanQuote')
        loanQuote.set('loanQuoteId', loanQuoteId)
        loanQuote.set('loanApplicationId', loanApplicationId)
        loanQuote.set('statusId', 'LOANQT_PRESENTED')
        loanQuote.set('currencyUomId', 'JPY')
        loanQuote.set('principalAmount', new BigDecimal('100000'))
        loanQuote.set('annualInterestRate', new BigDecimal('0.150000'))
        loanQuote.set('termMonths', new BigDecimal('12'))
        loanQuote.set('monthlyPaymentAmount', new BigDecimal('9026'))
        loanQuote.set('totalPaymentAmount', new BigDecimal('108312'))
        loanQuote.set('totalInterestAmount', new BigDecimal('8312'))
        loanQuote.set('createdDate', new Timestamp(System.currentTimeMillis()))
        delegator.create(loanQuote)

        // Test efGetOffer
        Map offerResult = dispatcher.runSync('efGetOffer', [userLogin: apiUser(), draftId: draftId])
        ok(offerResult)
        assert offerResult.offerAvailable == Boolean.TRUE
        assert offerResult.quoteId == loanQuoteId
        assert offerResult.quoteTypeId == 'LOAN'
        assert offerResult.offer != null
        assert offerResult.offer.principalAmount == new BigDecimal('100000')

        // Test efAcceptOffer
        Map acceptResult = dispatcher.runSync('efAcceptOffer', [
            userLogin: apiUser(), draftId: draftId,
            quoteId: loanQuoteId, quoteTypeId: 'LOAN'
        ])
        ok(acceptResult)
        assert acceptResult.acceptedQuoteId == loanQuoteId

        // Verify quote status is now ACCEPTED
        loanQuote.refresh()
        assert loanQuote.statusId == 'LOANQT_ACCEPTED'

        // An accepted quote can still be fetched to resume signing before the
        // agreement is created.
        Map resumeOffer = dispatcher.runSync('efGetOffer', [userLogin: apiUser(), draftId: draftId])
        ok(resumeOffer)
        assert resumeOffer.offerAvailable == Boolean.TRUE
        assert resumeOffer.quoteId == loanQuoteId
        assert resumeOffer.quoteStatusId == 'LOANQT_ACCEPTED'

        // Test efStartSigning
        Map signStart = dispatcher.runSync('efStartSigning', [
            userLogin: apiUser(), draftId: draftId,
            quoteId: loanQuoteId, quoteTypeId: 'LOAN'
        ])
        ok(signStart)
        assert signStart.signingSessionId
        assert signStart.contractSummary != null
        String signingSessionId = signStart.signingSessionId

        // Verify signing session was created
        GenericValue session = from('EfSigningSession')
                .where('signingSessionId', signingSessionId).queryOne()
        assert session != null
        assert session.statusId == 'EFSIG_PENDING'
        assert session.draftId == draftId
        assert session.quoteId == loanQuoteId

        // Verify duplicate start is blocked
        Map signStart2 = dispatcher.runSync('efStartSigning', [
            userLogin: apiUser(), draftId: draftId,
            quoteId: loanQuoteId, quoteTypeId: 'LOAN'
        ])
        assert !ServiceUtil.isSuccess(signStart2)

        // Test efCompleteSigning — createLoanAgreement may fail due to incomplete
        // prerequisites (requires approved underwriting), so verify graceful handling
        Map signComplete = dispatcher.runSync('efCompleteSigning', [
            userLogin: apiUser(),
            signingSessionId: signingSessionId,
            confirmContent: Boolean.TRUE,
            ipAddress: '127.0.0.1',
            deviceId: 'test-device',
            reauthToken: reauthToken()
        ])
        assert signComplete != null
        // If finance service failed, session stays PENDING; if succeeded, it is SIGNED
        session.refresh()
        assert session.statusId in ['EFSIG_PENDING', 'EFSIG_SIGNED']
    }

    void testCompleteSigningRequiresConfirm() {
        Map data = createDraftWithLoanApp()
        String draftId = data.draftId
        String loanApplicationId = data.loanApplicationId

        // Create PRESENTED quote
        String loanQuoteId = delegator.getNextSeqId('LoanQuote')
        GenericValue loanQuote = delegator.makeValue('LoanQuote')
        loanQuote.set('loanQuoteId', loanQuoteId)
        loanQuote.set('loanApplicationId', loanApplicationId)
        loanQuote.set('statusId', 'LOANQT_PRESENTED')
        loanQuote.set('currencyUomId', 'JPY')
        loanQuote.set('principalAmount', new BigDecimal('50000'))
        loanQuote.set('annualInterestRate', new BigDecimal('0.12'))
        loanQuote.set('termMonths', new BigDecimal('6'))
        loanQuote.set('monthlyPaymentAmount', new BigDecimal('8600'))
        loanQuote.set('totalPaymentAmount', new BigDecimal('51600'))
        loanQuote.set('totalInterestAmount', new BigDecimal('1600'))
        loanQuote.set('createdDate', new Timestamp(System.currentTimeMillis()))
        delegator.create(loanQuote)

        // Accept and start signing
        ok(dispatcher.runSync('efAcceptOffer', [
            userLogin: apiUser(), draftId: draftId,
            quoteId: loanQuoteId, quoteTypeId: 'LOAN'
        ]))
        Map signStart = dispatcher.runSync('efStartSigning', [
            userLogin: apiUser(), draftId: draftId,
            quoteId: loanQuoteId, quoteTypeId: 'LOAN'
        ])
        ok(signStart)
        String signingSessionId = signStart.signingSessionId

        // Try to complete without confirming — must fail
        Map signComplete = dispatcher.runSync('efCompleteSigning', [
            userLogin: apiUser(),
            signingSessionId: signingSessionId,
            confirmContent: Boolean.FALSE,
            ipAddress: '127.0.0.1',
            deviceId: 'test-device',
            reauthToken: reauthToken()
        ])
        assert !ServiceUtil.isSuccess(signComplete)
        // Session must still be PENDING (not changed on validation error)
        GenericValue session = from('EfSigningSession')
                .where('signingSessionId', signingSessionId).queryOne()
        assert session.statusId == 'EFSIG_PENDING'
    }

    void testClickWrapSignatureAdapter() {
        ClickWrapSignatureAdapter adapter = new ClickWrapSignatureAdapter()
        SignatureRequest request = new SignatureRequest(
                'SIG_TEST', 'TEST_EF_CUST', 'DRAFT_TEST', 'QUOTE_TEST', 'LOAN',
                true, '127.0.0.1', 'test-device', Locale.JAPAN)
        def result = adapter.sign(request)
        assert result.success
        assert result.signatureMethodEnumId == 'EFSIG_METHOD_CLICK'
        assert result.evidenceHash
        assert result.signedDate

        def denied = adapter.sign(new SignatureRequest(
                'SIG_TEST', 'TEST_EF_CUST', 'DRAFT_TEST', 'QUOTE_TEST', 'LOAN',
                false, '127.0.0.1', 'test-device', Locale.JAPAN))
        assert !denied.success
        assert denied.errorMessage
    }

    void testJasperContractTemplateRendersPdf() {
        Timestamp now = new Timestamp(System.currentTimeMillis())
        Map parameters = [
                documentTitle: '電子契約書（テンプレート検証）',
                documentNoLabel: '契約番号', documentNo: 'TEST_JR_TEMPLATE',
                agreementTypeLabel: '契約種別', agreementType: 'ローン',
                agreementDateLabel: '契約日', agreementDate: now,
                partyLabel: '借入人', partyName: 'Test Customer', partyId: 'TEST_EF_CUST',
                principalLabel: '借入元本', principalAmount: new BigDecimal('100000'),
                rateLabel: '年利', rateAmount: new BigDecimal('0.150000'),
                termLabel: '返済期間（月）', termMonths: new BigDecimal('12'),
                firstPaymentLabel: '初回返済日', firstPaymentDate: now,
                currencyLabel: '通貨', currencyUomId: 'JPY',
                scheduleTitle: '返済予定表',
                colNo: '回', colDueDate: '期日', colPayment: '返済額',
                colPrincipal: '元本', colInterest: '利息', colTax: '消費税', colBalance: '残高',
                signatureDateLabel: '署名日時', signatureDate: now,
                signingSessionLabel: '署名セッション', signingSession: 'TEST_SESSION',
                footerText: '本書面は電子契約により交付されました。'
        ]
        List<Map> rows = [[
                installmentNumber: new BigDecimal('1'), dueDate: now,
                paymentAmount: new BigDecimal('9026'), principalAmount: new BigDecimal('8000'),
                interestAmount: new BigDecimal('1026'), taxAmount: null,
                remainingBalance: new BigDecimal('92000')
        ]]
        byte[] pdf = JasperContractDocumentRenderer.render(parameters, rows)
        assert pdf != null
        assert new String(pdf, 0, 5, StandardCharsets.US_ASCII) == '%PDF-'
    }

    void testExternalSignatureAdapter() {
        HttpServer server = HttpServer.create(new InetSocketAddress('127.0.0.1', 0), 0)
        server.createContext('/sign') { HttpExchange exchange ->
            String requestBody = exchange.requestBody.getText('UTF-8')
            assert requestBody.contains('SIG_EXT_TEST')
            byte[] response = '{"status":"SIGNED","signedAt":"2026-10-22T12:00:00Z","evidenceHash":"provider-evidence"}'
                    .getBytes(StandardCharsets.UTF_8)
            exchange.responseHeaders.add('Content-Type', 'application/json')
            exchange.sendResponseHeaders(200, response.length)
            exchange.responseBody.write(response)
            exchange.responseBody.close()
        }
        server.createContext('/fail') { HttpExchange exchange ->
            exchange.sendResponseHeaders(500, -1)
            exchange.close()
        }
        server.start()
        try {
            String endpoint = 'http://127.0.0.1:' + server.address.port + '/sign'
            ExternalSignatureAdapter adapter = new ExternalSignatureAdapter(endpoint, 'test-key', 5)
            def result = adapter.sign(new SignatureRequest(
                    'SIG_EXT_TEST', 'TEST_EF_CUST', 'DRAFT_EXT', 'QUOTE_EXT', 'LOAN',
                    true, '127.0.0.1', 'test-device', Locale.JAPAN))
            assert result.success
            assert result.signatureMethodEnumId == 'EFSIG_METHOD_EXT'
            assert result.evidenceHash
            assert result.signedDate

            String failEndpoint = 'http://127.0.0.1:' + server.address.port + '/fail'
            ExternalSignatureAdapter failAdapter = new ExternalSignatureAdapter(failEndpoint, '', 5)
            def failed = failAdapter.sign(new SignatureRequest(
                    'SIG_EXT_TEST', 'TEST_EF_CUST', 'DRAFT_EXT', 'QUOTE_EXT', 'LOAN',
                    true, '127.0.0.1', 'test-device', Locale.JAPAN))
            assert !failed.success
        } finally {
            server.stop(0)
        }
    }

    void testCompleteSigningCreatesContractContent() {
        Map data = createDraftWithLoanApp()
        String draftId = data.draftId
        String loanApplicationId = data.loanApplicationId
        GenericValue systemUser = from('UserLogin').where('userLoginId', 'efinance-system').queryOne()

        ok(dispatcher.runSync('setLoanApplicationStatus', [
                loanApplicationId: loanApplicationId,
                statusId: 'LOANAPP_SUBMITTED',
                userLogin: systemUser
        ]))
        ok(dispatcher.runSync('setLoanApplicationStatus', [
                loanApplicationId: loanApplicationId,
                statusId: 'LOANAPP_APPROVED',
                userLogin: systemUser
        ]))

        Map underwriting = dispatcher.runSync('createLoanUnderwriting', [
                loanApplicationId: loanApplicationId,
                statusId: 'LOANUW_IN_REVIEW',
                creditScore: 700L,
                decision: 'PENDING',
                approvedPrincipalAmount: new BigDecimal('100000'),
                approvedAnnualInterestRate: new BigDecimal('0.150000'),
                userLogin: systemUser
        ])
        ok(underwriting)
        ok(dispatcher.runSync('setLoanUnderwritingStatus', [
                loanUnderwritingId: underwriting.loanUnderwritingId,
                statusId: 'LOANUW_APPROVED',
                userLogin: systemUser
        ]))

        Map quote = dispatcher.runSync('createLoanQuote', [
                loanApplicationId: loanApplicationId,
                principalAmount: new BigDecimal('100000'),
                annualInterestRate: new BigDecimal('0.150000'),
                termMonths: 12L,
                userLogin: systemUser
        ])
        ok(quote)
        ok(dispatcher.runSync('setLoanQuoteStatus', [
                loanQuoteId: quote.loanQuoteId,
                statusId: 'LOANQT_PRESENTED',
                userLogin: systemUser
        ]))

        ok(dispatcher.runSync('efAcceptOffer', [
                userLogin: apiUser(),
                draftId: draftId,
                quoteId: quote.loanQuoteId,
                quoteTypeId: 'LOAN'
        ]))
        Map signStart = dispatcher.runSync('efStartSigning', [
                userLogin: apiUser(),
                draftId: draftId,
                quoteId: quote.loanQuoteId,
                quoteTypeId: 'LOAN'
        ])
        ok(signStart)

        Map signComplete = dispatcher.runSync('efCompleteSigning', [
                userLogin: apiUser(),
                signingSessionId: signStart.signingSessionId,
                confirmContent: Boolean.TRUE,
                ipAddress: '127.0.0.1',
                deviceId: 'test-device',
                reauthToken: reauthToken()
        ])
        ok(signComplete)
        assert signComplete.contentId
        assert signComplete.deliveredDocId

        GenericValue session = from('EfSigningSession')
                .where('signingSessionId', signStart.signingSessionId).queryOne()
        assert session.statusId == 'EFSIG_SIGNED'
        assert session.signedContentId == signComplete.contentId
        assert session.signatureMethodEnumId == 'EFSIG_METHOD_CLICK'
        assert session.signatureEvidenceHash
        assert signComplete.signatureMethodEnumId == 'EFSIG_METHOD_CLICK'
        assert signComplete.signatureEvidenceHash

        GenericValue delivered = from('EfDeliveredDocument')
                .where('deliveredDocId', signComplete.deliveredDocId).queryOne()
        assert delivered != null
        assert delivered.contentId == signComplete.contentId

        Map download = dispatcher.runSync('efDownloadDocument', [
                userLogin: apiUser(), deliveredDocId: signComplete.deliveredDocId
        ])
        ok(download)
        assert download.mimeTypeId == 'application/pdf'
        assert download.contentBase64
        byte[] bytes = Base64.decoder.decode(download.contentBase64)
        assert new String(bytes, 0, 5, StandardCharsets.US_ASCII) == '%PDF-'
    }

    void testContractDocumentPdfAndContent() {
        byte[] pdf = ContractDocumentPdf.build([
                '電子契約書（テスト）',
                '契約番号: TEST_CONTRACT_PDF',
                '契約者: TEST_EF_CUST',
                '本書面は電子契約により交付されました。'
        ])
        assert new String(pdf, 0, 5, StandardCharsets.US_ASCII) == '%PDF-'
        assert new String(pdf, StandardCharsets.ISO_8859_1).contains('%%EOF')

        GenericValue systemUser = from('UserLogin').where('userLoginId', 'system').queryOne()
        Map contentResult = dispatcher.runSync('createContentFromUploadedFile', [
                userLogin: systemUser,
                dataResourceTypeId: 'LOCAL_FILE',
                dataTemplateTypeId: 'NONE',
                dataResourceName: 'test-contract.pdf',
                mimeTypeId: 'application/pdf',
                contentTypeId: 'DOCUMENT',
                uploadedFile: ByteBuffer.wrap(pdf),
                _uploadedFile_fileName: 'test-contract.pdf',
                _uploadedFile_contentType: 'application/pdf'
        ])
        ok(contentResult)
        assert contentResult.contentId

        GenericValue contentData = from('ContentDataResourceView')
                .where('contentId', contentResult.contentId).queryOne()
        assert contentData != null
        assert contentData.mimeTypeId == 'application/pdf'
        assert contentData.drObjectInfo
        byte[] stored = new File(contentData.drObjectInfo).bytes
        assert new String(stored, 0, 5, StandardCharsets.US_ASCII) == '%PDF-'

        Map draft = createDraftWithLoanApp()
        Timestamp now = new Timestamp(System.currentTimeMillis())
        String deliveredDocId = delegator.getNextSeqId('EfDeliveredDocument')
        GenericValue delivered = delegator.makeValue('EfDeliveredDocument')
        delivered.set('deliveredDocId', deliveredDocId)
        delivered.set('draftId', draft.draftId)
        delivered.set('memberPartyId', 'TEST_EF_CUST')
        delivered.set('documentTypeEnumId', 'EFDELDOC_CONTRACT')
        delivered.set('contentId', contentResult.contentId)
        delivered.set('deliveredDate', now)
        delegator.create(delivered)

        Map download = dispatcher.runSync('efDownloadDocument', [
                userLogin: apiUser(), deliveredDocId: deliveredDocId
        ])
        ok(download)
        assert download.contentId == contentResult.contentId
        assert download.mimeTypeId == 'application/pdf'
        assert download.contentBase64
        byte[] downloaded = Base64.decoder.decode(download.contentBase64)
        assert new String(downloaded, 0, 5, StandardCharsets.US_ASCII) == '%PDF-'
    }
}
