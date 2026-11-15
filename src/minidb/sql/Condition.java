package minidb.sql;

import java.util.List;

import minidb.DbException;
import minidb.model.ColumnType;
import minidb.model.Row;
import minidb.model.TableSchema;
import minidb.model.Values;

/**
 * Условие WHERE в виде дерева выражений (паттерн Interpreter).
 * Перед использованием условие нужно привязать к схеме таблицы методом {@link #bind(TableSchema)}:
 * при этом определяются номера колонок и литералы приводятся к типам колонок.
 */
public abstract class Condition {

    /**
     * Привязывает условие к таблице.
     *
     * @param schema схема таблицы
     * @throws DbException если колонка неизвестна или значение не подходит по типу
     */
    public abstract void bind(TableSchema schema);

    /**
     * Вычисляет условие по трёхзначной логике SQL.
     *
     * @param row строка таблицы
     * @return TRUE, FALSE или null («неизвестно», если в сравнении участвует NULL)
     */
    protected abstract Boolean evaluate(Row row);

    /**
     * @param row строка таблицы
     * @return true, только если условие истинно (FALSE и «неизвестно» строку не пропускают)
     */
    public final boolean matches(Row row) {
        return Boolean.TRUE.equals(evaluate(row));
    }

    /**
     * Собирает простые сравнения, которые должны выполняться одновременно (связанные через AND).
     * Используется оптимизатором для выбора индекса.
     *
     * @param out куда добавлять найденные сравнения
     */
    public void collectConjuncts(List<Comparison> out) {
        // по умолчанию (OR, NOT, IS NULL) индексом не пользуемся
    }

    /** Сравнение колонки с константой: {@code колонка оператор значение}. */
    public static final class Comparison extends Condition {
        private final String column;
        private final CompareOp op;
        private Object value;
        private int columnIndex = -1;
        private ColumnType type;

        /**
         * @param column имя колонки
         * @param op     оператор
         * @param value  константа (Long, Double, String, Boolean или null)
         */
        public Comparison(String column, CompareOp op, Object value) {
            this.column = column;
            this.op = op;
            this.value = value;
        }

        /** @return имя колонки */
        public String column() {
            return column;
        }

        /** @return оператор */
        public CompareOp op() {
            return op;
        }

        /** @return константа после привязки (приведена к типу колонки) */
        public Object value() {
            return value;
        }

        /** @return номер колонки (после привязки) */
        public int columnIndex() {
            return columnIndex;
        }

        @Override
        public void bind(TableSchema schema) {
            columnIndex = schema.requireIndex(column);
            type = schema.column(columnIndex).type();
            try {
                value = type.coerceForComparison(value);
            } catch (DbException e) {
                throw new DbException("Условие по колонке '" + column + "': " + e.getMessage());
            }
        }

        /**
         * Константа в точном типе колонки (для поиска по индексу).
         *
         * @return значение нужного типа или null, если точное приведение невозможно (например, 2.5 для INT)
         */
        public Object exactKey() {
            if (value == null) {
                return null;
            }
            try {
                return type.coerce(value);
            } catch (DbException e) {
                return null;
            }
        }

        @Override
        protected Boolean evaluate(Row row) {
            Object v = row.get(columnIndex);
            if (v == null || value == null) {
                return null;
            }
            return op.test(Values.compare(v, value));
        }

        @Override
        public void collectConjuncts(List<Comparison> out) {
            out.add(this);
        }
    }

    /** Проверка {@code колонка IS [NOT] NULL}. */
    public static final class IsNull extends Condition {
        private final String column;
        private final boolean negated;
        private int columnIndex = -1;

        /**
         * @param column  имя колонки
         * @param negated true для IS NOT NULL
         */
        public IsNull(String column, boolean negated) {
            this.column = column;
            this.negated = negated;
        }

        @Override
        public void bind(TableSchema schema) {
            columnIndex = schema.requireIndex(column);
        }

        @Override
        protected Boolean evaluate(Row row) {
            return (row.get(columnIndex) == null) != negated;
        }
    }

    /** Логическое И. */
    public static final class And extends Condition {
        private final Condition left;
        private final Condition right;

        /**
         * @param left  левый операнд
         * @param right правый операнд
         */
        public And(Condition left, Condition right) {
            this.left = left;
            this.right = right;
        }

        @Override
        public void bind(TableSchema schema) {
            left.bind(schema);
            right.bind(schema);
        }

        @Override
        protected Boolean evaluate(Row row) {
            Boolean l = left.evaluate(row);
            if (Boolean.FALSE.equals(l)) {
                return false;
            }
            Boolean r = right.evaluate(row);
            if (Boolean.FALSE.equals(r)) {
                return false;
            }
            return (l == null || r == null) ? null : Boolean.TRUE;
        }

        @Override
        public void collectConjuncts(List<Comparison> out) {
            left.collectConjuncts(out);
            right.collectConjuncts(out);
        }
    }

    /** Логическое ИЛИ. */
    public static final class Or extends Condition {
        private final Condition left;
        private final Condition right;

        /**
         * @param left  левый операнд
         * @param right правый операнд
         */
        public Or(Condition left, Condition right) {
            this.left = left;
            this.right = right;
        }

        @Override
        public void bind(TableSchema schema) {
            left.bind(schema);
            right.bind(schema);
        }

        @Override
        protected Boolean evaluate(Row row) {
            Boolean l = left.evaluate(row);
            if (Boolean.TRUE.equals(l)) {
                return true;
            }
            Boolean r = right.evaluate(row);
            if (Boolean.TRUE.equals(r)) {
                return true;
            }
            return (l == null || r == null) ? null : Boolean.FALSE;
        }
    }

    /** Логическое НЕ. */
    public static final class Not extends Condition {
        private final Condition inner;

        /** @param inner отрицаемое условие */
        public Not(Condition inner) {
            this.inner = inner;
        }

        @Override
        public void bind(TableSchema schema) {
            inner.bind(schema);
        }

        @Override
        protected Boolean evaluate(Row row) {
            Boolean v = inner.evaluate(row);
            return v == null ? null : !v;
        }
    }
}
