package minidb.model;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;

import minidb.DbException;

/**
 * Поддерживаемые типы данных колонок. Каждому типу соответствует Java-класс значения:
 * INT - {@link Long}, DOUBLE - {@link Double}, STRING - {@link String},
 * DATE - {@link LocalDate}, BOOLEAN - {@link Boolean}.
 */
public enum ColumnType {
    /** Целое число (64 бита). */
    INT,
    /** Число с плавающей точкой. */
    DOUBLE,
    /** Строка (UTF-8). */
    STRING,
    /** Дата в формате ГГГГ-ММ-ДД. */
    DATE,
    /** Логическое значение TRUE/FALSE. */
    BOOLEAN;

    /**
     * Находит тип по имени, принятому в SQL (допускаются синонимы INTEGER, VARCHAR, BOOL и т.д.).
     *
     * @param name имя типа без учёта регистра
     * @return тип или {@code null}, если такого типа нет
     */
    public static ColumnType fromSqlName(String name) {
        switch (name.toUpperCase(java.util.Locale.ROOT)) {
            case "INT":
            case "INTEGER":
            case "BIGINT":
                return INT;
            case "DOUBLE":
            case "FLOAT":
            case "REAL":
                return DOUBLE;
            case "STRING":
            case "TEXT":
            case "VARCHAR":
                return STRING;
            case "DATE":
                return DATE;
            case "BOOLEAN":
            case "BOOL":
                return BOOLEAN;
            default:
                return null;
        }
    }

    /**
     * Строго приводит литерал из запроса к Java-типу колонки (для INSERT/UPDATE).
     *
     * @param literal значение, полученное парсером (Long, Double, String, Boolean или null)
     * @return значение нужного типа
     * @throws DbException если значение не подходит к типу
     */
    public Object coerce(Object literal) {
        if (literal == null) {
            return null;
        }
        switch (this) {
            case INT:
                if (literal instanceof Long) {
                    return literal;
                }
                if (literal instanceof Double) {
                    double d = (Double) literal;
                    if (d == Math.rint(d) && Math.abs(d) < 9.0e18) {
                        return (long) d;
                    }
                }
                break;
            case DOUBLE:
                if (literal instanceof Double) {
                    return literal;
                }
                if (literal instanceof Long) {
                    return ((Long) literal).doubleValue();
                }
                break;
            case STRING:
                if (literal instanceof String) {
                    return literal;
                }
                break;
            case DATE:
                if (literal instanceof LocalDate) {
                    return literal;
                }
                if (literal instanceof String) {
                    try {
                        return LocalDate.parse((String) literal);
                    } catch (DateTimeParseException e) {
                        throw new DbException("Некорректная дата '" + literal + "', ожидается формат ГГГГ-ММ-ДД");
                    }
                }
                break;
            case BOOLEAN:
                if (literal instanceof Boolean) {
                    return literal;
                }
                break;
            default:
                break;
        }
        throw new DbException("значение " + Values.literal(literal) + " нельзя использовать для типа " + this);
    }

    /**
     * Приведение литерала для сравнения в WHERE: для INT допускается дробное число
     * (например {@code id < 2.5}), остальные типы приводятся строго.
     *
     * @param literal значение из запроса
     * @return значение, пригодное для {@link Values#compare(Object, Object)}
     */
    public Object coerceForComparison(Object literal) {
        if (this == INT && literal instanceof Double) {
            return literal;
        }
        return coerce(literal);
    }
}
