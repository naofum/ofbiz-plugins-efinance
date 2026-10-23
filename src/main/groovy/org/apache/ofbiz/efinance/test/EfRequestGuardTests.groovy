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

import org.apache.ofbiz.efinance.security.EfRequestGuard
import org.apache.ofbiz.service.testtools.OFBizTestCase

class EfRequestGuardTests extends OFBizTestCase {

    EfRequestGuardTests(String name) {
        super(name)
    }

    void testRateLimitAndIdempotency() {
        String suffix = String.valueOf(System.currentTimeMillis())
        String subject = 'TEST_RL_' + suffix

        assert EfRequestGuard.checkRateLimit(delegator, 'TEST_RATE', subject, 2, 60)
        assert EfRequestGuard.checkRateLimit(delegator, 'TEST_RATE', subject, 2, 60)
        assert !EfRequestGuard.checkRateLimit(delegator, 'TEST_RATE', subject, 2, 60)

        String key = 'TEST_IDEM_' + suffix
        String requestHash = EfRequestGuard.hash(subject, 'draft-a')
        EfRequestGuard.IdempotencyResult first = EfRequestGuard.begin(
                delegator, subject, 'TEST_OPERATION', key, requestHash)
        assert first.state == EfRequestGuard.IdempotencyState.NEW
        EfRequestGuard.complete(delegator, first, 'RESULT-1')

        EfRequestGuard.IdempotencyResult replay = EfRequestGuard.begin(
                delegator, subject, 'TEST_OPERATION', key, requestHash)
        assert replay.state == EfRequestGuard.IdempotencyState.REPLAY
        assert replay.record.resultEntityId == 'RESULT-1'

        EfRequestGuard.IdempotencyResult conflict = EfRequestGuard.begin(
                delegator, subject, 'TEST_OPERATION', key, EfRequestGuard.hash(subject, 'draft-b'))
        assert conflict.state == EfRequestGuard.IdempotencyState.CONFLICT
    }
}
