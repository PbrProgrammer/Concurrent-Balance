package com.balance.domain.exceptions;

public class SameAccountTransferException extends RuntimeException {

    public SameAccountTransferException(String accountId) {
        super("Source and destination accounts must be different, but both were: " + accountId);
    }
}
