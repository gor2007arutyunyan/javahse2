package minidb.sql;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import minidb.DbException;
import minidb.model.Column;
import minidb.model.ColumnType;
import minidb.model.ForeignKey;

/**
 * Рекурсивный нисходящий парсер SQL-подобного языка.
 * <pre>
 * CREATE TABLE t (col TYPE [PRIMARY KEY] [NOT NULL] [REFERENCES t2(col)], ..., [FOREIGN KEY (col) REFERENCES t2(col)])
 * DROP TABLE t
 * CREATE INDEX idx ON t (col)      DROP INDEX idx
 * INSERT INTO t [(c1, c2)] VALUES (v1, v2) [, (v1, v2) ...]
 * SELECT * | c1, c2 | COUNT(*) FROM t [WHERE cond] [ORDER BY c [ASC|DESC], ...] [LIMIT n]
 * UPDATE t SET c = v [, c = v ...] [WHERE cond]
 * DELETE FROM t [WHERE cond]
 * SHOW TABLES | SHOW INDEXES | DESCRIBE t
 * cond: сравнения (=, &lt;&gt;, !=, &lt;, &lt;=, &gt;, &gt;=), IS [NOT] NULL, связанные AND / OR / NOT и скобками
 * </pre>
 */
public final class Parser {
    private static final Set<String> RESERVED = new HashSet<String>(Arrays.asList(
            "SELECT", "FROM", "WHERE", "INSERT", "INTO", "VALUES", "UPDATE", "SET", "DELETE", "CREATE",
            "DROP", "TABLE", "INDEX", "ON", "AND", "OR", "NOT", "NULL", "ORDER", "BY", "LIMIT",
            "PRIMARY", "FOREIGN", "REFERENCES", "IS", "TRUE", "FALSE"));

    private final List<Token> tokens;
    private int pos;

    private Parser(List<Token> tokens) {
        this.tokens = tokens;
    }

    /**
     * Разбирает скрипт из одной или нескольких команд, разделённых {@code ;}.
     *
     * @param sql текст
     * @return список команд
     * @throws DbException при синтаксической ошибке (ничего не выполняется)
     */
    public static List<Statement> parse(String sql) {
        return new Parser(Lexer.tokenize(sql)).parseScript();
    }

    /**
     * Разбирает ровно одну команду.
     *
     * @param sql текст команды
     * @return команда
     * @throws DbException если команд не одна или есть синтаксическая ошибка
     */
    public static Statement parseOne(String sql) {
        List<Statement> list = parse(sql);
        if (list.size() != 1) {
            throw new DbException("Ожидалась ровно одна команда, получено: " + list.size());
        }
        return list.get(0);
    }

    private List<Statement> parseScript() {
        List<Statement> result = new ArrayList<Statement>();
        while (true) {
            while (acceptSymbol(";")) {
                // пустые команды пропускаем
            }
            if (peek().type == TokenType.EOF) {
                break;
            }
            result.add(parseStatement());
            if (peek().type != TokenType.EOF && !isSymbol(";")) {
                throw error("ожидалось ';' или конец команды");
            }
        }
        return result;
    }

    private Statement parseStatement() {
        Token t = peek();
        if (t.type != TokenType.IDENT) {
            throw error("ожидалась команда (SELECT, INSERT, UPDATE, DELETE, CREATE, DROP, SHOW, DESCRIBE)");
        }
        switch (t.text.toUpperCase(Locale.ROOT)) {
            case "SELECT":
                return parseSelect();
            case "INSERT":
                return parseInsert();
            case "UPDATE":
                return parseUpdate();
            case "DELETE":
                return parseDelete();
            case "CREATE":
                return parseCreate();
            case "DROP":
                return parseDrop();
            case "SHOW":
                return parseShow();
            case "DESCRIBE":
            case "DESC":
                pos++;
                return new Statements.Describe(expectIdent("имя таблицы"));
            default:
                throw new DbException("Неизвестная команда '" + t.text
                        + "'. Поддерживаются: SELECT, INSERT, UPDATE, DELETE, CREATE TABLE/INDEX, DROP TABLE/INDEX, SHOW, DESCRIBE");
        }
    }

