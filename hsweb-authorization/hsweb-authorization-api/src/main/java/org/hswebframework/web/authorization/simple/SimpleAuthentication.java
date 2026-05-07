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

    @Setter
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
        this.dimensions = new ArrayList<>(this.getDimensions());
        for (Dimension dimension : authentication.getDimensions()) {
            if (getDimension(dimension.getType(), dimension.getId()).isEmpty()) {
                dimensions.add(dimension);
            }
        }
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
        dimensions.add(user);
    }

    protected void setUser0(User user) {
        this.user = user;
    }

    public void setDimensions(List<Dimension> dimensions) {
        this.dimensions.addAll(dimensions);
    }

    public void setDimensions(Collection<Dimension> dimensions) {
        this.dimensions.addAll(dimensions);
    }

    public void addDimension(Dimension dimension) {
        this.dimensions.add(dimension);
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
