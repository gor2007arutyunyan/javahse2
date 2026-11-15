package minidb.cli;

import java.io.BufferedReader;
import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

import minidb.DbException;
import minidb.engine.Database;
import minidb.engine.QueryResult;
import minidb.sql.Parser;
import minidb.sql.Statement;
import minidb.storage.FileStorage;

/**
 * Консольный интерфейс мини-СУБД. Запуск: {@code java -cp out minidb.cli.Main [каталог_данных]}.
 * Команды SQL заканчиваются точкой с запятой и могут занимать несколько строк.
 * Служебные команды начинаются с точки: {@code .help}, {@code .read файл.sql}, {@code .plan on|off}, {@code .exit}.
 */
public final class Main {
    private static PrintStream out;
    private static boolean showPlan = true;

    private Main() {
    }

    /**
     * @param args необязательный каталог данных (по умолчанию {@code ./data})
     * @throws IOException при ошибке чтения ввода
     */
    public static void main(String[] args) throws IOException {
        out = new PrintStream(new FileOutputStream(FileDescriptor.out), true, "UTF-8");
        Path dataDir = Paths.get(args.length > 0 ? args[0] : "data");
        Database db;
        try {
            db = new Database(new FileStorage(dataDir));
        } catch (DbException e) {
            out.println("Ошибка: " + e.getMessage());
            return;
        }
        out.println("Мини-СУБД. Каталог данных: " + dataDir.toAbsolutePath());
        for (String note : db.recoveryNotes()) {
            out.println("[восстановление] " + note);
        }
        out.println("Введите SQL-команду (заканчивается ';') или .help для справки.");

        BufferedReader in = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
        StringBuilder buffer = new StringBuilder();
        while (true) {
            out.print(buffer.length() == 0 ? "minidb> " : "   ...> ");
            out.flush();
            String line = in.readLine();
            if (line == null) {
                break;
            }
            String trimmed = line.trim();
            if (buffer.length() == 0 && trimmed.startsWith(".")) {
                if (!meta(db, trimmed)) {
                    break;
                }
                continue;
            }
            if (trimmed.isEmpty() && buffer.length() == 0) {
                continue;
            }
            buffer.append(line).append('\n');
            if (trimmed.endsWith(";")) {
                runScript(db, buffer.toString(), true);
                buffer.setLength(0);
            }
        }
        db.close();
        out.println("До свидания.");
    }

    /** @return false, если нужно завершить работу */
    private static boolean meta(Database db, String line) {
        String[] parts = line.split("\\s+", 2);
        switch (parts[0].toLowerCase()) {
            case ".exit":
            case ".quit":
                return false;
            case ".help":
                printHelp();
                break;
            case ".tables":
                runScript(db, "SHOW TABLES;", false);
                break;
            case ".plan":
                showPlan = parts.length < 2 || !parts[1].trim().equalsIgnoreCase("off");
                out.println("Вывод плана выполнения: " + (showPlan ? "включён" : "выключен"));
                break;
            case ".read":
                if (parts.length < 2) {
                    out.println("Использование: .read файл.sql");
                } else {
                    readFile(db, parts[1].trim());
                }
                break;
            default:
                out.println("Неизвестная служебная команда. Список: .help");
        }
        return true;
    }

    private static void readFile(Database db, String name) {
        String sql;
        try {
            sql = new String(Files.readAllBytes(Paths.get(name)), StandardCharsets.UTF_8);
        } catch (IOException e) {
            out.println("Не удалось прочитать файл '" + name + "': " + e.getMessage());
            return;
        }
        boolean old = db.isAutoCommit();
        db.setAutoCommit(false);
        try {
            runScript(db, sql, true);
        } finally {
            db.setAutoCommit(old);
            try {
                db.commit();
            } catch (DbException e) {
                out.println("Ошибка: " + e.getMessage());
            }
        }
    }

    private static void runScript(Database db, String sql, boolean echo) {
        List<Statement> statements;
        try {
            statements = Parser.parse(sql);
        } catch (DbException e) {
            out.println("Ошибка: " + e.getMessage());
            return;
        }
        int number = 0;
        for (Statement st : statements) {
            number++;
            try {
                print(db.execute(st));
            } catch (DbException e) {
                out.println("Ошибка" + (statements.size() > 1 ? " в команде №" + number : "") + ": " + e.getMessage());
                return;
            }
        }
    }

    private static void print(QueryResult r) {
        if (r.hasTable()) {
            out.println(TableFormatter.format(r));
        } else {
            out.println(r.message());
        }
        if (showPlan && r.plan() != null) {
            out.println("План: " + r.plan());
        }
    }

    private static void printHelp() {
        out.println("Команды SQL (заканчиваются ';'):");
        out.println("  CREATE TABLE t (id INT PRIMARY KEY, name STRING NOT NULL, d DATE, ok BOOLEAN, p DOUBLE,");
        out.println("                  ref INT REFERENCES другая_таблица(id));");
        out.println("  DROP TABLE t;");
        out.println("  CREATE INDEX idx ON t (колонка);   DROP INDEX idx;");
        out.println("  INSERT INTO t VALUES (1, 'a', '2024-05-01', TRUE, 1.5, NULL), (2, ...);");
        out.println("  INSERT INTO t (id, name) VALUES (3, 'b');");
        out.println("  SELECT * | кол1, кол2 | COUNT(*) FROM t [WHERE условие] [ORDER BY кол [DESC]] [LIMIT n];");
        out.println("  UPDATE t SET кол = значение [, ...] [WHERE условие];");
        out.println("  DELETE FROM t [WHERE условие];");
        out.println("  SHOW TABLES;  SHOW INDEXES;  DESCRIBE t;");
        out.println("Условия: =, <>, !=, <, <=, >, >=, IS [NOT] NULL, AND, OR, NOT, скобки.");
        out.println("Типы: INT, DOUBLE, STRING, DATE ('ГГГГ-ММ-ДД'), BOOLEAN (TRUE/FALSE).");
        out.println("Служебные: .help  .tables  .read файл.sql  .plan on|off  .exit");
    }
}
