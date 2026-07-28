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
package org.apache.fineract.portfolio.loanaccount_molta.api;

import jakarta.ws.rs.*;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.UriInfo;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.fineract.infrastructure.core.api.ApiRequestParameterHelper;
import org.apache.fineract.infrastructure.core.api.DateParam;
import org.apache.fineract.infrastructure.core.data.DateFormat;
import org.apache.fineract.infrastructure.core.serialization.ApiRequestJsonSerializationSettings;
import org.apache.fineract.infrastructure.core.serialization.DefaultToApiJsonSerializer;
import org.apache.fineract.infrastructure.core.service.DateUtils;
import org.apache.fineract.infrastructure.core.service.Page;
import org.apache.fineract.infrastructure.core.service.SearchParameters;
import org.apache.fineract.infrastructure.security.service.PlatformSecurityContext;
import org.apache.fineract.infrastructure.security.service.SqlValidator;
import org.apache.fineract.portfolio.loanaccount.api.LoanApiConstants;
import org.apache.fineract.portfolio.loanaccount.data.LoanAccountData;
import org.apache.fineract.portfolio.loanaccount.data.LoanTransactionData;
import org.apache.fineract.portfolio.loanaccount_molta.service.LoanMoltaReadPlatformService;
import org.apache.fineract.portfolio.loanaccount_molta.service.MoltaForeclosureService;
import org.apache.fineract.portfolio.loanproduct.LoanProductConstants;
import org.apache.fineract.useradministration.domain.AppUser;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

@Slf4j
@Path("/v1/loans/molta")
@Component
@RequiredArgsConstructor
public class LoansMoltaApiResource {

    private static final Set<String> LOAN_DATA_PARAMETERS = new HashSet<>(Arrays.asList("id", "accountNo", "status", "externalId",
            "clientId", "group", "loanProductId", "loanProductName", "loanProductDescription", "isLoanProductLinkedToFloatingRate",
            "fundId", "fundName", "loanPurposeId", "loanPurposeName", "loanOfficerId", "loanOfficerName", "currency", "principal",
            "totalOverpaid", "inArrearsTolerance", "termFrequency", "termPeriodFrequencyType", "numberOfRepayments", "repaymentEvery",
            "interestRatePerPeriod", "annualInterestRate", "repaymentFrequencyType", "transactionProcessingStrategyCode",
            "transactionProcessingStrategyName", "interestRateFrequencyType", "amortizationType", "interestType",
            "interestCalculationPeriodType", LoanProductConstants.ALLOW_PARTIAL_PERIOD_INTEREST_CALCUALTION_PARAM_NAME,
            "expectedFirstRepaymentOnDate", "graceOnPrincipalPayment", "recurringMoratoriumOnPrincipalPeriods", "graceOnInterestPayment",
            "graceOnInterestCharged", "interestChargedFromDate", "timeline", "totalFeeChargesAtDisbursement", "summary",
            "repaymentSchedule", "transactions", "charges", "collateral", "guarantors", "meeting", "productOptions",
            "amortizationTypeOptions", "interestTypeOptions", "interestCalculationPeriodTypeOptions", "repaymentFrequencyTypeOptions",
            "repaymentFrequencyNthDayTypeOptions", "repaymentFrequencyDaysOfWeekTypeOptions", "termFrequencyTypeOptions",
            "interestRateFrequencyTypeOptions", "fundOptions", "repaymentStrategyOptions", "chargeOptions", "loanOfficerOptions",
            "loanPurposeOptions", "loanCollateralOptions", "chargeTemplate", "calendarOptions", "syncDisbursementWithMeeting",
            "loanCounter", "loanProductCounter", "notes", "accountLinkingOptions", "linkedAccount", "interestRateDifferential",
            "isFloatingInterestRate", "interestRatesPeriods", "lastClosedBusinessDate", LoanApiConstants.canUseForTopup,
            LoanApiConstants.isTopup, LoanApiConstants.loanIdToClose, LoanApiConstants.topupAmount,
            LoanApiConstants.clientActiveLoanOptions, LoanApiConstants.datatables, LoanProductConstants.RATES_PARAM_NAME,
            LoanApiConstants.MULTIDISBURSE_DETAILS_PARAMNAME, LoanApiConstants.EMI_AMOUNT_VARIATIONS_PARAMNAME,
            LoanApiConstants.COLLECTION_PARAMNAME, LoanApiConstants.INTEREST_RECOGNITION_ON_DISBURSEMENT_DATE));

    private static final String RESOURCE_NAME_FOR_PERMISSIONS = "LOAN";

    private static final int FORECLOSURE_DEFAULT_DAYS_AHEAD = 30;

    private final PlatformSecurityContext context;
    private final LoanMoltaReadPlatformService loanMoltaReadPlatformService;
    private final MoltaForeclosureService moltaForeclosureService;
    private final DefaultToApiJsonSerializer<LoanAccountData> toApiJsonSerializer;
    private final DefaultToApiJsonSerializer<LoanTransactionData> loanTransactionSerializer;
    private final DefaultToApiJsonSerializer<Map> changesSerializer;
    private final ApiRequestParameterHelper apiRequestParameterHelper;
    private final SqlValidator sqlValidator;

