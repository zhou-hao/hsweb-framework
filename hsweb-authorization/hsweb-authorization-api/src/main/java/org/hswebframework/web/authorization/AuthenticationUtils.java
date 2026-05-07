package org.hswebframework.web.authorization;

import org.hswebframework.web.authorization.simple.SimpleAuthentication;
import org.springframework.util.ObjectUtils;
import org.springframework.util.StringUtils;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * @author zhouhao
 * @since 3.0
 */
public class AuthenticationUtils {


    public static Mono<Authentication> merge(Flux<Authentication> authenticationFlux){
        return authenticationFlux
            .collect(AuthenticationMerging::new, AuthenticationMerging::merge)
            .mapNotNull(AuthenticationMerging::get);
    }

    static class AuthenticationMerging {

        private Authentication auth;
        private int count;

        public synchronized void merge(Authentication auth) {
            if (this.auth == null || this.auth == auth) {
                this.auth = auth;
            } else {
                if (count++ == 0) {
                    SimpleAuthentication newAuth = new SimpleAuthentication();
                    newAuth.merge(this.auth);
                    this.auth = newAuth;
                }
                this.auth.merge(auth);
            }
        }

        Authentication get() {
            return auth;
        }
    }


    public static AuthenticationPredicate createPredicate(String expression) {
        if (ObjectUtils.isEmpty(expression)) {
            return (authentication -> false);
        }
        AuthenticationPredicate main = null;
        // resource:user:add or update
        AuthenticationPredicate temp = null;
        boolean lastAnd = true;
        for (String conf : expression.split("[ ]")) {
            if (conf.startsWith("resource:")||conf.startsWith("permission:")) {
                String[] permissionAndActions = conf.split("[:]", 2);
                if (permissionAndActions.length < 2) {
                    temp = authentication -> !authentication.getPermissions().isEmpty();
                } else {
                    String[] real = permissionAndActions[1].split("[:]");
                    temp = real.length > 1 ?
                            AuthenticationPredicate.permission(real[0], real[1].split("[,]"))
                            : AuthenticationPredicate.permission(real[0]);
                }
            } else if (main != null && conf.equalsIgnoreCase("and")) {
                lastAnd = true;
                main = main.and(temp);
            } else if (main != null && conf.equalsIgnoreCase("or")) {
                main = main.or(temp);
                lastAnd = false;
            } else {
                String[] real = conf.split("[:]", 2);
                if (real.length < 2) {
                    temp = AuthenticationPredicate.dimension(real[0]);
                } else {
                    temp = AuthenticationPredicate.dimension(real[0], real[1].split(","));
                }
            }
            if (main == null) {
                main = temp;
            }
        }
        return main == null ? a -> false : (lastAnd ? main.and(temp) : main.or(temp));
    }
}
