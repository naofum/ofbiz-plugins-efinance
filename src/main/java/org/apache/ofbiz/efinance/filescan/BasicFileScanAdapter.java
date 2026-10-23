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
package org.apache.ofbiz.efinance.filescan;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

/**
 * Development and test adapter. It detects the EICAR anti-virus test string and
 * otherwise accepts the file. Do not use it as a production scanner.
 */
public class BasicFileScanAdapter implements FileScanAdapter {

    private static final String EICAR_SIGNATURE =
            "X5O!P%@AP[4\\PZX54(P^)7CC)7}$EICAR-STANDARD-ANTIVIRUS-TEST-FILE!$H+H*";

    @Override
    public FileScanResult scan(FileScanRequest request) {
        ByteBuffer content = request.getContent();
        if (content == null || !content.hasRemaining()) {
            return FileScanResult.pending("Empty file content");
        }
        ByteBuffer copy = content.asReadOnlyBuffer();
        byte[] bytes = new byte[copy.remaining()];
        copy.get(bytes);
        String text = new String(bytes, StandardCharsets.ISO_8859_1);
        if (text.contains(EICAR_SIGNATURE)) {
            return FileScanResult.infected("EICAR test signature detected", "EICAR");
        }
        return FileScanResult.ok("BASIC");
    }
}
