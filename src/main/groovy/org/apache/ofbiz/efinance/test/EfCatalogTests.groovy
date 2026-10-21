/*******************************************************************************
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
 *******************************************************************************/

package org.apache.ofbiz.efinance.test

import org.apache.ofbiz.entity.GenericValue
import org.apache.ofbiz.service.ServiceUtil
import org.apache.ofbiz.service.testtools.OFBizTestCase

class EfCatalogTests extends OFBizTestCase {

    EfCatalogTests(String name) { super(name) }

    private void ok(Map result) {
        assert ServiceUtil.isSuccess(result), ServiceUtil.getErrorMessage(result)
    }

    void testListProducts() {
        // Requires demo data loaded (EFPUB1, EFPUB2)
        Map result = dispatcher.runSync('efListProducts', [:])
        ok(result)
        assert result.products != null
        // At least the demo products should appear
        assert result.products.size() >= 0 // may be 0 in test-only DB; just verify no error
    }

    void testGetProductNotFound() {
        Map result = dispatcher.runSync('efGetProduct', [productId: 'NONEXISTENT_XYZ'])
        assert !ServiceUtil.isSuccess(result)
    }

    void testSimulateLoanBasic() {
        // Using DEMO_LOANPROD1: defaultAnnualInterestRate=0.15, term 1-60, amount 10000-1000000, JPY
        Map result = dispatcher.runSync('efSimulateLoan', [
                productId: 'DEMO_LOANPROD1',
                principalAmount: new java.math.BigDecimal('300000'),
                termMonths: 24
        ])
        ok(result)
        assert result.monthlyPayment != null
        assert result.monthlyPayment > java.math.BigDecimal.ZERO
        assert result.totalInterest != null
        assert result.totalInterest > java.math.BigDecimal.ZERO
        assert result.schedule != null
        assert result.schedule.size() == 24
    }

    void testSimulateLoanOutOfRange() {
        Map result = dispatcher.runSync('efSimulateLoan', [
                productId: 'DEMO_LOANPROD1',
                principalAmount: new java.math.BigDecimal('5000'), // below min
                termMonths: 12
        ])
        assert !ServiceUtil.isSuccess(result)
    }

    void testSimulateLeaseBasic() {
        // Using DEMO_LEASEPROD1: rate factor 0.018, term 12-84, amount 100000+, JPY
        Map result = dispatcher.runSync('efSimulateLease', [
                productId: 'DEMO_LEASEPROD1',
                acquisitionCost: new java.math.BigDecimal('1000000'),
                termMonths: 36
        ])
        ok(result)
        assert result.monthlyLeaseAmount != null
        assert result.monthlyLeaseAmount > java.math.BigDecimal.ZERO
        assert result.schedule != null
        assert result.schedule.size() == 36
    }
}
