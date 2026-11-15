package minidb.sql;

/**
 * Разобранная команда SQL (паттерн Command + Visitor): исполнитель обходит команду
 * через {@link #accept(StatementVisitor)}.
 */
public abstract class Statement {

    /** Категория команды - определяет, когда нужно сохранять изменения на диск. */
    public enum Kind {
        /** Чтение, данные не меняются. */
        QUERY,
        /** Изменение данных (INSERT, UPDATE, DELETE). */
        DML,
        /** Изменение структуры (CREATE/DROP TABLE/INDEX). */
        DDL
    }

    /** @return категория команды */
    public abstract Kind kind();

    /**
     * @param visitor посетитель
     * @param <R>     тип результата
     * @return результат посетителя
     */
    public abstract <R> R accept(StatementVisitor<R> visitor);
}
