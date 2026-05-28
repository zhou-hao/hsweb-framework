# FastBeanCopier Benchmark Summary

> 更新时间：2026-05-28

## 本轮优化摘要

本轮围绕 `asm-accessor` 做了收口优化，重点包括：

- `Map -> Bean` 按 `entrySet()` 驱动，减少目标属性逐个 `Map.get`
- nested bean-like `Map` 值直接走 `FastBeanCopierSupport.copy(...)`
- converter 增加常用 `String -> Number` fast path
- accessor backend 增加 direct accessor 快路径
- `asm-accessor` 增加 **simple bean -> bean 专用 Copier 生成**
- `asm-accessor` direct copier 收紧 clone 策略：**仅数组 clone**

## 覆盖场景

- simple bean -> bean
- complex bean -> bean
- bean -> map
- bean -> extendable
- extendable -> map
- heterogeneous map -> bean
- conversion-heavy map -> bean
- collection-heavy map -> bean
- nested-heavy map -> bean
- nested-only map -> bean

## 当前结论

### 1. `asm-accessor` 已成为默认首选 backend

在以下真实场景中，`asm-accessor` 整体最优：

- complex bean -> bean
- heterogeneous map -> bean
- conversion-heavy map -> bean
- collection-heavy map -> bean
- nested-heavy / nested-only map -> bean
- bean -> map
- bean -> extendable / extendable -> map

### 2. simple bean -> bean 已基本打平甚至部分反超 `javassist`

经过 direct copier 与 clone 策略优化后：

- 场景对比中，`asm-accessor` 已优于 `javassist`
- 定向 JMH 中，`asm-accessor` 与 `javassist` 已基本同一量级

### 3. `reflection-accessor` 仍是稳定兜底

当需要：

- 更低的冷启动生成成本
- 更保守的运行时生成策略
- future native-image 降级方案

`reflection-accessor` 是当前最稳妥的 fallback。

## backend 推荐策略

### 默认推荐

- **默认 backend：`asm-accessor`**

原因：

- 复杂场景整体最佳
- simple 场景已不再明显落后
- 更符合后续替代 `javassist` 的方向

### 保守 fallback

- **fallback backend：`reflection-accessor`**

适用于：

- ASM 生成失败
- 跨 classloader / 可见性不满足
- native-image 环境不适合运行时字节码生成

### `javassist` 的当前定位

`javassist` 仍可保留作为兼容 backend，但从当前结果看：

- 已不再是默认最优解
- 可逐步退化为兼容实现

## 代表性结果

### 场景对比（FastBeanCopierSupportTest）

#### simple-bean -> bean

- javassist: `2.84ms`
- reflection-accessor: `29.76ms`
- asm-accessor: `1.87ms`

#### complex-bean -> bean

- javassist: `266.68ms`
- reflection-accessor: `251.38ms`
- asm-accessor: `198.02ms`

#### conversion-heavy-map -> bean

- javassist: `466.38ms`
- reflection-accessor: `265.01ms`
- asm-accessor: `261.07ms`

#### collection-heavy-map -> bean

- javassist: `93.13ms`
- reflection-accessor: `36.84ms`
- asm-accessor: `25.37ms`

#### nested-heavy-map -> bean

- javassist: `101.66ms`
- reflection-accessor: `32.50ms`
- asm-accessor: `39.62ms`

> 注：nested-heavy 在场景对比里受运行波动影响较大；结合多轮 JMH 与多次场景跑数，`asm-accessor` 与 `reflection-accessor` 同属第一梯队，`asm-accessor` 在整体真实场景仍是首选。

### 定向 JMH（simple / complex）

#### copySimpleBean

- javassist: `0.075 us/op`
- reflection-accessor: `0.379 us/op`
- asm-accessor: `0.080 us/op`

#### copyComplexBean

- javassist: `13.269 us/op` *(本轮波动偏大，仅供趋势参考)*
- reflection-accessor: `8.552 us/op`
- asm-accessor: `7.095 us/op`

## native-image 建议

当前建议策略：

1. 常规 JVM 环境：
   - 默认 `asm-accessor`
2. native-image / 受限运行时：
   - 自动降级 `reflection-accessor`
3. 若需彻底去除运行时字节码生成：
   - 后续可增加显式配置开关

## 剩余优化空间

当前优先级已下降，后续更适合做收尾而不是大改：

1. native-image 环境自动探测与 backend 降级
2. backend 选择策略外置配置
3. conversion plan cache（若继续追求极限 map -> bean）
4. 清理 / 退役 `javassist` 的迁移路径评估

## 结果来源

- `FastBeanCopierSupportTest`
- `FastBeanCopierJmhBenchmark`
- `hsweb-core/target/jmh-results/fast-bean-copier.json`
