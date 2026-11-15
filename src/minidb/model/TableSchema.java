package minidb.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import minidb.DbException;

/**
 * Схема таблицы: имя, список колонок и позиция первичного ключа.
 * Имена колонок и таблиц нечувствительны к регистру.
 */
public final class TableSchema {
    private final String name;
    private final List<Column> columns;
    private final Map<String, Integer> byName = new HashMap<String, Integer>();
    private final int primaryKeyIndex;

    /**
     * Создаёт схему и проверяет её корректность.
     *
     * @param name    имя таблицы
     * @param columns колонки; должен быть ровно один первичный ключ, имена уникальны
     * @throws DbException если схема некорректна
     */
    public TableSchema(String name, List<Column> columns) {
        if (columns.isEmpty()) {
            throw new DbException("В таблице должна быть хотя бы одна колонка");
        }
        this.name = name;
        this.columns = Collections.unmodifiableList(new ArrayList<Column>(columns));
        int pk = -1;
        for (int i = 0; i < columns.size(); i++) {
            Column c = columns.get(i);
            if (byName.put(normalize(c.name()), i) != null) {
                throw new DbException("Колонка '" + c.name() + "' объявлена дважды");
            }
            if (c.isPrimaryKey()) {
                if (pk >= 0) {
                    throw new DbException("Составные первичные ключи не поддерживаются: PRIMARY KEY можно задать только для одной колонки");
                }
                pk = i;
            }
        }
        if (pk < 0) {
            throw new DbException("Не задан первичный ключ (PRIMARY KEY)");
        }
        this.primaryKeyIndex = pk;
    }

    /**
     * Приводит идентификатор к виду, в котором он используется как ключ в словарях и в именах файлов.
     *
     * @param identifier имя
     * @return имя в нижнем регистре
     */
    public static String normalize(String identifier) {
        return identifier.toLowerCase(Locale.ROOT);
    }

    /** @return имя таблицы */
    public String name() {
        return name;
    }

    /** @return неизменяемый список колонок */
    public List<Column> columns() {
        return columns;
    }

    /** @return число колонок */
    public int columnCount() {
        return columns.size();
    }

    /**
     * @param i номер колонки
     * @return колонка
     */
    public Column column(int i) {
        return columns.get(i);
    }

    /** @return номер колонки первичного ключа */
    public int primaryKeyIndex() {
        return primaryKeyIndex;
    }

    /**
     * @param columnName имя колонки
     * @return номер колонки или -1, если такой нет
     */
    public int indexOf(String columnName) {
        Integer i = byName.get(normalize(columnName));
        return i == null ? -1 : i;
    }

    /**
     * @param columnName имя колонки
     * @return номер колонки
     * @throws DbException если колонки нет
     */
    public int requireIndex(String columnName) {
        int i = indexOf(columnName);
        if (i < 0) {
            throw new DbException("Неизвестная колонка '" + columnName + "' в таблице '" + name + "'");
        }
        return i;
    }
}
