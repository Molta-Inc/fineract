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
package org.apache.fineract.accounting.glaccount.service;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.apache.fineract.accounting.glaccount.data.GLAccountData;
import org.apache.fineract.accounting.glaccount.data.GLAccountDataForLookup;
import org.apache.fineract.accounting.glaccount.domain.GLAccountType;
import org.apache.fineract.accounting.journalentry.data.JournalEntryAssociationParametersData;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

public interface GLAccountReadPlatformService {

    List<GLAccountData> retrieveAllGLAccounts(Integer accountClassification, String searchParam, Integer usage,
            Boolean manualTransactionsAllowed, Boolean disabled, JournalEntryAssociationParametersData associationParametersData);

    GLAccountData retrieveGLAccountById(long glAccountId, JournalEntryAssociationParametersData associationParametersData);

    List<GLAccountData> retrieveAllEnabledDetailGLAccounts();

    List<GLAccountData> retrieveAllEnabledDetailGLAccounts(GLAccountType accountType);

    List<GLAccountData> retrieveAllEnabledHeaderGLAccounts(GLAccountType accountType);

    GLAccountData retrieveNewGLAccountDetails(Integer type);

    List<GLAccountDataForLookup> retrieveAccountsByTagId(Long ruleId, Integer transactionType);

    /**
     * Retrieve GL Account balance from materialized balance table
     *
     * The balance is retrieved from acc_gl_account_balance table which is
     * automatically maintained by database trigger for performance.
     *
     * @param glAccountId the GL account ID
     * @return Map containing balance, totalDebits, and totalCredits
     */
    Map<String, BigDecimal> retrieveGLAccountBalance(long glAccountId);

    /**
     * Retrieve GL Account balances for multiple accounts from materialized balance table
     *
     * The balances are retrieved from acc_gl_account_balance table which is
     * automatically maintained by database trigger for performance.
     *
     * @param glAccountIds list of GL account IDs
     * @return List of Maps, each containing glAccountId, balance, totalDebits, and totalCredits.
     *         Returns null for accounts that don't exist instead of throwing an error.
     */
    List<Map<String, BigDecimal>> retrieveGLAccountBalances(List<Long> glAccountIds);

    /**
     * Retrieve aggregated/total balances for all GL accounts matching a GL code pattern
     *
     * The balances are retrieved from acc_gl_account_balance table which is
     * automatically maintained by database trigger for performance.
     * Returns a single total/summary of all matching accounts.
     *
     * @param glCodePattern the GL code pattern (e.g., "2700-CL-" to match accounts like 2700-CL-0000001)
     * @return Map containing totalBalance, totalDebits, totalCredits, and accountCount
     */
    Map<String, Object> retrieveGLAccountBalancesTotalByCodePattern(String glCodePattern);
}
