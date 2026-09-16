package com.balance.domain.exceptions;

public class InsufficientBalanceException extends RuntimeException {

    public InsufficientBalanceException(String accountId, long requested, long available) {
        super("Insufficient balance for account %s: requested %d, available %d"
                .formatted(accountId, requested, available));
    }
}
