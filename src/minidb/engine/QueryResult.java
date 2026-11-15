package minidb.engine;

import java.util.Collections;
import java.util.List;

/**
 * Результат выполнения команды: либо таблица значений (SELECT, SHOW, DESCRIBE),
 * либо текстовое сообщение (INSERT, UPDATE, DELETE, DDL). Для запросов с условием
 * дополнительно хранится описание плана выполнения (использован ли индекс).
 */
public final class QueryResult {
    private final List<String> columns;
    private final List<Object[]> rows;
    private final String message;
    private final String plan;

    private QueryResult(List<String> columns, List<Object[]> rows, String message, String plan) {
        this.columns = columns;
        this.rows = rows;
        this.message = message;
        this.plan = plan;
    }

    /**
     * @param message текст сообщения
     * @return результат без таблицы
     */
    public static QueryResult message(String message) {
        return new QueryResult(null, null, message, null);
    }

    /**
     * @param message текст сообщения
     * @param plan    описание плана выполнения
     * @return результат без таблицы, с планом
     */
    public static QueryResult message(String message, String plan) {
        return new QueryResult(null, null, message, plan);
    }

    /**
     * @param columns заголовки колонок
     * @param rows    строки результата
     * @param plan    описание плана выполнения или null
     * @return результат с таблицей значений
     */
    public static QueryResult table(List<String> columns, List<Object[]> rows, String plan) {
        return new QueryResult(Collections.unmodifiableList(columns), Collections.unmodifiableList(rows), null, plan);
    }

    /** @return true, если результат содержит таблицу значений */
    public boolean hasTable() {
        return columns != null;
    }

    /** @return заголовки колонок (только если {@link #hasTable()}) */
    public List<String> columns() {
        return columns;
    }

    /** @return строки результата (только если {@link #hasTable()}) */
    public List<Object[]> rows() {
        return rows;
    }

    /** @return сообщение или null для результата с таблицей */
    public String message() {
        return message;
    }

    /** @return описание плана выполнения или null */
    public String plan() {
        return plan;
    }
}
