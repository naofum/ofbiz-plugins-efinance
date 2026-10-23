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
import java.nio.charset.StandardCharsets

import org.apache.ofbiz.efinance.filescan.BasicFileScanAdapter
import org.apache.ofbiz.efinance.filescan.FileScanRequest
import org.apache.ofbiz.efinance.filescan.FileScanResult
import org.apache.ofbiz.efinance.filescan.NoopFileScanAdapter
import org.apache.ofbiz.service.testtools.OFBizTestCase

class EfFileScanTests extends OFBizTestCase {

    EfFileScanTests(String name) {
        super(name)
    }

    private FileScanRequest request(String content) {
        return new FileScanRequest('test.txt', 'text/plain',
                ByteBuffer.wrap(content.getBytes(StandardCharsets.UTF_8)))
    }

    void testNoopKeepsFilesPending() {
        FileScanResult result = new NoopFileScanAdapter().scan(request('ordinary content'))
        assert result.status == FileScanResult.STATUS_PENDING
    }

    void testBasicAdapterDetectsEicar() {
        BasicFileScanAdapter adapter = new BasicFileScanAdapter()
        FileScanResult normal = adapter.scan(request('ordinary content'))
        assert normal.status == FileScanResult.STATUS_OK

        String eicar = 'X5O!P%@AP[4\\PZX54(P^)7CC)7}$EICAR-STANDARD-ANTIVIRUS-TEST-FILE!$H+H*'
        FileScanResult infected = adapter.scan(request(eicar))
        assert infected.status == FileScanResult.STATUS_INFECTED
    }
}
