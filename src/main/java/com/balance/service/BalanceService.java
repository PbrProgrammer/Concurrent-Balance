package com.balance.service;

public interface BalanceService {

    void createAccount(String accountId, long initialBalance);

    void credit(String accountId, long amount, String transactionId);

    void debit(String accountId, long amount, String transactionId);

    void transfer(
            String sourceAccountId,
            String destinationAccountId,
            long amount,
            String transactionId
    );

    long getBalance(String accountId);
}
