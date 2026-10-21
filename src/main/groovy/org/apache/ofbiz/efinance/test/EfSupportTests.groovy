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
 * efinance -> support customer API bridge.
 */
class EfSupportTests extends OFBizTestCase {

    EfSupportTests(String name) {
        super(name)
    }

    private GenericValue apiUser() {
        return from('UserLogin').where('userLoginId', 'test-ef-customer').queryOne()
    }

    private void ok(Map result) {
        assert ServiceUtil.isSuccess(result), ServiceUtil.getErrorMessage(result)
    }

    void testCreateListGetReplyAndClose() {
        String externalId = 'EF-SUP-' + System.currentTimeMillis()
        Map created = dispatcher.runSync('efCreateSupportCase', [
                userLogin: apiUser(),
                subject: 'eFinance support integration test',
                description: 'Case created through the efinance gateway',
                externalId: externalId
        ])
        ok(created)
        assert created.supportCaseId
        String supportCaseId = created.supportCaseId

        Map listed = dispatcher.runSync('efListSupportCases', [userLogin: apiUser()])
        ok(listed)
        assert listed.supportCases.any { it.supportCaseId == supportCaseId }

        Map detailed = dispatcher.runSync('efGetSupportCase', [
                userLogin: apiUser(), supportCaseId: supportCaseId
        ])
        ok(detailed)
        assert detailed.supportCase.supportCaseId == supportCaseId

        Map replied = dispatcher.runSync('efReplySupportCase', [
                userLogin: apiUser(),
                supportCaseId: supportCaseId,
                subject: 'Additional information',
                content: 'Customer reply through efinance'
        ])
        ok(replied)
        assert replied.communicationEventId

        Map closed = dispatcher.runSync('efCloseSupportCase', [
                userLogin: apiUser(), supportCaseId: supportCaseId, reason: 'Resolved by customer'
        ])
        ok(closed)
        assert closed.statusId in ['SUPCS_CLOSED', 'SUPCS_CANCELLED']

        Map options = dispatcher.runSync('efGetSupportFormOptions', [userLogin: apiUser()])
        ok(options)
        assert options.caseTypes != null
    }
}
