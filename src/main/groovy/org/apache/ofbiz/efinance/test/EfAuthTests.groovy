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

import java.sql.Timestamp
import java.util.Locale

import org.apache.ofbiz.entity.GenericValue
import org.apache.ofbiz.efinance.security.EfAccessGuard
import org.apache.ofbiz.efinance.security.EfRequestValidator
import org.apache.ofbiz.service.ServiceUtil
import org.apache.ofbiz.service.testtools.OFBizTestCase

/**
 * Request-level token revocation and member-lock validation.
 */
class EfAuthTests extends OFBizTestCase {

    EfAuthTests(String name) {
        super(name)
    }

    private GenericValue apiUser() {
        return from('UserLogin').where('userLoginId', 'test-ef-customer').queryOne()
    }

    private void ok(Map result) {
        assert ServiceUtil.isSuccess(result), ServiceUtil.getErrorMessage(result)
    }

    private Map login() {
        Map result = dispatcher.runSync('efLogin', [
                userLoginId: 'test-ef-customer',
                password   : 'ofbiz',
                deviceId   : 'test-device',
                ipAddress  : '127.0.0.1'
        ])
        ok(result)
        assert result.accessToken
        return result
    }

    void testActiveTokenPassesAndRevokedTokenIsRejected() {
        Map result = login()
        String jwt = result.accessToken
        GenericValue user = apiUser()

        assert EfRequestValidator.validateRequest(delegator, user, jwt, Locale.US) == null

        GenericValue token = from('EfToken').where('tokenHash', EfAccessGuard.sha256(jwt)).queryOne()
        assert token != null
        token.revokedDate = new Timestamp(System.currentTimeMillis())
        token.store()

        String error = EfRequestValidator.validateRequest(delegator, user, jwt, Locale.US)
        assert error != null
        assert error.contains('revoked')
    }

    void testLockedMemberIsRejected() {
        Map result = login()
        String jwt = result.accessToken
        GenericValue user = apiUser()
        GenericValue member = from('EfMember').where('partyId', user.partyId).queryOne()
        String originalStatus = member.statusId
        try {
            member.statusId = 'EFMBR_LOCKED'
            member.store()

            String error = EfRequestValidator.validateRequest(delegator, user, jwt, Locale.US)
            assert error != null
            assert error.contains('locked')
        } finally {
            member.statusId = originalStatus
            member.store()
        }
    }
}
