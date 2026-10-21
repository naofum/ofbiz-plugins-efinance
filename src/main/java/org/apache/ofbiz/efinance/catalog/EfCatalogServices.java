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

package org.apache.ofbiz.efinance.catalog;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.apache.ofbiz.base.util.Debug;
import org.apache.ofbiz.base.util.UtilMisc;
import org.apache.ofbiz.base.util.UtilValidate;
import org.apache.ofbiz.entity.Delegator;
import org.apache.ofbiz.entity.GenericEntityException;
import org.apache.ofbiz.entity.GenericValue;
import org.apache.ofbiz.entity.condition.EntityCondition;
import org.apache.ofbiz.entity.condition.EntityOperator;
import org.apache.ofbiz.entity.util.EntityQuery;
import org.apache.ofbiz.finance.common.FinanceInterestCalculator;
import org.apache.ofbiz.finance.lease.LeaseCalculator;
import org.apache.ofbiz.service.DispatchContext;
import org.apache.ofbiz.service.ServiceUtil;

/**
 * W1: Product catalog and simulation services (no authentication required).
 */
public class EfCatalogServices {

    private static final String MODULE = EfCatalogServices.class.getName();
    private static final String EQUAL_PRINCIPAL = "EQUAL_PRINCIPAL";

    // -----------------------------------------------------------------------
    // efListProducts
    // -----------------------------------------------------------------------

    public static Map<String, Object> efListProducts(DispatchContext dctx, Map<String, Object> context) {
        Delegator delegator = dctx.getDelegator();
        String targetEnumId = (String) context.get("targetEnumId");
        Timestamp now = new Timestamp(System.currentTimeMillis());

        try {
            List<EntityCondition> conds = new ArrayList<>();
            conds.add(EntityCondition.makeCondition("active", "Y"));
            conds.add(EntityCondition.makeCondition("fromDate", EntityOperator.LESS_THAN_EQUAL_TO, now));
            conds.add(EntityCondition.makeCondition(
                    EntityCondition.makeCondition("thruDate", EntityOperator.EQUALS, null),
                    EntityOperator.OR,
                    EntityCondition.makeCondition("thruDate", EntityOperator.GREATER_THAN_EQUAL_TO, now)));

            if (UtilValidate.isNotEmpty(targetEnumId)) {
                conds.add(EntityCondition.makeCondition(
                        EntityCondition.makeCondition("targetEnumId", EntityOperator.EQUALS, targetEnumId),
                        EntityOperator.OR,
                        EntityCondition.makeCondition("targetEnumId", EntityOperator.EQUALS, "EFPROD_TGT_ALL")));
            }

            List<GenericValue> pubs = EntityQuery.use(delegator)
                    .from("EfProductPublication")
                    .where(EntityCondition.makeCondition(conds, EntityOperator.AND))
                    .orderBy("displayOrder")
                    .queryList();

            List<Map<String, Object>> products = new ArrayList<>();
            for (GenericValue pub : pubs) {
                if (!isPublished(pub, now)) {
                    continue;
                }
                String productId = pub.getString("productId");
                GenericValue fp = EntityQuery.use(delegator).from("FinancialProduct").where("productId", productId).queryOne();
                if (fp == null) {
                    continue;
                }
                GenericValue prod = EntityQuery.use(delegator).from("Product").where("productId", productId).queryOne();
                GenericValue fpl = EntityQuery.use(delegator).from("FinancialProductLease").where("productId", productId).queryOne();
                boolean isLease = (fpl != null);
                Map<String, Object> summary = productSummary(fp, prod, isLease);
                summary.put("targetEnumId", pub.getString("targetEnumId"));
                products.add(summary);
            }

            Map<String, Object> result = ServiceUtil.returnSuccess();
            result.put("products", products);
            return result;

        } catch (GenericEntityException e) {
            Debug.logError(e, MODULE);
            return ServiceUtil.returnError(e.getMessage());
        }
    }

    // -----------------------------------------------------------------------
    // efGetProduct
    // -----------------------------------------------------------------------