    // ---------- DDL ----------

    private Statement parseCreate() {
        expectKeyword("CREATE");
        if (acceptKeyword("TABLE")) {
            return parseCreateTable();
        }
        if (acceptKeyword("INDEX")) {
            String name = expectIdent("имя индекса");
            expectKeyword("ON");
            String table = expectIdent("имя таблицы");
            expectSymbol("(");
            String column = expectIdent("имя колонки");
            expectSymbol(")");
            return new Statements.CreateIndex(name, table, column);
        }
        throw error("ожидалось TABLE или INDEX");
    }

    /** Колонка, собираемая в процессе разбора CREATE TABLE. */
    private static final class ColumnDef {
        String name;
        ColumnType type;
        boolean primaryKey;
        boolean notNull;
        ForeignKey foreignKey;
    }

    private Statement parseCreateTable() {
        String name = expectIdent("имя таблицы");
        expectSymbol("(");
        List<ColumnDef> defs = new ArrayList<ColumnDef>();
        do {
            if (acceptKeyword("PRIMARY")) {
                expectKeyword("KEY");
                expectSymbol("(");
                String col = expectIdent("имя колонки");
                expectSymbol(")");
                find(defs, col).primaryKey = true;
            } else if (acceptKeyword("FOREIGN")) {
                expectKeyword("KEY");
                expectSymbol("(");
                String col = expectIdent("имя колонки");
                expectSymbol(")");
                expectKeyword("REFERENCES");
                find(defs, col).foreignKey = parseReference();
            } else {
                defs.add(parseColumnDef());
            }
        } while (acceptSymbol(","));
        expectSymbol(")");
        List<Column> columns = new ArrayList<Column>();
        for (ColumnDef d : defs) {
            columns.add(new Column(d.name, d.type, d.primaryKey, d.notNull, d.foreignKey));
        }
        return new Statements.CreateTable(name, columns);
    }

    private ColumnDef find(List<ColumnDef> defs, String name) {
        for (ColumnDef d : defs) {
            if (d.name.equalsIgnoreCase(name)) {
                return d;
            }
        }
        throw new DbException("Ограничение ссылается на неизвестную колонку '" + name + "'");
    }

    private ForeignKey parseReference() {
        String table = expectIdent("имя таблицы");
        expectSymbol("(");
        String column = expectIdent("имя колонки");
        expectSymbol(")");
        return new ForeignKey(table, column);
    }

    private ColumnDef parseColumnDef() {
        ColumnDef d = new ColumnDef();
        d.name = expectIdent("имя колонки");
        String typeName = expectIdent("тип данных");
        d.type = ColumnType.fromSqlName(typeName);
        if (d.type == null) {
            throw new DbException("Неизвестный тип данных '" + typeName + "'. Допустимы: INT, DOUBLE, STRING, DATE, BOOLEAN");
        }
        if (acceptSymbol("(")) {
            expectInteger();
            expectSymbol(")");
        }
        while (true) {
            if (acceptKeyword("PRIMARY")) {
                expectKeyword("KEY");
                d.primaryKey = true;
            } else if (acceptKeyword("NOT")) {
                expectKeyword("NULL");
                d.notNull = true;
            } else if (acceptKeyword("NULL")) {
                // явное разрешение NULL - поведение по умолчанию
            } else if (acceptKeyword("REFERENCES")) {
                d.foreignKey = parseReference();
            } else {
                break;
            }
        }
        return d;
    }

    private Statement parseDrop() {
        expectKeyword("DROP");
        if (acceptKeyword("TABLE")) {
            return new Statements.DropTable(expectIdent("имя таблицы"));
        }
        if (acceptKeyword("INDEX")) {
            return new Statements.DropIndex(expectIdent("имя индекса"));
        }
        throw error("ожидалось TABLE или INDEX");
    }

