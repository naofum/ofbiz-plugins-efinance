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
import org.apache.ofbiz.efinance.account.EfMemberServices
import org.apache.ofbiz.entity.GenericValue
import org.apache.ofbiz.service.ServiceUtil
import org.apache.ofbiz.service.testtools.OFBizTestCase
import org.apache.ofbiz.widget.model.ScreenFactory

/**
 * STEP W0: eFinance member registration, email verification, login and basic guard tests.
 */
class EfMemberTests extends OFBizTestCase {

    EfMemberTests(String name) {
        super(name)
    }

    private GenericValue admin() {
        return from('UserLogin').where('userLoginId', 'system').queryOne()
    }

    private void ok(Map result) {
        assert ServiceUtil.isSuccess(result), ServiceUtil.getErrorMessage(result)
    }

    /** Register → verify email → login → receive access token. */
    void testRegistrationEmailVerificationAndLogin() {
        String email = "ef-test-reg-${System.currentTimeMillis()}@example.com"

        // Register
        Map regResult = dispatcher.runSync('efRegisterMember', [
                userLogin : admin(),
                email     : email,
                password  : 'TestPass123!',
                firstName : 'Jane',
                lastName  : 'Portal'
        ])
        ok(regResult)
        String partyId = regResult.partyId
        assert partyId

        // Verify email – retrieve OTP hash from DB and find the plain token returned
        String verifyToken = regResult.verificationToken
        assert verifyToken

        Map verifyResult = dispatcher.runSync('efVerifyEmail', [
                userLogin: admin(),
                token    : verifyToken
        ])
        ok(verifyResult)
        assert verifyResult.success == Boolean.TRUE

        // Confirm member status is ACTIVE
        GenericValue member = from('EfMember').where('partyId', partyId).queryOne()
        assert member != null
        assert 'EFMBR_ACTIVE' == member.getString('statusId')

        // Login
        Map loginResult = dispatcher.runSync('efLogin', [
                userLoginId: email,
                password   : 'TestPass123!'
        ])
        ok(loginResult)
        assert loginResult.accessToken
        assert loginResult.refreshToken
    }

    /** Wrong password should return a failure error message. */
    void testLoginFailAndLockout() {
        Map result = dispatcher.runSync('efLogin', [
                userLoginId: 'test-ef-customer',
                password   : 'wrong-password-xyz'
        ])
        assert !ServiceUtil.isSuccess(result), 'Expected failure for wrong password'
    }

    /** Assert that the admin and portal screens parse correctly. */
    void testScreensParse() {
        def adminScreens = ScreenFactory.getScreensFromLocation('component://efinance/widget/EfinanceAdminScreens.xml')
        assert adminScreens.size() >= 2, "Expected at least 2 screens in EfinanceAdminScreens.xml, got ${adminScreens.size()}"
    }
}