    public static Map<String, Object> efGetProduct(DispatchContext dctx, Map<String, Object> context) {
        Delegator delegator = dctx.getDelegator();
        String productId = (String) context.get("productId");
        Timestamp now = new Timestamp(System.currentTimeMillis());

        try {
            List<GenericValue> pubs = EntityQuery.use(delegator)
                    .from("EfProductPublication")
                    .where(EntityCondition.makeCondition(UtilMisc.toList(
                            EntityCondition.makeCondition("productId", productId),
                            EntityCondition.makeCondition("active", "Y")),
                            EntityOperator.AND))
                    .queryList();

            GenericValue matchedPub = null;
            for (GenericValue pub : pubs) {
                if (isPublished(pub, now)) {
                    matchedPub = pub;
                    break;
                }
            }
            if (matchedPub == null) {
                return ServiceUtil.returnError("Product not found or not published");
            }

            GenericValue fp = EntityQuery.use(delegator).from("FinancialProduct").where("productId", productId).queryOne();
            if (fp == null) {
                return ServiceUtil.returnError("Product not found or not published");
            }
            GenericValue prod = EntityQuery.use(delegator).from("Product").where("productId", productId).queryOne();
            GenericValue fpl = EntityQuery.use(delegator).from("FinancialProductLease").where("productId", productId).queryOne();

            Map<String, Object> detail = productDetail(fp, prod, fpl);

            Map<String, Object> result = ServiceUtil.returnSuccess();
            result.put("product", detail);
            return result;

        } catch (GenericEntityException e) {
            Debug.logError(e, MODULE);
            return ServiceUtil.returnError(e.getMessage());
        }
    }

    // -----------------------------------------------------------------------
    // efSimulateLoan
    // -----------------------------------------------------------------------

