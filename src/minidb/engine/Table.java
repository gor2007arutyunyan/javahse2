package minidb.engine;

import java.util.Collection;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

import minidb.index.Index;
import minidb.model.Column;
import minidb.model.Row;
import minidb.model.TableSchema;
import minidb.model.Values;

/**
 * Таблица в памяти: схема, строки и индексы. Строки хранятся в порядке вставки
 * и доступны по внутреннему идентификатору. Все изменения проходят через методы этого класса,
 * которые синхронно обновляют индексы. Класс реализует {@link Iterable}, поэтому по строкам
 * можно итерироваться циклом {@code for (Row r : table)}.
 * <p>
 * Проверка ограничений (уникальность ключа, внешние ключи) выполняется выше, в {@link Executor};
 * здесь данные меняются без дополнительных проверок.
 */
public final class Table implements Iterable<Row> {
    /** Префикс имени автоматически создаваемого индекса первичного ключа. */
    public static final String PK_INDEX_PREFIX = "pk_";

    private final TableSchema schema;
    private final Map<Long, Row> rows = new LinkedHashMap<Long, Row>();
    private final Map<String, Index> indexes = new LinkedHashMap<String, Index>();
    private long nextRowId;
    private long version;
    private boolean dirty;

    private Table(TableSchema schema, long nextRowId, long version) {
        this.schema = schema;
        this.nextRowId = nextRowId;
        this.version = version;
    }

    /**
     * Создаёт пустую таблицу вместе с индексом первичного ключа.
     *
     * @param schema схема таблицы
     * @return новая таблица (помечена как требующая сохранения)
     */
    public static Table create(TableSchema schema) {
        Table t = new Table(schema, 1, 1);
        t.addIndexInternal(t.newPrimaryKeyIndex());
        t.dirty = true;
        return t;
    }

    /**
     * Создаёт таблицу при загрузке с диска. Строки добавляются через {@link #restoreRow(Row)},
     * индексы - через {@link #attachIndex(Index)}.
     *
     * @param schema    схема
     * @param nextRowId следующий свободный идентификатор строки
     * @param version   версия данных, с которой был сохранён файл
     * @return таблица без строк и индексов
     */
    public static Table restore(TableSchema schema, long nextRowId, long version) {
        return new Table(schema, nextRowId, version);
    }

    /**
     * Создаёт объект индекса первичного ключа этой таблицы (без заполнения).
     *
     * @return пустой индекс
     */
    public Index newPrimaryKeyIndex() {
        int pk = schema.primaryKeyIndex();
        Column c = schema.column(pk);
        return new Index(PK_INDEX_PREFIX + schema.name(), schema.name(), c.name(), pk, c.type(), true);
    }

    /** @return схема таблицы */
    public TableSchema schema() {
        return schema;
    }

    /** @return имя таблицы */
    public String name() {
        return schema.name();
    }

    /** @return число строк */
    public int size() {
        return rows.size();
    }

    /**
     * @param id внутренний идентификатор строки
     * @return строка или null
     */
    public Row get(long id) {
        return rows.get(id);
    }

    @Override
    public Iterator<Row> iterator() {
        return Collections.unmodifiableCollection(rows.values()).iterator();
    }

    /** @return следующий свободный идентификатор строки (сохраняется на диск) */
    public long nextRowId() {
        return nextRowId;
    }

    /** @return версия данных; увеличивается при каждом изменении */
    public long version() {
        return version;
    }

    /** @return true, если есть изменения, не записанные на диск */
    public boolean isDirty() {
        return dirty;
    }

    /** Помечает таблицу как требующую записи на диск (без изменения версии). */
    public void markDirty() {
        dirty = true;
    }

    /** Помечает таблицу как сохранённую. */
    public void markClean() {
        dirty = false;
    }

    private void touch() {
        version++;
        dirty = true;
    }

    // ---------- индексы ----------

    /** @return все индексы таблицы (включая индекс первичного ключа) */
    public Collection<Index> indexes() {
        return Collections.unmodifiableCollection(indexes.values());
    }

    /**
     * @param name имя индекса
     * @return индекс или null
     */
    public Index indexByName(String name) {
        return indexes.get(TableSchema.normalize(name));
    }

    /**
     * @param columnIndex номер колонки
     * @return индекс по этой колонке или null
     */
    public Index indexOnColumn(int columnIndex) {
        for (Index ix : indexes.values()) {
            if (ix.columnIndex() == columnIndex) {
                return ix;
            }
        }
        return null;
    }

    /** @return индекс первичного ключа */
    public Index primaryKeyIndex() {
        return indexOnColumn(schema.primaryKeyIndex());
    }

    /**
     * Добавляет новый индекс и заполняет его существующими строками.
     *
     * @param index пустой индекс
     */
    public void addIndex(Index index) {
        addIndexInternal(index);
        rebuildIndex(index);
        touch();
    }

    /**
     * Удаляет индекс.
     *
     * @param name имя индекса
     */
    public void dropIndex(String name) {
        indexes.remove(TableSchema.normalize(name));
        touch();
    }

    /**
     * Регистрирует индекс, не заполняя его (используется при загрузке с диска).
     *
     * @param index индекс
     */
    public void attachIndex(Index index) {
        addIndexInternal(index);
    }

    /**
     * Полностью перестраивает индекс по данным таблицы.
     *
     * @param index индекс этой таблицы
     */
    public void rebuildIndex(Index index) {
        index.clear();
        for (Row r : rows.values()) {
            index.add(r.get(index.columnIndex()), r.id());
        }
    }

    private void addIndexInternal(Index index) {
        indexes.put(TableSchema.normalize(index.name()), index);
    }

    // ---------- изменение данных ----------

    /**
     * Добавляет строку, присваивая ей новый идентификатор.
     *
     * @param values значения по колонкам (уже приведённые к типам)
     * @return добавленная строка
     */
    public Row insert(Object[] values) {
        Row r = new Row(nextRowId++, values);
        rows.put(r.id(), r);
        for (Index ix : indexes.values()) {
            ix.add(values[ix.columnIndex()], r.id());
        }
        touch();
        return r;
    }

    /**
     * Удаляет строку.
     *
     * @param row строка таблицы
     */
    public void delete(Row row) {
        rows.remove(row.id());
        for (Index ix : indexes.values()) {
            ix.remove(row.get(ix.columnIndex()), row.id());
        }
        touch();
    }

    /**
     * Заменяет значения строки, сохраняя её идентификатор и позицию.
     *
     * @param old       существующая строка
     * @param newValues новые значения по колонкам
     */
    public void update(Row old, Object[] newValues) {
        Row r = new Row(old.id(), newValues);
        rows.put(old.id(), r);
        for (Index ix : indexes.values()) {
            Object before = old.get(ix.columnIndex());
            Object after = newValues[ix.columnIndex()];
            if (!Values.equal(before, after)) {
                ix.remove(before, old.id());
                ix.add(after, old.id());
            }
        }
        touch();
    }

    /**
     * Добавляет строку с заранее известным идентификатором при загрузке с диска (индексы не трогает).
     *
     * @param row строка
     */
    public void restoreRow(Row row) {
        rows.put(row.id(), row);
    }
}
