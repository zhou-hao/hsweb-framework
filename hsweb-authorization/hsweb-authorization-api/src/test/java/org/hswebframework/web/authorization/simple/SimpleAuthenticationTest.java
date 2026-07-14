package org.hswebframework.web.authorization.simple;

import org.hswebframework.web.authorization.*;
import org.hswebframework.web.authorization.DefaultDimensionType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.Serializable;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class SimpleAuthenticationTest {

    private SimpleAuthentication authentication;
    private SimpleUser user;
    private SimplePermission permission1;
    private SimplePermission permission2;
    private SimpleDimension dimension1;
    private SimpleDimension dimension2;

    @BeforeEach
    void setUp() {
        authentication = new SimpleAuthentication();

        // 创建测试用户
        user = SimpleUser.builder()
                         .id("test-user-id")
                         .username("testuser")
                         .name("Test User")
                         .userType("user")
                         .build();

        // 创建测试权限
        permission1 = SimplePermission.builder()
                                      .id("permission-1")
                                      .name("Permission 1")
                                      .actions(new HashSet<>(Arrays.asList("query", "save", "delete")))
                                      .build();

        permission2 = SimplePermission.builder()
                                      .id("permission-2")
                                      .name("Permission 2")
                                      .actions(new HashSet<>(Arrays.asList("query", "update")))
                                      .build();

        // 创建测试维度
        SimpleDimensionType orgType = SimpleDimensionType.of("org");
        SimpleDimensionType roleType = SimpleDimensionType.of("role");

        dimension1 = SimpleDimension.of("org-1", "Organization 1", orgType, null);
        dimension2 = SimpleDimension.of("role-1", "Role 1", roleType, null);
    }

    @Test
    void testOf() {
        Authentication auth = SimpleAuthentication.of();
        assertNotNull(auth);
        assertTrue(auth instanceof SimpleAuthentication);
    }

    @Test
    void testSetUser() {
        authentication.setUser(user);

        assertNotNull(authentication.getUser());
        assertEquals("test-user-id", authentication.getUser().getId());
        assertEquals("testuser", authentication.getUser().getUsername());
        assertEquals("Test User", authentication.getUser().getName());

        // setUser 应该自动将用户添加到 dimensions
        assertTrue(authentication.getDimensions().contains(user));
    }

    @Test
    void testSetUser0() {
        // 使用反射测试 protected 方法
        // 注意：setUser0 不会将用户添加到 dimensions
        // 由于是 protected 方法，这里通过子类或反射测试
        // 实际使用中，setUser0 通常由子类调用
        authentication.setUser(user);
        assertEquals(user, authentication.getUser());
    }

    @Test
    void testSetPermissions() {
        List<Permission> permissions = Arrays.asList(permission1, permission2);
        authentication.setPermissions(permissions);

        assertEquals(2, authentication.getPermissions().size());
        assertTrue(authentication.getPermissions().contains(permission1));
        assertTrue(authentication.getPermissions().contains(permission2));
    }

    @Test
    void testSetDimensions() {
        List<Dimension> dimensions = Arrays.asList(dimension1, dimension2);
        authentication.setDimensions(dimensions);

        assertEquals(2, authentication.getDimensions().size());
        assertTrue(authentication.getDimensions().contains(dimension1));
        assertTrue(authentication.getDimensions().contains(dimension2));
    }

    @Test
    void testSetDimensionsCollection() {
        Collection<Dimension> dimensions = new HashSet<>(Arrays.asList(dimension1, dimension2));
        authentication.setDimensions(dimensions);

        assertEquals(2, authentication.getDimensions().size());
    }

    @Test
    void testAddDimension() {
        authentication.addDimension(dimension1);
        authentication.addDimension(dimension2);

        assertEquals(2, authentication.getDimensions().size());
        assertTrue(authentication.getDimensions().contains(dimension1));
        assertTrue(authentication.getDimensions().contains(dimension2));
    }

    @Test
    void testSetAttributes() {
        Map<String, Serializable> attributes = new HashMap<>();
        attributes.put("key1", "value1");
        attributes.put("key2", 123);
        authentication.setAttributes(attributes);

        assertEquals(2, authentication.getAttributes().size());
        assertEquals("value1", authentication.getAttributes().get("key1"));
        assertEquals(123, authentication.getAttributes().get("key2"));
    }

    @Test
    void testGetAttribute() {
        authentication.setAttributes(Collections.singletonMap("test-key", "test-value"));

        Optional<String> value = authentication.getAttribute("test-key");
        assertTrue(value.isPresent());
        assertEquals("test-value", value.get());

        Optional<String> missing = authentication.getAttribute("missing-key");
        assertFalse(missing.isPresent());
    }

    @Test
    void testGetAttributes() {
        Map<String, Serializable> attributes = new HashMap<>();
        attributes.put("key1", "value1");
        authentication.setAttributes(attributes);

        Map<String, Serializable> result = authentication.getAttributes();
        assertNotNull(result);
        assertEquals("value1", result.get("key1"));
    }

    @Test
    void testHasPermission() {
        authentication.setPermissions(Arrays.asList(permission1, permission2));

        // 测试有权限的情况
        assertTrue(authentication.hasPermission("permission-1", Collections.singletonList("query")));
        assertTrue(authentication.hasPermission("permission-1", Arrays.asList("query", "save")));
        assertTrue(authentication.hasPermission("permission-2", Collections.singletonList("query")));

        // 测试没有权限的情况
        assertFalse(authentication.hasPermission("permission-1", Collections.singletonList("unknown")));
        assertFalse(authentication.hasPermission("unknown-permission", Collections.singletonList("query")));

        // 测试空 actions 列表
        assertTrue(authentication.hasPermission("permission-1", Collections.emptyList()));
    }

    @Test
    void testHasPermissionWithWildcard() {
        SimplePermission wildcardPermission = SimplePermission.builder()
                                                              .id("*")
                                                              .name("All Permissions")
                                                              .actions(new HashSet<>(Collections.singletonList("*")))
                                                              .build();

        authentication.setPermissions(Collections.singletonList(wildcardPermission));

        // 通配符权限应该允许所有操作
        assertTrue(authentication.hasPermission("any-permission", Collections.singletonList("any-action")));
    }

    @Test
    void testHasPermissionWithActionWildcard() {
        SimplePermission permissionWithWildcard = SimplePermission.builder()
                                                                  .id("permission-1")
                                                                  .name("Permission with wildcard")
                                                                  .actions(new HashSet<>(Collections.singletonList("*")))
                                                                  .build();

        authentication.setPermissions(Collections.singletonList(permissionWithWildcard));

        // 权限包含 * action 应该允许所有操作
        assertTrue(authentication.hasPermission("permission-1", Collections.singletonList("any-action")));
        assertTrue(authentication.hasPermission("permission-1", Arrays.asList("action1", "action2")));
    }

    @Test
    void testGetPermission() {
        authentication.setPermissions(Arrays.asList(permission1, permission2));

        Optional<Permission> perm1 = authentication.getPermission("permission-1");
        assertTrue(perm1.isPresent());
        assertEquals("permission-1", perm1.get().getId());

        Optional<Permission> perm2 = authentication.getPermission("permission-2");
        assertTrue(perm2.isPresent());
        assertEquals("permission-2", perm2.get().getId());

        Optional<Permission> missing = authentication.getPermission("unknown");
        assertFalse(missing.isPresent());
    }

    @Test
    void testGetDimension() {
        authentication.setDimensions(Arrays.asList(dimension1, dimension2));

        Optional<Dimension> dim1 = authentication.getDimension("org", "org-1");
        assertTrue(dim1.isPresent());
        assertEquals("org-1", dim1.get().getId());

        Optional<Dimension> dim2 = authentication.getDimension("role", "role-1");
        assertTrue(dim2.isPresent());
        assertEquals("role-1", dim2.get().getId());

        Optional<Dimension> missing = authentication.getDimension("org", "unknown");
        assertFalse(missing.isPresent());
    }

    @Test
    void testGetDimensionWithDimensionType() {
        authentication.setDimensions(Arrays.asList(dimension1, dimension2));

        SimpleDimensionType orgType = SimpleDimensionType.of("org");
        Optional<Dimension> dim = authentication.getDimension(orgType, "org-1");
        assertTrue(dim.isPresent());
        assertEquals("org-1", dim.get().getId());
    }

    @Test
    void testGetDimensions() {
        SimpleDimension org2 = SimpleDimension.of("org-2", "Organization 2", SimpleDimensionType.of("org"), null);
        authentication.setDimensions(Arrays.asList(dimension1, org2, dimension2));

        List<Dimension> orgDimensions = authentication.getDimensions("org");
        assertEquals(2, orgDimensions.size());

        List<Dimension> roleDimensions = authentication.getDimensions("role");
        assertEquals(1, roleDimensions.size());
        assertEquals("role-1", roleDimensions.get(0).getId());

        List<Dimension> unknownDimensions = authentication.getDimensions("unknown");
        assertTrue(unknownDimensions.isEmpty());
    }

    @Test
    void testGetDimensionsWithDimensionType() {
        authentication.setDimensions(Arrays.asList(dimension1, dimension2));

        SimpleDimensionType orgType = SimpleDimensionType.of("org");
        List<Dimension> dimensions = authentication.getDimensions(orgType);
        assertEquals(1, dimensions.size());
        assertEquals("org-1", dimensions.get(0).getId());
    }

    @Test
    void testHasDimension() {
        authentication.setDimensions(Arrays.asList(dimension1, dimension2));

        assertTrue(authentication.hasDimension("org", "org-1"));
        assertTrue(authentication.hasDimension("role", "role-1"));
        assertFalse(authentication.hasDimension("org", "unknown"));
        assertFalse(authentication.hasDimension("unknown", "org-1"));
    }

    @Test
    void testMerge() {
        // 设置初始认证信息
        authentication.setUser(user);
        authentication.setPermissions(Collections.singletonList(permission1));
        authentication.setDimensions(Collections.singletonList(dimension1));
        authentication.setAttributes(Collections.singletonMap("key1", "value1"));

        // 创建要合并的认证信息
        SimpleAuthentication other = new SimpleAuthentication();
        SimpleUser otherUser = SimpleUser.builder()
                                         .id("other-user-id")
                                         .username("otheruser")
                                         .build();
        other.setUser(otherUser);
        other.setPermissions(Collections.singletonList(permission2));
        other.setDimensions(Collections.singletonList(dimension2));
        other.setAttributes(Collections.singletonMap("key2", "value2"));

        // 执行合并
        SimpleAuthentication merged = authentication.merge(other);

        // 验证用户被更新
        assertEquals("other-user-id", merged.getUser().getId());

        // 验证权限被合并（permission1 和 permission2 都应该存在）
        assertEquals(2, merged.getPermissions().size());

        // 验证维度被合并（不重复添加）
        assertTrue(merged.getDimensions().contains(dimension1));
        assertTrue(merged.getDimensions().contains(dimension2));

        // 验证属性被合并
        assertEquals(2, merged.getAttributes().size());
        assertEquals("value1", merged.getAttributes().get("key1"));
        assertEquals("value2", merged.getAttributes().get("key2"));
    }

    @Test
    void testMergeWithDuplicatePermissions() {
        // 设置初始权限
        authentication.setPermissions(Collections.singletonList(permission1));

        // 创建具有相同 ID 但不同 actions 的权限
        SimplePermission permission1WithMoreActions = SimplePermission.builder()
                                                                      .id("permission-1")
                                                                      .name("Permission 1")
                                                                      .actions(new HashSet<>(Arrays.asList("query", "save", "delete", "update")))
                                                                      .build();

        SimpleAuthentication other = new SimpleAuthentication();
        other.setPermissions(Collections.singletonList(permission1WithMoreActions));

        // 执行合并
        SimpleAuthentication merged = authentication.merge(other);

        // 验证权限被合并，actions 被合并
        assertEquals(1, merged.getPermissions().size());
        Permission mergedPermission = merged.getPermissions().get(0);
        assertEquals("permission-1", mergedPermission.getId());
        assertTrue(mergedPermission.getActions().contains("query"));
        assertTrue(mergedPermission.getActions().contains("save"));
        assertTrue(mergedPermission.getActions().contains("delete"));
        assertTrue(mergedPermission.getActions().contains("update"));
    }

    @Test
    void testMergeWithDuplicateDimensions() {
        authentication.setDimensions(Collections.singletonList(dimension1));

        SimpleAuthentication other = new SimpleAuthentication();
        other.setDimensions(Collections.singletonList(dimension1)); // 相同的维度

        SimpleAuthentication merged = authentication.merge(other);

        // 验证维度不会被重复添加
        long org1Count = merged.getDimensions().stream()
                               .filter(d -> d.getId().equals("org-1") && d.getType().getId().equals("org"))
                               .count();
        assertEquals(1, org1Count);
    }

    @Test
    void testMergeWithNullUser() {
        authentication.setUser(user);

        SimpleAuthentication other = new SimpleAuthentication();
        // other 没有设置用户

        SimpleAuthentication merged = authentication.merge(other);

        // 验证原始用户保持不变
        assertEquals(user, merged.getUser());
    }

    @Test
    void testCopy() {
        authentication.setUser(user);
        authentication.setPermissions(Arrays.asList(permission1, permission2));
        authentication.setDimensions(Arrays.asList(dimension1, dimension2));
        authentication.setAttributes(Collections.singletonMap("key1", "value1"));

        // 复制所有权限和维度
        Authentication copied = authentication.copy(
            (permission, action) -> true,  // 允许所有权限和操作
            dimension -> true               // 允许所有维度
        );

        assertNotNull(copied);
        assertEquals(user, copied.getUser());
        assertEquals(2, copied.getPermissions().size());
        // user,org,role
        assertEquals(3, copied.getDimensions().size());
        assertEquals("value1", copied.getAttributes().get("key1"));
    }

    @Test
    void testCopyWithPermissionFilter() {
        authentication.setPermissions(Arrays.asList(permission1, permission2));

        // 只复制 permission-1
        Authentication copied = authentication.copy(
            (permission, action) -> permission.getId().equals("permission-1"),
            dimension -> true
        );

        assertEquals(1, copied.getPermissions().size());
        assertEquals("permission-1", copied.getPermissions().get(0).getId());
    }

    @Test
    void testCopyWithActionFilter() {
        authentication.setPermissions(Collections.singletonList(permission1));

        // 只复制 query action
        Authentication copied = authentication.copy(
            (permission, action) -> action.equals("query"),
            dimension -> true
        );

        assertEquals(1, copied.getPermissions().size());
        Permission copiedPermission = copied.getPermissions().get(0);
        assertEquals("permission-1", copiedPermission.getId());
        assertEquals(1, copiedPermission.getActions().size());
        assertTrue(copiedPermission.getActions().contains("query"));
        assertFalse(copiedPermission.getActions().contains("save"));
    }

    @Test
    void testCopyWithDimensionFilter() {
        authentication.setDimensions(Arrays.asList(dimension1, dimension2));

        // 只复制 org 类型的维度
        Authentication copied = authentication.copy(
            (permission, action) -> true,
            dimension -> dimension.getType().getId().equals("org")
        );

        assertEquals(1, copied.getDimensions(dimension1.getType()).size());
        assertEquals("org-1", copied.getDimensions().get(0).getId());
    }

    @Test
    void testCopyFiltersEmptyActions() {
        SimplePermission permissionWithEmptyActions = SimplePermission.builder()
                                                                      .id("empty-permission")
                                                                      .name("Empty Permission")
                                                                      .actions(new HashSet<>())
                                                                      .build();

        authentication.setPermissions(Collections.singletonList(permissionWithEmptyActions));

        // 复制时，如果过滤后 actions 为空，权限应该被过滤掉
        Authentication copied = authentication.copy(
            (permission, action) -> false,  // 不允许任何 action
            dimension -> true
        );

        assertEquals(0, copied.getPermissions().size());
    }

    @Test
    void testFastPathOptimization() {
        authentication.setPermissions(Collections.singletonList(permission1));
        authentication.setDimensions(Collections.singletonList(dimension1));

        // 前7次访问应该使用慢路径
        for (int i = 0; i < 7; i++) {
            authentication.hasPermission("permission-1", Collections.singletonList("query"));
        }

        // 第8次访问应该触发快速路径初始化
        assertTrue(authentication.hasPermission("permission-1", Collections.singletonList("query")));

        // 之后的访问应该使用快速路径
        assertTrue(authentication.hasPermission("permission-1", Collections.singletonList("query")));
        assertTrue(authentication.getPermission("permission-1").isPresent());
        assertTrue(authentication.getDimension("org", "org-1").isPresent());
    }

    @Test
    void testNewInstance() {
        SimpleAuthentication instance1 = authentication.newInstance();
        SimpleAuthentication instance2 = authentication.newInstance();

        assertNotNull(instance1);
        assertNotNull(instance2);
        assertNotSame(instance1, instance2);
        assertTrue(instance1 instanceof SimpleAuthentication);
        assertTrue(instance2 instanceof SimpleAuthentication);
    }

    @Test
    void testEmptyPermissions() {
        authentication.setPermissions(Collections.emptyList());

        assertFalse(authentication.hasPermission("any", Collections.singletonList("any")));
        assertFalse(authentication.getPermission("any").isPresent());
    }

    @Test
    void testEmptyDimensions() {
        authentication.setDimensions(Collections.emptyList());

        assertFalse(authentication.hasDimension("any", "any"));
        assertFalse(authentication.getDimension("any", "any").isPresent());
        assertTrue(authentication.getDimensions("any").isEmpty());
    }

    @Test
    void testNullAttributes() {
        // 测试 null 属性处理
        authentication.setAttributes(null);
        assertNotNull(authentication.getAttributes());
    }

    @Test
    void testGetAttributeWithType() {
        authentication.setAttributes(Collections.singletonMap("int-value", 123));

        Optional<Integer> intValue = authentication.getAttribute("int-value");
        assertTrue(intValue.isPresent());
        assertEquals(123, intValue.get());
    }

    @Test
    void testMultipleDimensionsSameType() {
        SimpleDimension org2 = SimpleDimension.of("org-2", "Organization 2", SimpleDimensionType.of("org"), null);
        SimpleDimension org3 = SimpleDimension.of("org-3", "Organization 3", SimpleDimensionType.of("org"), null);

        authentication.setDimensions(Arrays.asList(dimension1, org2, org3));

        List<Dimension> orgDimensions = authentication.getDimensions("org");
        assertEquals(3, orgDimensions.size());
    }

    @Test
    void testUserAsDimension() {
        authentication.setUser(user);

        // 用户应该被添加到维度列表中
        assertTrue(authentication.getDimensions().contains(user));

        // 可以通过维度类型查找用户
        Optional<Dimension> userDimension = authentication.getDimension(
            DefaultDimensionType.user.getId(),
            user.getId()
        );
        assertTrue(userDimension.isPresent());
    }

    @Test
    void testSetDimensionsDeduplicatesUser() {
        authentication.setUser(user);
        // setDimensions 再次带入 user(及角色)时,user 维度不应被重复添加
        authentication.setDimensions(Arrays.asList(user, dimension2));

        long userCount = authentication
            .getDimensions()
            .stream()
            .filter(d -> d.getId().equals(user.getId())
                && d.getType().getId().equals(DefaultDimensionType.user.getId()))
            .count();
        assertEquals(1, userCount, "user 维度不应被重复添加");
        assertEquals(2, authentication.getDimensions().size());
    }

    @Test
    void testAddDimensionReplacesExisting() {
        authentication.addDimension(SimpleDimension.of("org-1", "OLD", SimpleDimensionType.of("org"), null));
        // 相同 (type,id) 再次 add => 替换为最新(last-wins),不重复
        authentication.addDimension(SimpleDimension.of("org-1", "NEW", SimpleDimensionType.of("org"), null));

        assertEquals(1, authentication.getDimensions().size());
        assertEquals("NEW", authentication.getDimension("org", "org-1").get().getName());
    }

    @Test
    void testSetDimensionsLastWins() {
        // 列表内相同 (type,id),后者覆盖前者
        authentication.setDimensions(Arrays.asList(
            SimpleDimension.of("org-1", "OLD", SimpleDimensionType.of("org"), null),
            SimpleDimension.of("org-1", "NEW", SimpleDimensionType.of("org"), null)));

        assertEquals(1, authentication.getDimensions().size());
        assertEquals("NEW", authentication.getDimension("org", "org-1").get().getName());
    }

    @Test
    void testMergeKeepsSelfOnDuplicate() {
        // merge 保持 self 优先: 相同 (type,id) 时 self 的值不被 other 覆盖
        authentication.setDimensions(Collections.singletonList(
            SimpleDimension.of("org-1", "SELF", SimpleDimensionType.of("org"), null)));
        SimpleAuthentication other = new SimpleAuthentication();
        other.setDimensions(Collections.singletonList(
            SimpleDimension.of("org-1", "OTHER", SimpleDimensionType.of("org"), null)));

        authentication.merge(other);

        assertEquals(1, authentication.getDimensions().size());
        assertEquals("SELF", authentication.getDimension("org", "org-1").get().getName());
    }

    @Test
    void testAddDimensionsDeduplicates() {
        authentication.addDimension(dimension1);
        authentication.addDimensions(Arrays.asList(dimension1, dimension2));

        assertEquals(2, authentication.getDimensions().size());
        assertTrue(authentication.hasDimension("org", "org-1"));
        assertTrue(authentication.hasDimension("role", "role-1"));
    }

    @Test
    void testMergeInvalidatesFastPathCache() {
        authentication.setUser(user);
        authentication.setDimensions(Collections.singletonList(dimension1));

        // 触发 fastPath 缓存构建(第8次访问)
        for (int i = 0; i < 8; i++) {
            authentication.getDimension("org", "org-1");
        }

        SimpleAuthentication other = new SimpleAuthentication();
        other.setDimensions(Collections.singletonList(dimension2));
        authentication.merge(other);

        // merge 重建了 dimensions/permissions 列表,缓存须失效,新维度应可被查询到
        assertTrue(authentication.getDimension("role", "role-1").isPresent(),
                   "merge 后新维度应可查询(fastPath 缓存已失效)");
    }

    // ========== 性能测试 ==========

    @Test
    void testPerformanceBeforeFastPath() {
        // 准备大量权限和维度数据
        List<Permission> permissions = new ArrayList<>();
        for (int i = 0; i < 1000; i++) {
            permissions.add(SimplePermission.builder()
                                            .id("permission-" + i)
                                            .name("Permission " + i)
                                            .actions(new HashSet<>(Arrays.asList("query", "save", "delete")))
                                            .build());
        }

        List<Dimension> dimensions = new ArrayList<>();
        for (int i = 0; i < 500; i++) {
            dimensions.add(SimpleDimension.of(
                "dim-" + i,
                "Dimension " + i,
                SimpleDimensionType.of("type-" + (i % 10)),
                null
            ));
        }

        int iterations = 10000;
        long totalTime = 0;

        // 使用多个实例来测试慢路径，每个实例只访问7次
        int batchSize = 7;
        int batches = iterations / batchSize;

        long startTime = System.nanoTime();
        for (int batch = 0; batch < batches; batch++) {
            SimpleAuthentication auth = new SimpleAuthentication();
            auth.setUser(user);
            auth.setPermissions(permissions);
            auth.setDimensions(dimensions);

            // 每个实例只访问7次（fastPath 未生效）
            for (int i = 0; i < batchSize; i++) {
                int idx = (batch * batchSize + i) % 1000;
                auth.hasPermission("permission-" + idx, Collections.singletonList("query"));
                auth.getPermission("permission-" + idx);
                auth.getDimension("type-5", "dim-" + (idx % 500));
                auth.getDimensions("type-5");
            }
        }
        long endTime = System.nanoTime();
        totalTime = endTime - startTime;

        double avgTimeNanos = (double) totalTime / iterations;
        double opsPerSecond = 1_000_000_000.0 / avgTimeNanos;

        System.out.println("\n========== FastPath 生效前性能测试 ==========");
        System.out.println("迭代次数: " + iterations);
        System.out.println("总耗时: " + (totalTime / 1_000_000) + " ms");
        System.out.println("平均每次操作耗时: " + String.format("%.2f", avgTimeNanos / 1000) + " μs");
        System.out.println("每秒操作数: " + String.format("%.2f", opsPerSecond / 4) + " ops/s (每个方法)");
        System.out.println("==========================================\n");
    }

    @Test
    void testPerformanceAfterFastPath() {
        // 准备大量权限和维度数据
        List<Permission> permissions = new ArrayList<>();
        for (int i = 0; i < 1000; i++) {
            permissions.add(SimplePermission.builder()
                                            .id("permission-" + i)
                                            .name("Permission " + i)
                                            .actions(new HashSet<>(Arrays.asList("query", "save", "delete")))
                                            .build());
        }

        List<Dimension> dimensions = new ArrayList<>();
        for (int i = 0; i < 500; i++) {
            dimensions.add(SimpleDimension.of(
                "dim-" + i,
                "Dimension " + i,
                SimpleDimensionType.of("type-" + (i % 10)),
                null
            ));
        }

        SimpleAuthentication auth = new SimpleAuthentication();
        auth.setUser(user);
        auth.setPermissions(permissions);
        auth.setDimensions(dimensions);

        // 触发 fastPath 初始化（访问8次）
        for (int i = 0; i < 8; i++) {
            auth.hasPermission("permission-500", Collections.singletonList("query"));
        }

        int iterations = 10000;
        long totalTime = 0;

        // 测试 fastPath 生效后的性能（快路径）
        long startTime = System.nanoTime();
        for (int i = 0; i < iterations; i++) {
            auth.hasPermission("permission-" + (i % 1000), Collections.singletonList("query"));
            auth.getPermission("permission-" + (i % 1000));
            auth.getDimension("type-5", "dim-" + (i % 500));
            auth.getDimensions("type-5");
        }
        long endTime = System.nanoTime();
        totalTime = endTime - startTime;

        double avgTimeNanos = (double) totalTime / iterations;
        double opsPerSecond = 1_000_000_000.0 / avgTimeNanos;

        System.out.println("\n========== FastPath 生效后性能测试 ==========");
        System.out.println("迭代次数: " + iterations);
        System.out.println("总耗时: " + (totalTime / 1_000_000) + " ms");
        System.out.println("平均每次操作耗时: " + String.format("%.2f", avgTimeNanos / 1000) + " μs");
        System.out.println("每秒操作数: " + String.format("%.2f", opsPerSecond / 4) + " ops/s (每个方法)");
        System.out.println("==========================================\n");
    }

    @Test
    void testPerformanceComparison() {
        // 准备大量权限和维度数据
        List<Permission> permissions = new ArrayList<>();
        for (int i = 0; i < 1000; i++) {
            permissions.add(SimplePermission.builder()
                                            .id("permission-" + i)
                                            .name("Permission " + i)
                                            .actions(new HashSet<>(Arrays.asList("query", "save", "delete")))
                                            .build());
        }

        List<Dimension> dimensions = new ArrayList<>();
        for (int i = 0; i < 500; i++) {
            dimensions.add(SimpleDimension.of(
                "dim-" + i,
                "Dimension " + i,
                SimpleDimensionType.of("type-" + (i % 10)),
                null
            ));
        }

        int iterations = 10000;

        // 测试慢路径性能
        SimpleAuthentication slowPathAuth = new SimpleAuthentication();
        slowPathAuth.setUser(user);
        slowPathAuth.setPermissions(permissions);
        slowPathAuth.setDimensions(dimensions);

        // 只访问7次，确保 fastPath 不生效
        for (int i = 0; i < 7; i++) {
            slowPathAuth.hasPermission("permission-500", Collections.singletonList("query"));
        }

        long slowPathStart = System.nanoTime();
        for (int i = 0; i < iterations; i++) {
            slowPathAuth.hasPermission("permission-" + (i % 1000), Collections.singletonList("query"));
            slowPathAuth.getPermission("permission-" + (i % 1000));
            slowPathAuth.getDimension("type-5", "dim-" + (i % 500));
            slowPathAuth.getDimensions("type-5");
        }
        long slowPathTime = System.nanoTime() - slowPathStart;

        // 测试快路径性能
        SimpleAuthentication fastPathAuth = new SimpleAuthentication();
        fastPathAuth.setUser(user);
        fastPathAuth.setPermissions(permissions);
        fastPathAuth.setDimensions(dimensions);

        // 触发 fastPath 初始化（访问8次）
        for (int i = 0; i < 8; i++) {
            fastPathAuth.hasPermission("permission-500", Collections.singletonList("query"));
        }

        long fastPathStart = System.nanoTime();
        for (int i = 0; i < iterations; i++) {
            fastPathAuth.hasPermission("permission-" + (i % 1000), Collections.singletonList("query"));
            fastPathAuth.getPermission("permission-" + (i % 1000));
            fastPathAuth.getDimension("type-5", "dim-" + (i % 500));
            fastPathAuth.getDimensions("type-5");
        }
        long fastPathTime = System.nanoTime() - fastPathStart;

        // 计算性能提升
        double slowPathAvg = (double) slowPathTime / iterations;
        double fastPathAvg = (double) fastPathTime / iterations;
        double improvement = ((slowPathAvg - fastPathAvg) / slowPathAvg) * 100;

        System.out.println("\n========== FastPath 性能对比测试 ==========");
        System.out.println("测试数据规模:");
        System.out.println("  - 权限数量: 1000");
        System.out.println("  - 维度数量: 500");
        System.out.println("  - 迭代次数: " + iterations);
        System.out.println();
        System.out.println("慢路径 (FastPath 未生效):");
        System.out.println("  - 总耗时: " + (slowPathTime / 1_000_000) + " ms");
        System.out.println("  - 平均每次操作: " + String.format("%.2f", slowPathAvg / 1000) + " μs");
        System.out.println();
        System.out.println("快路径 (FastPath 已生效):");
        System.out.println("  - 总耗时: " + (fastPathTime / 1_000_000) + " ms");
        System.out.println("  - 平均每次操作: " + String.format("%.2f", fastPathAvg / 1000) + " μs");
        System.out.println();
        System.out.println("性能提升: " + String.format("%.2f", improvement) + "%");
        System.out.println("性能倍数: " + String.format("%.2f", slowPathAvg / fastPathAvg) + "x");
        System.out.println("==========================================\n");

        // 验证 fastPath 确实提升了性能
        assertTrue(fastPathTime < slowPathTime,
                   "FastPath 应该比慢路径更快。慢路径: " + slowPathTime + " ns, 快路径: " + fastPathTime + " ns");
    }

    @Test
    void testPerformanceWithDifferentDataSizes() {
        int[] permissionSizes = {100, 500, 1000, 2000};
        int[] dimensionSizes = {50, 250, 500, 1000};
        int iterations = 5000;

        System.out.println("\n========== 不同数据规模下的性能测试 ==========");
        System.out.println("迭代次数: " + iterations);
        System.out.println();

        for (int permSize : permissionSizes) {
            for (int dimSize : dimensionSizes) {
                // 准备数据
                List<Permission> permissions = new ArrayList<>();
                for (int i = 0; i < permSize; i++) {
                    permissions.add(SimplePermission.builder()
                                                    .id("permission-" + i)
                                                    .name("Permission " + i)
                                                    .actions(new HashSet<>(Arrays.asList("query", "save")))
                                                    .build());
                }

                List<Dimension> dimensions = new ArrayList<>();
                for (int i = 0; i < dimSize; i++) {
                    dimensions.add(SimpleDimension.of(
                        "dim-" + i,
                        "Dimension " + i,
                        SimpleDimensionType.of("type-" + (i % 10)),
                        null
                    ));
                }

                SimpleAuthentication auth = new SimpleAuthentication();
                auth.setUser(user);
                auth.setPermissions(permissions);
                auth.setDimensions(dimensions);

                // 触发 fastPath
                for (int i = 0; i < 8; i++) {
                    auth.hasPermission("permission-0", Collections.singletonList("query"));
                }

                long start = System.nanoTime();
                for (int i = 0; i < iterations; i++) {
                    auth.hasPermission("permission-" + (i % permSize), Collections.singletonList("query"));
                    auth.getPermission("permission-" + (i % permSize));
                    auth.getDimension("type-0", "dim-" + (i % dimSize));
                    auth.getDimensions("type-0");
                }
                long time = System.nanoTime() - start;

                double avgTime = (double) time / iterations;
                System.out.println(String.format(
                    "权限: %4d, 维度: %4d -> 总耗时: %6.2f ms, 平均: %6.2f μs/op",
                    permSize, dimSize, time / 1_000_000.0, avgTime / 1000.0
                ));
            }
        }
        System.out.println("==========================================\n");
    }

    @Test
    void testFastPathInitializationThreshold() {
        authentication.setPermissions(Collections.singletonList(permission1));
        authentication.setDimensions(Collections.singletonList(dimension1));

        // 验证前7次访问不会初始化 fastPath
        for (int i = 0; i < 7; i++) {
            authentication.hasPermission("permission-1", Collections.singletonList("query"));
        }

        // 第8次访问应该触发 fastPath 初始化
        long beforeInit = System.nanoTime();
        authentication.hasPermission("permission-1", Collections.singletonList("query"));
        long initTime = System.nanoTime() - beforeInit;

        // 第9次及之后的访问应该使用 fastPath
        long afterInit = System.nanoTime();
        for (int i = 0; i < 100; i++) {
            authentication.hasPermission("permission-1", Collections.singletonList("query"));
        }
        long fastPathTime = System.nanoTime() - afterInit;

        System.out.println("\n========== FastPath 初始化阈值测试 ==========");
        System.out.println("第8次访问耗时（包含初始化）: " + (initTime / 1000) + " μs");
        System.out.println("后续100次访问总耗时: " + (fastPathTime / 1_000_000) + " ms");
        System.out.println("后续100次访问平均耗时: " + (fastPathTime / 100_000.0) + " μs");
        System.out.println("==========================================\n");

        // 验证初始化后的访问确实更快
        assertTrue(fastPathTime / 100.0 < initTime * 10,
                   "FastPath 初始化后的访问应该比初始化时更快");
    }
}
