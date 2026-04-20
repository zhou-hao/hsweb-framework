package org.hswebframework.web.crud.query;

import lombok.Getter;
import lombok.SneakyThrows;
import net.sf.jsqlparser.expression.*;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.schema.Column;
import net.sf.jsqlparser.schema.Table;
import net.sf.jsqlparser.statement.piped.FromQuery;
import net.sf.jsqlparser.statement.select.*;
import org.apache.commons.collections4.CollectionUtils;
import org.hswebframework.ezorm.core.meta.FeatureSupportedMetadata;
import org.hswebframework.ezorm.core.param.Sort;
import org.hswebframework.ezorm.core.param.Term;
import org.hswebframework.ezorm.rdb.executor.SqlRequest;
import org.hswebframework.ezorm.rdb.metadata.*;
import org.hswebframework.ezorm.rdb.metadata.dialect.Dialect;
import org.hswebframework.ezorm.rdb.operator.DatabaseOperator;
import org.hswebframework.ezorm.rdb.operator.builder.fragments.*;
import org.hswebframework.web.api.crud.entity.QueryParamEntity;
import org.springframework.util.Assert;
import org.springframework.util.StringUtils;

import java.util.*;

import static net.sf.jsqlparser.statement.select.Select.getFormattedList;
import static org.hswebframework.ezorm.rdb.operator.builder.fragments.TermFragmentBuilder.createFeatureId;


class QueryAnalyzerImpl implements FromItemVisitor<Void>, SelectItemVisitor<Void>, SelectVisitor<Void>, QueryAnalyzer {

    private static final String UNSUPPORTED_DISTANCE_OPERATOR = "<=>";

    private static final List<String> PARSER_OPERATOR_PLACEHOLDERS = Arrays.asList("!~*", "~*", "@@", "@>", "<@", "&&", "~");

    private final DatabaseOperator database;

    private String sql;

    private final net.sf.jsqlparser.statement.select.Select parsed;

    private String parserOperatorPlaceholder;

    private QueryAnalyzer.Select select;

    private final Map<String, QueryAnalyzer.Join> joins = new LinkedHashMap<>();

    private final List<WithItem<?>> withItems = new ArrayList<>();
    private QueryRefactor injector;

    private volatile Map<String, Column> columnMappings;

    private final Map<String, TableOrViewMetadata> virtualTable = new HashMap<>();

    private static <T> T acceptFromItem(FromItem fromItem, FromItemVisitor<T> visitor) {
        return fromItem.accept(visitor, null);
    }

    private static <T> T acceptSelect(net.sf.jsqlparser.statement.select.Select select, SelectVisitor<T> visitor) {
        return select.accept(visitor, null);
    }

    private static <T> T acceptWithItem(WithItem<?> withItem, SelectVisitor<T> visitor) {
        return withItem.accept(visitor, null);
    }

    private static <T> T acceptSelectItem(SelectItem<?> selectItem, SelectItemVisitor<T> visitor) {
        return selectItem.accept(visitor, null);
    }

    @Override
    public String originalSql() {
        return sql;
    }

    @Override
    public SqlRequest refactor(QueryParamEntity entity, Object... args) {
        if (injector == null) {
            initInjector();
        }
        return injector.refactor(entity, args);
    }

    @Override
    public SqlRequest refactorCount(QueryParamEntity entity, Object... args) {
        if (injector == null) {
            initInjector();
        }
        return injector.refactorCount(entity, args);
    }

    @Override
    public Select select() {
        return select;
    }

    @Override
    public Optional<Column> findColumn(String name) {
        return Optional.ofNullable(getColumnMappings().get(name));
    }

    @Override
    public List<Join> joins() {
        return new ArrayList<>(joins.values());
    }

    QueryAnalyzerImpl(DatabaseOperator database, String sql) {
        this(database, parse(sql));
        this.sql = sql;
    }

    private QueryAnalyzerImpl(DatabaseOperator database, ParsedSelect parsed) {
        this(database, parsed.select);
        this.parserOperatorPlaceholder = parsed.operatorPlaceholder;
    }


    public boolean columnIsExpression(String name, int index) {

        if (index >= 0 && select.getColumnList().size() > index) {
            return select.getColumnList().get(index) instanceof ExpressionColumn;
        }

        return select.findColumn(name).orElse(null) instanceof ExpressionColumn;
    }

    private Map<String, Column> getColumnMappings() {
        if (columnMappings == null) {
            synchronized (this) {
                if (columnMappings == null) {
                    columnMappings = new HashMap<>();

                    if (select.table instanceof SelectTable) {

                        for (Map.Entry<String, Column> entry :
                            ((SelectTable) select.getTable()).getColumns().entrySet()) {
                            Column column = entry.getValue();
                            Column col = new Column(column.getName(), column.getAlias(), select.table.alias, column.metadata);
                            columnMappings.put(entry.getKey(), col);
                            columnMappings.put(select.table.alias + "." + entry.getKey(), col);

                            if (!(column instanceof ExpressionColumn) && column.metadata != null) {
                                columnMappings.put(column.metadata.getName(), col);
                                columnMappings.put(select.table.alias + "." + column.metadata.getName(), col);
                                columnMappings.put(column.metadata.getAlias(), col);
                                columnMappings.put(select.table.alias + "." + column.metadata.getAlias(), col);
                            }
                        }

                        for (Column column : select.getColumnList()) {
                            columnMappings.put(column.getName(), column);
                            columnMappings.put(column.getAlias(), column);
                            if (null != column.getOwner()) {
                                columnMappings.put(column.getOwner() + "." + column.getName(), column);
                                columnMappings.put(column.getOwner() + "." + column.getAlias(), column);
                            }
                        }
                    } else {
                        // 主表
                        for (RDBColumnMetadata column : select.table.metadata.getColumns()) {
                            Column col = new Column(column.getName(), column.getAlias(), select.table.alias, column);
                            columnMappings.put(column.getName(), col);
                            columnMappings.put(column.getAlias(), col);
                            columnMappings.put(select.table.alias + "." + column.getName(), col);
                            columnMappings.put(select.table.alias + "." + column.getAlias(), col);
                        }
                    }

                    //关联表
                    for (Join join : joins.values()) {
                        if (join.table instanceof SelectTable) {
                            for (Column column : select.getColumnList()) {
                                columnMappings.putIfAbsent(column.getName(), column);
                                columnMappings.putIfAbsent(column.getAlias(), column);
                                columnMappings.put(column.getOwner() + "." + column.getName(), column);
                                columnMappings.put(column.getOwner() + "." + column.getAlias(), column);
                            }
                        } else {
                            for (RDBColumnMetadata column : join.table.metadata.getColumns()) {
                                Column col = new Column(column.getName(), column.getAlias(), join.alias, column);
                                columnMappings.putIfAbsent(column.getName(), col);
                                columnMappings.putIfAbsent(column.getAlias(), col);

                                columnMappings.put(join.alias + "." + column.getName(), col);
                                columnMappings.put(join.alias + "." + column.getAlias(), col);
                            }
                        }

                    }
                }
            }
        }
        return columnMappings;
    }

