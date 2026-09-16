package com.balance.domain.exceptions;

public class InvalidAmountException extends RuntimeException {

    public InvalidAmountException(long amount) {
        super("Amount must be greater than zero, but was: " + amount);
    }

    public InvalidAmountException(String message) {
        super(message);
    }
}
