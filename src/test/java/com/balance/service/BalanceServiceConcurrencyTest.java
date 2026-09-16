package com.balance.service;

import com.balance.domain.exceptions.InsufficientBalanceException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BalanceServiceConcurrencyTest {

    private BalanceService service;

    @BeforeEach
    void setUp() {
        service = new BalanceServiceImpl();
    }

    @Test
    @Timeout(15)
    void concurrentDuplicateCreditsAffectBalanceOnce() throws Exception {
        service.createAccount("A", 1000);

        runConcurrently(100, () -> service.credit("A", 100, "TX-1"));

        assertEquals(1100, service.getBalance("A"));
    }

    @Test
    @Timeout(15)
    void concurrentDuplicateDebitsAffectBalanceOnce() throws Exception {
        service.createAccount("A", 1000);

        runConcurrently(100, () -> service.debit("A", 100, "TX-1"));

        assertEquals(900, service.getBalance("A"));
    }

    @Test
    @Timeout(15)
    void concurrentDuplicateTransfersAffectBalancesOnce() throws Exception {
        service.createAccount("A", 1000);
        service.createAccount("B", 500);

        runConcurrently(100, () -> service.transfer("A", "B", 200, "TX-1"));

        assertEquals(800, service.getBalance("A"));
        assertEquals(700, service.getBalance("B"));
    }

    @Test
    @Timeout(15)
    void concurrentUniqueDebitsNeverGoNegativeAndPreserveExactBalance() throws Exception {
        service.createAccount("A", 100000);
        int threads = 100000;
        AtomicInteger successes = new AtomicInteger();
        AtomicInteger insufficient = new AtomicInteger();
        AtomicBoolean sawNegative = new AtomicBoolean(false);

        Thread observer = startNonNegativeObserver("A", sawNegative);
        try {
            runConcurrently(threads, index -> {
                try {
                    service.debit("A", 2, "TX-" + index);
                    successes.incrementAndGet();
                } catch (InsufficientBalanceException ignored) {
                    insufficient.incrementAndGet();
                }
            });
        } finally {
            stopObserver(observer);
        }

        assertEquals(50000, successes.get());
        assertEquals(50000, insufficient.get());
        assertEquals(0, service.getBalance("A"));
        assertFalse(sawNegative.get());
    }

    @Test
    @Timeout(20)
    void concurrentOperationsOnIndependentAccountsDoNotInterfere() throws Exception {
        service.createAccount("A", 100_000);
        service.createAccount("B", 100_000);
        service.createAccount("C", 100_000);

        int operationsPerAccount = 1_000;
        List<ThrowingConsumer> tasks = new ArrayList<>();
        for (int i = 0; i < operationsPerAccount; i++) {
            int index = i;
            tasks.add(ignored -> service.credit("A", 1, "A-CR-" + index));
            tasks.add(ignored -> service.credit("B", 1, "B-CR-" + index));
            tasks.add(ignored -> service.credit("C", 1, "C-CR-" + index));
            tasks.add(ignored -> service.debit("A", 1, "A-DB-" + index));
            tasks.add(ignored -> service.debit("B", 1, "B-DB-" + index));
            tasks.add(ignored -> service.debit("C", 1, "C-DB-" + index));
        }

        runConcurrently(tasks);

        assertEquals(100_000, service.getBalance("A"));
        assertEquals(100_000, service.getBalance("B"));
        assertEquals(100_000, service.getBalance("C"));
    }

    @Test
    @Timeout(20)
    void concurrentBidirectionalTransfersDoNotDeadlockAndConserveMoney() throws Exception {
        service.createAccount("A", 10_000);
        service.createAccount("B", 10_000);
        int transfersEachWay = 200;
        AtomicInteger aToBSuccess = new AtomicInteger();
        AtomicInteger bToASuccess = new AtomicInteger();
        AtomicInteger failures = new AtomicInteger();

        List<ThrowingConsumer> tasks = new ArrayList<>();
        for (int i = 0; i < transfersEachWay; i++) {
            int index = i;
            tasks.add(ignored -> {
                try {
                    service.transfer("A", "B", 7, "A-B-" + index);
                    aToBSuccess.incrementAndGet();
                } catch (InsufficientBalanceException ignoredEx) {
                    failures.incrementAndGet();
                }
            });
            tasks.add(ignored -> {
                try {
                    service.transfer("B", "A", 5, "B-A-" + index);
                    bToASuccess.incrementAndGet();
                } catch (InsufficientBalanceException ignoredEx) {
                    failures.incrementAndGet();
                }
            });
        }

        runConcurrently(tasks);

        long balanceA = service.getBalance("A");
        long balanceB = service.getBalance("B");
        long expectedA = 10_000L - (7L * aToBSuccess.get()) + (5L * bToASuccess.get());
        long expectedB = 10_000L + (7L * aToBSuccess.get()) - (5L * bToASuccess.get());

        assertEquals(expectedA, balanceA);
        assertEquals(expectedB, balanceB);
        assertEquals(20_000L, balanceA + balanceB);
        assertTrue(balanceA >= 0);
        assertTrue(balanceB >= 0);
        assertEquals(transfersEachWay, aToBSuccess.get());
        assertEquals(transfersEachWay, bToASuccess.get());
        assertEquals(0, failures.get());
    }

    @Test
    @Timeout(20)
    void concurrentTransfersAcrossThreeAccountsConserveTotalMoney() throws Exception {
        service.createAccount("A", 100_000);
        service.createAccount("B", 100_000);
        service.createAccount("C", 100_000);
        int transfers = 300;

        List<ThrowingConsumer> tasks = new ArrayList<>();
        for (int i = 0; i < transfers; i++) {
            int index = i;
            tasks.add(ignored -> service.transfer("A", "B", 1, "AB-" + index));
            tasks.add(ignored -> service.transfer("B", "C", 1, "BC-" + index));
            tasks.add(ignored -> service.transfer("C", "A", 1, "CA-" + index));
        }

        runConcurrently(tasks);

        long balanceA = service.getBalance("A");
        long balanceB = service.getBalance("B");
        long balanceC = service.getBalance("C");

        assertEquals(300_000L, balanceA + balanceB + balanceC);
        assertEquals(100_000L, balanceA);
        assertEquals(100_000L, balanceB);
        assertEquals(100_000L, balanceC);
    }

    @Test
    @Timeout(15)
    void concurrentCreditAndDebitOnSameAccountProduceExactBalance() throws Exception {
        service.createAccount("A", 1_000);
        int operations = 200;

        List<ThrowingConsumer> tasks = new ArrayList<>();
        for (int i = 0; i < operations; i++) {
            int index = i;
            tasks.add(ignored -> service.credit("A", 10, "CR-" + index));
            tasks.add(ignored -> service.debit("A", 10, "DB-" + index));
        }

        runConcurrently(tasks);

        assertEquals(1_000, service.getBalance("A"));
    }

    private void runConcurrently(int threadCount, Runnable action) throws Exception {
        List<ThrowingConsumer> tasks = new ArrayList<>(threadCount);
        for (int i = 0; i < threadCount; i++) {
            tasks.add(index -> action.run());
        }
        runConcurrently(tasks);
    }

    private void runConcurrently(int threadCount, ThrowingConsumer action) throws Exception {
        List<ThrowingConsumer> tasks = new ArrayList<>(threadCount);
        for (int i = 0; i < threadCount; i++) {
            tasks.add(action);
        }
        runConcurrently(tasks);
    }

    private void runConcurrently(List<ThrowingConsumer> actions) throws Exception {
        CyclicBarrier start = new CyclicBarrier(actions.size());
        List<Callable<Void>> tasks = new ArrayList<>(actions.size());
        for (int i = 0; i < actions.size(); i++) {
            int index = i;
            ThrowingConsumer action = actions.get(i);
            tasks.add(() -> {
                start.await();
                action.accept(index);
                return null;
            });
        }

        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<Void>> futures = executor.invokeAll(tasks, 12, TimeUnit.SECONDS);
            for (Future<Void> future : futures) {
                assertFalse(future.isCancelled(), "A concurrent task timed out; possible deadlock");
                future.get();
            }
        }
    }

    private Thread startNonNegativeObserver(String accountId, AtomicBoolean sawNegative) {
        Thread observer = new Thread(() -> {
            while (!Thread.currentThread().isInterrupted()) {
                if (service.getBalance(accountId) < 0) {
                    sawNegative.set(true);
                    return;
                }
            }
        }, "balance-observer");
        observer.setDaemon(true);
        observer.start();
        return observer;
    }

    private static void stopObserver(Thread observer) throws InterruptedException {
        observer.interrupt();
        observer.join(1_000);
    }

    @FunctionalInterface
    private interface ThrowingConsumer {
        void accept(int index) throws Exception;
    }
}
