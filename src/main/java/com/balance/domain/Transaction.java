package com.balance.domain;

public record Transaction(
        String transactionId,
        OperationType type,
        String sourceAccountId,
        String destinationAccountId,
        long amount
) {

    public static Transaction credit(String transactionId, String accountId, long amount) {
        return new Transaction(transactionId, OperationType.CREDIT, null, accountId, amount);
    }

    public static Transaction debit(String transactionId, String accountId, long amount) {
        return new Transaction(transactionId, OperationType.DEBIT, accountId, null, amount);
    }

    public static Transaction transfer(
            String transactionId,
            String sourceAccountId,
            String destinationAccountId,
            long amount
    ) {
        return new Transaction(
                transactionId,
                OperationType.TRANSFER,
                sourceAccountId,
                destinationAccountId,
                amount
        );
    }
}
