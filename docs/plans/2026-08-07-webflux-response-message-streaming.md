# WebFlux ResponseMessage 流式响应优化计划

## 状态

- 阶段：已实现（定向测试通过；全量回归存在与本变更无关的既有阻断）
- owning modules：`hsweb-commons/hsweb-commons-crud`、`hsweb-starter`
- 目标版本：`5.0.2-SNAPSHOT`

## 背景与当前事实

`ResponseMessageWrapper` 当前会把非 SSE、非 NDJSON 的 `Flux` 执行
`collectList()`，再包装为 `ResponseMessage<List<T>>`。这保持了统一响应结构，但会让
内存占用随结果总量线性增长，潜在无界流还可能永远不产生响应。

当前链路存在两个收集点：

1. `hsweb-commons/hsweb-commons-crud/src/main/java/org/hswebframework/web/crud/web/ResponseMessageWrapper.java`
   对待包装的 `Flux` 执行 `collectList()`。
2. `hsweb-starter/src/main/java/org/hswebframework/web/starter/jackson/CustomJackson2jsonEncoder.java`
   对普通 `application/json` 的多值 Publisher 再次执行 `collectList()`。因此即使通过
   `X-Response-Wrapper: Ignore` 绕过响应包装，原始 Flux 仍可能被完整收集。

Spring Framework 6.2 自带的 `Jackson2JsonEncoder` 已经支持将多值 Publisher 增量编码成
标准 JSON 数组：首元素输出 `[`，后续元素输出分隔符，完成时输出 `]`，不需要
`collectList()`。现有 `CustomJackson2jsonEncoder` 保留的是较早版本的收集逻辑，应改为
委托 Spring 官方实现，而不是继续维护一份平行的 Jackson 数组编码算法。

hsweb 的自定义编码器同时承担同步序列化所需的 ThreadLocal 上下文恢复。仓库已经提供
`AuthenticationThreadLocalAccessor`、`LocaleThreadLocalAccessor` 和 Micrometer Context
Propagation 依赖。实现应基于当前完整 Reactor Context 与所有已注册的 `ThreadLocalAccessor`
统一传播，而不是让编码器识别 Authentication、Locale 或其他具体上下文类型。

## 目标

1. `Flux<T>` 在 `application/json` 下继续输出兼容的单个 `ResponseMessage`：

   ```json
   {
     "message": "success",
     "result": [
       {"id": "device-001"},
       {"id": "device-002"}
     ],
     "status": 200,
     "timestamp": 0
   }
   ```

2. 不再按完整结果集执行 `collectList()`，内存边界收敛为单个元素序列化缓冲、少量
   JSON 框架缓冲和网络写出缓冲。
3. 保持 Reactive Streams 的 demand、cancel、onError、onComplete 语义，不调用
   `block()`、不嵌套 `subscribe()`、不引入额外线程切换。
4. 优先委托 Spring 6.2 官方 `Jackson2JsonEncoder`、`HttpMessageWriter`、
   `ReactiveAdapterRegistry` 和内容协商机制。
5. 保持 hsweb 的 ObjectMapper 配置、EntityFactory 扩展 ResponseMessage，以及所有通过
   Micrometer `ThreadLocalAccessor` 注册的上下文；认证和 Locale/i18n 继续通过相同机制兼容。
6. SSE、NDJSON 和显式忽略包装的响应继续按原协议输出，不额外套 ResponseMessage。

## 非目标

- 不修改 MVC `ResponseMessageWrapperAdvice`；Servlet 流式响应另行设计。
- 不把 SSE、NDJSON 改造成单一 JSON 外壳。
- 不为流中每个元素创建 `ResponseMessage<T>`，避免改变现有响应结构。
- 不新增数据库、事件、权限或业务接口行为。
- 不承诺客户端调用 `response.json()` 时也能逐元素消费；本次首先解决服务端无界收集和
  HTTP 分块写出。客户端增量消费仍应优先使用 NDJSON/SSE 或流式 JSON 解析器。

## 推荐方案

### 1. 自定义 Jackson 编码器回归 Spring 官方实现

将 `CustomJackson2jsonEncoder` 调整为继承 Spring 官方 `Jackson2JsonEncoder`，移除本地维护的：

- 非流式 `collectList()`；
- `SequenceWriter`、数组分隔符和 DataBuffer 拼装；
- 与 Spring 官方实现重复的媒体类型、ObjectWriter 和资源清理代码。

自定义类只保留一个职责：在编码信号进入 Spring 官方编码器前恢复已注册的同步上下文。

