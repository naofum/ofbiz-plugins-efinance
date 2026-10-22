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

import java.util.Locale;

public final class DirectDebitRequest {

    private final String memberPartyId;
    private final String agreementId;
    private final String agreementTypeId;
    private final String bankName;
    private final String branchName;
    private final String accountTypeEnumId;
    private final String accountNumber;
    private final String accountHolderName;
    private final Locale locale;

    public DirectDebitRequest(String memberPartyId, String agreementId, String agreementTypeId,
            String bankName, String branchName, String accountTypeEnumId, String accountNumber,
            String accountHolderName, Locale locale) {
        this.memberPartyId = memberPartyId;
        this.agreementId = agreementId;
        this.agreementTypeId = agreementTypeId;
        this.bankName = bankName;
        this.branchName = branchName;
        this.accountTypeEnumId = accountTypeEnumId;
        this.accountNumber = accountNumber;
        this.accountHolderName = accountHolderName;
        this.locale = locale;
    }

    public String getMemberPartyId() {
        return memberPartyId;
    }

    public String getAgreementId() {
        return agreementId;
    }

    public String getAgreementTypeId() {
        return agreementTypeId;
    }

    public String getBankName() {
        return bankName;
    }

    public String getBranchName() {
        return branchName;
    }

    public String getAccountTypeEnumId() {
        return accountTypeEnumId;
    }

    public String getAccountNumber() {
        return accountNumber;
    }

    public String getAccountHolderName() {
        return accountHolderName;
    }

    public Locale getLocale() {
        return locale;
    }
}
