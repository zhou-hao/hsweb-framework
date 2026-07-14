/*
 * Copyright 2020 http://www.hswebframework.org
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 */

package org.hswebframework.web.authorization.simple;

import lombok.Getter;
import lombok.Setter;
import org.hswebframework.web.authorization.*;

import javax.annotation.concurrent.NotThreadSafe;
import java.io.Serial;
import java.io.Serializable;
import java.util.*;
import java.util.concurrent.atomic.AtomicLongFieldUpdater;
import java.util.function.BiPredicate;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Collectors;

public class SimpleAuthentication implements Authentication {

    static final AtomicLongFieldUpdater<SimpleAuthentication> ACCESS_COUNT_UPDATER =
        AtomicLongFieldUpdater.newUpdater(SimpleAuthentication.class, "accessCount");

    @Serial
    private static final long serialVersionUID = -2898863220255336528L;

    @Getter
    private User user;

    @Setter
    private List<Permission> permissions = new ArrayList<>();

    private List<Dimension> dimensions = new ArrayList<>();

    private Map<String, Serializable> attributes = new HashMap<>();

    public static Authentication of() {
        return new SimpleAuthentication();
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T extends Serializable> Optional<T> getAttribute(String name) {
        return Optional.ofNullable((T) attributes.get(name));
    }

    public List<Dimension> getDimensions() {
        return dimensions == null ? Collections.emptyList() : dimensions;
    }

    public List<Permission> getPermissions() {
        return permissions == null ? Collections.emptyList() : permissions;
    }

    @Override
    public Map<String, Serializable> getAttributes() {
        return attributes == null ? Collections.emptyMap() : attributes;
    }

    public void setAttributes(Map<String, Serializable> attributes) {
        this.attributes = new HashMap<>(attributes);
    }

    @Override
    public void setAttribute(String key, Serializable value) {
        if (key == null) {
            return;
        }
        if (this.attributes == null) {
            this.attributes = new HashMap<>();
        }
        this.attributes.put(key, value);
    }

    public void putAttributes(Map<String, Serializable> attributes) {
        this.attributes = new HashMap<>(this.attributes);
        this.attributes.putAll(attributes);
    }

    public SimpleAuthentication merge(Authentication authentication) {
        Map<String, Permission> mePermissionGroup = permissions
            .stream()
            .collect(Collectors.toMap(Permission::getId, Function.identity()));

        if (authentication.getUser() != null) {
            user = authentication.getUser();
        }
        this.attributes = new HashMap<>(getAttributes());
        this.attributes.putAll(authentication.getAttributes());

        this.permissions = new ArrayList<>(this.getPermissions());
        for (Permission permission : authentication.getPermissions()) {
            Permission me = mePermissionGroup.get(permission.getId());
            if (me == null) {
                permissions.add(permission.copy());
                continue;
            }
            me.getActions().addAll(permission.getActions());
        }
        //merge 保持 self 优先:仅当 self 不含该 (type,id) 时才并入 other 的维度
        this.dimensions = new ArrayList<>(this.getDimensions());
        for (Dimension dimension : authentication.getDimensions()) {
            if (!containsDimension(this.dimensions, dimension)) {
                this.dimensions.add(dimension);
            }
        }
        //重建了 permissions/dimensions 列表,失效索引缓存
        this.permissionMapping = null;
        this.dimensionMapping = null;
        return this;
    }

    protected SimpleAuthentication newInstance() {
        return new SimpleAuthentication();
    }

    @Override
    public Authentication copy(BiPredicate<Permission, String> permissionFilter,
                               Predicate<Dimension> dimension) {
        SimpleAuthentication authentication = newInstance();
        authentication.setDimensions(dimensions
                                         .stream()
                                         .filter(dimension)
                                         .collect(Collectors.toList()));
        authentication.setPermissions(permissions
                                          .stream()
                                          .map(permission -> permission.copy(action -> permissionFilter.test(permission, action), conf -> true))
                                          .filter(per -> !per.getActions().isEmpty())
                                          .collect(Collectors.toList())
        );
        if (user != null) {
            authentication.setUser0(user);
        }
        authentication.setAttributes(new HashMap<>(attributes));
        return authentication;
    }

    public void setUser(User user) {
        this.user = user;
        addDimension(user);
    }

    protected void setUser0(User user) {
        this.user = user;
    }

    protected List<Dimension> newDimensions() {
        this.dimensions = new ArrayList<>();
        if (user != null) {
            this.dimensions.add(user);
        }
        return this.dimensions;
    }

    public void setDimensions(List<Dimension> dimensions) {
        this.dimensions = distinctLastWins(newDimensions(), dimensions);
        this.dimensionMapping = null;
    }

    public void setDimensions(Collection<Dimension> dimensions) {
        this.dimensions = distinctLastWins(newDimensions(), dimensions);
        this.dimensionMapping = null;
    }

    public void addDimension(Dimension dimension) {
        if (dimension != null) {
            List<Dimension> target = writableDimensions();
            int index = indexOfDimension(target, dimension);
            //已存在相同 (type,id) 则替换为最新,否则追加;线性查找避免分配索引结构
            if (index >= 0) {
                target.set(index, dimension);
            } else {
                target.add(dimension);
            }
        }
        this.dimensionMapping = null;
    }

    public void cleanPermissions() {
        this.permissions = new ArrayList<>();
        this.permissionMapping = null;
    }

    protected List<Dimension> writableDimensions() {
        return this.dimensions == null ? this.dimensions = new ArrayList<>() : this.dimensions;
    }

    public void addDimensions(Collection<? extends Dimension> dimensions) {
        this.dimensions = distinctLastWins(getDimensions(), dimensions);
        this.dimensionMapping = null;
    }

    /**
     * 以 seed 为基础并入 toAdd,按 (type,id) 去重;命中相同标识时<b>后者覆盖前者(替换为最新)</b>,
     * 并保持该标识首次出现的位置.
     */
    private static List<Dimension> distinctLastWins(List<Dimension> seed,
                                                    Collection<? extends Dimension> toAdd) {
        //结构化 (type,id) -> 维度,LinkedHashMap 保位置、put 覆盖实现 last-wins
        LinkedHashMap<List<String>, Dimension> merged = new LinkedHashMap<>();
        for (Dimension dimension : seed) {
            merged.put(dimensionKey(dimension), dimension);
        }
        if (toAdd != null) {
            for (Dimension dimension : toAdd) {
                if (dimension != null) {
                    merged.put(dimensionKey(dimension), dimension);
                }
            }
        }
        return new ArrayList<>(merged.values());
    }

    //结构化 (type,id) key,避免拼接字符串带来的分隔符歧义/碰撞
    private static List<String> dimensionKey(Dimension dimension) {
        DimensionType type = dimension.getType();
        return Arrays.asList(type == null ? null : type.getId(), dimension.getId());
    }

    //merge 中判断 self 是否已含相同 (type,id);零分配线性查找
    private static boolean containsDimension(List<Dimension> dimensions, Dimension dimension) {
        return indexOfDimension(dimensions, dimension) >= 0;
    }

    //线性查找相同 (type,id) 的下标,无则 -1;零分配,用于单个 add 的就地替换
    private static int indexOfDimension(List<Dimension> dimensions, Dimension dimension) {
        DimensionType type = dimension.getType();
        String typeId = type == null ? null : type.getId();
        String id = dimension.getId();
        for (int i = 0; i < dimensions.size(); i++) {
            Dimension exist = dimensions.get(i);
            DimensionType existType = exist.getType();
            if (Objects.equals(id, exist.getId())
                && Objects.equals(typeId, existType == null ? null : existType.getId())) {
                return i;
            }
        }
        return -1;
    }

    public void addPermissions(Collection<? extends Permission> permissions) {
        this.permissions = new ArrayList<>(getPermissions());
        this.permissions.addAll(permissions);
        this.permissionMapping = null;
    }

    public void addPermission(Permission permission) {
        this.permissions.add(permission);
        this.permissionMapping = null;
    }

    private transient volatile Map<String, Map<String, Dimension>> dimensionMapping;
    private transient volatile Map<String, Permission> permissionMapping;
    private transient volatile long accessCount;

    protected boolean fastPath() {
        // 总共访问超过8次,则进行初始化缓存.
        if (ACCESS_COUNT_UPDATER.incrementAndGet(this) == 8) {
            if (permissionMapping == null) {
                permissionMapping = permissions == null
                    ? Collections.emptyMap()
                    : permissions
                      .stream()
                      .collect(Collectors
                               .toMap(Permission::getId,
                                      Function.identity(),
                                      (a, b) -> b));
                dimensionMapping = dimensions == null
                    ? Collections.emptyMap()
                    : dimensions
                      .stream()
                      .collect(Collectors
                               .groupingBy(d -> d.getType().getId(),
                                           Collectors.toMap(
                                               Dimension::getId,
                                               Function.identity(),
                                               (a, b) -> a)));
            }
        }
        return permissionMapping != null;
    }

    @Override
    public boolean hasPermission(String permissionId, Collection<String> actions) {
        Map<String, Permission> permissionMapping = this.permissionMapping;
        if (fastPath() && permissionMapping != null) {
            Permission permission = permissionMapping.get(permissionId);
            if (permission == null) {
                permission = permissionMapping.get("*");
            }
            if (permission == null) {
                return false;
            }
            return actions.isEmpty()
                || permission.getActions().containsAll(actions)
                || permission.getActions().contains("*");
        }
        return Authentication.super.hasPermission(permissionId, actions);
    }

    @Override
    public Optional<Dimension> getDimension(String type, String id) {
        Map<String, Map<String, Dimension>> dimensionMapping = this.dimensionMapping;
        if (fastPath() && dimensionMapping != null) {
            Map<String, Dimension> mapping = dimensionMapping.get(type);
            if (mapping == null) {
                return Optional.empty();
            }
            return Optional.ofNullable(mapping.get(id));
        }
        return Authentication.super.getDimension(type, id);
    }

    @Override
    public Optional<Dimension> getDimension(DimensionType type, String id) {
        return getDimension(type.getId(), id);
    }

    @Override
    public List<Dimension> getDimensions(DimensionType type) {
        return this.getDimensions(type.getId());
    }

    @Override
    public List<Dimension> getDimensions(String type) {
        Map<String, Map<String, Dimension>> dimensionMapping = this.dimensionMapping;
        if (fastPath() && dimensionMapping != null) {
            Map<String, Dimension> mapping = dimensionMapping.get(type);
            if (mapping == null) {
                return List.of();
            }
            return new ArrayList<>(mapping.values());
        }
        return Authentication.super.getDimensions(type);
    }

    @Override
    public Optional<Permission> getPermission(String id) {
        Map<String, Permission> permissionMapping = this.permissionMapping;
        if (fastPath() && permissionMapping != null) {
            return Optional.ofNullable(permissionMapping.get(id));
        }
        return Authentication.super.getPermission(id);
    }

}
