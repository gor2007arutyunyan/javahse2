package minidb.model;

/**
 * Описание колонки таблицы (неизменяемый объект).
 */
public final class Column {
    private final String name;
    private final ColumnType type;
    private final boolean primaryKey;
    private final boolean notNull;
    private final ForeignKey foreignKey;

    /**
     * @param name       имя колонки
     * @param type       тип данных
     * @param primaryKey является ли колонка первичным ключом (тогда она автоматически NOT NULL)
     * @param notNull    запрещены ли значения NULL
     * @param foreignKey внешний ключ или null
     */
    public Column(String name, ColumnType type, boolean primaryKey, boolean notNull, ForeignKey foreignKey) {
        this.name = name;
        this.type = type;
        this.primaryKey = primaryKey;
        this.notNull = notNull || primaryKey;
        this.foreignKey = foreignKey;
    }

    /** @return имя колонки */
    public String name() {
        return name;
    }

    /** @return тип данных */
    public ColumnType type() {
        return type;
    }

    /** @return true, если колонка - первичный ключ */
    public boolean isPrimaryKey() {
        return primaryKey;
    }

    /** @return true, если NULL запрещён */
    public boolean isNotNull() {
        return notNull;
    }

    /** @return внешний ключ или null */
    public ForeignKey foreignKey() {
        return foreignKey;
    }
}
