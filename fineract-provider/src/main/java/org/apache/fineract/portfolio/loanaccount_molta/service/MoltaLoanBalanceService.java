/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements. See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership. The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.fineract.portfolio.loanaccount_molta.service;

import java.math.BigDecimal;
import java.time.LocalDate;
import org.apache.fineract.infrastructure.core.service.DateUtils;
import org.apache.fineract.organisation.monetary.domain.Money;
import org.apache.fineract.organisation.monetary.domain.MonetaryCurrency;
import org.apache.fineract.portfolio.loanaccount.domain.Loan;
import org.apache.fineract.portfolio.loanaccount.domain.LoanCharge;
import org.apache.fineract.portfolio.loanaccount.domain.LoanInstallmentCharge;
import org.apache.fineract.portfolio.loanaccount.domain.LoanRepaymentScheduleInstallment;
import org.apache.fineract.portfolio.loanaccount.domain.LoanRepaymentScheduleProcessingWrapper;
import org.springframework.stereotype.Service;

/**
 * Molta-owned balance service that mirrors LoanBalanceService foreclosure logic
 * but uses a fixed 30-day month for interest pro-ration.
 */
@Service
public class MoltaLoanBalanceService {

    private static final int THIRTY_DAY_MONTH = 30;

    public LoanRepaymentScheduleInstallment fetchLoanForeclosureDetail(final Loan loan, final LocalDate closureDate) {
        Money[] receivables = retrieveIncomeOutstandingTillDate(loan, closureDate);
        Money totalPrincipal = Money.of(loan.getCurrency(), loan.getSummary().getTotalPrincipalOutstanding());
        totalPrincipal = totalPrincipal.minus(receivables[3]);
        final LocalDate currentDate = DateUtils.getBusinessLocalDate();
        return new LoanRepaymentScheduleInstallment(null, 0, currentDate, currentDate, totalPrincipal.getAmount(),
                receivables[0].getAmount(), receivables[1].getAmount(), receivables[2].getAmount(), false, null);
    }

    public Money[] retrieveIncomeForOverlappingPeriod(final Loan loan, final LocalDate paymentDate) {
        Money[] balances = new Money[3];
        final MonetaryCurrency currency = loan.getCurrency();
        balances[0] = balances[1] = balances[2] = Money.zero(currency);
        int firstNormalInstallmentNumber = LoanRepaymentScheduleProcessingWrapper
                .fetchFirstNormalInstallmentNumber(loan.getRepaymentScheduleInstallments());
        for (final LoanRepaymentScheduleInstallment installment : loan.getRepaymentScheduleInstallments()) {
            boolean isFirstNormalInstallment = installment.getInstallmentNumber().equals(firstNormalInstallmentNumber);
            if (DateUtils.isEqual(paymentDate, installment.getDueDate())) {
                balances[0] = installment.getInterestCharged(currency);
                balances[1] = installment.getFeeChargesCharged(currency);
                balances[2] = installment.getPenaltyChargesCharged(currency);
                break;
            } else if (DateUtils.isDateInRangeExclusive(paymentDate, installment.getFromDate(), installment.getDueDate())) {
                balances = fetchInterestFeeAndPenaltyTillDate(loan, paymentDate, currency, installment, isFirstNormalInstallment);
                break;
            }
        }
        return balances;
    }

    private Money[] retrieveIncomeOutstandingTillDate(final Loan loan, final LocalDate paymentDate) {
        Money[] balances = new Money[4];
        final MonetaryCurrency currency = loan.getCurrency();
        Money interest = Money.zero(currency);
        Money paidFromFutureInstallments = Money.zero(currency);
        Money fee = Money.zero(currency);
        Money penalty = Money.zero(currency);
        int firstNormalInstallmentNumber = LoanRepaymentScheduleProcessingWrapper
                .fetchFirstNormalInstallmentNumber(loan.getRepaymentScheduleInstallments());

        for (final LoanRepaymentScheduleInstallment installment : loan.getRepaymentScheduleInstallments()) {
            boolean isFirstNormalInstallment = installment.getInstallmentNumber().equals(firstNormalInstallmentNumber);
            if (!DateUtils.isBefore(paymentDate, installment.getDueDate())) {
                interest = interest.plus(installment.getInterestOutstanding(currency));
                penalty = penalty.plus(installment.getPenaltyChargesOutstanding(currency));
                fee = fee.plus(installment.getFeeChargesOutstanding(currency));
            } else if (DateUtils.isAfter(paymentDate, installment.getFromDate())) {
                Money[] balancesForCurrentPeriod = fetchInterestFeeAndPenaltyTillDate(loan, paymentDate, currency, installment,
                        isFirstNormalInstallment);
                if (balancesForCurrentPeriod[0].isGreaterThan(balancesForCurrentPeriod[5])) {
                    interest = interest.plus(balancesForCurrentPeriod[0]).minus(balancesForCurrentPeriod[5]);
                } else {
                    paidFromFutureInstallments = paidFromFutureInstallments.plus(balancesForCurrentPeriod[5])
                            .minus(balancesForCurrentPeriod[0]);
                }
                if (balancesForCurrentPeriod[1].isGreaterThan(balancesForCurrentPeriod[3])) {
                    fee = fee.plus(balancesForCurrentPeriod[1].minus(balancesForCurrentPeriod[3]));
                } else {
                    paidFromFutureInstallments = paidFromFutureInstallments
                            .plus(balancesForCurrentPeriod[3].minus(balancesForCurrentPeriod[1]));
                }
                if (balancesForCurrentPeriod[2].isGreaterThan(balancesForCurrentPeriod[4])) {
                    penalty = penalty.plus(balancesForCurrentPeriod[2].minus(balancesForCurrentPeriod[4]));
                } else {
                    paidFromFutureInstallments = paidFromFutureInstallments.plus(balancesForCurrentPeriod[4])
                            .minus(balancesForCurrentPeriod[2]);
                }
            } else {
                paidFromFutureInstallments = paidFromFutureInstallments.plus(installment.getInterestPaid(currency))
                        .plus(installment.getPenaltyChargesPaid(currency)).plus(installment.getFeeChargesPaid(currency));
            }
        }
        balances[0] = interest;
        balances[1] = fee;
        balances[2] = penalty;
        balances[3] = paidFromFutureInstallments;
        return balances;
    }

