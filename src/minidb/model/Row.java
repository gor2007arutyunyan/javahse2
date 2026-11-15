package minidb.model;

/**
 * Запись таблицы: уникальный внутренний идентификатор и значения по колонкам.
 * Идентификатор строки не меняется при обновлении и растёт при вставке,
 * поэтому порядок по id совпадает с порядком вставки.
 */
public final class Row {
    private final long id;
    private final Object[] values;

    /**
     * @param id     внутренний идентификатор строки
     * @param values значения (массив не копируется - владельцем становится строка)
     */
    public Row(long id, Object[] values) {
        this.id = id;
        this.values = values;
    }

    /** @return внутренний идентификатор строки */
    public long id() {
        return id;
    }

    /**
     * @param column номер колонки
     * @return значение (может быть null)
     */
    public Object get(int column) {
        return values[column];
    }

    /** @return копия массива значений */
    public Object[] copyValues() {
        return values.clone();
    }
}
