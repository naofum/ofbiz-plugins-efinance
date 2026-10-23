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
import java.sql.Timestamp
import org.apache.ofbiz.entity.GenericValue
import org.apache.ofbiz.service.ServiceUtil
import org.apache.ofbiz.service.testtools.OFBizTestCase

class EfMyPageTests extends OFBizTestCase {

    EfMyPageTests(String name) { super(name) }

    private GenericValue apiUser() {
        from('UserLogin').where('userLoginId', 'test-ef-customer').queryOne()
    }

    private GenericValue adminUser() {
        from('UserLogin').where('userLoginId', 'system').queryOne()
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

    void testListApplicationsEmpty() {
        Map result = dispatcher.runSync('efListApplications', [userLogin: apiUser()])
        ok(result)
        assert result.applications != null
    }

    void testListContractsEmpty() {
        Map result = dispatcher.runSync('efListContracts', [userLogin: apiUser()])
        ok(result)
        assert result.contracts != null
    }

    void testGetContractNotFound() {
        Map result = dispatcher.runSync('efGetContract', [
            userLogin: apiUser(),
            agreementId: 'NONEXISTENT_XYZ',
            agreementTypeId: 'LOAN'
        ])
        assert !ServiceUtil.isSuccess(result)
    }

    void testSimulatePrepaymentWithoutContract() {
        Map result = dispatcher.runSync('efSimulatePrepayment', [
            userLogin: apiUser(),
            agreementId: 'NONEXISTENT_XYZ'
        ])
        assert !ServiceUtil.isSuccess(result)
    }

    void testListAndSendMessages() {
        Map list = dispatcher.runSync('efListMessages', [userLogin: apiUser()])
        ok(list)
        assert list.messages != null

        Map send = dispatcher.runSync('efSendMessage', [
            userLogin: apiUser(),
            subject: 'Test inquiry',
            content: 'This is a test message from the portal.'
        ])
        ok(send)
        assert send.communicationEventId

        Map list2 = dispatcher.runSync('efListMessages', [userLogin: apiUser()])
        ok(list2)
        assert list2.messages.size() >= 1
    }

    void testGetAndUpdateNotificationSettings() {
        Map get = dispatcher.runSync('efGetNotificationSettings', [userLogin: apiUser()])
        ok(get)
        assert get.settings != null
        assert get.settings.size() > 0

        Map update = dispatcher.runSync('efUpdateNotificationSettings', [
            userLogin: apiUser(),
            notificationTypeEnumId: 'EFNOTIF_PAYMENT',
            emailEnabled: Boolean.TRUE,
            smsEnabled: Boolean.FALSE
        ])
        ok(update)

        Map get2 = dispatcher.runSync('efGetNotificationSettings', [userLogin: apiUser()])
        ok(get2)
        def paymentSetting = get2.settings.find { it.notificationTypeEnumId == 'EFNOTIF_PAYMENT' }
        assert paymentSetting != null
        assert paymentSetting.emailEnabled == 'Y'
        assert paymentSetting.smsEnabled == 'N'
    }

    void testCreateServiceRequestsWithManualAgreement() {
        Timestamp now = new Timestamp(System.currentTimeMillis())

        // Create LoanApplication directly
        String loanApplicationId = delegator.getNextSeqId('LoanApplication')
        GenericValue loanApp = delegator.makeValue('LoanApplication')
        loanApp.set('loanApplicationId', loanApplicationId)
        loanApp.set('productId', 'DEMO_LOANPROD1')
        loanApp.set('applicantPartyId', 'TEST_EF_CUST')
        loanApp.set('statusId', 'LOANAPP_RECEIVED')
        loanApp.set('currencyUomId', 'JPY')
        loanApp.set('requestedPrincipalAmount', new BigDecimal('100000'))
        loanApp.set('requestedTermMonths', new BigDecimal('12'))
        loanApp.set('applicationDate', now)
        delegator.create(loanApp)

        // Create LoanQuote
        String loanQuoteId = delegator.getNextSeqId('LoanQuote')
        GenericValue loanQuote = delegator.makeValue('LoanQuote')
        loanQuote.set('loanQuoteId', loanQuoteId)
        loanQuote.set('loanApplicationId', loanApplicationId)
        loanQuote.set('statusId', 'LOANQT_ACCEPTED')
        loanQuote.set('currencyUomId', 'JPY')
        loanQuote.set('principalAmount', new BigDecimal('100000'))
        loanQuote.set('annualInterestRate', new BigDecimal('0.150000'))
        loanQuote.set('termMonths', new BigDecimal('12'))
        loanQuote.set('monthlyPaymentAmount', new BigDecimal('9026'))
        loanQuote.set('totalPaymentAmount', new BigDecimal('108312'))
        loanQuote.set('totalInterestAmount', new BigDecimal('8312'))
        loanQuote.set('createdDate', now)
        delegator.create(loanQuote)

        // Create LoanAgreement
        String loanAgreementId = delegator.getNextSeqId('LoanAgreement')
        GenericValue loanAgreement = delegator.makeValue('LoanAgreement')
        loanAgreement.set('loanAgreementId', loanAgreementId)
        loanAgreement.set('loanApplicationId', loanApplicationId)
        loanAgreement.set('loanQuoteId', loanQuoteId)
        loanAgreement.set('statusId', 'LOANAGR_ACTIVE')
        loanAgreement.set('borrowerPartyId', 'TEST_EF_CUST')
        loanAgreement.set('currencyUomId', 'JPY')
        loanAgreement.set('principalAmount', new BigDecimal('100000'))
        loanAgreement.set('annualInterestRate', new BigDecimal('0.150000'))
        loanAgreement.set('termMonths', new BigDecimal('12'))
        loanAgreement.set('agreementDate', now)
        delegator.create(loanAgreement)

        // Create EfApplicationLink (draftId not required to point to real draft)
        String draftId = 'TEST_W6_DRAFT_' + loanAgreementId
        String linkId = delegator.getNextSeqId('EfApplicationLink')
        GenericValue link = delegator.makeValue('EfApplicationLink')
        link.set('linkId', linkId)
        link.set('draftId', draftId)
        link.set('memberPartyId', 'TEST_EF_CUST')
        link.set('financeApplicationId', loanApplicationId)
        link.set('financeApplicationTypeId', 'LOAN')
        link.set('linkedDate', now)
        delegator.create(link)

        // Test efListApplications – should find the one we created
        Map listApps = dispatcher.runSync('efListApplications', [userLogin: apiUser()])
        ok(listApps)
        assert listApps.applications.any { it.financeApplicationId == loanApplicationId }

        // Test efListContracts
        Map listContracts = dispatcher.runSync('efListContracts', [userLogin: apiUser()])
        ok(listContracts)
        assert listContracts.contracts.any { it.agreementId == loanAgreementId }

        // Payment fixture: interest is paid first, then 50,000 of principal.
        String invoiceId = delegator.getNextSeqId('Invoice')
        GenericValue invoice = delegator.makeValue('Invoice')
        invoice.set('invoiceId', invoiceId)
        invoice.set('invoiceTypeId', 'SALES_INVOICE')
        invoice.set('partyIdFrom', 'Company')
        invoice.set('partyId', 'TEST_EF_CUST')
        invoice.set('roleTypeId', 'BILL_TO_CUSTOMER')
        invoice.set('statusId', 'INVOICE_READY')
        invoice.set('invoiceDate', now)
        invoice.set('currencyUomId', 'JPY')
        delegator.create(invoice)

        String paymentId = delegator.getNextSeqId('Payment')
        GenericValue payment = delegator.makeValue('Payment')
        payment.set('paymentId', paymentId)
        payment.set('paymentTypeId', 'CUSTOMER_PAYMENT')
        payment.set('paymentMethodTypeId', 'EFT_ACCOUNT')
        payment.set('partyIdFrom', 'TEST_EF_CUST')
        payment.set('partyIdTo', 'Company')
        payment.set('statusId', 'PMNT_RECEIVED')
        payment.set('effectiveDate', now)
        payment.set('amount', new BigDecimal('51000.00'))
        payment.set('currencyUomId', 'JPY')
        delegator.create(payment)

        GenericValue application = delegator.makeValue('PaymentApplication')
        application.set('paymentApplicationId', delegator.getNextSeqId('PaymentApplication'))
        application.set('paymentId', paymentId)
        application.set('invoiceId', invoiceId)
        application.set('amountApplied', new BigDecimal('51000.00'))
        delegator.create(application)

        GenericValue installment = delegator.makeValue('LoanInstallmentInvoice')
        installment.set('loanAgreementId', loanAgreementId)
        installment.set('installmentNumber', 1L)
        installment.set('invoiceId', invoiceId)
        installment.set('loanQuoteId', loanQuoteId)
        installment.set('dueDate', now)
        installment.set('principalAmount', new BigDecimal('50000.00'))
        installment.set('interestAmount', new BigDecimal('1000.00'))
        installment.set('billedAmount', new BigDecimal('51000.00'))
        installment.set('createdDate', now)
        delegator.create(installment)

        // Test efGetContract
        Map getContract = dispatcher.runSync('efGetContract', [
            userLogin: apiUser(),
            agreementId: loanAgreementId,
            agreementTypeId: 'LOAN'
        ])
        ok(getContract)
        assert getContract.contract != null
        assert getContract.contract.agreementId == loanAgreementId
        assert getContract.balance.outstandingPrincipal.compareTo(new BigDecimal('50000.00')) == 0
        assert getContract.balance.installmentPrincipalPaid.compareTo(new BigDecimal('50000.00')) == 0
        assert getContract.balance.prepaymentPrincipalPaid.compareTo(BigDecimal.ZERO) == 0

        // Test efSimulatePrepayment (uses the live payment-based balance)
        Map simulate = dispatcher.runSync('efSimulatePrepayment', [
            userLogin: apiUser(),
            agreementId: loanAgreementId
        ])
        ok(simulate)
        assert simulate.outstandingPrincipal.compareTo(new BigDecimal('50000.00')) == 0

        // Test efRequestPrepayment
        Map prepay = dispatcher.runSync('efRequestPrepayment', [
            userLogin: apiUser(),
            agreementId: loanAgreementId,
            prepaymentTypeEnumId: 'FULL',
            prepaymentAmount: new BigDecimal('100000'),
            reauthToken: reauthToken()
        ])
        ok(prepay)
        assert prepay.serviceRequestId

        // Test efRequestModification
        Map modify = dispatcher.runSync('efRequestModification', [
            userLogin: apiUser(),
            agreementId: loanAgreementId,
            agreementTypeId: 'LOAN',
            requestContent: 'Please reduce the interest rate',
            reauthToken: reauthToken()
        ])
        ok(modify)
        assert modify.serviceRequestId

        // Test efRequestCertificate
        Map cert = dispatcher.runSync('efRequestCertificate', [
            userLogin: apiUser(),
            agreementId: loanAgreementId,
            agreementTypeId: 'LOAN'
        ])
        ok(cert)
        assert cert.serviceRequestId

        // Test efRequestProfileChange
        Map profile = dispatcher.runSync('efRequestProfileChange', [
            userLogin: apiUser(),
            changeType: 'ADDRESS',
            changeData: [postalCode: '100-0002', address: 'New address'],
            reauthToken: reauthToken()
        ])
        ok(profile)
        assert profile.serviceRequestId
    }
}