    private Money[] fetchInterestFeeAndPenaltyTillDate(final Loan loan, final LocalDate paymentDate, final MonetaryCurrency currency,
            final LoanRepaymentScheduleInstallment installment, final boolean isFirstNormalInstallment) {
        Money penaltyForCurrentPeriod = Money.zero(loan.getCurrency());
        Money penaltyAccoutedForCurrentPeriod = Money.zero(loan.getCurrency());
        Money feeForCurrentPeriod = Money.zero(loan.getCurrency());
        Money feeAccountedForCurrentPeriod = Money.zero(loan.getCurrency());
        int tillDays = DateUtils.getExactDifferenceInDays(installment.getFromDate(), paymentDate);
        Money interestForCurrentPeriod = Money.of(loan.getCurrency(), BigDecimal.valueOf(
                calculateInterestForDays(THIRTY_DAY_MONTH, installment.getInterestCharged(loan.getCurrency()).getAmount(), tillDays)));
        Money interestAccountedForCurrentPeriod = installment.getInterestWaived(loan.getCurrency())
                .plus(installment.getInterestPaid(loan.getCurrency()));
        for (LoanCharge loanCharge : loan.getLoanCharges()) {
            if (loanCharge.isActive() && !loanCharge.isDueAtDisbursement()) {
                boolean isDue = loanCharge.isDueInPeriod(installment.getFromDate(), paymentDate, isFirstNormalInstallment);
                if (isDue) {
                    if (loanCharge.isPenaltyCharge()) {
                        penaltyForCurrentPeriod = penaltyForCurrentPeriod.plus(loanCharge.getAmount(loan.getCurrency()));
                        penaltyAccoutedForCurrentPeriod = penaltyAccoutedForCurrentPeriod
                                .plus(loanCharge.getAmountWaived(loan.getCurrency()).plus(loanCharge.getAmountPaid(loan.getCurrency())));
                    } else {
                        feeForCurrentPeriod = feeForCurrentPeriod.plus(loanCharge.getAmount(currency));
                        feeAccountedForCurrentPeriod = feeAccountedForCurrentPeriod
                                .plus(loanCharge.getAmountWaived(loan.getCurrency()).plus(loanCharge.getAmountPaid(loan.getCurrency())));
                    }
                } else if (loanCharge.isInstalmentFee()) {
                    LoanInstallmentCharge loanInstallmentCharge = loanCharge.getInstallmentLoanCharge(installment.getInstallmentNumber());
                    if (loanCharge.isPenaltyCharge()) {
                        penaltyAccoutedForCurrentPeriod = penaltyAccoutedForCurrentPeriod
                                .plus(loanInstallmentCharge.getAmountPaid(currency));
                    } else {
                        feeAccountedForCurrentPeriod = feeAccountedForCurrentPeriod.plus(loanInstallmentCharge.getAmountPaid(currency));
                    }
                }
            }
        }
        Money[] balances = new Money[6];
        balances[0] = interestForCurrentPeriod;
        balances[1] = feeForCurrentPeriod;
        balances[2] = penaltyForCurrentPeriod;
        balances[3] = feeAccountedForCurrentPeriod;
        balances[4] = penaltyAccoutedForCurrentPeriod;
        balances[5] = interestAccountedForCurrentPeriod;
        return balances;
    }

    private double calculateInterestForDays(final int daysInPeriod, final BigDecimal interest, final int days) {
        if (interest.doubleValue() == 0) {
            return 0;
        }
        return interest.doubleValue() / daysInPeriod * days;
    }
}