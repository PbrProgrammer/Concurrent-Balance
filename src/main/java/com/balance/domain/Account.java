package com.balance.domain;

import com.balance.domain.exceptions.InsufficientBalanceException;

import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Mutable in-memory account. The lock is the unit of concurrency for this account:
 * all balance reads and writes must be performed while holding it.
 */
public final class Account {

    private final String id;
    private long balance;
    private final Lock lock = new ReentrantLock();

    public Account(String id, long initialBalance) {
        this.id = id;
        this.balance = initialBalance;
    }

    public String id() {
        return id;
    }

    public long balance() {
        return balance;
    }

    public void credit(long amount) {
        balance = Math.addExact(balance, amount);
    }

    public void debit(long amount) {
        if (balance < amount) {
            throw new InsufficientBalanceException(id, amount, balance);
        }
        balance -= amount;
    }

    public void lock() {
        lock.lock();
    }

    public void unlock() {
        lock.unlock();
    }
}
