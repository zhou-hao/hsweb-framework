/*
 *  Copyright 2019 http://www.hswebframework.org
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *        http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 *
 */

package org.hswebframework.web.authorization;

import com.google.common.collect.Lists;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.util.context.Context;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;

/**
 * 响应式权限保持器,用于响应式方式获取当前登录用户的权限信息.
 * 例如:
 * <pre>{@code
 *     @RequestMapping("/example")
 *     public Mono<Authorization> example(){
 *         return ReactiveAuthenticationHolder.get();
 *     }
 *     }
 * </pre>
 *
 * @author zhouhao
 * @see ReactiveAuthenticationSupplier
 * @since 4.0
 */
public final class ReactiveAuthenticationHolder {
    private static final List<ReactiveAuthenticationSupplier> suppliers = new CopyOnWriteArrayList<>();

    public static final String IGNORE_AUTH_KEY = ".auth.ignore";

    static final Context IGNORE_AUTH_CONTEXT_Y = Context.of(IGNORE_AUTH_KEY, true);
    static final Context IGNORE_AUTH_CONTEXT_N = Context.of(IGNORE_AUTH_KEY, false);

    private static Mono<Authentication> get(Function<ReactiveAuthenticationSupplier, Mono<Authentication>> function) {
        return AuthenticationUtils
            .merge(Flux.merge(Lists.transform(suppliers, function::apply)));
    }

    /**
     * 获取当前登录的用户权限信息。
     *
     * <p>调用链显式写入的认证快照优先于 Supplier，供网关或上游过滤器在不改写全局
     * Supplier 的情况下收敛本次请求的授权范围。</p>
     *
     * @return 当前登录的用户权限信息
     */
    public static Mono<Authentication> get() {

        return Mono.deferContextual(ctx -> {
            if (Boolean.TRUE.equals(ctx.getOrDefault(IGNORE_AUTH_KEY, false))) {
                return Mono.empty();
            }
            Authentication authentication = ctx.getOrDefault(Authentication.class, null);
            if (authentication != null) {
                return Mono.just(authentication);
            }
            return get(ReactiveAuthenticationSupplier::get);
        });
    }

    /**
     * 获取指定用户的权限信息
     *
     * @param userId 用户ID
     * @return 权限信息
     */
    public static Mono<Authentication> get(String userId) {
        return get(supplier -> supplier.get(userId));
    }

    /**
     * 初始化 {@link ReactiveAuthenticationSupplier}
     *
     * @param supplier
     */
    public static void addSupplier(ReactiveAuthenticationSupplier supplier) {
        suppliers.add(supplier);
    }

    public static void setSupplier(ReactiveAuthenticationSupplier supplier) {
        suppliers.clear();
        suppliers.add(supplier);
    }

    public static Context ignoreContext(boolean ignore) {
        return ignore ? IGNORE_AUTH_CONTEXT_Y : IGNORE_AUTH_CONTEXT_N;
    }

    public static Function<Context, Context> ignoreIfAbsent(boolean ignore) {
        return ctx -> ctx.hasKey(IGNORE_AUTH_KEY)
            ? ctx
            : ctx.put(IGNORE_AUTH_KEY, ignore);
    }


}