    private Column getColumnOrSelectColumn(String name) {
        Column column = select.findColumn(name).orElse(null);

        if (column != null) {
            return column;
        }

        return getColumnMappings().get(name);
    }

    @SneakyThrows
    private static ParsedSelect parse(String sql) {
        String operatorPlaceholder = null;
        String sqlToParse = sql;
        if (sql.contains(UNSUPPORTED_DISTANCE_OPERATOR)) {
            operatorPlaceholder = PARSER_OPERATOR_PLACEHOLDERS
                .stream()
                .filter(candidate -> !sql.contains(candidate))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unable to parse sql with operator " + UNSUPPORTED_DISTANCE_OPERATOR));
            sqlToParse = sql.replace(UNSUPPORTED_DISTANCE_OPERATOR, operatorPlaceholder);
        }
        return new ParsedSelect(
            ((net.sf.jsqlparser.statement.select.Select) CCJSqlParserUtil.parse(sqlToParse)),
            operatorPlaceholder
        );
    }

    private static net.sf.jsqlparser.statement.select.Select unwrapParenthesedSelect(net.sf.jsqlparser.statement.select.Select select) {
        while (select instanceof ParenthesedSelect parenthesedSelect) {
            select = parenthesedSelect.getSelect();
        }
        return select;
    }

    private String restoreParserPlaceholders(Object sql) {
        return sql == null ? null : restoreParserPlaceholders(sql.toString());
    }

    private String restoreParserPlaceholders(String sql) {
        if (parserOperatorPlaceholder == null || sql == null) {
            return sql;
        }
        return sql.replace(parserOperatorPlaceholder, UNSUPPORTED_DISTANCE_OPERATOR);
    }

    QueryAnalyzerImpl(DatabaseOperator database, net.sf.jsqlparser.statement.select.Select selectBody, QueryAnalyzerImpl parent) {
        this.database = database;
        this.virtualTable.putAll(parent.virtualTable);
        if (null != selectBody) {
            this.parsed = selectBody;
            if (CollectionUtils.isNotEmpty(selectBody.getWithItemsList())) {
                for (WithItem<?> withItem : selectBody.getWithItemsList()) {
                    acceptWithItem(withItem, this);
                }
            }
            acceptSelect(selectBody, this);
        } else {
            this.parsed = null;
        }
    }

    QueryAnalyzerImpl(DatabaseOperator database, net.sf.jsqlparser.statement.select.Select select) {
        this.parsed = select;
        this.database = database;
        //with ...
        if (CollectionUtils.isNotEmpty(select.getWithItemsList())) {
            for (WithItem<?> withItem : select.getWithItemsList()) {
                acceptWithItem(withItem, this);
            }
        }

        if (this.parsed != null) {
            acceptSelect(this.parsed, this);
        }
    }

    private String parsePlainName(String name) {
        if (name == null || name.isEmpty()) {
            return null;
        }
        char firstChar = name.charAt(0);

        if (firstChar == '`' || firstChar == '"' || firstChar == '[' ||
            name.startsWith(database.getMetadata().getDialect().getQuoteStart())) {

            return new String(name.toCharArray(), 1, name.length() - 2);
        }

        return name;
    }

    @Override
    public <S> Void visit(net.sf.jsqlparser.schema.Table tableName, S context) {
        String schema = parsePlainName(tableName.getSchemaName());

        String name = parsePlainName(tableName.getName());

        RDBSchemaMetadata schemaMetadata;
        if (schema != null) {
            schemaMetadata = database
                .getMetadata()
                .getSchema(schema)
                .orElseThrow(() -> new IllegalStateException("schema " + schema + " not initialized"));
        } else {
            schemaMetadata = database.getMetadata().getCurrentSchema();
            if (!virtualTable.containsKey(name)) {
                tableName.setSchemaName(schemaMetadata.getQuoteName());
            }
        }

        String alias = tableName.getAlias() == null ? tableName.getName() : tableName.getAlias().getName();

        TableOrViewMetadata tableMetadata = schemaMetadata
            .getTableOrView(name, false)
            .orElseGet(() -> virtualTable.get(name));

        if (tableMetadata == null) {
            throw new IllegalStateException("table or view " + tableName.getName() + " not found in " + schemaMetadata.getName());
        }
        tableName.setName(tableMetadata.getRealName());
        QueryAnalyzer.Table table = new QueryAnalyzer.Table(
            parsePlainName(alias),
            tableMetadata
        );

        select = new QueryAnalyzer.Select(new ArrayList<>(), table);
        return null;
    }

