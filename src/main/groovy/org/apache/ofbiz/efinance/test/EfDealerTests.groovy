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

class EfDealerTests extends OFBizTestCase {

    EfDealerTests(String name) {
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

    void testDealerPortalFlow() {
        String suffix = String.valueOf(System.currentTimeMillis() % 100000)
        Map created = dispatcher.runSync('efCreateDealer', [
                userLogin: admin(),
                dealerName: 'Test Dealer',
                dealerCode: 'TD' + suffix,
                adminPartyId: 'TEST_EF_CUST'
        ])
        ok(created)
        String dealerPartyId = created.dealerPartyId
        assert dealerPartyId

        Map profile = dispatcher.runSync('efGetDealerProfile', [userLogin: apiUser()])
            ok(profile)
            assert profile.dealers.any { it.dealerPartyId == dealerPartyId
                    && it.roleApply == 'Y' && it.roleView == 'Y' }

            Map consent = dispatcher.runSync('efRegisterDealerCustomer', [
                    userLogin: apiUser(),
                    dealerPartyId: dealerPartyId,
                    customerPartyId: 'TEST_EF_CUST',
                    consentReference: 'TEST-CONSENT-' + suffix
            ])
            ok(consent)
            assert consent.statusId == 'EFDEALER_CONSENT_OK'

            Map customers = dispatcher.runSync('efListDealerCustomers', [
                    userLogin: apiUser(), dealerPartyId: dealerPartyId
            ])
            ok(customers)
            assert customers.customers.any { it.customerPartyId == 'TEST_EF_CUST'
                    && it.statusId == 'EFDEALER_CONSENT_OK' }

            Map draft = dispatcher.runSync('efCreateDealerDraft', [
                    userLogin: apiUser(),
                    dealerPartyId: dealerPartyId,
                    customerPartyId: 'TEST_EF_CUST',
                    productId: 'DEMO_LOANPROD1',
                    applicationTypeId: 'LOAN'
            ])
            ok(draft)
            assert draft.draftId
            assert draft.existingDraft == false

            GenericValue draftRow = from('EfApplicationDraft').where('draftId', draft.draftId).queryOne()
            assert draftRow.memberPartyId == 'TEST_EF_CUST'
            assert draftRow.dealerPartyId == dealerPartyId
            assert draftRow.sourceEnumId == 'EFSRC_DEALER'

            Map drafts = dispatcher.runSync('efListDealerDrafts', [
                    userLogin: apiUser(), dealerPartyId: dealerPartyId
            ])
            ok(drafts)
            assert drafts.drafts.any { it.draftId == draft.draftId
                    && it.customerPartyId == 'TEST_EF_CUST' }

            Map applications = dispatcher.runSync('efListDealerApplications', [
                    userLogin: apiUser(), dealerPartyId: dealerPartyId
            ])
            ok(applications)
            assert applications.applications != null

            Map contracts = dispatcher.runSync('efListDealerContracts', [
                    userLogin: apiUser(), dealerPartyId: dealerPartyId
            ])
            ok(contracts)
            assert contracts.contracts != null
    }
}
