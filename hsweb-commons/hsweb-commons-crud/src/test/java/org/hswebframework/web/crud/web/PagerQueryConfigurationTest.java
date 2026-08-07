package org.hswebframework.web.crud.web;

import org.hswebframework.web.crud.query.PagerQueryPolicy;
import org.junit.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ReactiveWebApplicationContextRunner;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.WebFilter;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import reactor.test.StepVerifier;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;

public class PagerQueryConfigurationTest {

    private final ReactiveWebApplicationContextRunner contextRunner =
        new ReactiveWebApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(CommonWebFluxConfiguration.class))
            .withPropertyValues("hsweb.webflux.response-wrapper.enabled=false");

    @Test
    public void testPropertiesCreatePolicyAndFilterPropagatesIt() {
        contextRunner
            .withPropertyValues(
                "hsweb.web.pageable.max-page-size=321",
                "hsweb.web.pageable.overflow-policy=reject")
            .run(context -> {
                PagerQueryPolicy policy = context.getBean(PagerQueryPolicy.class);
                assertEquals(321, policy.getMaxPageSize());
                assertEquals(
                    PagerQueryPolicy.OverflowPolicy.REJECT,
                    policy.getOverflowPolicy());

                WebFilter filter = context.getBean(
                    "pagerQueryPolicyWebFilter",
                    WebFilter.class);
                MockServerWebExchange exchange = MockServerWebExchange.from(
                    MockServerHttpRequest.get("/pager"));

                StepVerifier
                    .create(filter.filter(
                        exchange,
                        ignored -> Mono
                            .just(1)
                            .publishOn(Schedulers.parallel())
                            .then(Mono.deferContextual(contextView -> {
                                assertSame(policy, PagerQueryPolicy.from(contextView));
                                return Mono.empty();
                            }))))
                    .verifyComplete();
            });
    }

    @Test
    public void testCustomPolicyBeanBacksOffAutoConfiguration() {
        PagerQueryPolicy custom = new PagerQueryPolicy(
            77,
            PagerQueryPolicy.OverflowPolicy.CLAMP);

        contextRunner
            .withBean(PagerQueryPolicy.class, () -> custom)
            .run(context -> assertSame(
                custom,
                context.getBean(PagerQueryPolicy.class)));
    }
}