    // select * from ( select a,b,c from table ) t
    @Override
    public <S> Void visit(ParenthesedSelect select, S context) {
        visit(select, select.getAlias() == null ? null : select.getAlias().getName());
        return null;
    }

    @Override
    public void visit(ParenthesedSelect select) {
        visit(select, (Object) null);
    }

    private void visit(ParenthesedSelect subSelect, String alias) {
        net.sf.jsqlparser.statement.select.Select body = subSelect.getSelect();
        if (body instanceof Values values) {
            visitValues(values, subSelect.getAlias() == null ? values.getAlias() : subSelect.getAlias());
            return;
        }

        QueryAnalyzerImpl sub = new QueryAnalyzerImpl(database, body, this);
        Map<String, Column> columnMap = new LinkedHashMap<>();
        for (Column column : sub.select.getColumnList()) {
            // 判断子查询的列是否有显式的 SQL 别名（如 select name as n）
            // vs 隐式的 ORM 别名（如 select * 展开时，别名来自元数据）
            boolean hasExplicitSqlAlias = column.metadata != null
                && !Objects.equals(column.alias, column.metadata.getAlias());

            String exposedName;
            RDBColumnMetadata colMetadata = column.metadata;

            if (hasExplicitSqlAlias) {
                // 显式 SQL 别名：子查询暴露的列名是 SQL 别名（如 "n"）
                // 克隆 metadata，设置 name 为别名，清除 realName
                // 使得 getRealName() 返回别名，realNameDetected() 返回 false（需要大小写规范化）
                exposedName = column.alias;
                colMetadata = column.metadata.clone();
                colMetadata.setName(column.alias);
                colMetadata.setAlias(column.alias);
                colMetadata.setRealName(null);
            } else if (column.metadata == null) {
                // 表达式列或无元数据：使用别名作为暴露名
                exposedName = column.alias;
            } else {
                // 隐式 ORM 别名（如 select *）：子查询暴露的列名是原始列名
                exposedName = column.name;
            }

            columnMap.put(column.getAlias(),
                          new Column(exposedName, column.getAlias(), column.owner, colMetadata));
        }

        select = new QueryAnalyzer.Select(
            new ArrayList<>(),
            new QueryAnalyzer.SelectTable(
                parsePlainName(alias),
                columnMap,
                sub.select.table.metadata
            )
        );
    }

    @Override
    public <S> Void visit(LateralSubSelect lateralSubSelect, S context) {
        this.visit((ParenthesedSelect) lateralSubSelect,
                   lateralSubSelect.getAlias() == null ? null : lateralSubSelect.getAlias().getName());
        return null;
    }

    @Override
    public void visit(LateralSubSelect lateralSubSelect) {
        visit(lateralSubSelect, (Object) null);
    }

    private void visitValues(Values values, Alias alias) {
        if (alias == null) {
            throw new IllegalArgumentException("values[" + values + "] must have alias");
        }
        String name = parsePlainName(alias.getName());
        FakeTable view = new FakeTable();
        view.setSchema(database.getMetadata().getCurrentSchema());

        if (alias.getAliasColumns() != null) {
            for (Alias.AliasColumn aliasColumn : alias.getAliasColumns()) {
                RDBColumnMetadata ignore = view.getColumn(parsePlainName(aliasColumn.name)).orElse(null);
            }
        }

        view.setName(name);
        view.setRealName(name);
        view.setSchema(database.getMetadata().getCurrentSchema());
        view.setAlias(name);

        Table table = new Table(name, view);

        select = new QueryAnalyzer.Select(new ArrayList<>(), table);
    }

    @Override
    public <S> Void visit(TableFunction tableFunction, S context) {
        if (tableFunction.getAlias() == null) {
            throw new IllegalArgumentException("table function[" + tableFunction + "] must have alias");
        }
        String name = parsePlainName(tableFunction.getAlias().getName());

        FakeTable view = new FakeTable();

        view.setName(name);
        view.setSchema(database.getMetadata().getCurrentSchema());
        view.setAlias(name);

        Table table = new Table(name, view);

        select = new QueryAnalyzer.Select(new ArrayList<>(), table);
        return null;
    }

    @Override
    public <S> Void visit(ParenthesedFromItem aThis, S context) {
        if (aThis.getFromItem() instanceof Values values) {
            visitValues(values, aThis.getAlias());
        } else {
            acceptFromItem(aThis.getFromItem(), this);
        }
        if (CollectionUtils.isNotEmpty(aThis.getJoins())) {
            for (net.sf.jsqlparser.statement.select.Join join : aThis.getJoins()) {
                FromItem fromItem = join.getRightItem();
                QueryAnalyzerImpl joinAn = new QueryAnalyzerImpl(database, (net.sf.jsqlparser.statement.select.Select) null, this);
                acceptFromItem(fromItem, joinAn);

                Join.Type type;
                if (join.isLeft()) {
                    type = Join.Type.left;
                } else if (join.isRight()) {
                    type = Join.Type.right;
                } else if (join.isInner()) {
                    type = Join.Type.inner;
                } else {
                    type = null;
                }
                joins.put(joinAn.select.table.alias, new Join(joinAn.select.table.alias, type, joinAn.select.table));
            }
        }
        String alias = parsePlainName(aThis.getAlias() == null ? null : aThis.getAlias().getName());
        if (alias != null) {
            this.select = select.newSelectAlias(alias);
        }
        return null;
    }

    public void visit(AllColumns allColumns) {
        putSelectColumns(select.table, select.columnList);

        for (QueryAnalyzer.Join value : new HashSet<>(joins.values())) {
            putSelectColumns(value.table, select.columnList);
        }
    }

