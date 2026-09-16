package com.balance.service;

import com.balance.domain.exceptions.InsufficientBalanceException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class BalanceServiceIdempotencyTest {

    private BalanceService service;

    @BeforeEach
    void setUp() {
        service = new BalanceServiceImpl();
    }

    @Test
    void repeatedCreditWithSameTransactionIdAppliesOnce() {
        service.createAccount("A", 1000);

        service.credit("A", 100, "TX-1");
        service.credit("A", 100, "TX-1");
        service.credit("A", 100, "TX-1");

        assertEquals(1100, service.getBalance("A"));
    }

    @Test
    void repeatedDebitWithSameTransactionIdAppliesOnce() {
        service.createAccount("A", 1000);

        service.debit("A", 200, "TX-1");
        service.debit("A", 200, "TX-1");
        service.debit("A", 200, "TX-1");

        assertEquals(800, service.getBalance("A"));
    }

    @Test
    void successfulDebitRetryDoesNotFailAfterBalanceHasChanged() {
        service.createAccount("A", 1000);

        service.debit("A", 700, "TX-1");
        service.debit("A", 300, "TX-2");
        service.debit("A", 700, "TX-1");

        assertEquals(0, service.getBalance("A"));
    }

    @Test
    void repeatedTransferWithSameTransactionIdAppliesOnce() {
        service.createAccount("A", 1000);
        service.createAccount("B", 500);

        service.transfer("A", "B", 300, "TX-1");
        service.transfer("A", "B", 300, "TX-1");
        service.transfer("A", "B", 300, "TX-1");

        assertEquals(700, service.getBalance("A"));
        assertEquals(800, service.getBalance("B"));
    }

    @Test
    void differentTransactionIdsAreIndependent() {
        service.createAccount("A", 1000);

        service.credit("A", 100, "TX-1");
        service.credit("A", 100, "TX-2");
        service.credit("A", 100, "TX-3");

        assertEquals(1300, service.getBalance("A"));
    }

    @Test
    void failedTransferDoesNotBecomeIdempotentSuccess() {
        service.createAccount("A", 100);
        service.createAccount("B", 0);

        assertThrows(InsufficientBalanceException.class, () -> service.transfer("A", "B", 250, "TX-1"));
        service.credit("A", 200, "TX-FUND");
        service.transfer("A", "B", 250, "TX-1");

        assertEquals(50, service.getBalance("A"));
        assertEquals(250, service.getBalance("B"));
    }
}
