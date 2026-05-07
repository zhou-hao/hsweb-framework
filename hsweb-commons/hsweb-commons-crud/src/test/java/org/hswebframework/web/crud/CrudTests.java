package org.hswebframework.web.crud;

import lombok.SneakyThrows;
import org.hswebframework.web.crud.entity.CustomTestEntity;
import org.hswebframework.web.crud.entity.TestEntity;
import org.hswebframework.web.crud.events.EntityBeforeModifyEvent;
import org.hswebframework.web.crud.service.CustomTestCustom;
import org.hswebframework.web.crud.service.TestEntityService;
import org.hswebframework.web.crud.utils.TransactionUtils;
import org.junit.Assert;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.event.EventListener;
import org.springframework.test.context.junit4.SpringJUnit4ClassRunner;
import org.springframework.transaction.reactive.TransactionalOperator;
import reactor.core.Disposable;
import reactor.core.Disposables;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import reactor.test.StepVerifier;
import reactor.util.context.Context;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

@SpringBootTest(classes = {TestApplication.class, TestEntityService.class, CustomTestCustom.class},
    properties = {
        "spring.r2dbc.pool.enabled=true",
        "spring.r2dbc.pool.max-size=32",
        "logging.level.org.springframework.r2dbc.connection=debug"
    })
@RunWith(SpringJUnit4ClassRunner.class)
public class CrudTests {

    @Autowired
    private TestEntityService service;

    @Autowired
    private TransactionalOperator transactionalOperator;

    @Test
    public void test() {

        CustomTestEntity entity = new CustomTestEntity();
        entity.setExt("xxx");
        entity.setAge(1);
        entity.setName("test");

        entity.setExtension("extName", "test");

        service.insert(entity)
               .as(StepVerifier::create)
               .expectNext(1)
               .verifyComplete();
        Assert.assertNotNull(entity.getId());

        service.findById(entity.getId())
               .doOnNext(System.out::println)
               .as(StepVerifier::create)
               .expectNextMatches(e -> e instanceof CustomTestEntity)
               .verifyComplete();

        service.createUpdate()
               .set("name", "test2")
               .where("id", entity.getId())
               .execute()
               .as(StepVerifier::create)
               .expectNext(1)
               .verifyComplete();
    }

    @Test
    @SneakyThrows
    public void testMultiThread() {
        Flux.range(0, 100)
            .map(e -> {
                CustomTestEntity entity = new CustomTestEntity();
                entity.setExt("xxx-" + e);
                entity.setAge(1);
                entity.setName("mt-" + e);
                return entity;
            })
            .cast(TestEntity.class)
            .as(service::save)
            .block();

        Disposable.Swap disposable = Disposables.swap();

        CountDownLatch latch = new CountDownLatch(50);
        disposable.update(
            service
                .createQuery()
                .like(CustomTestEntity::getName, "mt-%")
                .fetch()
                .flatMap(e -> service
                    .updateById(e.getId(), e)
                    .flatMap(i -> TransactionUtils
                        .afterCommit(Mono.deferContextual((c) -> service
                             .updateById(e.getId(), e)
                             .doOnNext(x -> {
                                 latch.countDown();
                                 if (latch.getCount() <= 0) {
                                     disposable.dispose();
                                 }
                             })
                             //.as(transactionalOperator::transactional)
                             .subscribeOn(Schedulers.boundedElastic())
                             .then())))
                    // .subscribeOn(Schedulers.boundedElastic())
                )
               // .as(transactionalOperator::transactional)
                .subscribe()
        );
        Assert.assertTrue(latch.await(20, TimeUnit.SECONDS));

        Thread.sleep(2000);

    }
}