上下文恢复使用已经存在的 Micrometer `ContextSnapshotFactory` 与
`ThreadLocalAccessor`，通过 Reactor 官方 `Operators.liftPublisher` 装饰订阅者。在调用
下游 `onNext` 的同步作用域内，从当前完整 Reactor Context 恢复所有已注册 accessor 对应的
ThreadLocal，Spring `Jackson2JsonEncoder` 的同步元素序列化完成后立即恢复旧值。每次订阅使用
Micrometer `ContextSnapshotFactory.captureAll(contextView)` 创建一次通用快照：先捕获订阅线程上
所有已注册 accessor 的兼容 ThreadLocal，再合并 Reactor Context；按官方覆盖顺序，Reactor
Context 中的同 key 值优先。该装饰器必须完整透传 Subscription、demand、cancel 和终止信号，
不创建第二次订阅。

不能只在链路上追加 Reactor `contextCapture()`。该操作符解决的是相反方向：在订阅阶段把当前
线程中已注册 accessor 对应的 ThreadLocal 捕获到 Reactor Context。当前请求上下文已经以 Reactor
Context 为事实来源，实际缺口是在 `publishOn` 等异步边界之后，把 Reactor Context 恢复到执行
Jackson getter/serializer 的线程。默认传播模式下，`handle`、`tap` 等操作符提供的有限恢复只覆盖
它们自己的回调，也不能保证作用域覆盖后续 Spring encoder 的同步 `onNext`。只有由应用显式全局
启用 `Hooks.enableAutomaticContextPropagation()` 后，`contextCapture()` 才能与自动恢复机制组合
简化这部分桥接；框架组件不应为了一个 writer 改变整个应用的传播语义。因此这里保留局部
`ContextSnapshot` 作用域，并让它精确包围官方 Jackson encoder 的同步编码调用。

不通过以下方式实现：

- 不复制 Spring `AbstractJackson2Encoder` 源码；
- 不通过反射访问 Spring 私有方法；
- 不强制全局调用 `Hooks.enableAutomaticContextPropagation()`；
- 不自己创建一套脱离 Spring 配置的 ObjectMapper。

### 2. 引入内部流式响应标记

在 `hsweb-commons-crud` 增加仅供响应处理链使用的内部类型，例如：

```java
final class StreamingResponseMessage<T> {
    private final ResponseMessage<?> metadata;
    private final Publisher<T> result;
    private final ResolvableType elementType;
}
```

该类型不是新的 Controller 公共返回契约，只用于在 `ResponseMessageWrapper` 与专用
`HttpMessageWriter` 之间传递外壳元数据、元素类型和原始 Publisher。禁止让普通 Jackson
直接把嵌套 Publisher 当 JavaBean 属性序列化。

### 3. 使用专用 HttpMessageWriter 输出外层 ResponseMessage

新增 `ResponseMessageJacksonHttpMessageWriter`，实现 Spring 官方
`HttpMessageWriter<StreamingResponseMessage<?>>` 扩展点：

1. 只声明支持 `application/json` 和 `application/*+json`。
2. 从 `ResponseMessageWrapper` 已配置的 writers 中复用现有
   `EncoderHttpMessageWriter` 所持有的 `Jackson2JsonEncoder`，不另建 ObjectMapper。
3. 将 `result` Publisher 委托给 Spring 官方编码器，得到增量 JSON 数组
   `Flux<DataBuffer>`。
4. 在第一个数组 DataBuffer 前拼接 ResponseMessage JSON 前缀，在数组完成后拼接元数据
   后缀，并通过 `ServerHttpResponse.writeWith(...)` 写出。
5. 使用官方 encoder 的 `getEncodeHints(...)` 保留 `@JsonView`、日志前缀、具体元素类型
   和 Reactor Context hints。

专用 writer 只加入当前 `ResponseMessageWrapper` 的 writer 列表，并排在通用 Jackson
writer 前面。其余 Spring WebFlux handler 不需要认识内部标记类型，避免修改全局 Codec 顺序。

数据流如下：

```text
Controller Flux<T>
  -> ResponseMessageWrapper
  -> StreamingResponseMessage<T>
  -> ResponseMessageJacksonHttpMessageWriter
  -> Spring Jackson2JsonEncoder(result Flux<T>)
  -> prefix + JSON array buffers + suffix
  -> ServerHttpResponse.writeWith
```

### 4. 首个响应提交与 JSON 框架

不能先单独写出 `{"message":"success","result":`，否则源 Publisher 在首元素前失败时
响应已经提交。

