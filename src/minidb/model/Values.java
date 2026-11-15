package minidb.model;

/**
 * Вспомогательные функции для сравнения и форматирования значений ячеек.
 */
public final class Values {
    private Values() {
    }

    /**
     * Сравнивает два не-null значения одного (или совместимого числового) типа.
     *
     * @param a первое значение
     * @param b второе значение
     * @return отрицательное, ноль или положительное число
     */
    @SuppressWarnings("unchecked")
    public static int compare(Object a, Object b) {
        if (a instanceof Long && b instanceof Long) {
            return Long.compare((Long) a, (Long) b);
        }
        if (a instanceof Number && b instanceof Number) {
            return Double.compare(((Number) a).doubleValue(), ((Number) b).doubleValue());
        }
        return ((Comparable<Object>) a).compareTo(b);
    }

    /**
     * Сравнение, в котором null считается меньше любого значения (для ORDER BY).
     *
     * @param a первое значение (может быть null)
     * @param b второе значение (может быть null)
     * @return результат сравнения
     */
    public static int compareNullsFirst(Object a, Object b) {
        if (a == null || b == null) {
            return a == b ? 0 : (a == null ? -1 : 1);
        }
        return compare(a, b);
    }

    /**
     * Равенство с учётом null.
     *
     * @param a первое значение
     * @param b второе значение
     * @return true, если значения равны или оба null
     */
    public static boolean equal(Object a, Object b) {
        if (a == null || b == null) {
            return a == b;
        }
        return compare(a, b) == 0;
    }

    /**
     * Текст ячейки для вывода в таблице.
     *
     * @param v значение
     * @return {@code NULL} для null, иначе строковое представление
     */
    public static String format(Object v) {
        return v == null ? "NULL" : v.toString();
    }

    /**
     * Представление значения как SQL-литерала (строки в кавычках) - для сообщений и плана.
     *
     * @param v значение
     * @return литерал
     */
    public static String literal(Object v) {
        if (v == null) {
            return "NULL";
        }
        if (v instanceof String || v instanceof java.time.LocalDate) {
            return "'" + v + "'";
        }
        return v.toString();
    }
}