    public static Map<String, Object> efSimulateLoan(DispatchContext dctx, Map<String, Object> context) {
        Delegator delegator = dctx.getDelegator();
        String productId = (String) context.get("productId");
        BigDecimal principalAmount = (BigDecimal) context.get("principalAmount");
        Integer termMonths = (Integer) context.get("termMonths");
        String repaymentMethod = (String) context.get("repaymentMethod");
        BigDecimal annualInterestRate = (BigDecimal) context.get("annualInterestRate");

        try {
            GenericValue fp = EntityQuery.use(delegator).from("FinancialProduct").where("productId", productId).queryOne();
            if (fp == null) {
                return ServiceUtil.returnError("Product not found or not published");
            }

            // Validate amount range
            BigDecimal minAmt = fp.getBigDecimal("minPrincipalAmount");
            BigDecimal maxAmt = fp.getBigDecimal("maxPrincipalAmount");
            if (minAmt != null && principalAmount.compareTo(minAmt) < 0) {
                return ServiceUtil.returnError("Amount is outside the allowed range for this product.");
            }
            if (maxAmt != null && principalAmount.compareTo(maxAmt) > 0) {
                return ServiceUtil.returnError("Amount is outside the allowed range for this product.");
            }

            // Validate term range
            Integer minTerm = fp.getLong("minTermMonths") != null ? fp.getLong("minTermMonths").intValue() : null;
            Integer maxTerm = fp.getLong("maxTermMonths") != null ? fp.getLong("maxTermMonths").intValue() : null;
            if (minTerm != null && termMonths < minTerm) {
                return ServiceUtil.returnError("Term is outside the allowed range for this product.");
            }
            if (maxTerm != null && termMonths > maxTerm) {
                return ServiceUtil.returnError("Term is outside the allowed range for this product.");
            }

            // Resolve interest rate
            if (annualInterestRate == null) {
                annualInterestRate = fp.getBigDecimal("defaultAnnualInterestRate");
            }
            if (annualInterestRate == null) {
                return ServiceUtil.returnError("No interest rate available for this product.");
            }

            String currencyUomId = fp.getString("currencyUomId");
            int scale = FinanceInterestCalculator.resolveScale(currencyUomId, delegator);
            // Round payment up for zero-decimal currencies (JPY scale=0), HALF_UP otherwise
            RoundingMode paymentRounding = (scale == 0) ? RoundingMode.CEILING : RoundingMode.HALF_UP;

            MathContext mc = new MathContext(12, RoundingMode.HALF_UP);
            int n = termMonths;
            BigDecimal monthlyRate = annualInterestRate.divide(BigDecimal.valueOf(12), 10, RoundingMode.HALF_UP);

            boolean equalPrincipal = EQUAL_PRINCIPAL.equals(repaymentMethod);

            List<Map<String, Object>> schedule = new ArrayList<>();
            BigDecimal totalPayment = BigDecimal.ZERO;

            if (equalPrincipal) {
                // EQUAL_PRINCIPAL: fixed principal per period + declining interest
                BigDecimal principalPerMonth = principalAmount.divide(BigDecimal.valueOf(n), 10, RoundingMode.HALF_UP);
                BigDecimal balance = principalAmount;
                for (int k = 1; k <= n; k++) {
                    BigDecimal interest;
                    BigDecimal principal;
                    if (k == n) {
                        principal = balance;
                    } else {
                        principal = principalPerMonth.setScale(scale, paymentRounding);
                    }
                    interest = balance.multiply(monthlyRate).setScale(scale, RoundingMode.HALF_UP);
                    BigDecimal payment = principal.add(interest);
                    balance = balance.subtract(principal);
                    totalPayment = totalPayment.add(payment);
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("month", k);
                    row.put("payment", payment);
                    row.put("principal", principal);
                    row.put("interest", interest);
                    row.put("remaining", balance);
                    schedule.add(row);
                }
            } else {
                // EQUAL_PAYMENT (annuity): level payment
                BigDecimal monthlyPayment;
                if (monthlyRate.signum() == 0) {
                    monthlyPayment = principalAmount.divide(BigDecimal.valueOf(n), scale, paymentRounding);
                } else {
                    // annuity = P * r * (1+r)^n / ((1+r)^n - 1)
                    BigDecimal onePlusR = BigDecimal.ONE.add(monthlyRate);
                    BigDecimal pow = onePlusR.pow(n, mc);
                    BigDecimal numerator = principalAmount.multiply(monthlyRate).multiply(pow);
                    BigDecimal denominator = pow.subtract(BigDecimal.ONE);
                    monthlyPayment = numerator.divide(denominator, scale, paymentRounding);
                }

                BigDecimal balance = principalAmount;
                for (int k = 1; k <= n; k++) {
                    BigDecimal interest = balance.multiply(monthlyRate).setScale(scale, RoundingMode.HALF_UP);
                    BigDecimal principal;
                    if (k == n) {
                        principal = balance;
                        // last payment absorbs rounding
                    } else {
                        principal = monthlyPayment.subtract(interest);
                    }
                    BigDecimal payment = (k == n) ? principal.add(interest) : monthlyPayment;
                    balance = balance.subtract(principal);
                    totalPayment = totalPayment.add(payment);
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("month", k);
                    row.put("payment", payment);
                    row.put("principal", principal);
                    row.put("interest", interest);
                    row.put("remaining", balance);
                    schedule.add(row);
                }

                Map<String, Object> result = ServiceUtil.returnSuccess();
                BigDecimal totalInterest = totalPayment.subtract(principalAmount);
                result.put("monthlyPayment", monthlyPayment);
                result.put("totalPayment", totalPayment);
                result.put("totalInterest", totalInterest);
                result.put("effectiveAnnualRate", annualInterestRate);
                result.put("schedule", schedule);
                return result;
            }

            // For EQUAL_PRINCIPAL path, compute summary
            Map<String, Object> result = ServiceUtil.returnSuccess();
            BigDecimal totalInterest = totalPayment.subtract(principalAmount);
            // first payment as representative monthly for equal-principal
            BigDecimal firstPayment = schedule.isEmpty() ? BigDecimal.ZERO
                    : (BigDecimal) schedule.get(0).get("payment");
            result.put("monthlyPayment", firstPayment);
            result.put("totalPayment", totalPayment);
            result.put("totalInterest", totalInterest);
            result.put("effectiveAnnualRate", annualInterestRate);
            result.put("schedule", schedule);
            return result;

        } catch (GenericEntityException e) {
            Debug.logError(e, MODULE);
            return ServiceUtil.returnError(e.getMessage());
        }
    }

    // -----------------------------------------------------------------------
    // efSimulateLease
    // -----------------------------------------------------------------------