writer 应等待官方数组编码器的第一个信号：

- 首元素成功编码：把外层前缀与第一个数组 DataBuffer 合并为首个输出 buffer；
- 空 Flux：官方编码器生成 `[]`，输出完整成功外壳；
- 首元素前 onError 或首元素序列化失败：不输出任何 buffer，错误继续交给 WebFlux
  异常处理链。

后续元素以官方编码器产生的逗号和元素 buffer 继续写出，完成后追加外层后缀。
DataBuffer 合并、丢弃和取消路径按 Spring `DataBufferUtils.release(...)` 规则处理。本地 writer
通过 discard hook 释放未写出的 DataBuffer；JSON generator 与 ByteArrayBuilder 的生命周期由
官方 encoder 管理。当前 Spring Framework 6.2.10 的官方实现内部使用 `doAfterTerminate`，且不
公开私有 generator 的关闭钩子，因此本地实现不通过复制源码或反射声称修复该内部 cancel 边界。

### 5. ResponseMessage 元数据兼容

- JSON 字段语义保持 `message/result/status/code/timestamp` 不变；`code == null` 继续遵循
  `@JsonInclude` 省略。
- 外层元数据使用同一个 ObjectMapper 和 EntityFactory 创建的 `ResponseMessage` 实例，
  兼容自定义 ResponseMessage 子类、Jackson Module、Mixin 和命名策略。
- writer 将 `result` 作为流式数组插入，其他元数据按 ObjectMapper 可见属性输出；扩展字段
  不能被静默丢弃。
- JSON 对象字段顺序在语义上不构成协议，但实现和回归测试优先维持当前基础字段顺序，
  降低快照测试和非规范客户端的兼容风险。
- `timestamp` 在 `ResponseMessageWrapper` 创建流式外壳元数据时生成。它不再等待完整结果收集，
  因而语义从“收集完成时间”调整为“流式响应开始时间”；失败或取消时不会追加完整成功后缀。

### 6. Wrapper 使用 ReactiveAdapterRegistry 和官方内容协商

`ResponseMessageWrapper` 不再只通过 `instanceof Mono/Flux` 判断：

1. 使用 `ReactiveAdapterRegistry` 获取 adapter；
2. 单值 Publisher 继续映射成单个 `ResponseMessage<T>`；
3. 多值 Publisher 在协商结果为普通 JSON 且专用 writer 可用时转换为
   `StreamingResponseMessage<T>`；
4. no-value Publisher 保持空成功响应语义；
5. 已返回 `ResponseMessage`、`ResponseEntity` 或命中 excludes 时继续跳过。

媒体类型判断改为使用 Spring `selectMediaType(...)` 及 writer 声明的 streaming media types，
不再依赖 `accept.contains(...)` 精确相等。以下类型继续直接交给原始 body：

- `text/event-stream`；
- `application/x-ndjson`；
- Spring encoder 声明的其他 streaming media type，包括兼容的 vendor `+x-ndjson`；
- `X-Response-Wrapper: Ignore`。

### 7. 错误、取消和背压契约

#### 首个 buffer 前错误

响应未提交，错误原样传播，由现有 `CommonErrorControllerAdvice` 生成 HTTP 状态和错误
`ResponseMessage`。

#### 首个 buffer 后错误

HTTP 响应已经提交，无法再可靠改写状态码或完整 ResponseMessage。推荐行为是：

- 不吞异常；
- 不把部分结果伪装成成功；
- 终止写出并让连接以不完整 JSON/传输错误结束；
- 日志保留原始异常，但不记录完整 payload。

如果调用方要求流内错误事件，应使用 NDJSON/SSE，而不是普通 JSON 数组外壳。

#### 取消与背压

- 客户端断开或取消必须沿同一 Subscription 取消原始 result Publisher；
- 不使用 `cache`、`replay`、`collectList`、无界 `buffer` 或额外 `subscribe`；
- 元素序列化是同步一对一转换，使用 `map`/官方 encoder 即可，不引入并发 `flatMap`；
- 默认使用 `writeWith`，不为每个元素强制 flush。若后续需要低延迟刷新，应基于媒体类型或
  有界批次单独设计，不能默认逐元素 `writeAndFlushWith`。

### 8. queryPager 有界聚合保护

`PagerResult.data` 是 `List<T>`，因此 `QueryHelper.queryPager(...)` 仍需要对当前页执行
`collectList()`。页大小必须保持跨请求、跨节点稳定，不能根据 JVM 实时剩余内存动态变化，否则
offset 分页可能重复或漏数。采用不可变 `PagerQueryPolicy` 统一规范化：

