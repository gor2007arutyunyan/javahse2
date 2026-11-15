package minidb.sql;

import java.util.List;

import minidb.model.Column;

/**
 * Набор классов-команд, которые создаёт {@link Parser}. Классы содержат только данные запроса.
 */
public final class Statements {
    private Statements() {
    }

    /** CREATE TABLE. */
    public static final class CreateTable extends Statement {
        /** Имя таблицы. */
        public final String name;
        /** Колонки с ограничениями. */
        public final List<Column> columns;

        /**
         * @param name    имя таблицы
         * @param columns колонки
         */
        public CreateTable(String name, List<Column> columns) {
            this.name = name;
            this.columns = columns;
        }

        @Override
        public Kind kind() {
            return Kind.DDL;
        }

        @Override
        public <R> R accept(StatementVisitor<R> v) {
            return v.visitCreateTable(this);
        }
    }

    /** DROP TABLE. */
    public static final class DropTable extends Statement {
        /** Имя таблицы. */
        public final String name;

        /** @param name имя таблицы */
        public DropTable(String name) {
            this.name = name;
        }

        @Override
        public Kind kind() {
            return Kind.DDL;
        }

        @Override
        public <R> R accept(StatementVisitor<R> v) {
            return v.visitDropTable(this);
        }
    }

    /** CREATE INDEX. */
    public static final class CreateIndex extends Statement {
        /** Имя индекса. */
        public final String name;
        /** Имя таблицы. */
        public final String table;
        /** Имя колонки. */
        public final String column;

        /**
         * @param name   имя индекса
         * @param table  имя таблицы
         * @param column имя колонки
         */
        public CreateIndex(String name, String table, String column) {
            this.name = name;
            this.table = table;
            this.column = column;
        }

        @Override
        public Kind kind() {
            return Kind.DDL;
        }

        @Override
        public <R> R accept(StatementVisitor<R> v) {
            return v.visitCreateIndex(this);
        }
    }

    /** DROP INDEX. */
    public static final class DropIndex extends Statement {
        /** Имя индекса. */
        public final String name;

        /** @param name имя индекса */
        public DropIndex(String name) {
            this.name = name;
        }

        @Override
        public Kind kind() {
            return Kind.DDL;
        }

        @Override
        public <R> R accept(StatementVisitor<R> v) {
            return v.visitDropIndex(this);
        }
    }

    /** INSERT INTO ... VALUES (...), (...). */
    public static final class Insert extends Statement {
        /** Имя таблицы. */
        public final String table;
        /** Список колонок или null, если значения идут для всех колонок по порядку. */
        public final List<String> columns;
        /** Вставляемые строки (литералы). */
        public final List<List<Object>> rows;

        /**
         * @param table   имя таблицы
         * @param columns колонки или null
         * @param rows    строки значений
         */
        public Insert(String table, List<String> columns, List<List<Object>> rows) {
            this.table = table;
            this.columns = columns;
            this.rows = rows;
        }

        @Override
        public Kind kind() {
            return Kind.DML;
        }

        @Override
        public <R> R accept(StatementVisitor<R> v) {
            return v.visitInsert(this);
        }
    }

    /** Ключ сортировки ORDER BY. */
    public static final class OrderKey {
        /** Имя колонки. */
        public final String column;
        /** Сортировка по убыванию. */
        public final boolean descending;

        /**
         * @param column     имя колонки
         * @param descending по убыванию
         */
        public OrderKey(String column, boolean descending) {
            this.column = column;
            this.descending = descending;
        }
    }

    /** SELECT. */
    public static final class Select extends Statement {
        /** Имя таблицы. */
        public final String table;
        /** Выбираемые колонки или null для {@code *}. */
        public final List<String> columns;
        /** true для {@code SELECT COUNT(*)}. */
        public final boolean countAll;
        /** Условие или null. */
        public final Condition where;
        /** Ключи сортировки (может быть пустым). */
        public final List<OrderKey> orderBy;
        /** Ограничение числа строк или null. */
        public final Integer limit;

        /**
         * @param table    имя таблицы
         * @param columns  колонки или null
         * @param countAll COUNT(*)
         * @param where    условие или null
         * @param orderBy  ключи сортировки
         * @param limit    LIMIT или null
         */
        public Select(String table, List<String> columns, boolean countAll, Condition where,
                      List<OrderKey> orderBy, Integer limit) {
            this.table = table;
            this.columns = columns;
            this.countAll = countAll;
            this.where = where;
            this.orderBy = orderBy;
            this.limit = limit;
        }

        @Override
        public Kind kind() {
            return Kind.QUERY;
        }

        @Override
        public <R> R accept(StatementVisitor<R> v) {
            return v.visitSelect(this);
        }
    }

    /** UPDATE ... SET ... WHERE. */
    public static final class Update extends Statement {
        /** Имя таблицы. */
        public final String table;
        /** Изменяемые колонки. */
        public final List<String> setColumns;
        /** Новые значения (литералы). */
        public final List<Object> setValues;
        /** Условие или null. */
        public final Condition where;

        /**
         * @param table      имя таблицы
         * @param setColumns изменяемые колонки
         * @param setValues  новые значения
         * @param where      условие или null
         */
        public Update(String table, List<String> setColumns, List<Object> setValues, Condition where) {
            this.table = table;
            this.setColumns = setColumns;
            this.setValues = setValues;
            this.where = where;
        }

        @Override
        public Kind kind() {
            return Kind.DML;
        }

        @Override
        public <R> R accept(StatementVisitor<R> v) {
            return v.visitUpdate(this);
        }
    }

    /** DELETE FROM ... WHERE. */
    public static final class Delete extends Statement {
        /** Имя таблицы. */
        public final String table;
        /** Условие или null. */
        public final Condition where;

        /**
         * @param table имя таблицы
         * @param where условие или null
         */
        public Delete(String table, Condition where) {
            this.table = table;
            this.where = where;
        }

        @Override
        public Kind kind() {
            return Kind.DML;
        }

        @Override
        public <R> R accept(StatementVisitor<R> v) {
            return v.visitDelete(this);
        }
    }

    /** SHOW TABLES. */
    public static final class ShowTables extends Statement {
        @Override
        public Kind kind() {
            return Kind.QUERY;
        }

        @Override
        public <R> R accept(StatementVisitor<R> v) {
            return v.visitShowTables(this);
        }
    }

    /** SHOW INDEXES. */
    public static final class ShowIndexes extends Statement {
        @Override
        public Kind kind() {
            return Kind.QUERY;
        }

        @Override
        public <R> R accept(StatementVisitor<R> v) {
            return v.visitShowIndexes(this);
        }
    }

    /** DESCRIBE таблица. */
    public static final class Describe extends Statement {
        /** Имя таблицы. */
        public final String table;

        /** @param table имя таблицы */
        public Describe(String table) {
            this.table = table;
        }

        @Override
        public Kind kind() {
            return Kind.QUERY;
        }

        @Override
        public <R> R accept(StatementVisitor<R> v) {
            return v.visitDescribe(this);
        }
    }
}
