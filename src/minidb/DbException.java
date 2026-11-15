package minidb;

/**
 * Ошибка работы СУБД: синтаксическая ошибка запроса, нарушение ограничений,
 * обращение к несуществующей таблице и т.п. Сообщение предназначено для показа пользователю.
 */
public class DbException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    /**
     * @param message текст ошибки для пользователя
     */
    public DbException(String message) {
        super(message);
    }

    /**
     * @param message текст ошибки для пользователя
     * @param cause   исходная причина
     */
    public DbException(String message, Throwable cause) {
        super(message, cause);
    }
}