- 默认阈值为 1000，保留 JVM 系统属性 `hsweb.max-pager-page-size`，并支持 Spring 配置
  `hsweb.web.pageable.max-page-size`；配置值必须大于 0；
- 默认溢出策略为兼容模式 `WARN`：显式 `pageSize > maxPageSize` 时记录不含查询条件的告警并保留
  原值，避免 5.0.x 已有大页调用被静默截断；
- `CLAMP` 将超大页截断到最大值，`PagerResult.pageSize` 返回规范化后的实际值；`REJECT` 返回
  `pageSize` 参数校验错误；配置为 `hsweb.web.pageable.overflow-policy`；
- `pageSize < 1` 时回退到 easy-orm 默认页大小，再受最大页大小约束；
- `paging=false` 传入分页结果接口时，不受 `WARN` 兼容豁免，始终转换为最大受限页；真正的无分页
  结果继续走返回 `Flux` 的 `/_query/no-paging`，大结果优先使用 NDJSON；
- Spring WebFlux 将不可变策略 bean 写入 Reactor Context，`QueryHelper` 在订阅时读取；非 HTTP
  调用使用稳定默认策略，也可通过显式策略/最大值重载或 `contextWrite` 指定业务级规则；
- `ReactiveCrudService` 提供服务级 `resolvePagerQueryPolicy(ContextView)` 默认扩展点以及显式
  `PagerQueryPolicy` 查询重载。策略优先级固定为“调用时显式策略 > 服务扩展点 > Reactor Context
  > 框架默认策略”；默认扩展点只读取当前订阅上下文，不阻塞、不修改上下文，也不引入可变状态；
- 查询参数先 `clone()`，不修改调用方对象；查询侧分页限制之外，`collectList()` 前再使用
  `take(effectivePageSize)`，即使自定义 `ReactiveQuery` 未正确应用分页也不会超过当前策略实际允许
  的数量；不使用可变全局字段模拟请求级配置。

此保护只约束返回 `PagerResult` 的有界聚合链路，不限制显式流式查询，不修改数据库 SQL 方言、
排序、总数复用或重新分页语义。

## 兼容与发布策略

兼容对象来自已发布的 hsweb 响应协议和现有前端/外部调用方：

- 保持 `application/json` 的单个 ResponseMessage 外壳和 `result` 数组结构；
- 保持 Mono、显式 ResponseMessage、ResponseEntity、SSE、NDJSON、excludes 和
  `X-Response-Wrapper: Ignore` 行为；
- 保持所有已注册 ThreadLocalAccessor 参与同步序列化；编码器不识别具体上下文类型；
- 传输方式从完成后一次性写出变为 chunked/增量写出；
- 首元素后的异常由“可返回完整错误 ResponseMessage”变为“连接终止”，这是流式输出的固有
  语义变化，必须在发布说明中明确。

推荐直接使用单一 canonical 流式实现，不长期维护 `collect` 与 `stream` 两套编码链。
如集成验证发现已发布调用方强依赖晚期错误仍返回完整 JSON，再补充临时回滚配置；该配置必须
有明确移除条件和最大收集条数，不能恢复无界 `collectList()`。

## 任务拆分

1. 新增测试，固定当前成功响应 JSON、Mono、空 Flux、显式 ResponseMessage、SSE/NDJSON、
   excludes 和 Ignore 行为。
2. 重构 `CustomJackson2jsonEncoder`：继承官方 `Jackson2JsonEncoder`，增加基于
   ContextSnapshot 的信号上下文装饰器，删除本地数组编码和 `collectList()`。
3. 为编码器补统一上下文、Reactor Context 优先级、异步线程切换、普通 JSON Flux 增量数组、
   取消和首元素错误测试。
4. 新增内部 `StreamingResponseMessage` 与 `ResponseMessageJacksonHttpMessageWriter`。
5. 调整 `ResponseMessageWrapper`：使用 ReactiveAdapterRegistry、官方内容协商和专用 writer，
   删除 Flux `collectList()` 及无效 `switchIfEmpty()`。
6. 补 WebFlux 集成测试，验证首个 DataBuffer 在源完成前到达、JSON 外壳兼容、背压和取消。
7. 为 `QueryHelper.queryPager(...)` 增加不可变 `PagerQueryPolicy`、策略/最大页大小重载、订阅期
   Reactor Context 解析，以及 `collectList()` 前的 `take(effectivePageSize)` 最终保护；为
   `ReactiveCrudService` 增加服务级策略解析扩展点和显式策略重载。
