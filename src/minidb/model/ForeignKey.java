package minidb.model;

/**
 * Описание внешнего ключа: колонка ссылается на первичный ключ другой таблицы.
 */
public final class ForeignKey {
    private final String refTable;
    private final String refColumn;

    /**
     * @param refTable  имя родительской таблицы
     * @param refColumn имя колонки родительской таблицы (должна быть её первичным ключом)
     */
    public ForeignKey(String refTable, String refColumn) {
        this.refTable = refTable;
        this.refColumn = refColumn;
    }

    /** @return имя родительской таблицы */
    public String refTable() {
        return refTable;
    }

    /** @return имя колонки родительской таблицы */
    public String refColumn() {
        return refColumn;
    }

    @Override
    public String toString() {
        return refTable + "(" + refColumn + ")";
    }
}
