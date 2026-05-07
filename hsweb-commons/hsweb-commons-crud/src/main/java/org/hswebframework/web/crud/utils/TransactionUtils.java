package org.hswebframework.web.crud.utils;

import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import org.springframework.transaction.NoTransactionException;
import org.springframework.transaction.ReactiveTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionManager;
import org.springframework.transaction.reactive.TransactionSynchronization;
import org.springframework.transaction.reactive.TransactionSynchronizationManager;
import org.springframework.transaction.reactive.TransactionalOperator;
import org.springframework.transaction.support.DefaultTransactionDefinition;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.function.Function;

import static org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRES_NEW;

@Slf4j
public class TransactionUtils {

    static TransactionManager transactionManager;

    static final DefaultTransactionDefinition PROPAGATION_REQUIRES_NEW_DEF
        = new DefaultTransactionDefinition(PROPAGATION_REQUIRES_NEW);

    public static void setup(TransactionManager transactionManager) {
        TransactionUtils.transactionManager = transactionManager;
    }

    public static <T> Mono<T> tryRunInTransaction(Mono<T> task, TransactionDefinition definition) {
        if (transactionManager instanceof ReactiveTransactionManager tx) {
            TransactionalOperator requiresNew =
                TransactionalOperator.create(
                    tx,
                    definition);
            return requiresNew.transactional(task);
        }
        return task;
    }

    public static <T> Flux<T> tryRunInTransaction(Flux<T> task, TransactionDefinition definition) {
        if (transactionManager instanceof ReactiveTransactionManager tx) {
            TransactionalOperator requiresNew =
                TransactionalOperator.create(
                    tx,
                    definition);
            return requiresNew.transactional(task);
        }
        return task;
    }

    public static Mono<Void> afterCommitWithOutTransaction(Mono<Void> task) {
        return TransactionUtils.registerSynchronization(
            new TransactionSynchronization() {

                @Override
                @NonNull
                public Mono<Void> afterCompletion(int status) {
                    if (status == TransactionSynchronization.STATUS_COMMITTED) {
                        return task;
                    }
                    return TransactionSynchronization.super.afterCompletion(status);
                }
            },
            sync -> sync.afterCompletion(TransactionSynchronization.STATUS_COMMITTED)
        );
    }

    public static Mono<Void> afterCommit(Mono<Void> task) {
        return TransactionUtils.registerSynchronization(
            new TransactionSynchronization() {
                @Override
                @NonNull
                public Mono<Void> afterCompletion(int status) {
                    if (status == TransactionSynchronization.STATUS_COMMITTED) {
                        // 开启新事务
                        return tryRunInTransaction(task, PROPAGATION_REQUIRES_NEW_DEF);
                    }
                    return TransactionSynchronization.super.afterCompletion(status);
                }
            },

            sync -> sync.afterCompletion(TransactionSynchronization.STATUS_COMMITTED)
        );
    }

    /**
     * @param synchronization   TransactionSynchronization
     * @param whenNoTransaction TransactionSynchronization
     * @return TransactionSynchronization
     * @see TransactionUtils#tryRunInTransaction(Flux, TransactionDefinition)
     */
    public static Mono<Void> registerSynchronization(TransactionSynchronization synchronization,
                                                     Function<TransactionSynchronization, Mono<Void>> whenNoTransaction) {
        return TransactionSynchronizationManager
            .forCurrentTransaction()
            .flatMap(manager -> {
                if (manager.isSynchronizationActive()) {
                    try {
                        manager.registerSynchronization(synchronization);
                    } catch (Throwable err) {
                        log.warn("register TransactionSynchronization [{}] error", synchronization, err);
                        return whenNoTransaction.apply(synchronization);
                    }
                    return Mono.empty();
                } else {
                    log.info("transaction is not active,execute TransactionSynchronization [{}] immediately.", synchronization);
                    return whenNoTransaction.apply(synchronization);
                }
            })
            .onErrorResume(NoTransactionException.class, err -> whenNoTransaction.apply(synchronization));
    }
}