8. 在 `CommonWebFluxConfiguration` 增加 `hsweb.web.pageable` 配置绑定和策略 WebFilter；默认
   `WARN` 保持显式大页兼容，`CLAMP/REJECT` 由部署按容量开启。
9. 补分页边界测试：默认页、0/负数、最大值、超大值、`paging=false`、三种溢出策略、并行/串行/
   复用 total、调用方参数不变、Reactor Context 覆盖、Spring 属性绑定、服务策略覆盖、显式策略
   优先级，以及查询源忽略分页时的最终限制与取消。
10. 运行目标模块测试与上游聚合测试；若实现假设变化，先更新本设计并重新确认。

## 测试目标与验收标准

### 编码器单元测试

文件：
`hsweb-starter/src/test/java/org/hswebframework/web/starter/jackson/CustomJackson2jsonEncoderTest.java`

- 普通 `Flux<TestEntity>` + `application/json` 输出合法 JSON 数组，且首个 DataBuffer 在源
  complete 前可被请求到。
- 空 Flux 输出 `[]`。
- `application/x-ndjson` 保持逐行编码和流式媒体类型声明。
- Locale 为 `zh-CN`、`en-US` 时，现有 EnumDict 文案序列化保持一致。
- Reactor Context 中的 Authentication 在序列化 getter/扩展字段中可见，完成后 ThreadLocal
  恢复原值。
- Reactor Context 与订阅线程 ThreadLocal 存在相同 accessor key 时，以 Reactor Context 为准，
  序列化完成后恢复原 ThreadLocal。
- 任意新增的 Micrometer `ThreadLocalAccessor` 无需修改 encoder；经过 `publishOn` 切换线程后，
  对应值在同步 Jackson getter 中可见，序列化后工作线程恢复原值且不污染线程池。
- 下游取消后，上游收到 cancel，编码器资源完成清理。
- 首元素序列化失败时不产生任何 DataBuffer，错误类型和原因不被吞掉。

### Writer 单元测试

建议新增：
`hsweb-commons/hsweb-commons-crud/src/test/java/org/hswebframework/web/crud/web/ResponseMessageJacksonHttpMessageWriterTest.java`

- 三个真实形态实体输出一个 ResponseMessage，`result` 是按原顺序排列的 JSON 数组。
- 空 Flux 输出 `result: []`。
- `ResponseMessage` 的 status、message、timestamp、可选 code 及 EntityFactory 扩展字段均保留。
- 首元素前源错误、首元素序列化错误时响应未提交。
- 首元素后源错误时错误向下游传播，writer 不追加成功后缀。
- StepVerifier 以逐次 request 验证 writer 不会主动收集完整 Publisher。
- cancel 传播到源 Publisher，已经分配但未写出的 DataBuffer 被释放。

### Wrapper/WebFlux 集成测试

建议新增：
`hsweb-commons/hsweb-commons-crud/src/test/java/org/hswebframework/web/crud/web/ResponseMessageWrapperTest.java`

- `Mono<TestEntity>` 保持单对象 ResponseMessage。
- `Flux<TestEntity>` + `application/json` 在源完成前收到首块数据，完整后 JSON 与现有协议兼容。
- 空 Flux 返回成功且 `result` 为 `[]`。
- `Flux.never()` 不产生完整响应，但不会在服务端累计元素；客户端取消后源被取消。
- SSE、NDJSON、vendor `+x-ndjson`、Ignore header 和 excludes 均绕过包装。
- 显式 `Mono<ResponseMessage<T>>` 不被重复包装。
- 首元素前业务异常由 `CommonErrorControllerAdvice` 返回对应 HTTP 状态和错误响应。

### 回归与规模验证

- 使用可解释的 `TestEntity` 数据生成 100,000 个元素，验证链路能完成且不存在
  `collectList()`；不以脆弱的瞬时堆内存数字作为唯一断言。
- 通过 TestPublisher/自定义 Publisher 记录 request 与 cancel，验证没有
  `Long.MAX_VALUE` 驱动的完整收集语义；允许网络 writer 使用有限预取。
- 不使用 `Thread.sleep`；使用 StepVerifier、TestPublisher 和 WebTestClient 的响应体订阅控制。

### 验证命令