    public static Map<String, Object> efSimulateLease(DispatchContext dctx, Map<String, Object> context) {
        Delegator delegator = dctx.getDelegator();
        String productId = (String) context.get("productId");
        BigDecimal acquisitionCost = (BigDecimal) context.get("acquisitionCost");
        Integer termMonths = (Integer) context.get("termMonths");
        BigDecimal taxRate = (BigDecimal) context.get("taxRate");

        try {
            GenericValue fp = EntityQuery.use(delegator).from("FinancialProduct").where("productId", productId).queryOne();
            if (fp == null) {
                return ServiceUtil.returnError("Product not found or not published");
            }
            GenericValue fpl = EntityQuery.use(delegator).from("FinancialProductLease").where("productId", productId).queryOne();
            if (fpl == null) {
                return ServiceUtil.returnError("Product is not a lease product");
            }

            // Validate amount range
            BigDecimal minAmt = fp.getBigDecimal("minPrincipalAmount");
            BigDecimal maxAmt = fp.getBigDecimal("maxPrincipalAmount");
            if (minAmt != null && acquisitionCost.compareTo(minAmt) < 0) {
                return ServiceUtil.returnError("Amount is outside the allowed range for this product.");
            }
            if (maxAmt != null && acquisitionCost.compareTo(maxAmt) > 0) {
                return ServiceUtil.returnError("Amount is outside the allowed range for this product.");
            }

            // Validate term range
            Integer minTerm = fp.getLong("minTermMonths") != null ? fp.getLong("minTermMonths").intValue() : null;
            Integer maxTerm = fp.getLong("maxTermMonths") != null ? fp.getLong("maxTermMonths").intValue() : null;
            if (minTerm != null && termMonths < minTerm) {
                return ServiceUtil.returnError("Term is outside the allowed range for this product.");
            }
            if (maxTerm != null && termMonths > maxTerm) {
                return ServiceUtil.returnError("Term is outside the allowed range for this product.");
            }

            String currencyUomId = fp.getString("currencyUomId");
            int scale = FinanceInterestCalculator.resolveScale(currencyUomId, delegator);

            BigDecimal residualValueRate = fpl.getBigDecimal("residualValueRate");
            BigDecimal residualValue = BigDecimal.ZERO;
            if (residualValueRate != null && residualValueRate.signum() > 0) {
                residualValue = acquisitionCost.multiply(residualValueRate).setScale(scale, RoundingMode.HALF_UP);
            }

            String paymentTimingEnumId = fpl.getString("paymentTimingEnumId");
            boolean advance = "LEASE_PAY_ADVANCE".equals(paymentTimingEnumId);

            BigDecimal effectiveTaxRate = (taxRate != null) ? taxRate : BigDecimal.ZERO;

            LeaseCalculator.Input input = new LeaseCalculator.Input()
                    .acquisitionCost(acquisitionCost)
                    .residualValue(residualValue)
                    .termMonths(termMonths)
                    .advance(advance)
                    .taxRate(effectiveTaxRate)
                    .currencyRounding(scale, RoundingMode.HALF_UP);

            BigDecimal monthlyRateFactor = fpl.getBigDecimal("defaultMonthlyRateFactor");
            BigDecimal defaultCalcInterestRate = fpl.getBigDecimal("defaultCalcInterestRate");
            // Prefer calc interest rate for simulation (more flexible across term lengths);
            // fall back to monthly rate factor if no interest rate is configured.
            if (defaultCalcInterestRate != null) {
                input.annualCalcRate(defaultCalcInterestRate);
            } else if (monthlyRateFactor != null) {
                input.monthlyRateFactor(monthlyRateFactor);
            } else {
                return ServiceUtil.returnError("No rate configured for this lease product.");
            }

            LeaseCalculator.Result calcResult = LeaseCalculator.calculate(input);

            List<Map<String, Object>> schedule = new ArrayList<>();
            for (LeaseCalculator.Row row : calcResult.getRows()) {
                Map<String, Object> rowMap = new LinkedHashMap<>();
                rowMap.put("installmentNumber", row.getInstallmentNumber());
                rowMap.put("leaseAmount", row.getLeaseAmount());
                rowMap.put("principalPortion", row.getPrincipalPortion());
                rowMap.put("interestPortion", row.getInterestPortion());
                rowMap.put("taxAmount", row.getTaxAmount());
                rowMap.put("remainingPrincipal", row.getRemainingPrincipal());
                schedule.add(rowMap);
            }

            Map<String, Object> result = ServiceUtil.returnSuccess();
            result.put("monthlyLeaseAmount", calcResult.getMonthlyLeaseAmount());
            result.put("totalLeaseAmount", calcResult.getTotalLeaseAmount());
            result.put("totalTax", calcResult.getTotalTaxAmount());
            result.put("calcInterestRate", calcResult.getCalcInterestRate());
            result.put("schedule", schedule);
            return result;

        } catch (GenericEntityException e) {
            Debug.logError(e, MODULE);
            return ServiceUtil.returnError(e.getMessage());
        } catch (IllegalArgumentException e) {
            Debug.logError(e, MODULE);
            return ServiceUtil.returnError(e.getMessage());
        }
    }

