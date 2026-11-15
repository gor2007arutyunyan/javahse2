package minidb.engine;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import minidb.DbException;
import minidb.index.Index;
import minidb.model.Column;
import minidb.model.ForeignKey;
import minidb.model.Row;
import minidb.model.TableSchema;
import minidb.model.Values;
import minidb.sql.CompareOp;
import minidb.sql.Condition;
import minidb.sql.StatementVisitor;
import minidb.sql.Statements;

/**
 * Исполнитель команд (посетитель {@link StatementVisitor}). Отвечает за проверку ограничений
 * (уникальность первичного ключа, NOT NULL, внешние ключи), выбор плана выполнения
 * (полный перебор или поиск по индексу) и изменение таблиц.
 * <p>
 * Все проверки выполняются ДО изменения данных, поэтому команда, завершившаяся ошибкой,
 * не оставляет частично применённых изменений.
 */
final class Executor implements StatementVisitor<QueryResult> {
    private final Database db;

    Executor(Database db) {
        this.db = db;
    }

    // ---------- DDL ----------

    @Override
    public QueryResult visitCreateTable(Statements.CreateTable s) {
        if (db.findTable(s.name) != null) {
            throw new DbException("Таблица '" + s.name + "' уже существует");
        }
        TableSchema schema = new TableSchema(s.name, s.columns);
        for (Column c : schema.columns()) {
            ForeignKey fk = c.foreignKey();
            if (fk == null) {
                continue;
            }
            TableSchema ref = fk.refTable().equalsIgnoreCase(schema.name()) ? schema : db.requireTable(fk.refTable()).schema();
            int ri = ref.indexOf(fk.refColumn());
            if (ri < 0) {
                throw new DbException("Внешний ключ: в таблице '" + ref.name() + "' нет колонки '" + fk.refColumn() + "'");
            }
            if (ri != ref.primaryKeyIndex()) {
                throw new DbException("Внешний ключ должен ссылаться на первичный ключ таблицы '" + ref.name() + "'");
            }
            if (ref.column(ri).type() != c.type()) {
                throw new DbException("Тип колонки '" + c.name() + "' (" + c.type() + ") не совпадает с типом ключа "
                        + ref.name() + "." + ref.column(ri).name() + " (" + ref.column(ri).type() + ")");
            }
        }
        db.addTable(Table.create(schema));
        return QueryResult.message("Таблица '" + s.name + "' создана");
    }

    @Override
    public QueryResult visitDropTable(Statements.DropTable s) {
        Table t = db.requireTable(s.name);
        for (Table other : db.tables()) {
            if (other == t) {
                continue;
            }
            for (Column c : other.schema().columns()) {
                ForeignKey fk = c.foreignKey();
                if (fk != null && fk.refTable().equalsIgnoreCase(t.name())) {
                    throw new DbException("Нельзя удалить таблицу '" + t.name() + "': на неё ссылается внешний ключ "
                            + other.name() + "." + c.name());
                }
            }
        }
        db.removeTable(t.name());
        return QueryResult.message("Таблица '" + t.name() + "' удалена");
    }

    @Override
    public QueryResult visitCreateIndex(Statements.CreateIndex s) {
        Table t = db.requireTable(s.table);
        if (db.findIndex(s.name) != null) {
            throw new DbException("Индекс с именем '" + s.name + "' уже существует");
        }
        int col = t.schema().requireIndex(s.column);
        Index existing = t.indexOnColumn(col);
        if (existing != null) {
            throw new DbException("По колонке '" + t.schema().column(col).name() + "' уже есть индекс '" + existing.name() + "'");
        }
        Column c = t.schema().column(col);
        Index ix = new Index(s.name, t.name(), c.name(), col, c.type(), false);
        t.addIndex(ix);
        return QueryResult.message("Индекс '" + s.name + "' создан (уникальных ключей: " + ix.keyCount() + ")");
    }

    @Override
    public QueryResult visitDropIndex(Statements.DropIndex s) {
        Index ix = db.findIndex(s.name);
        if (ix == null) {
            throw new DbException("Индекс '" + s.name + "' не существует");
        }
        if (ix.isSystem()) {
            throw new DbException("Индекс первичного ключа '" + ix.name() + "' удалить нельзя");
        }
        db.requireTable(ix.tableName()).dropIndex(ix.name());
        return QueryResult.message("Индекс '" + ix.name() + "' удалён");
    }