```bash
mvn -pl hsweb-starter,hsweb-commons/hsweb-commons-crud -am \
  -Dtest=CustomJackson2jsonEncoderTest,ResponseMessageJacksonHttpMessageWriterTest,ResponseMessageWrapperTest,ResponseMessageStreamingIntegrationTest \
  -Dsurefire.failIfNoSpecifiedTests=false test

mvn -pl hsweb-starter,hsweb-commons/hsweb-commons-crud -am test
```

通过标准：相关测试全部通过；普通 JSON、NDJSON、SSE 和 i18n 回归均符合上述契约；目标生产
代码不再对可能无界的响应 Flux 使用 `collectList()`。

## 可观测性与运维判断

- 不新增 TraceHolder/MonoTracer/FluxTracer：这是 HTTP 序列化基础设施，不是业务阶段，现有
  WebFlux HTTP tracing 已覆盖请求生命周期；逐元素 span 会造成高频噪声。
- 不新增 MBean：实现不维护常驻队列、缓存或后台线程，背压和缓冲由 Reactor/Netty 管理。
- 不涉及 SQL、数据权限、事务、事件或跨模块远程调用。

## 代码注释目标

实现时仅在以下非显然边界增加注释：

- 首个数组 buffer 与外层前缀合并，用于保留提交前异常处理能力；
- 首元素后错误只能终止已提交响应；
- ContextSnapshot 作用域必须包围官方 Jackson encoder 的同步 onNext 编码；
- discard 路径负责本地 DataBuffer 的 cancel 释放；官方 encoder 的私有资源生命周期不重复实现。

普通 `map`、`switchIfEmpty`、writer 选择等自解释胶水不增加冗余注释。

## 风险与待确认点

1. **晚期错误语义**：推荐在首个 buffer 已写出后中断响应，不尝试输出“部分结果 + 错误
   ResponseMessage”。需要用户确认接受该流式固有语义。
2. **传输变化**：普通 JSON Flux 将使用 chunked/增量写出，不再等完成后计算 Content-Length。
3. **自定义 ResponseMessage**：必须通过 ObjectMapper/EntityFactory 回归测试确认扩展字段与命名
   策略不丢失；若某个扩展重定义了 `result` 的序列化形态，需要把它建模为 writer SPI，而不是
   硬编码兼容分支。
4. **Spring 升级边界**：只使用公开的 `Jackson2JsonEncoder`、`HttpMessageWriter`、
   `ReactiveAdapterRegistry`、`Operators.liftPublisher` 和 Context Propagation API，不依赖私有
   方法或复制源码，以降低版本升级风险。

## 实施结果（2026-08-07）

### 实际代码落点

- `hsweb-starter/src/main/java/org/hswebframework/web/starter/jackson/CustomJackson2jsonEncoder.java`
  已改为继承 Spring `Jackson2JsonEncoder`。普通 JSON 多值 Publisher 的数组 framing、逐元素
  编码和错误语义全部委托官方实现；本地只用 `ContextSnapshotFactory` 与
  `Operators.liftPublisher` 在同步 `onNext` 序列化范围恢复当前 Reactor Context 中所有已注册的
  Micrometer `ThreadLocalAccessor`。每次订阅通过 `captureAll(contextView)` 通用合并兼容
  ThreadLocal 与 Reactor Context，同 key 由 Reactor Context 覆盖；编码器不依赖任何具体上下文
  类型。
- `hsweb-commons/hsweb-commons-crud/src/main/java/org/hswebframework/web/crud/web/StreamingResponseMessage.java`
  作为 package-private 内部交接模型，Publisher 不作为普通 JavaBean 属性交给 Jackson。
- `hsweb-commons/hsweb-commons-crud/src/main/java/org/hswebframework/web/crud/web/ResponseMessageJacksonHttpMessageWriter.java`
  使用现有 Jackson encoder 编码外层元数据，并把 `result` 委托同一 encoder 增量编码为 JSON
  数组；首个数组 buffer 与外层前缀合并，首元素前错误不会提交响应，晚期错误不会追加成功后缀。
- `hsweb-commons/hsweb-commons-crud/src/main/java/org/hswebframework/web/crud/web/ResponseMessageWrapper.java`
  已删除目标 WebFlux 响应链路的 `collectList()`，改用 `ReactiveAdapterRegistry`、官方内容协商
  和 encoder 声明的 streaming media types。专用 writer 仅加入当前 wrapper 的 writer 列表，
  不改变全局 codec 顺序。
- `hsweb-starter/src/test/java/org/hswebframework/web/starter/jackson/ResponseMessageStreamingIntegrationTest.java`
  启动最小 `@EnableWebFlux` 上下文与真实 Reactor Netty 随机端口服务，同时装配实际
  `ResponseMessageWrapper` 和 `CustomJackson2jsonEncoder`，验证 HTTP 传输层而非直接调用 writer。
