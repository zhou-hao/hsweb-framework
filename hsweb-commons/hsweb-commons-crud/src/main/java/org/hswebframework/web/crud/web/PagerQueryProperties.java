package org.hswebframework.web.crud.web;

import org.hswebframework.web.crud.query.PagerQueryPolicy;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 响应式分页结果的聚合保护配置。
 *
 * <p>配置只负责创建不可变的 {@link PagerQueryPolicy}，请求级传播由 WebFlux 过滤器负责；
 * 显式返回 Flux 的流式查询不受此配置限制。</p>
 *
 * @since 5.0.2
 */
@ConfigurationProperties(prefix = "hsweb.web.pageable")
public class PagerQueryProperties {

    private int maxPageSize = PagerQueryPolicy.defaults().getMaxPageSize();

    private PagerQueryPolicy.OverflowPolicy overflowPolicy = PagerQueryPolicy
        .defaults()
        .getOverflowPolicy();

    public PagerQueryPolicy createPolicy() {
        return new PagerQueryPolicy(maxPageSize, overflowPolicy);
    }

    public int getMaxPageSize() {
        return maxPageSize;
    }

    public void setMaxPageSize(int maxPageSize) {
        this.maxPageSize = maxPageSize;
    }

    public PagerQueryPolicy.OverflowPolicy getOverflowPolicy() {
        return overflowPolicy;
    }

    public void setOverflowPolicy(PagerQueryPolicy.OverflowPolicy overflowPolicy) {
        this.overflowPolicy = overflowPolicy;
    }
}