    // ---------- DML ----------

    @Override
    public QueryResult visitInsert(Statements.Insert s) {
        Table t = db.requireTable(s.table);
        TableSchema sc = t.schema();
        int n = sc.columnCount();
        int[] targets;
        if (s.columns == null) {
            targets = new int[n];
            for (int i = 0; i < n; i++) {
                targets[i] = i;
            }
        } else {
            targets = new int[s.columns.size()];
            boolean[] seen = new boolean[n];
            for (int i = 0; i < targets.length; i++) {
                targets[i] = sc.requireIndex(s.columns.get(i));
                if (seen[targets[i]]) {
                    throw new DbException("Колонка '" + s.columns.get(i) + "' указана дважды");
                }
                seen[targets[i]] = true;
            }
        }
        int pk = sc.primaryKeyIndex();
        Index pkIndex = t.primaryKeyIndex();
        List<Object[]> newRows = new ArrayList<Object[]>();
        Set<Object> batchKeys = new HashSet<Object>();
        for (List<Object> literals : s.rows) {
            if (literals.size() != targets.length) {
                throw new DbException("Ожидалось значений: " + targets.length + ", получено: " + literals.size());
            }
            Object[] values = new Object[n];
            for (int k = 0; k < targets.length; k++) {
                Column c = sc.column(targets[k]);
                try {
                    values[targets[k]] = c.type().coerce(literals.get(k));
                } catch (DbException e) {
                    throw new DbException("Колонка '" + c.name() + "': " + e.getMessage());
                }
            }
            for (int i = 0; i < n; i++) {
                if (values[i] == null && sc.column(i).isNotNull()) {
                    throw new DbException("Колонка '" + sc.column(i).name() + "' не допускает NULL");
                }
            }
            Object key = values[pk];
            if (pkIndex.lookup(key).length > 0 || !batchKeys.add(key)) {
                throw new DbException("Нарушение уникальности первичного ключа: значение " + Values.literal(key)
                        + " уже есть в таблице '" + t.name() + "'");
            }
            newRows.add(values);
        }
        for (Object[] values : newRows) {
            checkForeignKeys(t, values, null, batchKeys);
        }
        for (Object[] values : newRows) {
            t.insert(values);
        }
        return QueryResult.message("Добавлено записей: " + newRows.size());
    }

    @Override
    public QueryResult visitSelect(Statements.Select s) {
        Table t = db.requireTable(s.table);
        TableSchema sc = t.schema();
        int[] proj;
        List<String> names = new ArrayList<String>();
        if (s.countAll) {
            proj = new int[0];
        } else if (s.columns == null) {
            proj = new int[sc.columnCount()];
            for (int i = 0; i < proj.length; i++) {
                proj[i] = i;
                names.add(sc.column(i).name());
            }
        } else {
            proj = new int[s.columns.size()];
            for (int i = 0; i < proj.length; i++) {
                proj[i] = sc.requireIndex(s.columns.get(i));
                names.add(sc.column(proj[i]).name());
            }
        }
        final int[] orderCols = new int[s.orderBy.size()];
        final boolean[] desc = new boolean[orderCols.length];
        for (int i = 0; i < orderCols.length; i++) {
            orderCols[i] = sc.requireIndex(s.orderBy.get(i).column);
            desc[i] = s.orderBy.get(i).descending;
        }
        Scan scan = scan(t, s.where);
        if (s.countAll) {
            List<Object[]> one = new ArrayList<Object[]>();
            one.add(new Object[]{(long) scan.rows.size()});
            return QueryResult.table(Collections.singletonList("COUNT(*)"), one, scan.plan);
        }
        if (orderCols.length > 0) {
            Collections.sort(scan.rows, new Comparator<Row>() {
                @Override
                public int compare(Row a, Row b) {
                    for (int i = 0; i < orderCols.length; i++) {
                        int c = Values.compareNullsFirst(a.get(orderCols[i]), b.get(orderCols[i]));
                        if (c != 0) {
                            return desc[i] ? -c : c;
                        }
                    }
                    return 0;
                }
            });
        }
        int limit = s.limit == null ? scan.rows.size() : Math.min(s.limit, scan.rows.size());
        List<Object[]> out = new ArrayList<Object[]>(limit);
        for (int r = 0; r < limit; r++) {
            Row row = scan.rows.get(r);
            Object[] cells = new Object[proj.length];
            for (int i = 0; i < proj.length; i++) {
                cells[i] = row.get(proj[i]);
            }
            out.add(cells);
        }
        return QueryResult.table(names, out, scan.plan);
    }

