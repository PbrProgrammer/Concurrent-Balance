package com.balance.domain.exceptions;

public class AccountAlreadyExistsException extends RuntimeException {

    public AccountAlreadyExistsException(String accountId) {
        super("Account already exists: " + accountId);
    }
}
