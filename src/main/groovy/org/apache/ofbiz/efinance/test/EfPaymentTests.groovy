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

import java.math.BigDecimal
import java.sql.Timestamp

import org.apache.ofbiz.base.util.UtilDateTime
import org.apache.ofbiz.entity.GenericValue
import org.apache.ofbiz.service.ServiceUtil
import org.apache.ofbiz.service.testtools.OFBizTestCase

class EfPaymentTests extends OFBizTestCase {

    EfPaymentTests(String name) {
        super(name)
    }

    private GenericValue apiUser() {
        return from('UserLogin').where('userLoginId', 'test-ef-customer').queryOne()
    }

    private GenericValue admin() {
        return from('UserLogin').where('userLoginId', 'system').queryOne()
    }

    private void ok(Map result) {
        assert ServiceUtil.isSuccess(result), ServiceUtil.getErrorMessage(result)
    }

    private String reauthToken() {
        Map result = dispatcher.runSync('efReauthenticate', [
                userLogin: apiUser(),
                password: 'ofbiz',
                ipAddress: '127.0.0.1',
                deviceId: 'payment-test'
        ])
        ok(result)
        return result.reauthToken
    }

    private String createAgreementWithSchedule() {
        Timestamp now = UtilDateTime.nowTimestamp()
        String agreementId = 'TEST_EF_PAY_' + (System.currentTimeMillis() % 100000)
        GenericValue agreement = delegator.makeValue('LoanAgreement')
        agreement.set('loanAgreementId', agreementId)
        agreement.set('statusId', 'LOANAGR_ACTIVE')
        agreement.set('borrowerPartyId', 'TEST_EF_CUST')
        agreement.set('currencyUomId', 'JPY')
        agreement.set('principalAmount', new BigDecimal('100000'))
        agreement.set('annualInterestRate', new BigDecimal('0.120000'))
        agreement.set('termMonths', 2L)
        agreement.set('agreementDate', now)
        agreement.set('firstPaymentDate', now)
        delegator.create(agreement)

        GenericValue first = delegator.makeValue('LoanAgreementRepaymentSchedule')
        first.set('loanAgreementId', agreementId)
        first.set('scheduleRevisionNumber', 1L)
        first.set('installmentNumber', 1L)
        first.set('dueDate', now)
        first.set('paymentAmount', new BigDecimal('51000'))
        first.set('principalAmount', new BigDecimal('50000'))
        first.set('interestAmount', new BigDecimal('1000'))
        first.set('remainingBalance', new BigDecimal('50000'))
        delegator.create(first)

        GenericValue second = delegator.makeValue('LoanAgreementRepaymentSchedule')
        second.set('loanAgreementId', agreementId)
        second.set('scheduleRevisionNumber', 1L)
        second.set('installmentNumber', 2L)
        second.set('dueDate', now)
        second.set('paymentAmount', new BigDecimal('50500'))
        second.set('principalAmount', new BigDecimal('50000'))
        second.set('interestAmount', new BigDecimal('500'))
        second.set('remainingBalance', BigDecimal.ZERO)
        delegator.create(second)
        return agreementId
    }

    void testDirectDebitAndPaymentInstructions() {
        String agreementId = createAgreementWithSchedule()

        Map requested = dispatcher.runSync('efRequestDirectDebit', [
                userLogin: apiUser(),
                agreementId: agreementId,
                agreementTypeId: 'LOAN',
                bankName: 'Test Bank',
                branchName: 'Head Office',
                accountTypeEnumId: 'EFDD_ACC_ORDINARY',
                accountNumber: '1234567',
                accountHolderName: 'Test Customer',
                reauthToken: reauthToken()
        ])
        ok(requested)
        assert requested.mandateId
        assert requested.accountNumberLast4 == '4567'
        assert requested.statusId == 'EFDD_REQUESTED'

        Map mandates = dispatcher.runSync('efGetDirectDebit', [userLogin: apiUser()])
        ok(mandates)
        assert mandates.mandates.any { it.mandateId == requested.mandateId
                && it.accountNumberLast4 == '4567' }

        Map instructions = dispatcher.runSync('efGetPaymentInstructions', [
                userLogin: apiUser(), agreementId: agreementId, agreementTypeId: 'LOAN'
        ])
        ok(instructions)
        assert instructions.contractNo == agreementId
        assert instructions.bank.bankName == 'Sample Bank'
        assert instructions.instructions.size() == 2
        assert instructions.instructions[0].amount == new BigDecimal('51000')

        Map registered = dispatcher.runSync('efUpdateDirectDebitStatus', [
                userLogin: admin(),
                mandateId: requested.mandateId,
                statusId: 'EFDD_REGISTERED',
                staffNote: 'Registered by test staff'
        ])
        ok(registered)
        GenericValue mandate = from('EfDirectDebitMandate')
                .where('mandateId', requested.mandateId).queryOne()
        assert mandate.statusId == 'EFDD_REGISTERED'
        assert mandate.accountNumber == '1234567'
    }
}
