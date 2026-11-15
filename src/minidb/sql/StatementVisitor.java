package minidb.sql;

/**
 * Посетитель команд SQL. Реализуется исполнителем запросов.
 *
 * @param <R> тип результата выполнения
 */
public interface StatementVisitor<R> {
    /** @param s команда CREATE TABLE @return результат */
    R visitCreateTable(Statements.CreateTable s);

    /** @param s команда DROP TABLE @return результат */
    R visitDropTable(Statements.DropTable s);

    /** @param s команда CREATE INDEX @return результат */
    R visitCreateIndex(Statements.CreateIndex s);

    /** @param s команда DROP INDEX @return результат */
    R visitDropIndex(Statements.DropIndex s);

    /** @param s команда INSERT @return результат */
    R visitInsert(Statements.Insert s);

    /** @param s команда SELECT @return результат */
    R visitSelect(Statements.Select s);

    /** @param s команда UPDATE @return результат */
    R visitUpdate(Statements.Update s);

    /** @param s команда DELETE @return результат */
    R visitDelete(Statements.Delete s);

    /** @param s команда SHOW TABLES @return результат */
    R visitShowTables(Statements.ShowTables s);

    /** @param s команда SHOW INDEXES @return результат */
    R visitShowIndexes(Statements.ShowIndexes s);

    /** @param s команда DESCRIBE @return результат */
    R visitDescribe(Statements.Describe s);
}
