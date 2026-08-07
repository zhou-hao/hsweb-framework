package org.hswebframework.web.crud.query;

import org.hswebframework.ezorm.core.param.QueryParam;
import org.hswebframework.web.api.crud.entity.QueryParamEntity;
import org.hswebframework.web.exception.ValidationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.util.context.Context;
import reactor.util.context.ContextView;

import java.util.Locale;
import java.util.Objects;

/**
 * 响应式分页结果的页大小策略。
 *
 * <p>策略在订阅期从 Reactor Context 获取，保证异步链路使用稳定配置；它只约束必须聚合为
 * {@code PagerResult<List<T>>} 的分页查询，不限制显式返回 {@code Flux<T>} 的流式查询。</p>
 *
 * @since 5.0.2
 */
public final class PagerQueryPolicy {

    public static final String MAX_PAGE_SIZE_PROPERTY = "hsweb.web.pageable.max-page-size";

    public static final String LEGACY_MAX_PAGE_SIZE_PROPERTY = "hsweb.max-pager-page-size";

    public static final String OVERFLOW_POLICY_PROPERTY = "hsweb.web.pageable.overflow-policy";

    public static final int DEFAULT_MAX_PAGE_SIZE = 1000;

    private static final Logger log = LoggerFactory.getLogger(PagerQueryPolicy.class);

    private static final PagerQueryPolicy DEFAULT = new PagerQueryPolicy(
        resolveDefaultMaxPageSize(),
        resolveDefaultOverflowPolicy());

    private final int maxPageSize;

    private final OverflowPolicy overflowPolicy;

    public PagerQueryPolicy(int maxPageSize, OverflowPolicy overflowPolicy) {
        if (maxPageSize < 1) {
            throw new IllegalArgumentException("maxPageSize must be greater than 0");
        }
        this.maxPageSize = maxPageSize;
        this.overflowPolicy = Objects.requireNonNull(overflowPolicy, "overflowPolicy");
    }

    /**
     * 获取非 Spring 场景使用的稳定默认策略。
     *
     * @return 默认策略
     */
    public static PagerQueryPolicy defaults() {
        return DEFAULT;
    }

    /**
     * 创建一个始终截断超大页的策略。
     *
     * @param maxPageSize 最大页大小
     * @return CLAMP策略
     */
    public static PagerQueryPolicy clamp(int maxPageSize) {
        return new PagerQueryPolicy(maxPageSize, OverflowPolicy.CLAMP);
    }

    /**
     * 从 Reactor Context 获取当前订阅使用的策略。
     *
     * @param contextView 当前订阅上下文
     * @return 上下文策略；未设置时返回稳定默认策略
     */
    public static PagerQueryPolicy from(ContextView contextView) {
        return contextView.getOrDefault(PagerQueryPolicy.class, DEFAULT);
    }

    /**
     * 将策略写入 Reactor Context。
     *
     * @param context 上下文
     * @param policy  分页策略
     * @return 包含策略的新上下文
     */
    public static Context writeTo(Context context, PagerQueryPolicy policy) {
        return context.put(PagerQueryPolicy.class, Objects.requireNonNull(policy, "policy"));
    }

    /**
     * 复制并规范化分页参数。显式大页按溢出策略处理，{@code paging=false} 始终转换为最大受限页。
     *
     * @param source 原始查询参数，不会被修改
     * @return 已开启分页并应用当前策略的查询参数副本
     */
    public QueryParamEntity normalize(QueryParamEntity source) {
        Objects.requireNonNull(source, "source");

        QueryParamEntity normalized = source.clone();
        int requestedPageSize = normalized.getPageSize();
        int fallbackPageSize = Math.min(
            Math.max(QueryParam.DEFAULT_PAGE_SIZE, 1),
            maxPageSize);
        int effectivePageSize;

        if (!normalized.isPaging()) {
            effectivePageSize = maxPageSize;
        } else if (requestedPageSize < 1) {
            effectivePageSize = fallbackPageSize;
        } else if (requestedPageSize <= maxPageSize) {
            effectivePageSize = requestedPageSize;
        } else {
            effectivePageSize = handleOverflow(requestedPageSize);
        }

        normalized.setPaging(true);
        normalized.setPageSize(effectivePageSize);
        return normalized;
    }

    public int getMaxPageSize() {
        return maxPageSize;
    }

    public OverflowPolicy getOverflowPolicy() {
        return overflowPolicy;
    }

    private int handleOverflow(int requestedPageSize) {
        return switch (overflowPolicy) {
            case WARN -> {
                // 兼容已发布的大页调用；告警只包含数量，不记录查询条件或业务数据。
                log.warn(
                    "Requested pageSize [{}] exceeds configured maxPageSize [{}], preserving it because overflow policy is WARN",
                    requestedPageSize,
                    maxPageSize);
                yield requestedPageSize;
            }
            case CLAMP -> maxPageSize;
            case REJECT -> throw new ValidationException.NoStackTrace(
                "pageSize",
                "error.page_size_exceeded",
                requestedPageSize,
                maxPageSize);
        };
    }

    private static int resolveDefaultMaxPageSize() {
        return Integer.getInteger(
            MAX_PAGE_SIZE_PROPERTY,
            Integer.getInteger(LEGACY_MAX_PAGE_SIZE_PROPERTY, DEFAULT_MAX_PAGE_SIZE));
    }

    private static OverflowPolicy resolveDefaultOverflowPolicy() {
        String value = System.getProperty(OVERFLOW_POLICY_PROPERTY, OverflowPolicy.WARN.name());
        try {
            return OverflowPolicy.valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException error) {
            throw new IllegalArgumentException(
                "Unsupported pager overflow policy: " + value,
                error);
        }
    }

    /**
     * 显式分页大小超过阈值时的处理方式。
     */
    public enum OverflowPolicy {
        /** 记录告警并保留调用方页大小，用于兼容迁移。 */
        WARN,
        /** 截断为最大页大小。 */
        CLAMP,
        /** 返回参数校验错误。 */
        REJECT
    }
}
