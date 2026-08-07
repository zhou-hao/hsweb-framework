package org.hswebframework.web.crud.service;

import org.hswebframework.ezorm.core.param.QueryParam;
import org.hswebframework.ezorm.rdb.mapping.ReactiveQuery;
import org.hswebframework.ezorm.rdb.mapping.ReactiveRepository;
import org.hswebframework.web.api.crud.entity.PagerResult;
import org.hswebframework.web.api.crud.entity.QueryParamEntity;
import org.hswebframework.web.crud.query.PagerQueryPolicy;
import org.junit.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import reactor.test.StepVerifier;
import reactor.util.context.ContextView;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class ReactiveCrudServicePagerPolicyTest {

    @Test
    public void testDefaultServiceUsesReactorContextAfterAsyncBoundary() {
        QueryParamEntity source = query(100);
        List<QueryParamEntity> appliedParams = new ArrayList<>();
        TestService service = new TestService(mockQuery(appliedParams));
        PagerQueryPolicy contextPolicy = PagerQueryPolicy.clamp(15);

        StepVerifier
            .create(Mono
                .just(source)
                .publishOn(Schedulers.parallel())
                .flatMap(service::queryPager)
                .contextWrite(context -> PagerQueryPolicy.writeTo(context, contextPolicy)))
            .assertNext(result -> assertPage(result, 15))
            .verifyComplete();

        assertEquals(15, appliedParams.get(0).getPageSize());
    }

    @Test
    public void testServicePolicyOverridesReactorContext() {
        QueryParamEntity source = query(100);
        List<QueryParamEntity> appliedParams = new ArrayList<>();
        CustomPolicyService service = new CustomPolicyService(
            mockQuery(appliedParams),
            PagerQueryPolicy.clamp(7));

        StepVerifier
            .create(service
                .queryPager(source)
                .contextWrite(context -> PagerQueryPolicy.writeTo(
                    context,
                    PagerQueryPolicy.clamp(20))))
            .assertNext(result -> assertPage(result, 7))
            .verifyComplete();

        assertEquals(1, service.getResolutionCount());
        assertEquals(7, appliedParams.get(0).getPageSize());
    }

    @Test
    public void testExplicitPolicyOverridesServiceAndReactorContext() {
        QueryParamEntity source = query(100);
        List<QueryParamEntity> appliedParams = new ArrayList<>();
        CustomPolicyService service = new CustomPolicyService(
            mockQuery(appliedParams),
            PagerQueryPolicy.clamp(7));

        StepVerifier
            .create(service
                .queryPager(source, Object::toString, PagerQueryPolicy.clamp(13))
                .contextWrite(context -> PagerQueryPolicy.writeTo(
                    context,
                    PagerQueryPolicy.clamp(20))))
            .assertNext(result -> {
                assertPage(result, 13);
                assertEquals("0", result.getData().get(0));
            })
            .verifyComplete();

        assertEquals(0, service.getResolutionCount());
        assertEquals(13, appliedParams.get(0).getPageSize());
    }

    @Test
    public void testExplicitPolicyConvenienceOverload() {
        QueryParamEntity source = query(100);
        List<QueryParamEntity> appliedParams = new ArrayList<>();
        TestService service = new TestService(mockQuery(appliedParams));

        StepVerifier
            .create(service.queryPager(source, PagerQueryPolicy.clamp(9)))
            .assertNext(result -> assertPage(result, 9))
            .verifyComplete();

        assertEquals(9, appliedParams.get(0).getPageSize());
    }

    private static QueryParamEntity query(int pageSize) {
        QueryParamEntity source = QueryParamEntity.of();
        source.setPageSize(pageSize);
        source.setTotal(200);
        return source;
    }

    private static void assertPage(PagerResult<?> result, int pageSize) {
        assertEquals(200, result.getTotal());
        assertEquals(pageSize, result.getPageSize());
        assertEquals(pageSize, result.getData().size());
    }

    @SuppressWarnings("unchecked")
    private static ReactiveQuery<Integer> mockQuery(List<QueryParamEntity> appliedParams) {
        ReactiveQuery<Integer> query = mock(ReactiveQuery.class);
        when(query.setParam(any(QueryParam.class))).thenAnswer(invocation -> {
            appliedParams.add(QueryParamEntity.of(invocation.getArgument(0)));
            return query;
        });
        when(query.fetch()).thenReturn(Flux.range(0, 200));
        return query;
    }

    private static class TestService implements ReactiveCrudService<Integer, Integer> {

        private final ReactiveQuery<Integer> query;

        private TestService(ReactiveQuery<Integer> query) {
            this.query = query;
        }

        @Override
        public ReactiveRepository<Integer, Integer> getRepository() {
            throw new UnsupportedOperationException();
        }

        @Override
        public ReactiveQuery<Integer> createQuery() {
            return query;
        }
    }

    private static final class CustomPolicyService extends TestService {

        private final PagerQueryPolicy policy;

        private final AtomicInteger resolutionCount = new AtomicInteger();

        private CustomPolicyService(ReactiveQuery<Integer> query,
                                    PagerQueryPolicy policy) {
            super(query);
            this.policy = policy;
        }

        @Override
        public PagerQueryPolicy resolvePagerQueryPolicy(ContextView contextView) {
            resolutionCount.incrementAndGet();
            return policy;
        }

        private int getResolutionCount() {
            return resolutionCount.get();
        }
    }
}