    private Statement parseShow() {
        expectKeyword("SHOW");
        if (acceptKeyword("TABLES")) {
            return new Statements.ShowTables();
        }
        if (acceptKeyword("INDEXES") || acceptKeyword("INDICES")) {
            return new Statements.ShowIndexes();
        }
        throw error("ожидалось TABLES или INDEXES");
    }

    // ---------- DML ----------

    private Statement parseInsert() {
        expectKeyword("INSERT");
        expectKeyword("INTO");
        String table = expectIdent("имя таблицы");
        List<String> columns = null;
        if (acceptSymbol("(")) {
            columns = new ArrayList<String>();
            do {
                columns.add(expectIdent("имя колонки"));
            } while (acceptSymbol(","));
            expectSymbol(")");
        }
        expectKeyword("VALUES");
        List<List<Object>> rows = new ArrayList<List<Object>>();
        do {
            expectSymbol("(");
            List<Object> values = new ArrayList<Object>();
            do {
                values.add(parseLiteral());
            } while (acceptSymbol(","));
            expectSymbol(")");
            rows.add(values);
        } while (acceptSymbol(","));
        return new Statements.Insert(table, columns, rows);
    }

    private Statement parseSelect() {
        expectKeyword("SELECT");
        List<String> columns = null;
        boolean countAll = false;
        if (acceptSymbol("*")) {
            columns = null;
        } else if (isKeyword("COUNT") && tokens.get(pos + 1).type == TokenType.SYMBOL
                && tokens.get(pos + 1).text.equals("(")) {
            pos++;
            expectSymbol("(");
            expectSymbol("*");
            expectSymbol(")");
            countAll = true;
        } else {
            columns = new ArrayList<String>();
            do {
                columns.add(expectIdent("имя колонки или *"));
            } while (acceptSymbol(","));
        }
        expectKeyword("FROM");
        String table = expectIdent("имя таблицы");
        Condition where = acceptKeyword("WHERE") ? parseOr() : null;
        List<Statements.OrderKey> order = new ArrayList<Statements.OrderKey>();
        if (acceptKeyword("ORDER")) {
            expectKeyword("BY");
            do {
                String col = expectIdent("имя колонки");
                boolean desc = false;
                if (acceptKeyword("DESC")) {
                    desc = true;
                } else {
                    acceptKeyword("ASC");
                }
                order.add(new Statements.OrderKey(col, desc));
            } while (acceptSymbol(","));
        }
        Integer limit = null;
        if (acceptKeyword("LIMIT")) {
            limit = (int) Math.min(Integer.MAX_VALUE, expectInteger());
        }
        return new Statements.Select(table, columns, countAll, where, order, limit);
    }

    private Statement parseUpdate() {
        expectKeyword("UPDATE");
        String table = expectIdent("имя таблицы");
        expectKeyword("SET");
        List<String> cols = new ArrayList<String>();
        List<Object> vals = new ArrayList<Object>();
        do {
            cols.add(expectIdent("имя колонки"));
            if (!acceptSymbol("=")) {
                throw error("ожидалось '='");
            }
            vals.add(parseLiteral());
        } while (acceptSymbol(","));
        Condition where = acceptKeyword("WHERE") ? parseOr() : null;
        return new Statements.Update(table, cols, vals, where);
    }

    private Statement parseDelete() {
        expectKeyword("DELETE");
        expectKeyword("FROM");
        String table = expectIdent("имя таблицы");
        Condition where = acceptKeyword("WHERE") ? parseOr() : null;
        return new Statements.Delete(table, where);
    }

    // ---------- условия ----------

    private Condition parseOr() {
        Condition left = parseAnd();
        while (acceptKeyword("OR")) {
            left = new Condition.Or(left, parseAnd());
        }
        return left;
    }

    private Condition parseAnd() {
        Condition left = parseNot();
        while (acceptKeyword("AND")) {
            left = new Condition.And(left, parseNot());
        }
        return left;
    }

