package org.hswebframework.web.authorization.simple;

import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.hswebframework.web.authorization.*;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.function.Function;
import java.util.stream.Collectors;

@AllArgsConstructor
@Slf4j
public class CompositeReactiveAuthenticationManager implements ReactiveAuthenticationManager {

    private final List<ReactiveAuthenticationManagerProvider> providers;

    @Override
    public Mono<Authentication> authenticate(Mono<AuthenticationRequest> request) {
        return Flux
            .concat(
                providers
                    .stream()
                    .map(manager -> manager
                        .authenticate(request)
                        .onErrorResume((err) -> {
                            log.warn("get user authenticate error", err);
                            return Mono.empty();
                        }))
                    .collect(Collectors.toList()))
            .take(1)
            .next();
    }

    @Override
    public Mono<Authentication> getByUserId(String userId) {
        if (providers.size() == 1) {
            return providers.get(0).getByUserId(userId);
        }
        return Flux
            .fromStream(providers
                            .stream()
                            .map(manager -> manager
                                .getByUserId(userId)
                                .onErrorResume((err) -> {
                                    log.warn("get user [{}] authentication error", userId, err);
                                    return Mono.empty();
                                })
                            ))
            .flatMap(Function.identity())
            .as(AuthenticationUtils::merge);
    }

}