    // -----------------------------------------------------------------------
    // Private helpers
    // -----------------------------------------------------------------------

    /** Check active='Y' and fromDate <= now and (thruDate null or thruDate >= now). */
    private static boolean isPublished(GenericValue pub, Timestamp now) {
        if (!"Y".equals(pub.getString("active"))) {
            return false;
        }
        Timestamp fromDate = pub.getTimestamp("fromDate");
        if (fromDate != null && fromDate.after(now)) {
            return false;
        }
        Timestamp thruDate = pub.getTimestamp("thruDate");
        if (thruDate != null && thruDate.before(now)) {
            return false;
        }
        return true;
    }

    /** Build compact summary Map for the product list. */
    private static Map<String, Object> productSummary(GenericValue fp, GenericValue prod, boolean isLease) {
        Map<String, Object> m = new LinkedHashMap<>();
        String productId = fp.getString("productId");
        m.put("productId", productId);
        if (prod != null) {
            m.put("productName", prod.getString("productName"));
            m.put("internalName", prod.getString("internalName"));
        }
        m.put("financialProductTypeId", fp.getString("financialProductTypeId"));
        m.put("minPrincipalAmount", fp.getBigDecimal("minPrincipalAmount"));
        m.put("maxPrincipalAmount", fp.getBigDecimal("maxPrincipalAmount"));
        m.put("minTermMonths", fp.getLong("minTermMonths"));
        m.put("maxTermMonths", fp.getLong("maxTermMonths"));
        m.put("defaultAnnualInterestRate", isLease ? null : fp.getBigDecimal("defaultAnnualInterestRate"));
        m.put("currencyUomId", fp.getString("currencyUomId"));
        m.put("isLease", isLease);
        return m;
    }

    /** Build full detail Map for a single product. */
    private static Map<String, Object> productDetail(GenericValue fp, GenericValue prod, GenericValue fpl) {
        Map<String, Object> m = new LinkedHashMap<>();
        String productId = fp.getString("productId");
        m.put("productId", productId);
        if (prod != null) {
            m.put("productName", prod.getString("productName"));
            m.put("internalName", prod.getString("internalName"));
        }
        m.put("financialProductTypeId", fp.getString("financialProductTypeId"));
        m.put("currencyUomId", fp.getString("currencyUomId"));
        m.put("minPrincipalAmount", fp.getBigDecimal("minPrincipalAmount"));
        m.put("maxPrincipalAmount", fp.getBigDecimal("maxPrincipalAmount"));
        m.put("minTermMonths", fp.getLong("minTermMonths"));
        m.put("maxTermMonths", fp.getLong("maxTermMonths"));
        m.put("defaultAnnualInterestRate", fp.getBigDecimal("defaultAnnualInterestRate"));
        m.put("requiresCollateral", fp.getString("requiresCollateral"));
        m.put("lateFeeAnnualRate", fp.getBigDecimal("lateFeeAnnualRate"));
        m.put("prepaymentFeeRate", fp.getBigDecimal("prepaymentFeeRate"));
        m.put("prepaymentFeeAmount", fp.getBigDecimal("prepaymentFeeAmount"));
        if (fpl != null) {
            m.put("leaseClassEnumId", fpl.getString("leaseClassEnumId"));
            m.put("defaultMonthlyRateFactor", fpl.getBigDecimal("defaultMonthlyRateFactor"));
            m.put("defaultCalcInterestRate", fpl.getBigDecimal("defaultCalcInterestRate"));
            m.put("paymentTimingEnumId", fpl.getString("paymentTimingEnumId"));
            m.put("residualValueRate", fpl.getBigDecimal("residualValueRate"));
            m.put("taxable", fpl.getString("taxable"));
        }
        return m;
    }
}
