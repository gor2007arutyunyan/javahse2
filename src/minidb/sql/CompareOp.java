package minidb.sql;

/** Операторы сравнения в условиях WHERE. */
public enum CompareOp {
    /** Равно. */
    EQ("="),
    /** Не равно. */
    NE("<>"),
    /** Меньше. */
    LT("<"),
    /** Меньше или равно. */
    LE("<="),
    /** Больше. */
    GT(">"),
    /** Больше или равно. */
    GE(">=");

    private final String symbol;

    CompareOp(String symbol) {
        this.symbol = symbol;
    }

    /** @return написание оператора в SQL */
    public String symbol() {
        return symbol;
    }

    /**
     * @param text текст оператора
     * @return оператор или null, если текст не является оператором сравнения
     */
    public static CompareOp fromSymbol(String text) {
        for (CompareOp op : values()) {
            if (op.symbol.equals(text)) {
                return op;
            }
        }
        return null;
    }

    /**
     * Проверяет оператор по результату сравнения значений.
     *
     * @param cmp результат compare(значение_в_строке, константа)
     * @return выполняется ли условие
     */
    public boolean test(int cmp) {
        switch (this) {
            case EQ:
                return cmp == 0;
            case NE:
                return cmp != 0;
            case LT:
                return cmp < 0;
            case LE:
                return cmp <= 0;
            case GT:
                return cmp > 0;
            default:
                return cmp >= 0;
        }
    }
}