    @GET
    @Consumes({ MediaType.APPLICATION_JSON })
    @Produces({ MediaType.APPLICATION_JSON })
    public String retrieveAll(@Context final UriInfo uriInfo,
            @QueryParam("externalId") final String externalId,
            @QueryParam("offset") final Integer offset,
            @QueryParam("limit") final Integer limit,
            @QueryParam("orderBy") final String orderBy,
            @QueryParam("sortOrder") final String sortOrder,
            @QueryParam("accountNo") final String accountNo,
            @QueryParam("status") final String status,
            @QueryParam("sortByOverdue") final Boolean sortByOverdue) {

        AppUser appUser = this.context.authenticatedUser();
        appUser.validateHasReadPermission(RESOURCE_NAME_FOR_PERMISSIONS);
        boolean hasPermission = hasPermission(appUser);

        if (appUser.getStaff() == null && !hasPermission) {
            final Page<LoanAccountData> loanBasicDetails = new Page<>(new ArrayList<>(), 0);
            final ApiRequestJsonSerializationSettings settings = this.apiRequestParameterHelper.process(uriInfo.getQueryParameters());
            return this.toApiJsonSerializer.serialize(settings, loanBasicDetails, LOAN_DATA_PARAMETERS);
        }

        sqlValidator.validate(orderBy);
        sqlValidator.validate(sortOrder);
        sqlValidator.validate(accountNo);
        sqlValidator.validate(externalId);
        final SearchParameters searchParameters = SearchParameters.builder().accountNo(accountNo).sortOrder(sortOrder)
                .externalId(externalId).offset(offset).limit(limit).orderBy(orderBy).status(status).build();

        final Page<LoanAccountData> loanBasicDetails = this.loanMoltaReadPlatformService.retrieveAll(searchParameters, hasPermission, sortByOverdue);

        final ApiRequestJsonSerializationSettings settings = this.apiRequestParameterHelper.process(uriInfo.getQueryParameters());
        return this.toApiJsonSerializer.serialize(settings, loanBasicDetails, LOAN_DATA_PARAMETERS);
    }

    @GET
    @Path("{loanId}/transactions/template")
    @Consumes({ MediaType.APPLICATION_JSON })
    @Produces({ MediaType.APPLICATION_JSON })
    public String retrieveForeclosureTemplate(@PathParam("loanId") final Long loanId,
            @QueryParam("command") final String commandParam,
            @QueryParam("transactionDate") final DateParam transactionDateParam,
            @QueryParam("dateFormat") final String rawDateFormat,
            @QueryParam("locale") final String locale,
            @Context final UriInfo uriInfo) {

        this.context.authenticatedUser().validateHasReadPermission(RESOURCE_NAME_FOR_PERMISSIONS);

        if (!"foreclosure".equalsIgnoreCase(commandParam)) {
            throw new jakarta.ws.rs.WebApplicationException(jakarta.ws.rs.core.Response.Status.BAD_REQUEST);
        }

        LocalDate transactionDate;
        if (transactionDateParam == null) {
            transactionDate = DateUtils.getBusinessLocalDate().plusDays(FORECLOSURE_DEFAULT_DAYS_AHEAD);
        } else {
            final DateFormat dateFormat = rawDateFormat == null ? null : new DateFormat(rawDateFormat);
            transactionDate = transactionDateParam.getDate("transactionDate", dateFormat, locale);
        }

        final LoanTransactionData transactionData = moltaForeclosureService.retrieveForeclosureTemplate(loanId, transactionDate);
        final ApiRequestJsonSerializationSettings settings = this.apiRequestParameterHelper.process(uriInfo.getQueryParameters());
        return this.loanTransactionSerializer.serialize(settings, transactionData);
    }

    @POST
    @Path("{loanId}/transactions")
    @Consumes({ MediaType.APPLICATION_JSON })
    @Produces({ MediaType.APPLICATION_JSON })
    public String performForeclosure(@PathParam("loanId") final Long loanId,
            @QueryParam("command") final String commandParam,
            final String body) {

        this.context.authenticatedUser();

        if (!"foreclosure".equalsIgnoreCase(commandParam)) {
            throw new jakarta.ws.rs.WebApplicationException(jakarta.ws.rs.core.Response.Status.BAD_REQUEST);
        }

        final Map<String, Object> changes = moltaForeclosureService.performForeclosure(loanId, body);
        return this.changesSerializer.serialize(changes);
    }

    private boolean hasPermission(AppUser appUser) {
        final String ALL_FUNCTIONS = "ALL_FUNCTIONS";
        final String ALL_FUNCTIONS_READ = "ALL_FUNCTIONS_READ";

        try {
            appUser.validateHasReadPermission(ALL_FUNCTIONS);
            return true;
        } catch (Exception e) {
            log.error("Loan - user has no permission all functions");
        }

        try {
            appUser.validateHasReadPermission(ALL_FUNCTIONS_READ);
            return true;
        } catch (Exception e) {
            log.error("Loan - user has no permission to read all functions");
        }

        return false;
    }
}