    private void putSelectColumns(QueryAnalyzer.Table table, List<QueryAnalyzer.Column> container) {

        if (table instanceof QueryAnalyzer.SelectTable) {
            QueryAnalyzer.SelectTable selectTable = ((QueryAnalyzer.SelectTable) table);

            for (QueryAnalyzer.Column column : selectTable.columns.values()) {
                String alias = table == select.table ? column.getAlias() : table.alias + "." + column.getAlias();
                container.add(new QueryAnalyzer.Column(
                    column.getName(),
                    alias,
                    table.alias,
                    column.metadata
                ));
            }
        } else {
            for (RDBColumnMetadata column : table.metadata.getColumns()) {
                String alias = table == select.table ? column.getAlias() : table.alias + "." + column.getAlias();

                container.add(new QueryAnalyzer.Column(
                    column.getRealName(),
                    alias,
                    table.alias,
                    column
                ));
            }
        }
    }

    public void visit(AllTableColumns allTableColumns) {
        net.sf.jsqlparser.schema.Table table = allTableColumns.getTable();

        String name = table.getName();

        if (Objects.equals(select.table.alias, name)) {
            putSelectColumns(select.table, select.columnList);
            return;
        }

        QueryAnalyzer.Join join = joins.get(parsePlainName(table.getName()));

        if (join == null) {
            throw new IllegalStateException("table " + table.getName() + " not found in join");
        }
        putSelectColumns(join.table, select.columnList);
    }

    private QueryAnalyzer.Table getTable(net.sf.jsqlparser.schema.Table table) {
        QueryAnalyzer.Table meta;
        if (null == table) {
            return select.table;
        }
        String tableName = parsePlainName(table.getName());

        if (Objects.equals(tableName, select.table.alias)) {
            meta = select.table;
        } else {
            QueryAnalyzer.Join join = joins.get(tableName);
            if (join == null) {
                throw new IllegalStateException("table " + table + " not found in from or join");
            }
            meta = join.table;
        }
        return meta;
    }


    static class ExpressionColumn extends Column {

        private final SelectItem<?> expr;

        public ExpressionColumn(String alias, String owner, RDBColumnMetadata metadata, SelectItem<?> expr) {
            super(alias, alias, owner, metadata);
            this.expr = expr;
        }

        @Override
        public ExpressionColumn moveOwner(String owner) {
            return new ExpressionColumn(alias, owner, metadata, expr);
        }
    }

    private void refactorAlias(Alias alias) {
        if (alias != null) {
            alias.setName(
                database
                    .getMetadata()
                    .getDialect()
                    .quote(parsePlainName(alias.getName()), false)
            );
        }
    }

    @Override
    public <S> Void visit(SelectItem<? extends Expression> selectExpressionItem, S context) {
        Expression expr = selectExpressionItem.getExpression();
        if (expr instanceof AllColumns allColumns) {
            visit(allColumns);
            return null;
        }
        if (expr instanceof AllTableColumns allTableColumns) {
            visit(allTableColumns);
            return null;
        }

        Alias alias = selectExpressionItem.getAlias();

        if (!(expr instanceof net.sf.jsqlparser.schema.Column column)) {
            String aliasName = parsePlainName(alias == null ? expr.toString() : alias.getName());
            refactorAlias(alias);
            select.columnList.add(new ExpressionColumn(aliasName, null, null, selectExpressionItem));

            return null;
        }

        String columnName = parsePlainName(column.getColumnName());

        QueryAnalyzer.Table table = getTable(column.getTable());

        String aliasName = alias == null ? columnName : parsePlainName(alias.getName());

        RDBColumnMetadata metadata = table
            .getMetadata()
            .getColumn(columnName)
            .orElse(null);

        if (metadata == null) {
            if (table instanceof QueryAnalyzer.SelectTable) {
                Column c = ((SelectTable) table).columns.get(columnName);
                if (null != c) {
                    if (c.metadata == null) {
                        select.columnList.add(new QueryAnalyzer.Column(c.getName(), aliasName, table.alias, null));
                        return null;
                    }
                    metadata = c.metadata;
                }
            }
        }

        if (metadata == null) {
            throw new IllegalStateException("column [" + column.getColumnName() + "] not found in " + table.metadata.getName());
        }

        select.columnList.add(new QueryAnalyzer.Column(metadata.getRealName(), aliasName, table.alias, metadata));
        return null;
    }

    @Override
    public <S> Void visit(PlainSelect select, S context) {

        FromItem from = select.getFromItem();

        if (from == null) {
            throw new IllegalArgumentException("select can not be without 'from'");
        }
        acceptFromItem(from, this);


        List<net.sf.jsqlparser.statement.select.Join> joinList = select.getJoins();

        if (joinList != null) {
            for (net.sf.jsqlparser.statement.select.Join join : joinList) {
                FromItem fromItem = join.getRightItem();
                QueryAnalyzerImpl joinAn = new QueryAnalyzerImpl(database, (net.sf.jsqlparser.statement.select.Select) null, this);
                acceptFromItem(fromItem, joinAn);

                Join.Type type;
                if (join.isLeft()) {
                    type = Join.Type.left;
                } else if (join.isRight()) {
                    type = Join.Type.right;
                } else if (join.isInner()) {
                    type = Join.Type.inner;
                } else {
                    type = null;
                }
                joins.put(joinAn.select.table.alias, new Join(joinAn.select.table.alias, type, joinAn.select.table));
            }
        }

        for (SelectItem<?> selectItem : select.getSelectItems()) {
            acceptSelectItem(selectItem, this);
        }
        return null;
    }

    @Override
    public void visit(PlainSelect select) {
        visit(select, (Object) null);
    }

    @Override
    public <S> Void visit(SetOperationList setOpList, S context) {
        //union

        for (net.sf.jsqlparser.statement.select.Select body : setOpList.getSelects()) {
            acceptSelect(body, this);
            // break;
        }
        return null;
    }

    @Override
    public void visit(SetOperationList setOpList) {
        visit(setOpList, (Object) null);
    }

