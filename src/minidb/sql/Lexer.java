package minidb.sql;

import java.util.ArrayList;
import java.util.List;

import minidb.DbException;

/**
 * Лексический анализатор: разбивает текст запроса на лексемы.
 * Поддерживаются комментарии {@code -- до конца строки} и экранирование кавычки как {@code ''}.
 */
public final class Lexer {
    private Lexer() {
    }

    /**
     * @param src текст запроса или скрипта
     * @return список лексем, оканчивающийся лексемой EOF
     * @throws DbException при недопустимом символе или незакрытой строке
     */
    public static List<Token> tokenize(String src) {
        List<Token> out = new ArrayList<Token>();
        int i = 0;
        int n = src.length();
        while (i < n) {
            char c = src.charAt(i);
            if (Character.isWhitespace(c)) {
                i++;
                continue;
            }
            if (c == '-' && i + 1 < n && src.charAt(i + 1) == '-') {
                while (i < n && src.charAt(i) != '\n') {
                    i++;
                }
                continue;
            }
            int start = i;
            if (isIdentStart(c)) {
                while (i < n && isIdentPart(src.charAt(i))) {
                    i++;
                }
                out.add(new Token(TokenType.IDENT, src.substring(start, i), start));
            } else if (isDigit(c)) {
                while (i < n && isDigit(src.charAt(i))) {
                    i++;
                }
                boolean decimal = false;
                if (i + 1 < n && src.charAt(i) == '.' && isDigit(src.charAt(i + 1))) {
                    decimal = true;
                    i++;
                    while (i < n && isDigit(src.charAt(i))) {
                        i++;
                    }
                }
                out.add(new Token(decimal ? TokenType.DECIMAL : TokenType.INTEGER, src.substring(start, i), start));
            } else if (c == '\'') {
                StringBuilder sb = new StringBuilder();
                i++;
                boolean closed = false;
                while (i < n) {
                    char ch = src.charAt(i);
                    if (ch == '\'') {
                        if (i + 1 < n && src.charAt(i + 1) == '\'') {
                            sb.append('\'');
                            i += 2;
                            continue;
                        }
                        i++;
                        closed = true;
                        break;
                    }
                    sb.append(ch);
                    i++;
                }
                if (!closed) {
                    throw new DbException("Синтаксическая ошибка: не закрыта строковая константа (позиция " + (start + 1) + ")");
                }
                out.add(new Token(TokenType.STRING, sb.toString(), start));
            } else {
                String two = i + 1 < n ? src.substring(i, i + 2) : "";
                if (two.equals("<=") || two.equals(">=") || two.equals("<>")) {
                    out.add(new Token(TokenType.SYMBOL, two, start));
                    i += 2;
                } else if (two.equals("!=")) {
                    out.add(new Token(TokenType.SYMBOL, "<>", start));
                    i += 2;
                } else if ("(),;*=<>.-".indexOf(c) >= 0) {
                    out.add(new Token(TokenType.SYMBOL, String.valueOf(c), start));
                    i++;
                } else {
                    throw new DbException("Синтаксическая ошибка: недопустимый символ '" + c + "' (позиция " + (start + 1) + ")");
                }
            }
        }
        out.add(new Token(TokenType.EOF, "", n));
        return out;
    }

    private static boolean isDigit(char c) {
        return c >= '0' && c <= '9';
    }

    private static boolean isIdentStart(char c) {
        return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || c == '_';
    }

    private static boolean isIdentPart(char c) {
        return isIdentStart(c) || isDigit(c);
    }
}