- `hsweb-starter/pom.xml` 显式声明项目已有的 `io.micrometer:context-propagation` 依赖，并增加
  test-scope `reactor-netty-http` 用于真实 HTTP 集成测试，不改变发布依赖。
- `hsweb-commons/hsweb-commons-crud/src/main/java/org/hswebframework/web/crud/query/QueryHelper.java`
  的分页重载统一委托 `PagerQueryPolicy`，默认重载在订阅期从 Reactor Context 取策略；已知 total、
  并行分页和串行分页三条 `collectList()` 链路前均增加 `take(effectivePageSize)`。显式
  `maxPageSize` 重载固定采用 `CLAMP`，显式策略重载用于非 HTTP 或更严格的业务场景。
- `hsweb-commons/hsweb-commons-crud/src/main/java/org/hswebframework/web/crud/query/PagerQueryPolicy.java`
  是线程安全的不可变策略对象，统一处理默认页、非法小值、显式超大页和 `paging=false`；默认
  `WARN` 保留已发布大页调用，`CLAMP` 截断，`REJECT` 返回 i18n 参数校验错误，并继续兼容
  JVM 属性 `hsweb.max-pager-page-size`。
- `PagerQueryProperties` 与 `CommonWebFluxConfiguration` 绑定
  `hsweb.web.pageable.max-page-size` / `overflow-policy`，创建可覆盖的策略 bean，并由
  WebFilter 写入每次请求的 Reactor Context；不依赖可变静态 Holder 或 ThreadLocal。
- `ReactiveCrudService.queryPager(...)` 默认重载在每次订阅时调用
  `resolvePagerQueryPolicy(ContextView)`；默认实现读取 Reactor Context，服务实现可覆盖为稳定的
  业务级策略。调用时显式传入 `PagerQueryPolicy` 的重载优先级最高，并直接委托 `QueryHelper`，
  不触发服务解析扩展点。
- `hsweb-commons/hsweb-commons-crud/src/test/java/org/hswebframework/web/crud/query/QueryHelperPagerTest.java`
  覆盖正常页、0/负数、显式大页兼容、`paging=false`、`WARN/CLAMP/REJECT`、已知/零 total、
  并行/串行分页、映射、参数不变、Reactor Context 覆盖和上游取消。
- `PagerQueryConfigurationTest` 使用 `ReactiveWebApplicationContextRunner` 验证 Spring 属性绑定、
  自定义策略 bean back-off，以及经过 `publishOn` 异步边界后的 Reactor Context 传播。
- `ReactiveCrudServicePagerPolicyTest` 验证默认服务经过 `publishOn` 后读取 Reactor Context、服务级
  策略覆盖上下文、显式策略覆盖服务策略和上下文，以及显式策略便利重载。

### 已验证契约

- 普通 `application/json` Flux 在源完成前产生首个 DataBuffer，最终仍是单个
  `ResponseMessage`，且 `result` 为有序 JSON 数组。
- 空 Flux 输出 `result: []`；Mono、显式 ResponseMessage、NDJSON、SSE、Ignore header 和
  excludes 保持原有边界。
- Authentication、中文/英文 Locale 在同步 Jackson 序列化期间可见，完成后 ThreadLocal 恢复。
- 订阅线程 ThreadLocal 与 Reactor Context 存在相同 accessor key 时，序列化使用 Reactor Context
  中的值，结束后恢复订阅线程原值。
- 真实 HTTP 请求在 `publishOn` 切换到专用 Scheduler 后，Authentication、Locale 和测试动态注册
  的 correlation-id accessor 都能在 Jackson getter 中读取；响应完成后再次检查同一工作线程，
  correlation-id 与 Authentication 均已恢复，不存在请求上下文泄漏。
- 首元素前错误不输出外壳；首元素后错误原样传播且不追加成功后缀；取消传播到原 Publisher。
- EntityFactory 创建的 ResponseMessage 子类及其 Jackson 扩展字段通过同一个 ObjectMapper
  保留。
- 真实 Reactor Netty 连接中，首个元素对应的 JSON 已在源 Publisher 完成前到达客户端；客户端
  收到首元素后停止读取，cancel 能继续传播到服务端原 Publisher。
- 100,000 个元素通过网络完整输出，客户端使用 Jackson non-blocking parser 按 ByteBuf 分片解析，
  不把响应重新聚合为字符串或对象列表；最终元素数、`status`、`result` 数组和根对象闭合均正确。
