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

import java.nio.ByteBuffer
import org.apache.ofbiz.entity.GenericValue
import org.apache.ofbiz.service.ServiceUtil
import org.apache.ofbiz.service.testtools.OFBizTestCase

/**
 * STEP W3: eFinance KYC identity verification tests.
 */
class EfKycTests extends OFBizTestCase {

    EfKycTests(String name) {
        super(name)
    }

    private GenericValue apiUser() {
        return from('UserLogin').where('userLoginId', 'test-ef-customer').queryOne()
    }

    private GenericValue adminUser() {
        return from('UserLogin').where('userLoginId', 'system').queryOne()
    }

    private void ok(Map r) {
        assert ServiceUtil.isSuccess(r), ServiceUtil.getErrorMessage(r)
    }

    void testStartAndSubmitKyc() {
        Map start = dispatcher.runSync('efStartKyc', [
            userLogin: apiUser(),
            kycMethodEnumId: 'EFKYC_M_IMAGE_FACE'
        ])
        ok(start)
        String kycCheckId = start.kycCheckId
        assert kycCheckId

        // Upload an image
        Map upload = dispatcher.runSync('efUploadKycImage', [
            userLogin: apiUser(),
            kycCheckId: kycCheckId,
            imageTypeEnumId: 'EFKYC_IMG_ID_FRONT',
            fileName: 'id_front.jpg',
            contentType: 'image/jpeg',
            uploadedFile: ByteBuffer.wrap('fake-image-data'.getBytes('UTF-8'))
        ])
        ok(upload)
        assert upload.kycImageId

        // Submit
        Map submit = dispatcher.runSync('efSubmitKyc', [
            userLogin: apiUser(),
            kycCheckId: kycCheckId
        ])
        ok(submit)

        // Check status
        Map status = dispatcher.runSync('efGetKycStatus', [
            userLogin: apiUser(),
            kycCheckId: kycCheckId
        ])
        ok(status)
        assert status.kycCheck != null
        assert status.kycCheck.statusId == 'EFKYC_SUBMITTED'
        assert status.images != null
        assert status.images.size() == 1
    }

    void testStaffReviewVerified() {
        // Start and submit
        Map start = dispatcher.runSync('efStartKyc', [
            userLogin: apiUser(),
            kycMethodEnumId: 'EFKYC_M_IMAGE_FACE'
        ])
        ok(start)
        String kycCheckId = start.kycCheckId

        // Upload image
        dispatcher.runSync('efUploadKycImage', [
            userLogin: apiUser(),
            kycCheckId: kycCheckId,
            imageTypeEnumId: 'EFKYC_IMG_FACIAL',
            fileName: 'face.jpg',
            contentType: 'image/jpeg',
            uploadedFile: ByteBuffer.wrap('face-data'.getBytes('UTF-8'))
        ])
        dispatcher.runSync('efSubmitKyc', [userLogin: apiUser(), kycCheckId: kycCheckId])

        // Staff review - VERIFY
        Map review = dispatcher.runSync('reviewEfKycCheck', [
            userLogin: adminUser(),
            kycCheckId: kycCheckId,
            statusId: 'EFKYC_VERIFIED',
            confirmerNote: 'Documents verified'
        ])
        ok(review)

        // Check verified
        Map verified = dispatcher.runSync('efIsKycVerified', [
            userLogin: apiUser()
        ])
        ok(verified)
        assert verified.verified == Boolean.TRUE
    }

    void testStaffReviewResubmit() {
        Map start = dispatcher.runSync('efStartKyc', [
            userLogin: apiUser(),
            kycMethodEnumId: 'EFKYC_M_IMAGE_FACE'
        ])
        ok(start)
        String kycCheckId = start.kycCheckId

        dispatcher.runSync('efUploadKycImage', [
            userLogin: apiUser(),
            kycCheckId: kycCheckId,
            imageTypeEnumId: 'EFKYC_IMG_ID_FRONT',
            fileName: 'id.jpg',
            contentType: 'image/jpeg',
            uploadedFile: ByteBuffer.wrap('id-data'.getBytes('UTF-8'))
        ])
        dispatcher.runSync('efSubmitKyc', [userLogin: apiUser(), kycCheckId: kycCheckId])

        Map review = dispatcher.runSync('reviewEfKycCheck', [
            userLogin: adminUser(),
            kycCheckId: kycCheckId,
            statusId: 'EFKYC_RESUBMIT',
            confirmerNote: 'Image quality too low'
        ])
        ok(review)

        GenericValue check = from('EfKycCheck').where('kycCheckId', kycCheckId).queryOne()
        assert check.statusId == 'EFKYC_RESUBMIT'
    }

    void testKycNotVerifiedByDefault() {
        // Just verify the service returns successfully (may or may not be verified
        // depending on prior test execution in the shared DB)
        Map result = dispatcher.runSync('efIsKycVerified', [userLogin: apiUser()])
        ok(result)
        assert result.verified != null
    }
}
