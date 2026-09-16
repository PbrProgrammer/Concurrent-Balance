package com.balance.service;

import com.balance.domain.exceptions.AccountAlreadyExistsException;
import com.balance.domain.exceptions.AccountNotFoundException;
import com.balance.domain.exceptions.InsufficientBalanceException;
import com.balance.domain.exceptions.InvalidAmountException;
import com.balance.domain.exceptions.InvalidRequestException;
import com.balance.domain.exceptions.SameAccountTransferException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class BalanceServiceTest {

    private BalanceService service;

    @BeforeEach
    void setUp() {
        service = new BalanceServiceImpl();
    }

    @Test
    void createAccountAndGetBalance() {
        service.createAccount("A", 1000);

        assertEquals(1000, service.getBalance("A"));
    }

    @Test
    void createAccountWithZeroBalance() {
        service.createAccount("A", 0);

        assertEquals(0, service.getBalance("A"));
    }

    @Test
    void creditIncreasesBalance() {
        service.createAccount("A", 1000);

        service.credit("A", 500, "TX-1");

        assertEquals(1500, service.getBalance("A"));
    }

    @Test
    void debitDecreasesBalanceWhenFundsAreSufficient() {
        service.createAccount("A", 1000);

        service.debit("A", 700, "TX-1");

        assertEquals(300, service.getBalance("A"));
    }

    @Test
    void debitFailsAndLeavesBalanceUnchangedWhenFundsAreInsufficient() {
        service.createAccount("A", 1000);

        assertThrows(InsufficientBalanceException.class, () -> service.debit("A", 1200, "TX-1"));
        assertEquals(1000, service.getBalance("A"));
    }

    @Test
    void debitNeverProducesNegativeBalance() {
        service.createAccount("A", 1000);

        assertThrows(InsufficientBalanceException.class, () -> service.debit("A", 1000 + 1, "TX-1"));
        assertEquals(1000, service.getBalance("A"));
    }

    @Test
    void transferMovesFundsAtomically() {
        service.createAccount("A", 1000);
        service.createAccount("B", 500);

        service.transfer("A", "B", 300, "TX-1");

        assertEquals(700, service.getBalance("A"));
        assertEquals(800, service.getBalance("B"));
    }

    @Test
    void transferFailsWhenSourceHasInsufficientFunds() {
        service.createAccount("A", 1000);
        service.createAccount("B", 500);

        assertThrows(InsufficientBalanceException.class, () -> service.transfer("A", "B", 1200, "TX-1"));
        assertEquals(1000, service.getBalance("A"));
        assertEquals(500, service.getBalance("B"));
    }

    @Test
    void unknownAccountOnGetBalance() {
        assertThrows(AccountNotFoundException.class, () -> service.getBalance("missing"));
    }

    @Test
    void unknownAccountOnCredit() {
        assertThrows(AccountNotFoundException.class, () -> service.credit("missing", 100, "TX-1"));
    }

    @Test
    void unknownAccountOnDebit() {
        assertThrows(AccountNotFoundException.class, () -> service.debit("missing", 100, "TX-1"));
    }

    @Test
    void unknownSourceAccountOnTransfer() {
        service.createAccount("B", 500);

        assertThrows(AccountNotFoundException.class, () -> service.transfer("missing", "B", 100, "TX-1"));
        assertEquals(500, service.getBalance("B"));
    }

    @Test
    void unknownDestinationAccountOnTransfer() {
        service.createAccount("A", 1000);

        assertThrows(AccountNotFoundException.class, () -> service.transfer("A", "missing", 100, "TX-1"));
        assertEquals(1000, service.getBalance("A"));
    }

    @Test
    void invalidAmountIsRejectedForCreditDebitAndTransfer() {
        service.createAccount("A", 1000);
        service.createAccount("B", 1000);

        assertThrows(InvalidAmountException.class, () -> service.credit("A", 0, "TX-1"));
        assertThrows(InvalidAmountException.class, () -> service.credit("A", -1, "TX-2"));
        assertThrows(InvalidAmountException.class, () -> service.debit("A", 0, "TX-3"));
        assertThrows(InvalidAmountException.class, () -> service.debit("A", -5, "TX-4"));
        assertThrows(InvalidAmountException.class, () -> service.transfer("A", "B", 0, "TX-5"));
        assertThrows(InvalidAmountException.class, () -> service.transfer("A", "B", -10, "TX-6"));

        assertEquals(1000, service.getBalance("A"));
        assertEquals(1000, service.getBalance("B"));
    }

    @Test
    void blankIdsAreRejected() {
        service.createAccount("A", 1000);

        assertThrows(InvalidRequestException.class, () -> service.credit(" ", 100, "TX-1"));
        assertThrows(InvalidRequestException.class, () -> service.credit("A", 100, " "));
        assertThrows(InvalidRequestException.class, () -> service.credit(null, 100, "TX-1"));
        assertThrows(InvalidRequestException.class, () -> service.debit("A", 100, null));
        assertThrows(InvalidRequestException.class, () -> service.createAccount("", 0));
    }

    @Test
    void sameAccountTransferIsRejected() {
        service.createAccount("A", 1000);

        assertThrows(SameAccountTransferException.class, () -> service.transfer("A", "A", 100, "TX-1"));
        assertEquals(1000, service.getBalance("A"));
    }

    @Test
    void sameAccountTransferIsRejectedEvenIfAccountDoesNotExist() {
        assertThrows(SameAccountTransferException.class, () -> service.transfer("A", "A", 100, "TX-1"));
    }

    @Test
    void duplicateAccountCreationIsRejected() {
        service.createAccount("A", 1000);

        assertThrows(AccountAlreadyExistsException.class, () -> service.createAccount("A", 0));
        assertEquals(1000, service.getBalance("A"));
    }

    @Test
    void negativeInitialBalanceIsRejected() {
        assertThrows(InvalidAmountException.class, () -> service.createAccount("A", -1));
        assertThrows(AccountNotFoundException.class, () -> service.getBalance("A"));
    }

    @Test
    void failedDebitDoesNotConsumeTransactionId() {
        service.createAccount("A", 100);

        assertThrows(InsufficientBalanceException.class, () -> service.debit("A", 200, "TX-1"));
        service.credit("A", 150, "TX-FUND");
        service.debit("A", 200, "TX-1");

        assertEquals(50, service.getBalance("A"));
    }
}
