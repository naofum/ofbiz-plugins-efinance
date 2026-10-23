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

public final class FileScanResult {

    public static final String STATUS_PENDING = "EFSCAN_PENDING";
    public static final String STATUS_OK = "EFSCAN_OK";
    public static final String STATUS_INFECTED = "EFSCAN_INFECTED";

    private final String status;
    private final String message;
    private final String scannerReference;

    private FileScanResult(String status, String message, String scannerReference) {
        this.status = status;
        this.message = message;
        this.scannerReference = scannerReference;
    }

    public static FileScanResult pending(String message) {
        return new FileScanResult(STATUS_PENDING, message, null);
    }

    public static FileScanResult ok(String scannerReference) {
        return new FileScanResult(STATUS_OK, null, scannerReference);
    }

    public static FileScanResult infected(String message, String scannerReference) {
        return new FileScanResult(STATUS_INFECTED, message, scannerReference);
    }

    public String getStatus() {
        return status;
    }

    public String getMessage() {
        return message;
    }

    public String getScannerReference() {
        return scannerReference;
    }

    public boolean isInfected() {
        return STATUS_INFECTED.equals(status);
    }
}
