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

import com.google.gson.JsonElement;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.apache.commons.lang3.StringUtils;
import org.apache.fineract.infrastructure.core.domain.ExternalId;
import org.apache.fineract.infrastructure.core.exception.GeneralPlatformDomainRuleException;
import org.apache.fineract.infrastructure.core.serialization.FromJsonHelper;
import org.apache.fineract.infrastructure.core.service.DateUtils;
import org.apache.fineract.infrastructure.event.business.domain.loan.LoanBalanceChangedBusinessEvent;
import org.apache.fineract.infrastructure.event.business.domain.loan.transaction.LoanForeClosurePostBusinessEvent;
import org.apache.fineract.infrastructure.event.business.domain.loan.transaction.LoanForeClosurePreBusinessEvent;
import org.apache.fineract.infrastructure.event.business.service.BusinessEventNotifierService;
import org.apache.fineract.organisation.monetary.domain.ApplicationCurrencyRepositoryWrapper;
import org.apache.fineract.organisation.monetary.domain.Money;
import org.apache.fineract.organisation.monetary.domain.MonetaryCurrency;
import org.apache.fineract.organisation.monetary.data.CurrencyData;
import org.apache.fineract.organisation.monetary.domain.ApplicationCurrency;
import org.apache.fineract.portfolio.loanaccount.data.LoanTransactionData;
import org.apache.fineract.portfolio.loanaccount.domain.Loan;
import org.apache.fineract.portfolio.loanaccount.domain.LoanAccountService;
import org.apache.fineract.portfolio.loanaccount.domain.LoanCharge;
import org.apache.fineract.portfolio.loanaccount.domain.LoanChargePaidBy;
import org.apache.fineract.portfolio.loanaccount.domain.LoanDisbursementDetails;
import org.apache.fineract.portfolio.loanaccount.domain.LoanEvent;
import org.apache.fineract.portfolio.loanaccount.domain.LoanRepaymentScheduleInstallment;
import org.apache.fineract.portfolio.loanaccount.domain.LoanRepositoryWrapper;
import org.apache.fineract.portfolio.loanaccount.domain.LoanSubStatus;
import org.apache.fineract.portfolio.loanaccount.domain.LoanTransaction;
import org.apache.fineract.portfolio.loanaccount.exception.LoanForeclosureException;
import org.apache.fineract.portfolio.loanaccount.loanschedule.service.LoanScheduleHistoryWritePlatformService;
import org.apache.fineract.portfolio.loanaccount.serialization.LoanChargeValidator;
import org.apache.fineract.portfolio.loanaccount.serialization.LoanDownPaymentTransactionValidator;
import org.apache.fineract.portfolio.loanaccount.serialization.LoanForeclosureValidator;
import org.apache.fineract.portfolio.loanaccount.serialization.LoanTransactionValidator;
import org.apache.fineract.portfolio.loanaccount.service.LoanAccrualsProcessingService;
import org.apache.fineract.portfolio.loanaccount.service.LoanChargeService;
import org.apache.fineract.portfolio.loanaccount.service.LoanDownPaymentHandlerService;
import org.apache.fineract.portfolio.loanaccount.service.LoanJournalEntryPoster;
import org.apache.fineract.portfolio.loanaccount.domain.LoanTransactionType;
import org.apache.fineract.portfolio.loanaccount.data.LoanTransactionEnumData;
import org.apache.fineract.portfolio.loanproduct.service.LoanEnumerations;
import org.apache.fineract.portfolio.note.domain.Note;
import org.apache.fineract.portfolio.note.domain.NoteRepository;
import org.apache.fineract.portfolio.paymenttype.data.PaymentTypeData;
import org.apache.fineract.portfolio.paymenttype.service.PaymentTypeReadService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class MoltaForeclosureService {

    private final MoltaLoanBalanceService moltaLoanBalanceService;
    private final LoanRepositoryWrapper loanRepositoryWrapper;
    private final ApplicationCurrencyRepositoryWrapper applicationCurrencyRepository;
    private final PaymentTypeReadService paymentTypeReadService;
    private final LoanForeclosureValidator loanForeclosureValidator;
    private final LoanTransactionValidator loanTransactionValidator;
    private final LoanScheduleHistoryWritePlatformService loanScheduleHistoryWritePlatformService;
    private final BusinessEventNotifierService businessEventNotifierService;
    private final LoanAccrualsProcessingService loanAccrualsProcessingService;
    private final LoanDownPaymentTransactionValidator loanDownPaymentTransactionValidator;
    private final LoanDownPaymentHandlerService loanDownPaymentHandlerService;
    private final LoanAccountService loanAccountService;
    private final LoanJournalEntryPoster journalEntryPoster;
    private final NoteRepository noteRepository;
    private final LoanChargeService loanChargeService;
    private final LoanChargeValidator loanChargeValidator;
    private final FromJsonHelper fromJsonHelper;

    public LoanTransactionData retrieveForeclosureTemplate(final Long loanId, final LocalDate transactionDate) {
        final Loan loan = loanRepositoryWrapper.findOneWithNotFoundDetection(loanId, true);
        loanForeclosureValidator.validateForForeclosure(loan, transactionDate);

        final MonetaryCurrency currency = loan.getCurrency();
        final ApplicationCurrency applicationCurrency = applicationCurrencyRepository.findOneWithNotFoundDetection(currency);
        final CurrencyData currencyData = applicationCurrency.toData();
        final Collection<PaymentTypeData> paymentTypeOptions = paymentTypeReadService.retrieveAllPaymentTypes();

        final LoanRepaymentScheduleInstallment foreCloseDetail = moltaLoanBalanceService.fetchLoanForeclosureDetail(loan, transactionDate);

        final LoanTransactionEnumData transactionType = LoanEnumerations.transactionType(LoanTransactionType.REPAYMENT);
        final Money outStandingAmount = foreCloseDetail.getTotalOutstanding(currency);

        return LoanTransactionData.builder().type(transactionType).currency(currencyData).date(DateUtils.getBusinessLocalDate())
                .amount(outStandingAmount.getAmount()).netDisbursalAmount(loan.getNetDisbursalAmount())
                .principalPortion(foreCloseDetail.getPrincipalOutstanding(currency).getAmount())
                .interestPortion(foreCloseDetail.getInterestOutstanding(currency).getAmount())
                .feeChargesPortion(foreCloseDetail.getFeeChargesOutstanding(currency).getAmount())
                .penaltyChargesPortion(foreCloseDetail.getPenaltyChargesOutstanding(currency).getAmount())
                .paymentTypeOptions(paymentTypeOptions).externalId(ExternalId.empty())
                .outstandingLoanBalance(foreCloseDetail.getPrincipalOutstanding(currency).getAmount())
                .manuallyReversed(false).loanId(loanId).externalLoanId(loan.getExternalId()).build();
    }

    @Transactional
    public Map<String, Object> performForeclosure(final Long loanId, final String json) {
        loanTransactionValidator.validateLoanForeclosure(json);

        final JsonElement element = fromJsonHelper.parse(json);
        final LocalDate transactionDate = fromJsonHelper.extractLocalDateNamed("transactionDate", element);
        final String noteText = fromJsonHelper.extractStringNamed("note", element);

        Loan loan = loanRepositoryWrapper.findOneWithNotFoundDetection(loanId, true);

        for (LoanDisbursementDetails ldd : loan.getDisbursementDetails()) {
            if (!DateUtils.isAfter(ldd.expectedDisbursementDateAsLocalDate(), transactionDate)
                    && ldd.actualDisbursementDate() == null) {
                throw new LoanForeclosureException(
                        "loan.with.undisbursed.tranche.before.foreclosure.cannot.be.foreclosured",
                        "The loan with undisbursed tranche before foreclosure cannot be foreclosed.", transactionDate);
            }
        }

        loanScheduleHistoryWritePlatformService.createAndSaveLoanScheduleArchive(loan.getRepaymentScheduleInstallments(), loan, null);

        if (loan.isChargedOff() && DateUtils.isBefore(transactionDate, loan.getChargedOffOnDate())) {
            throw new GeneralPlatformDomainRuleException(
                    "error.msg.transaction.date.cannot.be.earlier.than.charge.off.date",
                    "Loan: " + loan.getId() + " backdated transaction is not allowed. Transaction date cannot be earlier than the charge-off date of the loan",
                    loan.getId());
        }

        businessEventNotifierService.notifyPreBusinessEvent(new LoanForeClosurePreBusinessEvent(loan));

        final MonetaryCurrency currency = loan.getCurrency();
        final List<LoanTransaction> newTransactions = new ArrayList<>();

        final LoanRepaymentScheduleInstallment foreCloseDetail = moltaLoanBalanceService.fetchLoanForeclosureDetail(loan, transactionDate);

        loanAccrualsProcessingService.processAccrualsOnLoanForeClosure(loan, transactionDate, newTransactions);

        final Money interestPayable = foreCloseDetail.getInterestCharged(currency);
        final Money feePayable = foreCloseDetail.getFeeChargesCharged(currency);
        final Money penaltyPayable = foreCloseDetail.getPenaltyChargesCharged(currency);
        final Money payPrincipal = foreCloseDetail.getPrincipal(currency);

        updateInstallmentsPostDate(loan, transactionDate);

        LoanTransaction payment = null;
        if (payPrincipal.plus(interestPayable).plus(feePayable).plus(penaltyPayable).isGreaterThanZero()) {
            payment = LoanTransaction.repayment(loan.getOffice(),
                    payPrincipal.plus(interestPayable).plus(feePayable).plus(penaltyPayable),
                    null, transactionDate, ExternalId.empty());
            payment.updateLoan(loan);
            newTransactions.add(payment);
        }

        if (payment != null) {
            loanForeclosureValidator.validateForForeclosure(loan, payment.getTransactionDate());
        }
        loanDownPaymentTransactionValidator.validateAccountStatus(loan, LoanEvent.LOAN_FORECLOSURE);

        loan.setLoanSubStatus(LoanSubStatus.FORECLOSED);
        loanDownPaymentHandlerService.handleRepaymentOrRecoveryOrWaiverTransaction(loan, payment, null, null);

        loanAccrualsProcessingService.reprocessExistingAccruals(loan, true);
        if (loan.isInterestBearingAndInterestRecalculationEnabled()) {
            loanAccrualsProcessingService.processIncomePostingAndAccruals(loan, true);
        }

        final List<Long> transactionIds = new ArrayList<>();
        for (LoanTransaction newTransaction : newTransactions) {
            LoanTransaction saved = loanAccountService.saveLoanTransactionWithDataIntegrityViolationChecks(newTransaction);
            loan.addLoanTransaction(saved);
            journalEntryPoster.postJournalEntriesForLoanTransaction(newTransaction, false, false);
            transactionIds.add(saved.getId());
        }

        loan = loanAccountService.saveAndFlushLoanWithDataIntegrityViolationChecks(loan);

        if (StringUtils.isNotBlank(noteText)) {
            noteRepository.save(Note.loanNote(loan, noteText));
        }

        businessEventNotifierService.notifyPostBusinessEvent(new LoanBalanceChangedBusinessEvent(loan));
        businessEventNotifierService.notifyPostBusinessEvent(new LoanForeClosurePostBusinessEvent(payment));

        final Map<String, Object> changes = new LinkedHashMap<>();
        changes.put("loanId", loanId);
        if (payment != null) {
            changes.put("transactionId", payment.getId());
        }
        changes.put("transactionIds", transactionIds);
        return changes;
    }

    private void updateInstallmentsPostDate(final Loan loan, final LocalDate transactionDate) {
        final List<LoanRepaymentScheduleInstallment> newInstallments = new ArrayList<>(loan.getRepaymentScheduleInstallments());
        final MonetaryCurrency currency = loan.getCurrency();
        Money totalPrincipal = Money.zero(currency);
        final Money[] balances = moltaLoanBalanceService.retrieveIncomeForOverlappingPeriod(loan, transactionDate);
        boolean isInterestComponent = true;

        for (final LoanRepaymentScheduleInstallment installment : loan.getRepaymentScheduleInstallments()) {
            if (!DateUtils.isAfter(transactionDate, installment.getDueDate())) {
                totalPrincipal = totalPrincipal.plus(installment.getPrincipal(currency));
                newInstallments.remove(installment);
                if (DateUtils.isEqual(transactionDate, installment.getDueDate())) {
                    isInterestComponent = false;
                }
            }
        }

        for (LoanDisbursementDetails ldd : loan.getDisbursementDetails()) {
            if (ldd.actualDisbursementDate() == null) {
                totalPrincipal = Money.of(currency, totalPrincipal.getAmount().subtract(ldd.getPrincipal()));
            }
        }

        LocalDate installmentStartDate = loan.getDisbursementDate();
        if (!newInstallments.isEmpty()) {
            installmentStartDate = newInstallments.get(newInstallments.size() - 1).getDueDate();
        }

        int installmentNumber = newInstallments.size();
        if (!isInterestComponent) {
            installmentNumber++;
        }

        final LoanRepaymentScheduleInstallment newInstallment = new LoanRepaymentScheduleInstallment(null, newInstallments.size() + 1,
                installmentStartDate, transactionDate, totalPrincipal.getAmount(), balances[0].getAmount(), balances[1].getAmount(),
                balances[2].getAmount(), isInterestComponent, null);
        newInstallment.updateInstallmentNumber(newInstallments.size() + 1);
        newInstallments.add(newInstallment);
        loan.updateLoanScheduleOnForeclosure(newInstallments);

        final Set<LoanCharge> charges = loan.getActiveCharges();
        for (LoanCharge loanCharge : charges) {
            if (DateUtils.isAfter(loanCharge.getDueLocalDate(), transactionDate)) {
                loanCharge.setActive(false);
            } else if (loanCharge.getDueLocalDate() == null) {
                loanChargeService.recalculateLoanCharge(loan, loanCharge, 0);
                loanCharge.updateWaivedAmount(currency);
            }
        }

        for (LoanTransaction loanTransaction : loan.getLoanTransactions()) {
            if (loanTransaction.isChargesWaiver()) {
                for (LoanChargePaidBy chargePaidBy : loanTransaction.getLoanChargesPaid()) {
                    if ((chargePaidBy.getLoanCharge().isDueDateCharge()
                            && DateUtils.isBefore(transactionDate, chargePaidBy.getLoanCharge().getDueLocalDate()))
                            || (chargePaidBy.getLoanCharge().isInstalmentFee()
                                    && chargePaidBy.getInstallmentNumber() != null
                                    && chargePaidBy.getInstallmentNumber() > installmentNumber)) {
                        loanChargeValidator.validateRepaymentTypeTransactionNotBeforeAChargeRefund(
                                loanTransaction.getLoan(), loanTransaction, "reversed");
                        loanTransaction.reverse();
                    }
                }
            }
        }
    }
}