    @Override
    public <S> Void visit(WithItem<?> withItem, S context) {
        withItems.add(withItem);

        String name = withItem.getAlias().getName();
        RDBViewMetadata view = new RDBViewMetadata();
        view.setName(name);
        view.setSchema(database.getMetadata().getCurrentSchema());
        virtualTable.put(name, view);
        if (withItem.getSelect() != null) {
            QueryAnalyzerImpl analyzer = new QueryAnalyzerImpl(database, unwrapParenthesedSelect(withItem.getSelect()), this);
            for (Column column : analyzer.select.getColumnList()) {
                RDBColumnMetadata metadata;
                if (column.getMetadata() == null) {
                    metadata = new RDBColumnMetadata();
                } else {
                    metadata = column.metadata.clone();
                }
                metadata.setName(column.getName());
                metadata.setAlias(column.getAlias());
                view.addColumn(metadata);
            }
        }
        return null;
    }

    @Override
    public <S> Void visit(Values values, S context) {
        visitValues(values, values.getAlias());
        return null;
    }

    @Override
    public void visit(Values values) {
        visit(values, (Object) null);
    }

    @Override
    public <S> Void visit(TableStatement tableStatement, S context) {
        return null;
    }

    @Override
    public void visit(TableStatement tableStatement) {
    }

    @Override
    public <S> Void visit(FromQuery fromQuery, S context) {
        throw new UnsupportedOperationException("Pipe query syntax is not supported");
    }

    private void initInjector() {
        SimpleQueryRefactor injector = new SimpleQueryRefactor();
        acceptSelect(parsed, injector);
        for (WithItem<?> withItem : withItems) {
            acceptWithItem(withItem, injector);
        }
        this.injector = injector;
    }

    static class QueryAnalyzerTermsFragmentBuilder extends AbstractTermsFragmentBuilder<QueryAnalyzerImpl> {

        @Override
        public SqlFragments createTermFragments(QueryAnalyzerImpl parameter, List<Term> terms) {
            return super.createTermFragments(parameter, terms);
        }

        @Override
        public SqlFragments createTermFragments(QueryAnalyzerImpl impl, Term term) {
            Dialect dialect = impl.database.getMetadata().getDialect();

            Table table = impl.select.table;
            String column = term.getColumn();

            Column col = impl.getColumnMappings().get(column);
//
//            if (col == null) {
//                if (column.contains(".")) {
//                    String[] split = column.split("\\.");
//                    if (split.length == 2) {
//                        QueryAnalyzer.Join join = impl.joins.get(split[0]);
//                        if (null != join) {
//                            table = join.table;
//                            column = split[1];
//                        } else {
//                            throw new IllegalArgumentException("undefined column [" + column + "]");
//                        }
//                    }
//                }
//                RDBColumnMetadata columnMetadata = table
//                    .getMetadata()
//                    .getColumn(column)
//                    .orElse(null);
//                if (columnMetadata != null) {
//                    col = new Column(column, column, table.alias, columnMetadata);
//                } else {
//                    throw new IllegalArgumentException("undefined column [" + column + "]");
//                }
//            }
            if (col == null) {
                throw new IllegalArgumentException("undefined column [" + column + "]");
            }

            if (!Objects.equals(impl.select.table.alias, col.getOwner())) {
                QueryAnalyzer.Join join = impl.joins.get(col.getOwner());
                if (null != join) {
                    table = join.table;
                } else {
                    throw new IllegalArgumentException("undefined column [" + column + "]");
                }
            }

            FeatureSupportedMetadata metadata = col.metadata;
            if (col.metadata == null) {
                metadata = table.metadata;
            }

            String colName = col.metadata != null ? col.metadata.getRealName() : col.name;

            String fullName = col.metadata != null
                ? col.getMetadata().getFullName(table.alias)
                : table.alias + "." + dialect.quote(colName, false);

            return metadata
                .findFeature(createFeatureId(term.getTermType()))
                .map(feature -> feature.createFragments(
                    fullName, col.metadata, term))
                .orElse(EmptySqlFragments.INSTANCE);
        }
    }

    static QueryAnalyzerTermsFragmentBuilder TERMS_BUILDER = new QueryAnalyzerTermsFragmentBuilder();

    class SimpleQueryRefactor implements QueryRefactor, SelectVisitor<Void> {
        private String prefix = "";
        private String from;

        private String columns;

        private String where;
        private int prefixParameters;
        private String orderBy;

        private String suffix;
        private int suffixParameters;

        private boolean fastCount = true;

        private SqlFragments QUERY, SUFFIX, FAST_COUNT, SLOW_COUNT;

        SimpleQueryRefactor() {

        }


        private void initColumns(StringBuilder columns) {
            int idx = 0;
            Dialect dialect = database.getMetadata().getDialect();

            if (select.columnList.size() == 1 && "*".equals(select.columnList.get(0).name)) {
                columns.append(select.columnList.get(0).owner).append('.').append('*');
                return;
            }

            for (Column column : select.columnList) {
                if ("*".equals(column.name)) {
                    continue;
                }

                if (idx++ > 0) {
                    columns.append(",");
                }
                if (column instanceof ExpressionColumn) {
                    columns.append(restoreParserPlaceholders(((ExpressionColumn) column).expr));
                    fastCount = false;
                    continue;
                }

                columns.append(column.owner)
                       .append('.')
                       .append(dialect.quote(column.name, column.metadata != null && !column.metadata.realNameDetected()))
                       .append(" as ")
                       .append(dialect.quote(column.alias, false));
            }
        }