    @Override
    public QueryResult visitUpdate(Statements.Update s) {
        Table t = db.requireTable(s.table);
        TableSchema sc = t.schema();
        int n = s.setColumns.size();
        int[] idx = new int[n];
        Object[] vals = new Object[n];
        boolean[] changed = new boolean[sc.columnCount()];
        for (int i = 0; i < n; i++) {
            idx[i] = sc.requireIndex(s.setColumns.get(i));
            if (changed[idx[i]]) {
                throw new DbException("Колонка '" + s.setColumns.get(i) + "' указана в SET дважды");
            }
            changed[idx[i]] = true;
            Column c = sc.column(idx[i]);
            try {
                vals[i] = c.type().coerce(s.setValues.get(i));
            } catch (DbException e) {
                throw new DbException("Колонка '" + c.name() + "': " + e.getMessage());
            }
            if (vals[i] == null && c.isNotNull()) {
                throw new DbException("Колонка '" + c.name() + "' не допускает NULL");
            }
        }
        Scan scan = scan(t, s.where);
        int pk = sc.primaryKeyIndex();
        boolean pkSet = changed[pk];
        if (pkSet && scan.rows.size() > 1) {
            throw new DbException("Нельзя присвоить одно значение первичного ключа нескольким записям ("
                    + scan.rows.size() + ")");
        }
        List<Object[]> newValues = new ArrayList<Object[]>();
        Set<Object> oldKeys = new HashSet<Object>();
        for (Row r : scan.rows) {
            Object[] nv = r.copyValues();
            for (int i = 0; i < n; i++) {
                nv[idx[i]] = vals[i];
            }
            if (pkSet && !Values.equal(nv[pk], r.get(pk))) {
                if (t.primaryKeyIndex().lookup(nv[pk]).length > 0) {
                    throw new DbException("Нарушение уникальности первичного ключа: значение " + Values.literal(nv[pk])
                            + " уже есть в таблице '" + t.name() + "'");
                }
                oldKeys.add(r.get(pk));
            }
            checkForeignKeys(t, nv, changed, null);
            newValues.add(nv);
        }
        if (!oldKeys.isEmpty()) {
            checkNotReferenced(t, oldKeys, Collections.<Long>emptySet());
        }
        for (int k = 0; k < newValues.size(); k++) {
            t.update(scan.rows.get(k), newValues.get(k));
        }
        return QueryResult.message("Обновлено записей: " + newValues.size(), scan.plan);
    }

    @Override
    public QueryResult visitDelete(Statements.Delete s) {
        Table t = db.requireTable(s.table);
        Scan scan = scan(t, s.where);
        int pk = t.schema().primaryKeyIndex();
        Set<Object> keys = new HashSet<Object>();
        Set<Long> ids = new HashSet<Long>();
        for (Row r : scan.rows) {
            keys.add(r.get(pk));
            ids.add(r.id());
        }
        checkNotReferenced(t, keys, ids);
        for (Row r : scan.rows) {
            t.delete(r);
        }
        return QueryResult.message("Удалено записей: " + scan.rows.size(), scan.plan);
    }

    // ---------- служебные команды ----------

    @Override
    public QueryResult visitShowTables(Statements.ShowTables s) {
        List<Object[]> rows = new ArrayList<Object[]>();
        for (Table t : db.tables()) {
            rows.add(new Object[]{t.name(), (long) t.size(), (long) t.indexes().size()});
        }
        return QueryResult.table(java.util.Arrays.asList("Таблица", "Записей", "Индексов"), rows, null);
    }

