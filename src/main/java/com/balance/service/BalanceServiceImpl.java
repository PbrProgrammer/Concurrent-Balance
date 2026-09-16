package com.balance.service;

import com.balance.domain.Account;
import com.balance.domain.Transaction;
import com.balance.domain.exceptions.AccountAlreadyExistsException;
import com.balance.domain.exceptions.AccountNotFoundException;
import com.balance.domain.exceptions.InvalidAmountException;
import com.balance.domain.exceptions.InvalidRequestException;
import com.balance.domain.exceptions.SameAccountTransferException;
import org.springframework.stereotype.Service;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

@Service
public class BalanceServiceImpl implements BalanceService {

    private final ConcurrentMap<String, Account> accounts = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, Transaction> processedTransactions = new ConcurrentHashMap<>();

    @Override
    public void createAccount(String accountId, long initialBalance) {
        validateAccountId(accountId);
        if (initialBalance < 0) {
            throw new InvalidAmountException("Initial balance cannot be negative: " + initialBalance);
        }
        Account created = new Account(accountId, initialBalance);
        Account existing = accounts.putIfAbsent(accountId, created);
        if (existing != null) {
            throw new AccountAlreadyExistsException(accountId);
        }
    }

    @Override
    public void credit(String accountId, long amount, String transactionId) {
        validateAmount(amount);
        validateTransactionId(transactionId);
        Account account = requireAccount(accountId);

        account.lock();
        try {
            if (alreadyProcessed(transactionId)) {
                return;
            }
            account.credit(amount);
            processedTransactions.put(transactionId, Transaction.credit(transactionId, accountId, amount));
        } finally {
            account.unlock();
        }
    }

    @Override
    public void debit(String accountId, long amount, String transactionId) {
        validateAmount(amount);
        validateTransactionId(transactionId);
        Account account = requireAccount(accountId);

        account.lock();
        try {
            if (alreadyProcessed(transactionId)) {
                return;
            }
            account.debit(amount);
            processedTransactions.put(transactionId, Transaction.debit(transactionId, accountId, amount));
        } finally {
            account.unlock();
        }
    }

    @Override
    public void transfer(
            String sourceAccountId,
            String destinationAccountId,
            long amount,
            String transactionId
    ) {
        validateAmount(amount);
        validateTransactionId(transactionId);
        validateAccountId(sourceAccountId);
        validateAccountId(destinationAccountId);
        if (sourceAccountId.equals(destinationAccountId)) {
            throw new SameAccountTransferException(sourceAccountId);
        }

        Account source = requireAccount(sourceAccountId);
        Account destination = requireAccount(destinationAccountId);

        lockInAccountIdOrder(source, destination);
        try {
            if (alreadyProcessed(transactionId)) {
                return;
            }
            source.debit(amount);
            destination.credit(amount);
            processedTransactions.put(
                    transactionId,
                    Transaction.transfer(transactionId, sourceAccountId, destinationAccountId, amount)
            );
        } finally {
            unlockInReverseOrder(source, destination);
        }
    }

    @Override
    public long getBalance(String accountId) {
        Account account = requireAccount(accountId);
        account.lock();
        try {
            return account.balance();
        } finally {
            account.unlock();
        }
    }

    private boolean alreadyProcessed(String transactionId) {
        return processedTransactions.containsKey(transactionId);
    }

    /**
     * Acquire both account locks in a deterministic order (by accountId) so that
     * concurrent A→B and B→A transfers cannot deadlock.
     */
    private void lockInAccountIdOrder(Account source, Account destination) {
        Account first = min(source, destination);
        Account second = first == source ? destination : source;
        first.lock();
        try {
            second.lock();
        } catch (RuntimeException | Error e) {
            first.unlock();
            throw e;
        }
    }

    private void unlockInReverseOrder(Account source, Account destination) {
        Account first = min(source, destination);
        Account second = first == source ? destination : source;
        second.unlock();
        first.unlock();
    }

    private static Account min(Account left, Account right) {
        return left.id().compareTo(right.id()) < 0 ? left : right;
    }

    private Account requireAccount(String accountId) {
        validateAccountId(accountId);
        Account account = accounts.get(accountId);
        if (account == null) {
            throw new AccountNotFoundException(accountId);
        }
        return account;
    }

    private static void validateAmount(long amount) {
        if (amount <= 0) {
            throw new InvalidAmountException(amount);
        }
    }

    private static void validateAccountId(String accountId) {
        if (accountId == null || accountId.isBlank()) {
            throw new InvalidRequestException("Account id must not be blank");
        }
    }

    private static void validateTransactionId(String transactionId) {
        if (transactionId == null || transactionId.isBlank()) {
            throw new InvalidRequestException("Transaction id must not be blank");
        }
    }
}
