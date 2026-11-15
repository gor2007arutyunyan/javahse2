package minidb.sql;

/** Лексема: вид, текст и позиция в исходном запросе. */
public final class Token {
    final TokenType type;
    final String text;
    final int pos;

    Token(TokenType type, String text, int pos) {
        this.type = type;
        this.text = text;
        this.pos = pos;
    }
}