        @Override
        public <S> Void visit(PlainSelect plainSelect, S context) {

            StringBuilder from = new StringBuilder();
            StringBuilder columns = new StringBuilder();
            StringBuilder suffix = new StringBuilder();


            if (plainSelect.getDistinct() != null) {
                columns.append(plainSelect.getDistinct())
                       .append(' ');
                fastCount = false;
            }

            initColumns(columns);

            if (plainSelect.getSelectItems() != null) {
                PrepareStatementVisitor visitor = new PrepareStatementVisitor();
                for (SelectItem<?> selectItem : plainSelect.getSelectItems()) {
                    acceptSelectItem(selectItem, visitor);
                }
                prefixParameters += visitor.parameterSize;
            }

            if (plainSelect.getFromItem() != null) {
                from.append("FROM ");

                from.append(restoreParserPlaceholders(plainSelect.getFromItem()));
                PrepareStatementVisitor visitor = new PrepareStatementVisitor();
                acceptFromItem(plainSelect.getFromItem(), visitor);
                prefixParameters += visitor.parameterSize;
            }

            if (plainSelect.getJoins() != null) {
                PrepareStatementVisitor visitor = new PrepareStatementVisitor();
                for (net.sf.jsqlparser.statement.select.Join join : plainSelect.getJoins()) {
                    if (join.isSimple()) {
                        from.append(", ").append(restoreParserPlaceholders(join));
                    } else {
                        from.append(" ").append(restoreParserPlaceholders(join));
                    }
                    if (null != join.getRightItem()) {
                        acceptFromItem(join.getRightItem(), visitor);
                    }
                    if (null != join.getOnExpressions()) {
                        for (Expression onExpression : join.getOnExpressions()) {
                            onExpression.accept(visitor);
                        }
                    }
                }
                prefixParameters += visitor.parameterSize;
            }

            if (plainSelect.getWhere() != null) {
                PrepareStatementVisitor visitor = new PrepareStatementVisitor();
                plainSelect.getWhere().accept(visitor);
                prefixParameters += visitor.parameterSize;
                where = restoreParserPlaceholders(plainSelect.getWhere());
            }

            if (plainSelect.getOrderByElements() != null) {
                PrepareStatementVisitor visitor = new PrepareStatementVisitor();
                for (OrderByElement orderByElement : plainSelect.getOrderByElements()) {
                    orderByElement.getExpression().accept(visitor);
                }
                suffixParameters = visitor.parameterSize;
                orderBy = restoreParserPlaceholders(getFormattedList(plainSelect.getOrderByElements(), ""));
            }

            if (plainSelect.getGroupBy() != null) {
                fastCount = false;
                suffix.append(' ').append(restoreParserPlaceholders(plainSelect.getGroupBy()));

                PrepareStatementVisitor visitor = new PrepareStatementVisitor();
                plainSelect.getGroupBy().getGroupByExpressionList().accept(visitor);
                suffixParameters = visitor.parameterSize;
            }
            suffix.append(' ');

            if (plainSelect.getHaving() != null) {
                PrepareStatementVisitor visitor = new PrepareStatementVisitor();
                plainSelect.getHaving().accept(visitor);
                suffixParameters = visitor.parameterSize;
                suffix.append(" HAVING ").append(restoreParserPlaceholders(plainSelect.getHaving()));
            }

            this.columns = columns.toString();
            this.from = from.toString();
            this.suffix = suffix.toString();
            return null;
        }

        @Override
        public <S> Void visit(SetOperationList setOpList, S context) {
            StringBuilder from = new StringBuilder();
            StringBuilder columns = new StringBuilder();

            initColumns(columns);

            from.append("FROM (");
            from.append(restoreParserPlaceholders(setOpList));
            from.append(") ");
            from.append(select.table.alias);

            this.from = from.toString();
            this.columns = columns.toString();
            this.suffix = "";
            return null;
        }

        @Override
        public <S> Void visit(ParenthesedSelect select, S context) {
            if (select.getSelect() != null) {
                acceptSelect(select.getSelect(), this);
            }
            return null;
        }

        @Override
        public <S> Void visit(WithItem<?> withItem, S context) {
            if (!StringUtils.hasText(prefix)) {
                prefix += "WITH ";
            }
            prefix += restoreParserPlaceholders(withItem);
            PrepareStatementVisitor visitor = new PrepareStatementVisitor();
            acceptWithItem(withItem, visitor);
            prefixParameters += visitor.parameterSize;
            return null;
        }

        @Override
        public <S> Void visit(Values values, S context) {
            PrepareStatementVisitor visitor = new PrepareStatementVisitor();
            values.getExpressions().accept(visitor);
            return null;
        }

        @Override
        public <S> Void visit(LateralSubSelect lateralSubSelect, S context) {
            return visit((ParenthesedSelect) lateralSubSelect, context);
        }

        @Override
        public <S> Void visit(TableStatement tableStatement, S context) {
            return null;
        }

        @Override
        public <S> Void visit(FromQuery fromQuery, S context) {
            throw new UnsupportedOperationException("Pipe query syntax is not supported");
        }

        public Object[] getPrefixParameters(Object... args) {
            if (prefixParameters == 0) {
                return new Object[0];
            }
            Assert.isTrue(args.length >= prefixParameters,
                          "Illegal prepare statement parameter size, expect: " + prefixParameters + ", actual: " + args.length);

            return Arrays.copyOfRange(args, 0, prefixParameters);
        }

        public Object[] getSuffixParameters(Object... args) {
            if (suffixParameters == 0) {
                return new Object[0];
            }
            Assert.isTrue(args.length >= suffixParameters + prefixParameters,
                          "Illegal prepare statement parameter size, expect: " + suffixParameters + prefixParameters + ", actual: " + args.length);

            return Arrays.copyOfRange(args, prefixParameters, suffixParameters + prefixParameters);
        }

        @Override
        public SqlRequest refactor(QueryParamEntity param, Object... args) {
            if (QUERY == null) {
                QUERY = SqlFragments.of(prefix, "SELECT", columns, from);
            }
            BatchSqlFragments sql = new BatchSqlFragments(
                StringUtils.hasText(where) ? 10 : 6, 2);
            sql.add(QUERY)
               .addParameter(getPrefixParameters(args));

            appendWhere(sql, param);

            sql.addSql(suffix)
               .addParameter(getSuffixParameters(args));

            appendOrderBy(sql, param);

            return sql.toRequest();
        }


