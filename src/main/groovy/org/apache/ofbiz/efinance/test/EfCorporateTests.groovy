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

import org.apache.ofbiz.base.util.UtilDateTime
import org.apache.ofbiz.entity.GenericValue
import org.apache.ofbiz.efinance.security.EfAccessGuard
import org.apache.ofbiz.service.ServiceUtil
import org.apache.ofbiz.service.testtools.OFBizTestCase

/**
 * Corporate account and role management.
 */
class EfCorporateTests extends OFBizTestCase {

    EfCorporateTests(String name) {
        super(name)
    }

    private GenericValue admin() {
        return from('UserLogin').where('userLoginId', 'system').queryOne()
    }

    private GenericValue apiUser() {
        return from('UserLogin').where('userLoginId', 'test-ef-customer').queryOne()
    }

    private void ok(Map result) {
        assert ServiceUtil.isSuccess(result), ServiceUtil.getErrorMessage(result)
    }

    void testCreateAndListCorporateAccount() {
        String corporatePartyId = 'TEST_EF_CORP_' + (System.currentTimeMillis() % 100000)
        Map created = dispatcher.runSync('efCreateCorporateAccount', [
                userLogin: admin(),
                corporatePartyId: corporatePartyId,
                adminMemberPartyId: 'TEST_EF_CUST',
                companyName: 'Test eFinance Corporation'
        ])
        ok(created)

        GenericValue account = from('EfCorporateAccount')
                .where('corporatePartyId', corporatePartyId).queryOne()
        assert account != null
        assert account.statusId == 'EFCORP_ACTIVE'

        GenericValue user = from('EfCorporateUser')
                .where('corporatePartyId', corporatePartyId, 'memberPartyId', 'TEST_EF_CUST').queryOne()
        assert user != null
        assert user.roleAdmin == 'Y'
        assert user.roleApply == 'Y'

        Map accounts = dispatcher.runSync('efListCorporateAccounts', [userLogin: apiUser()])
        ok(accounts)
        assert accounts.corporateAccounts.any { it.corporatePartyId == corporatePartyId }

        Map users = dispatcher.runSync('efListCorporateUsers', [
                userLogin: apiUser(), corporatePartyId: corporatePartyId
        ])
        ok(users)
        assert users.corporateUsers.any { it.memberPartyId == 'TEST_EF_CUST' }

        Map updated = dispatcher.runSync('efUpdateCorporateUser', [
                userLogin: apiUser(),
                corporatePartyId: corporatePartyId,
                memberPartyId: 'TEST_EF_CUST',
                roleApply: 'N',
                roleView: 'Y',
                rolePayment: 'Y',
                roleAdmin: 'Y'
        ])
        ok(updated)
        user.refresh()
        assert user.roleApply == 'N'
        assert user.roleAdmin == 'Y'
    }

    void testCorporateContractAccess() {
        String corporatePartyId = 'TEST_EF_CORP_' + (System.currentTimeMillis() % 100000)
        Map created = dispatcher.runSync('efCreateCorporateAccount', [
                userLogin: admin(),
                corporatePartyId: corporatePartyId,
                adminMemberPartyId: 'TEST_EF_CUST',
                companyName: 'Test eFinance Corporate Contract'
        ])
        ok(created)

        String loanAgreementId = 'TEST_EF_CAGR_' + (System.currentTimeMillis() % 100000)
        GenericValue agreement = delegator.makeValue('LoanAgreement')
        agreement.set('loanAgreementId', loanAgreementId)
        agreement.set('statusId', 'LOANAGR_ACTIVE')
        agreement.set('borrowerPartyId', corporatePartyId)
        agreement.set('currencyUomId', 'JPY')
        agreement.set('principalAmount', new java.math.BigDecimal('100000'))
        agreement.set('annualInterestRate', new java.math.BigDecimal('0.120000'))
        agreement.set('termMonths', 12L)
        agreement.set('agreementDate', UtilDateTime.nowTimestamp())
        agreement.set('firstPaymentDate', UtilDateTime.nowTimestamp())
        delegator.create(agreement)

        assert EfAccessGuard.isContractAccessible(delegator, agreement, 'TEST_EF_CUST', 'roleView')
        assert !EfAccessGuard.isContractAccessible(delegator, agreement, 'NOT_A_MEMBER', 'roleView')

        Map contracts = dispatcher.runSync('efListContracts', [userLogin: apiUser()])
        ok(contracts)
        assert contracts.contracts.any { it.agreementId == loanAgreementId }

        Map detail = dispatcher.runSync('efGetContract', [
                userLogin: apiUser(),
                agreementId: loanAgreementId,
                agreementTypeId: 'LOAN'
        ])
        ok(detail)
        assert detail.contract.agreementId == loanAgreementId
        assert detail.contract.principalAmount == new java.math.BigDecimal('100000')
    }
}