    private Condition parseNot() {
        if (acceptKeyword("NOT")) {
            return new Condition.Not(parseNot());
        }
        return parsePrimary();
    }

    private Condition parsePrimary() {
        if (acceptSymbol("(")) {
            Condition c = parseOr();
            expectSymbol(")");
            return c;
        }
        String column = expectIdent("имя колонки");
        if (acceptKeyword("IS")) {
            boolean negated = acceptKeyword("NOT");
            expectKeyword("NULL");
            return new Condition.IsNull(column, negated);
        }
        Token t = peek();
        CompareOp op = t.type == TokenType.SYMBOL ? CompareOp.fromSymbol(t.text) : null;
        if (op == null) {
            throw error("ожидался оператор сравнения (=, <>, <, <=, >, >=) или IS");
        }
        pos++;
        return new Condition.Comparison(column, op, parseLiteral());
    }

    private Object parseLiteral() {
        Token t = peek();
        switch (t.type) {
            case STRING:
                pos++;
                return t.text;
            case INTEGER:
                pos++;
                return parseLong(t.text);
            case DECIMAL:
                pos++;
                return Double.parseDouble(t.text);
            case SYMBOL:
                if (t.text.equals("-")) {
                    Token n = tokens.get(pos + 1);
                    if (n.type == TokenType.INTEGER) {
                        pos += 2;
                        return parseLong("-" + n.text);
                    }
                    if (n.type == TokenType.DECIMAL) {
                        pos += 2;
                        return -Double.parseDouble(n.text);
                    }
                }
                break;
            case IDENT:
                if (t.text.equalsIgnoreCase("TRUE")) {
                    pos++;
                    return Boolean.TRUE;
                }
                if (t.text.equalsIgnoreCase("FALSE")) {
                    pos++;
                    return Boolean.FALSE;
                }
                if (t.text.equalsIgnoreCase("NULL")) {
                    pos++;
                    return null;
                }
                break;
            default:
                break;
        }
        throw error("ожидалось значение (число, 'строка', TRUE, FALSE или NULL)");
    }

    private Long parseLong(String text) {
        try {
            return Long.parseLong(text);
        } catch (NumberFormatException e) {
            throw new DbException("Целое число слишком велико: " + text);
        }
    }

    // ---------- вспомогательные методы ----------

    private Token peek() {
        return tokens.get(pos);
    }

    private boolean isSymbol(String s) {
        Token t = peek();
        return t.type == TokenType.SYMBOL && t.text.equals(s);
    }

    private boolean acceptSymbol(String s) {
        if (isSymbol(s)) {
            pos++;
            return true;
        }
        return false;
    }

    private void expectSymbol(String s) {
        if (!acceptSymbol(s)) {
            throw error("ожидалось '" + s + "'");
        }
    }

    private boolean isKeyword(String k) {
        Token t = peek();
        return t.type == TokenType.IDENT && t.text.equalsIgnoreCase(k);
    }

    private boolean acceptKeyword(String k) {
        if (isKeyword(k)) {
            pos++;
            return true;
        }
        return false;
    }

    private void expectKeyword(String k) {
        if (!acceptKeyword(k)) {
            throw error("ожидалось " + k);
        }
    }

    private String expectIdent(String what) {
        Token t = peek();
        if (t.type != TokenType.IDENT || RESERVED.contains(t.text.toUpperCase(Locale.ROOT))) {
            throw error("ожидалось " + what);
        }
        pos++;
        return t.text;
    }

    private long expectInteger() {
        Token t = peek();
        if (t.type != TokenType.INTEGER) {
            throw error("ожидалось целое число");
        }
        pos++;
        return parseLong(t.text);
    }

    private DbException error(String expected) {
        Token t = peek();
        String found = t.type == TokenType.EOF ? "конец команды" : "'" + t.text + "'";
        return new DbException("Синтаксическая ошибка: " + expected + ", найдено " + found + " (позиция " + (t.pos + 1) + ")");
    }
}