        @Override
        public SqlRequest refactorCount(QueryParamEntity param, Object... args) {
            BatchSqlFragments sql = new BatchSqlFragments(
                StringUtils.hasText(where) ? 10 : 7, 2);
            if (SUFFIX == null) {
                SUFFIX = SqlFragments.of(suffix);
            }

            if (fastCount) {
                if (FAST_COUNT == null) {
                    FAST_COUNT = SqlFragments.of(
                        prefix, "SELECT count(1) as",
                        database.getMetadata().getDialect().quote("_total"),
                        from);
                }
                //SELECT count(1) as _total from
                sql.add(FAST_COUNT);
                sql.addParameter(getPrefixParameters(args));

                appendWhere(sql, param);

                sql.add(SUFFIX);
            } else {
                if (SLOW_COUNT == null) {
                    SLOW_COUNT = SqlFragments
                        .of(prefix,
                            "SELECT count(1) as",
                            database.getMetadata().getDialect().quote("_total"),
                            "from (SELECT", columns, from);
                }

                sql.add(SLOW_COUNT);
                sql.addParameter(getPrefixParameters(args));

                appendWhere(sql, param);

                sql.add(SUFFIX);
                sql.addSql(") _t");
            }

            return sql
                .addParameter(getSuffixParameters(args))
                .toRequest();
        }

        private void appendOrderBy(AppendableSqlFragments sql, QueryParamEntity param) {

            if (CollectionUtils.isNotEmpty(param.getSorts())) {
                int index = 0;
                BatchSqlFragments orderByValue = null;
                BatchSqlFragments orderByColumn = null;
                for (Sort sort : param.getSorts()) {
                    String name = sort.getName();
                    Column column = getColumnOrSelectColumn(name);

                    if (column == null) {
                        continue;
                    }
                    boolean desc = "desc".equalsIgnoreCase(sort.getOrder());
                    String columnName = column.getOwner() == null ?
                        database.getMetadata().getDialect().quote(column.getName(), false)
                        : org.hswebframework.ezorm.core.utils.StringUtils
                        .concat(column.getOwner(),
                                ".",
                                database.getMetadata().getDialect().quote(column.getName()));
                    //按固定值排序
                    if (sort.getValue() != null) {
                        if (orderByValue == null) {
                            orderByValue = new BatchSqlFragments();
                            orderByValue.addSql("case");
                        }
                        orderByValue.addSql("when");
                        orderByValue.addSql(columnName, "= ?").addParameter(sort.getValue());
                        orderByValue.addSql("then").addSql(String.valueOf(desc ? 10000 + index++ : index++));
                    } else {
                        if (orderByColumn == null) {
                            orderByColumn = new BatchSqlFragments();
                        } else {
                            orderByColumn.addSql(",");
                        }
                        //todo function支持
                        orderByColumn
                            .addSql(columnName)
                            .addSql(desc ? "DESC" : "ASC");
                    }
                }

                boolean customOrder = (orderByValue != null || orderByColumn != null);

                if (customOrder || orderBy != null) {
                    sql.addSql("ORDER BY");
                }
                //按固定值
                if (orderByValue != null) {
                    orderByValue.addSql("else 10000 end");
                    sql.addFragments(orderByValue);
                }
                //按列
                if (orderByColumn != null) {
                    if (orderByValue != null) {
                        sql.add(SqlFragments.COMMA);
                    }
                    sql.addFragments(orderByColumn);
                }
                if (orderBy != null) {
                    if (customOrder) {
                        sql.add(SqlFragments.COMMA);
                    }
                    sql.addSql(orderBy);
                }
            } else {
                if (orderBy != null) {
                    sql.addSql("ORDER BY", orderBy);
                }
            }

        }

        private void appendWhere(AppendableSqlFragments sql, QueryParamEntity param) {
            SqlFragments fragments = TERMS_BUILDER.createTermFragments(QueryAnalyzerImpl.this, param.getTerms());

            if (fragments.isNotEmpty() || StringUtils.hasText(where)) {
                sql.add(SqlFragments.WHERE);
            }

            if (StringUtils.hasText(where)) {
                sql.add(SqlFragments.LEFT_BRACKET);
                sql.addSql(where);
                sql.add(SqlFragments.RIGHT_BRACKET);
            }

            if (fragments.isNotEmpty()) {
                if (StringUtils.hasText(where)) {
                    sql.add(SqlFragments.AND);
                }
                sql.add(SqlFragments.LEFT_BRACKET);
                sql.addFragments(fragments);
                sql.add(SqlFragments.RIGHT_BRACKET);
            }
        }

    }


    @Getter
    static class PrepareStatementVisitor extends ExpressionVisitorAdapter<Void> implements FromItemVisitor<Void>, SelectVisitor<Void> {
        private int parameterSize;

        public PrepareStatementVisitor() {
            setSelectVisitor(this);
        }

        @Override
        public <S> Void visit(JdbcParameter parameter, S context) {
            parameterSize++;
            return super.visit(parameter, context);
        }

        @Override
        public <S> Void visit(net.sf.jsqlparser.schema.Table tableName, S context) {
            return null;
        }

        @Override
        public <S> Void visit(ParenthesedFromItem fromItem, S context) {
            if (fromItem.getFromItem() != null) {
                acceptFromItem(fromItem.getFromItem(), this);
            }
            if (CollectionUtils.isNotEmpty(fromItem.getJoins())) {
                for (net.sf.jsqlparser.statement.select.Join join : fromItem.getJoins()) {
                    if (join.getRightItem() != null) {
                        acceptFromItem(join.getRightItem(), this);
                    }
                    if (join.getOnExpressions() != null) {
                        join.getOnExpressions().forEach(expr -> expr.accept(this));
                    }
                }
            }
            return null;
        }