    @Override
    public QueryResult visitShowIndexes(Statements.ShowIndexes s) {
        List<Object[]> rows = new ArrayList<Object[]>();
        for (Table t : db.tables()) {
            for (Index ix : t.indexes()) {
                rows.add(new Object[]{ix.name(), t.name(), ix.columnName(), (long) ix.keyCount(),
                        ix.isSystem() ? "первичный ключ" : "пользовательский"});
            }
        }
        return QueryResult.table(java.util.Arrays.asList("Индекс", "Таблица", "Колонка", "Ключей", "Вид"), rows, null);
    }

    @Override
    public QueryResult visitDescribe(Statements.Describe s) {
        Table t = db.requireTable(s.table);
        List<Object[]> rows = new ArrayList<Object[]>();
        for (Column c : t.schema().columns()) {
            rows.add(new Object[]{c.name(), c.type().toString(), c.isPrimaryKey() ? "PK" : "",
                    c.isNotNull() ? "NOT NULL" : "", c.foreignKey() == null ? "" : "-> " + c.foreignKey()});
        }
        return QueryResult.table(java.util.Arrays.asList("Колонка", "Тип", "Ключ", "NULL", "Внешний ключ"), rows, null);
    }

    // ---------- ограничения целостности ----------

    /**
     * Проверяет, что значения внешних ключей строки указывают на существующие записи родительской таблицы.
     *
     * @param t       таблица, в которую вставляется/обновляется строка
     * @param values  значения строки
     * @param only    проверять только отмеченные колонки (null - все)
     * @param pending ключи, вставляемые в той же команде (для ссылок таблицы на саму себя), или null
     */
    private void checkForeignKeys(Table t, Object[] values, boolean[] only, Set<Object> pending) {
        TableSchema sc = t.schema();
        for (int i = 0; i < sc.columnCount(); i++) {
            ForeignKey fk = sc.column(i).foreignKey();
            if (fk == null || (only != null && !only[i]) || values[i] == null) {
                continue;
            }
            Table ref = fk.refTable().equalsIgnoreCase(t.name()) ? t : db.requireTable(fk.refTable());
            boolean exists = ref.primaryKeyIndex().lookup(values[i]).length > 0
                    || (ref == t && pending != null && pending.contains(values[i]));
            if (!exists) {
                throw new DbException("Нарушение внешнего ключа: в таблице '" + ref.name() + "' нет записи с "
                        + fk.refColumn() + " = " + Values.literal(values[i]) + " (колонка " + t.name() + "."
                        + sc.column(i).name() + ")");
            }
        }
    }

    /**
     * Проверяет, что на удаляемые/изменяемые ключи родительской таблицы никто не ссылается (RESTRICT).
     *
     * @param parent   родительская таблица
     * @param keys     значения ключей
     * @param excluded идентификаторы строк родительской таблицы, которые удаляются в этой же команде
     */
    private void checkNotReferenced(Table parent, Set<Object> keys, Set<Long> excluded) {
        if (keys.isEmpty()) {
            return;
        }
        for (Table child : db.tables()) {
            TableSchema cs = child.schema();
            for (int i = 0; i < cs.columnCount(); i++) {
                ForeignKey fk = cs.column(i).foreignKey();
                if (fk == null || !fk.refTable().equalsIgnoreCase(parent.name())) {
                    continue;
                }
                Index ix = child.indexOnColumn(i);
                if (ix != null) {
                    for (Object key : keys) {
                        for (long id : ix.lookup(key)) {
                            if (!(child == parent && excluded.contains(id))) {
                                throw referenced(child, cs.column(i), key);
                            }
                        }
                    }
                } else {
                    for (Row r : child) {
                        Object v = r.get(i);
                        if (v != null && keys.contains(v) && !(child == parent && excluded.contains(r.id()))) {
                            throw referenced(child, cs.column(i), v);
                        }
                    }
                }
            }
        }
    }

    private DbException referenced(Table child, Column column, Object key) {
        return new DbException("Нарушение внешнего ключа: на запись со значением " + Values.literal(key)
                + " ссылается таблица '" + child.name() + "' (колонка " + column.name() + ")");
    }

    // ---------- выбор плана выполнения ----------

    /** Найденные строки и описание способа их получения. */
    private static final class Scan {
        final List<Row> rows;
        final String plan;

        Scan(List<Row> rows, String plan) {
            this.rows = rows;
            this.plan = plan;
        }
    }

