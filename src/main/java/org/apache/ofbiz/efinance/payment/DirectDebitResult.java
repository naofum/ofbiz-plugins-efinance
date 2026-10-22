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
package org.apache.ofbiz.efinance.payment;

public final class DirectDebitResult {

    private final boolean success;
    private final String statusId;
    private final String adapterResult;
    private final String errorMessage;

    private DirectDebitResult(boolean success, String statusId, String adapterResult, String errorMessage) {
        this.success = success;
        this.statusId = statusId;
        this.adapterResult = adapterResult;
        this.errorMessage = errorMessage;
    }

    public static DirectDebitResult success(String statusId, String adapterResult) {
        return new DirectDebitResult(true, statusId, adapterResult, null);
    }

    public static DirectDebitResult failure(String errorMessage) {
        return new DirectDebitResult(false, null, null, errorMessage);
    }

    public boolean isSuccess() {
        return success;
    }

    public String getStatusId() {
        return statusId;
    }

    public String getAdapterResult() {
        return adapterResult;
    }

    public String getErrorMessage() {
        return errorMessage;
    }
}
