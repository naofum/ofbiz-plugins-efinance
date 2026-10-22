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
import org.apache.ofbiz.service.ServiceUtil
import org.apache.ofbiz.service.testtools.OFBizTestCase

/**
 * Staff administration services.
 */
class EfAdminTests extends OFBizTestCase {

    EfAdminTests(String name) {
        super(name)
    }

    private GenericValue admin() {
        return from('UserLogin').where('userLoginId', 'system').queryOne()
    }

    private void ok(Map result) {
        assert ServiceUtil.isSuccess(result), ServiceUtil.getErrorMessage(result)
    }

    void testUnlockMember() {
        GenericValue member = from('EfMember').where('partyId', 'TEST_EF_CUST').queryOne()
        String originalStatus = member.statusId
        try {
            member.statusId = 'EFMBR_LOCKED'
            member.store()

            Map result = dispatcher.runSync('efUnlockMember', [
                    userLogin: admin(), partyId: 'TEST_EF_CUST'
            ])
            ok(result)
            member.refresh()
            assert member.statusId == 'EFMBR_ACTIVE'
        } finally {
            member.statusId = originalStatus
            member.store()
        }
    }

    void testUpdateServiceRequestStatus() {
        String serviceRequestId = delegator.getNextSeqId('EfServiceRequest')
        GenericValue request = delegator.makeValue('EfServiceRequest')
        request.set('serviceRequestId', serviceRequestId)
        request.set('memberPartyId', 'TEST_EF_CUST')
        request.set('requestTypeEnumId', 'EFSVCREQ_OTHER')
        request.set('statusId', 'EFSVCREQ_RECEIVED')
        request.set('subject', 'Admin test request')
        request.set('requestedByPartyId', 'TEST_EF_CUST')
        request.set('requestedDate', UtilDateTime.nowTimestamp())
        delegator.create(request)

        Map started = dispatcher.runSync('efUpdateServiceRequestStatus', [
                userLogin: admin(),
                serviceRequestId: serviceRequestId,
                statusId: 'EFSVCREQ_IN_PROG',
                assignedToPartyId: 'TEST_EF_CUST',
                staffNotes: 'Assigned to the test owner'
        ])
        ok(started)
        request.refresh()
        assert request.statusId == 'EFSVCREQ_IN_PROG'
        assert request.assignedToPartyId == 'TEST_EF_CUST'

        Map completed = dispatcher.runSync('efUpdateServiceRequestStatus', [
                userLogin: admin(),
                serviceRequestId: serviceRequestId,
                statusId: 'EFSVCREQ_COMPLETE',
                staffNotes: 'Completed'
        ])
        ok(completed)
        request.refresh()
        assert request.statusId == 'EFSVCREQ_COMPLETE'
        assert request.completedDate != null

        Map invalid = dispatcher.runSync('efUpdateServiceRequestStatus', [
                userLogin: admin(),
                serviceRequestId: serviceRequestId,
                statusId: 'EFSVCREQ_RECEIVED'
        ])
        assert !ServiceUtil.isSuccess(invalid)
    }

    void testListAuditLogs() {
        Map result = dispatcher.runSync('efListAuditLogs', [
                userLogin: admin(),
                partyId: 'TEST_EF_CUST',
                viewIndex: 0,
                viewSize: 50
        ])
        ok(result)
        assert result.auditLogs != null
        assert result.auditLogs.every { it.partyId == 'TEST_EF_CUST' }
    }
}