    /**
     * Находит строки, удовлетворяющие условию. Если среди условий, связанных через AND,
     * есть сравнение по индексированной колонке (=, &lt;, &lt;=, &gt;, &gt;=), кандидаты берутся из индекса,
     * а затем проверяются полным условием; иначе выполняется полный перебор таблицы.
     * Строки всегда возвращаются в порядке вставки, поэтому результат не зависит от плана.
     */
    private Scan scan(Table t, Condition where) {
        List<Row> out = new ArrayList<Row>();
        if (where == null) {
            for (Row r : t) {
                out.add(r);
            }
            return new Scan(out, "FULL SCAN: чтение всех записей (" + t.size() + ")");
        }
        where.bind(t.schema());
        List<Condition.Comparison> conj = new ArrayList<Condition.Comparison>();
        where.collectConjuncts(conj);
        int col = -1;
        for (Condition.Comparison c : conj) {
            if (!usable(t, c)) {
                continue;
            }
            if (c.op() == CompareOp.EQ) {
                col = c.columnIndex();
                break;
            }
            if (col < 0) {
                col = c.columnIndex();
            }
        }
        if (col < 0) {
            for (Row r : t) {
                if (where.matches(r)) {
                    out.add(r);
                }
            }
            return new Scan(out, "FULL SCAN: индекс не применим, проверено записей: " + t.size());
        }
        Index ix = t.indexOnColumn(col);
        Bounds b = new Bounds();
        for (Condition.Comparison c : conj) {
            if (c.columnIndex() == col && usable(t, c)) {
                b.apply(c.op(), c.exactKey());
            }
        }
        long[] ids = b.fetch(ix);
        for (long id : ids) {
            Row r = t.get(id);
            if (r != null && where.matches(r)) {
                out.add(r);
            }
        }
        return new Scan(out, "INDEX SCAN: индекс " + ix.name() + " (" + b.describe(ix.columnName())
                + "), прочитано кандидатов: " + ids.length + " из " + t.size());
    }

    private boolean usable(Table t, Condition.Comparison c) {
        return c.op() != CompareOp.NE && c.value() != null && t.indexOnColumn(c.columnIndex()) != null
                && c.exactKey() != null;
    }

    /** Границы диапазона по индексированной колонке, собранные из условий AND. */
    private static final class Bounds {
        Object lo;
        Object hi;
        boolean loInc;
        boolean hiInc;

        void apply(CompareOp op, Object key) {
            switch (op) {
                case EQ:
                    tightenLo(key, true);
                    tightenHi(key, true);
                    break;
                case GT:
                    tightenLo(key, false);
                    break;
                case GE:
                    tightenLo(key, true);
                    break;
                case LT:
                    tightenHi(key, false);
                    break;
                case LE:
                    tightenHi(key, true);
                    break;
                default:
                    break;
            }
        }

        private void tightenLo(Object key, boolean inc) {
            if (lo == null) {
                lo = key;
                loInc = inc;
                return;
            }
            int c = Values.compare(key, lo);
            if (c > 0 || (c == 0 && !inc)) {
                lo = key;
                loInc = inc;
            }
        }

        private void tightenHi(Object key, boolean inc) {
            if (hi == null) {
                hi = key;
                hiInc = inc;
                return;
            }
            int c = Values.compare(key, hi);
            if (c < 0 || (c == 0 && !inc)) {
                hi = key;
                hiInc = inc;
            }
        }

        long[] fetch(Index ix) {
            if (lo != null && hi != null && loInc && hiInc && Values.compare(lo, hi) == 0) {
                return ix.lookup(lo);
            }
            return ix.range(lo, loInc, hi, hiInc);
        }

        String describe(String column) {
            if (lo != null && hi != null && loInc && hiInc && Values.compare(lo, hi) == 0) {
                return column + " = " + Values.literal(lo);
            }
            List<String> parts = new ArrayList<String>();
            if (lo != null) {
                parts.add(column + (loInc ? " >= " : " > ") + Values.literal(lo));
            }
            if (hi != null) {
                parts.add(column + (hiInc ? " <= " : " < ") + Values.literal(hi));
            }
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < parts.size(); i++) {
                sb.append(i > 0 ? " AND " : "").append(parts.get(i));
            }
            return sb.toString();
        }
    }
}