- `application/vnd.hsweb+json` 保持 ResponseMessage 外壳；Ignore header 返回原始数组；NDJSON 和
  SSE 保持不包装。
- 目标生产链路 `ResponseMessageWrapper` 和 `CustomJackson2jsonEncoder` 中已无
  `collectList()`；MVC `ResponseMessageWrapperAdvice` 不在本次范围内，仍保持原实现。
- `QueryHelper.queryPager(...)` 仍按 `PagerResult<List<T>>` 契约聚合当前页，但页大小已同时受查询
  参数和 Reactor `take` 约束；自定义查询忽略分页参数时，测试确认在实际上限处取消上游。
- `pageSize < 1` 回退到受限默认值；显式超大页默认告警并保留旧值，可配置为截断或拒绝；
  `paging=false` 无条件转换为最大受限页。规范化后的 `pageSize` 写入结果元数据，原始
  `QueryParamEntity` 保持不变。
- Spring WebFlux 请求和默认 `ReactiveCrudService` 在异步调度后仍从同一 Reactor Context 读取
  不可变策略；自定义 `PagerQueryPolicy` bean 会替代自动配置。服务可覆盖
  `resolvePagerQueryPolicy(ContextView)`，非 HTTP 链路也可通过显式重载或 `contextWrite` 使用
  相同契约；显式重载不会调用服务解析器。

定向验证命令通过：

```bash
mvn -pl hsweb-starter,hsweb-commons/hsweb-commons-crud -am \
  -Dtest=CustomJackson2jsonEncoderTest,ResponseMessageJacksonHttpMessageWriterTest,ResponseMessageWrapperTest,ResponseMessageStreamingIntegrationTest \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

结果为 12/12 reactor modules success，相关测试 29 个全部通过：wrapper 10、writer 7、encoder 7、
真实 HTTP 集成测试 5。10 万元素测试属于可重复的规模集成验证，用于证明传输层确实增量工作；它
不等同于多并发、长时间运行并采集 JVM/Direct Memory 指标的正式容量压测。

分页聚合保护执行：

```bash
mvn -pl hsweb-commons/hsweb-commons-crud -am \
  -Dtest=QueryHelperPagerTest,PagerQueryConfigurationTest,ReactiveCrudServicePagerPolicyTest \
  -Dsurefire.failIfNoSpecifiedTests=false test

mvn -pl hsweb-commons/hsweb-commons-crud test
```

定向测试 17 个全部通过：`QueryHelperPagerTest` 11 个，
`PagerQueryConfigurationTest` 2 个，`ReactiveCrudServicePagerPolicyTest` 4 个。目标模块全量结果为
124 tests、0 failures、0 errors、1 skipped。JaCoCo 方法级结果：
`QueryHelper.doQueryPager` 25/25 lines、4/4 branches；`PagerQueryPolicy.normalize` 15/15 lines、
6/6 branches；`handleOverflow` 9/9 lines、3/3 branches；`ReactiveCrudService` 默认策略入口、
两项显式策略重载和 `resolvePagerQueryPolicy` 均已覆盖。

全量 `-am test` 在进入目标模块前被既有的
`hsweb-datasource-api/DefaultSwitcherTest` 阻断（初始状态期望为空，实际为 `test`）。直接运行两个
目标模块的全量测试时，`hsweb-commons-crud` 通过，`hsweb-starter/SystemInitializeTest` 因当前
依赖组合缺少 `io.r2dbc.postgresql.codec.PostgresqlObjectId` 而失败；本次新增的 encoder、writer
和 wrapper 测试均通过。这两个失败均未触达本次生产代码。

### JavaBean 属性中嵌套 Flux 的结论

Spring WebFlux 通过 `ReactiveAdapterRegistry` 展开的是 Controller 顶层返回值。Jackson
serializer 是同步、单值的序列化扩展点，不能异步订阅 JavaBean 属性中的 Publisher，因此
`PagerResult{total: 100, data: Flux<T>}` 不能依赖普通 Jackson 或一个内部 `subscribe()` 的
自定义 serializer 实现可靠的背压式输出。

需要该协议时应复用本次模式：定义显式的内部流式响应模型，由专用 `HttpMessageWriter` 先编码
`total` 等有界元数据，再把 `data` Publisher 委托官方 encoder 增量写为数组。该能力应作为后续
独立、通用的“嵌套 Publisher 响应 writer”设计，不在本次修改 `PagerResult` 或 Jackson 全局行为。