        @Override
        public <S> Void visit(LateralSubSelect lateralSubSelect, S context) {
            if (lateralSubSelect.getSelect() != null) {
                acceptSelect(lateralSubSelect.getSelect(), this);
            }
            return null;
        }

        @Override
        public void visit(LateralSubSelect lateralSubSelect) {
            visit(lateralSubSelect, (Object) null);
        }

        @Override
        public <S> Void visit(Values values, S context) {
            if (values.getExpressions() != null) {
                values.getExpressions().accept(this);
            }
            return null;
        }

        @Override
        public void visit(Values values) {
            visit(values, (Object) null);
        }

        @Override
        public <S> Void visit(TableFunction tableFunction, S context) {
            tableFunction.getFunction().accept(this);
            return null;
        }

        @Override
        public <S> Void visit(PlainSelect plainSelect, S context) {
            acceptFromItem(plainSelect.getFromItem(), this);
            if (plainSelect.getJoins() != null) {
                for (net.sf.jsqlparser.statement.select.Join join : plainSelect.getJoins()) {
                    acceptFromItem(join.getRightItem(), this);
                    if (join.getOnExpressions() != null) {
                        join.getOnExpressions().forEach(expr -> expr.accept(this));
                    }
                }
            }
            if (plainSelect.getSelectItems() != null) {
                for (SelectItem<?> selectItem : plainSelect.getSelectItems()) {
                    acceptSelectItem(selectItem, this);
                }
            }
            if (plainSelect.getWhere() != null) {
                plainSelect.getWhere().accept(this);
            }
            if (plainSelect.getHaving() != null) {
                plainSelect.getHaving().accept(this);
            }

            if (plainSelect.getDistinct() != null && plainSelect.getDistinct().getOnSelectItems() != null) {
                plainSelect.getDistinct().getOnSelectItems().forEach(item -> item.getExpression().accept(this));
            }

            if (plainSelect.getOrderByElements() != null) {
                for (OrderByElement orderByElement : plainSelect.getOrderByElements()) {
                    orderByElement.getExpression().accept(this);
                }
            }

            if (plainSelect.getGroupBy() != null) {
                for (Object expression : plainSelect.getGroupBy().getGroupByExpressionList()) {
                    ((Expression) expression).accept(this);
                }
            }
            return null;
        }

        @Override
        public void visit(PlainSelect plainSelect) {
            visit(plainSelect, (Object) null);
        }

        @Override
        public <S> Void visit(SetOperationList setOpList, S context) {
            if (CollectionUtils.isNotEmpty(setOpList.getSelects())) {
                for (net.sf.jsqlparser.statement.select.Select select : setOpList.getSelects()) {
                    acceptSelect(select, this);
                }
            }
            if (setOpList.getOffset() != null) {
                setOpList.getOffset().getOffset().accept(this);
            }
            if (setOpList.getLimit() != null) {
                if (setOpList.getLimit().getRowCount() != null) {
                    setOpList.getLimit().getRowCount().accept(this);
                }
                if (setOpList.getLimit().getOffset() != null) {
                    setOpList.getLimit().getOffset().accept(this);
                }
            }
            return null;
        }

        @Override
        public void visit(SetOperationList setOpList) {
            visit(setOpList, (Object) null);
        }

        @Override
        public <S> Void visit(WithItem<?> withItem, S context) {
            if (CollectionUtils.isNotEmpty(withItem.getWithItemList())) {
                for (SelectItem<?> selectItem : withItem.getWithItemList()) {
                    acceptSelectItem(selectItem, this);
                }
            }
            if (withItem.getSelect() != null) {
                acceptSelect(withItem.getSelect(), this);
            }
            return null;
        }

        @Override
        public <S> Void visit(ParenthesedSelect select, S context) {
            if (select.getSelect() != null) {
                acceptSelect(select.getSelect(), this);
            }
            return null;
        }

        @Override
        public void visit(ParenthesedSelect select) {
            visit(select, (Object) null);
        }

        @Override
        public <S> Void visit(TableStatement tableStatement, S context) {
            return null;
        }

        @Override
        public void visit(TableStatement tableStatement) {
        }

        @Override
        public <S> Void visit(FromQuery fromQuery, S context) {
            if (fromQuery.getFromItem() != null) {
                acceptFromItem(fromQuery.getFromItem(), this);
            }
            if (CollectionUtils.isNotEmpty(fromQuery.getJoins())) {
                for (net.sf.jsqlparser.statement.select.Join join : fromQuery.getJoins()) {
                    if (join.getRightItem() != null) {
                        acceptFromItem(join.getRightItem(), this);
                    }
                    if (join.getOnExpressions() != null) {
                        join.getOnExpressions().forEach(expr -> expr.accept(this));
                    }
                }
            }
            return null;
        }

        @Override
        public <S> Void visit(SelectItem<? extends Expression> selectItem, S context) {
            if (selectItem.getExpression() != null) {
                selectItem.getExpression().accept(this);
            }
            return null;
        }
    }

    static class FakeTable extends RDBViewMetadata {
        @Override
        public Optional<RDBColumnMetadata> getColumn(String name) {
            //sql中声明的列都可以使用

            QueryHelperUtils.assertLegalColumn(name);

            RDBColumnMetadata fake = newColumn();
            fake.setOwner(this);
            fake.setName(name);
            addColumn(fake);
            return Optional.of(fake);
        }
    }

    private static class ParsedSelect {
        final net.sf.jsqlparser.statement.select.Select select;
        final String operatorPlaceholder;

        private ParsedSelect(net.sf.jsqlparser.statement.select.Select select, String operatorPlaceholder) {
            this.select = select;
            this.operatorPlaceholder = operatorPlaceholder;
        }
    }

    private interface QueryRefactor {

        SqlRequest refactor(QueryParamEntity param, Object... args);

        SqlRequest refactorCount(QueryParamEntity param, Object... args);
    }

}
