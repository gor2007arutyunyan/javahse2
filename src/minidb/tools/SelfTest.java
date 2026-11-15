package minidb.tools;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.TreeMap;
import java.util.TreeSet;

import minidb.DbException;
import minidb.engine.Database;
import minidb.engine.QueryResult;
import minidb.index.BPlusTree;
import minidb.storage.FileStorage;

/**
 * Самотесты без внешних библиотек. Запуск: {@code java -cp out minidb.tools.SelfTest}.
 * Код возврата 0 - все проверки пройдены, 1 - есть ошибки.
 */
public final class SelfTest {
    private static int passed;
    private static int failed;

    private SelfTest() {
    }

    /**
     * @param args не используются
     * @throws Exception при непредвиденной ошибке теста
     */
    public static void main(String[] args) throws Exception {
        System.setOut(new java.io.PrintStream(new java.io.FileOutputStream(java.io.FileDescriptor.out), true, "UTF-8"));
        testBPlusTree();
        testParserAndBasics();
        testConstraints();
        testForeignKeys();
        testConditions();
        testIndexMatchesFullScan();
        testPersistence();
        testCrashRecovery();
        System.out.println();
        System.out.println("Итог: пройдено " + passed + ", провалено " + failed);
        System.exit(failed == 0 ? 0 : 1);
    }

    // ---------- B+-дерево ----------

    private static void testBPlusTree() {
        section("B+-дерево (сравнение со стандартным TreeMap на случайных операциях)");
        Random rnd = new Random(42);
        BPlusTree tree = new BPlusTree();
        TreeMap<Long, TreeSet<Long>> ref = new TreeMap<Long, TreeSet<Long>>();
        boolean ok = true;
        for (int step = 0; step < 60000 && ok; step++) {
            long key = rnd.nextInt(3000);
            long id = rnd.nextInt(40);
            if (rnd.nextInt(100) < 65) {
                tree.add(key, id);
                if (!ref.containsKey(key)) {
                    ref.put(key, new TreeSet<Long>());
                }
                ref.get(key).add(id);
            } else {
                boolean removed = tree.remove(key, id);
                TreeSet<Long> s = ref.get(key);
                boolean expected = s != null && s.remove(id);
                if (s != null && s.isEmpty()) {
                    ref.remove(key);
                }
                ok = removed == expected;
            }
            if (step % 5000 == 0) {
                ok = ok && sameContent(tree, ref) && sameRanges(tree, ref, rnd);
            }
        }
        ok = ok && sameContent(tree, ref) && sameRanges(tree, ref, rnd) && tree.keyCount() == ref.size();
        check("60000 случайных add/remove, поиск и диапазоны совпадают с эталоном", ok);
    }

    private static boolean sameContent(BPlusTree tree, TreeMap<Long, TreeSet<Long>> ref) {
        for (long k = -2; k < 3002; k++) {
            TreeSet<Long> s = ref.get(k);
            long[] got = tree.get(k);
            int n = s == null ? 0 : s.size();
            if (got.length != n) {
                return false;
            }
            if (s != null) {
                int i = 0;
                for (long id : s) {
                    if (got[i++] != id) {
                        return false;
                    }
                }
            }
        }
        return true;
    }

    private static boolean sameRanges(BPlusTree tree, TreeMap<Long, TreeSet<Long>> ref, Random rnd) {
        for (int t = 0; t < 40; t++) {
            long a = rnd.nextInt(3100) - 50;
            long b = rnd.nextInt(3100) - 50;
            boolean ai = rnd.nextBoolean();
            boolean bi = rnd.nextBoolean();
            final List<Long> got = new ArrayList<Long>();
            tree.range(a, ai, b, bi, new java.util.function.LongConsumer() {
                @Override
                public void accept(long v) {
                    got.add(v);
                }
            });
            List<Long> want = new ArrayList<Long>();
            for (java.util.Map.Entry<Long, TreeSet<Long>> e : ref.entrySet()) {
                long k = e.getKey();
                boolean lo = ai ? k >= a : k > a;
                boolean hi = bi ? k <= b : k < b;
                if (lo && hi) {
                    want.addAll(e.getValue());
                }
            }
            if (!got.equals(want)) {
                return false;
            }
        }
        return true;
    }

    // ---------- примеры из задания ----------

    private static Database freshDb(Path dir) {
        return new Database(new FileStorage(dir));
    }

    private static void createCars(Database db) {
        db.execute("CREATE TABLE cars (id INT PRIMARY KEY, brand STRING, price DOUBLE, available BOOLEAN)");
    }

    private static void testParserAndBasics() throws IOException {
        section("Запросы из условия лабораторной");
        Path dir = tempDir();
        Database db = freshDb(dir);
        createCars(db);
        db.execute("INSERT INTO cars VALUES (1, 'Toyota', 25000.0, TRUE);");
        db.execute("INSERT INTO cars (id, brand) VALUES (2, 'BMW');");
        check("SELECT * возвращает 2 строки", db.execute("SELECT * FROM cars;").rows().size() == 2);
        QueryResult r = db.execute("SELECT brand, price FROM cars WHERE price > 20000;");
        check("WHERE price > 20000 находит только Toyota (NULL не проходит условие)",
                r.rows().size() == 1 && "Toyota".equals(r.rows().get(0)[0]) && r.columns().size() == 2);
        db.execute("UPDATE cars SET price = 30000 WHERE id = 2;");
        check("UPDATE: целое 30000 приведено к DOUBLE", dump(db, "SELECT price FROM cars WHERE id = 2").equals("30000.0"));
        db.execute("DELETE FROM cars WHERE brand = 'Toyota';");
        check("DELETE удалил запись", dump(db, "SELECT id FROM cars").equals("2"));
        db.execute("INSERT INTO cars VALUES (3,'Audi',41000.5,TRUE),(4,'Skoda',15500,FALSE),(5,'Audi',39000,TRUE)");
        check("INSERT нескольких записей", db.execute("SELECT COUNT(*) FROM cars").rows().get(0)[0].equals(4L));
        check("ORDER BY ... DESC LIMIT", dump(db, "SELECT id FROM cars ORDER BY price DESC LIMIT 2").equals("3|5"));
        check("Регистр ключевых слов не важен", db.execute("select * from CARS where ID = 3").rows().size() == 1);
        check("Строка с кавычкой внутри", insertQuote(db));
        expectError("Неизвестная команда", db, "FOO BAR", "Неизвестная команда");
        expectError("Синтаксическая ошибка: пропущена скобка", db, "INSERT INTO cars VALUES (9, 'x'", "Синтаксическая ошибка");
        expectError("Незакрытая строка", db, "SELECT * FROM cars WHERE brand = 'abc", "не закрыта строковая константа");
        expectError("Неизвестная таблица", db, "SELECT * FROM nothing", "не существует");
        expectError("Неизвестная колонка", db, "SELECT nope FROM cars", "Неизвестная колонка");
        expectError("Неверный тип значения", db, "INSERT INTO cars VALUES (10, 5, 1.0, TRUE)", "нельзя использовать для типа STRING");
        expectError("Неверное число значений", db, "INSERT INTO cars VALUES (10, 'a')", "Ожидалось значений");
        deleteTree(dir);
    }

    private static boolean insertQuote(Database db) {
        db.execute("INSERT INTO cars (id, brand) VALUES (6, 'O''Neil')");
        return dump(db, "SELECT brand FROM cars WHERE id = 6").equals("O'Neil");
    }

    // ---------- ограничения ----------

    private static void testConstraints() throws IOException {
        section("Первичный ключ, NOT NULL, атомарность команд");
        Path dir = tempDir();
        Database db = freshDb(dir);
        createCars(db);
        db.execute("INSERT INTO cars VALUES (1, 'A', 1.0, TRUE)");
        expectError("Дубликат первичного ключа", db, "INSERT INTO cars VALUES (1, 'B', 2.0, FALSE)", "уникальности первичного ключа");
        expectError("Дубликат внутри одной команды", db, "INSERT INTO cars VALUES (7, 'x', 1.0, TRUE), (7, 'y', 2.0, TRUE)", "уникальности");
        check("Команда с ошибкой не оставила частичных изменений", dump(db, "SELECT id FROM cars").equals("1"));
        expectError("NULL в первичный ключ", db, "INSERT INTO cars (brand) VALUES ('no id')", "не допускает NULL");
        db.execute("INSERT INTO cars VALUES (2, 'B', 2.0, TRUE)");
        expectError("UPDATE первичного ключа на существующий", db, "UPDATE cars SET id = 1 WHERE id = 2", "уникальности");
        db.execute("UPDATE cars SET id = 20 WHERE id = 2");
        check("UPDATE первичного ключа на свободное значение", dump(db, "SELECT id FROM cars ORDER BY id").equals("1|20"));
        check("Индекс первичного ключа обновился", db.execute("SELECT * FROM cars WHERE id = 20").plan().startsWith("INDEX SCAN")
                && db.execute("SELECT * FROM cars WHERE id = 2").rows().isEmpty());
        expectError("Один ключ нескольким записям", db, "UPDATE cars SET id = 5", "одно значение первичного ключа");
        db.execute("CREATE TABLE t (id INT PRIMARY KEY, d DATE)");
        expectError("Некорректная дата", db, "INSERT INTO t VALUES (1, '2026-13-45')", "Некорректная дата");
        expectError("Таблица без первичного ключа", db, "CREATE TABLE bad (a INT)", "первичный ключ");
        expectError("Таблица существует", db, "CREATE TABLE t (id INT PRIMARY KEY)", "уже существует");
        deleteTree(dir);
    }

    private static void testForeignKeys() throws IOException {
        section("Связь таблиц по внешнему ключу");
        Path dir = tempDir();
        Database db = freshDb(dir);
        createCars(db);
        db.execute("CREATE TABLE rentals (id INT PRIMARY KEY, car_id INT NOT NULL REFERENCES cars(id), customer STRING, rent_date DATE)");
        db.execute("INSERT INTO cars VALUES (1, 'Toyota', 25000.0, TRUE), (2, 'BMW', 50000.0, TRUE)");
        db.execute("INSERT INTO rentals VALUES (1, 1, 'Иванов', '2026-11-01')");
        expectError("Вставка с несуществующим родителем", db, "INSERT INTO rentals VALUES (2, 99, 'Петров', '2026-11-02')", "внешнего ключа");
        expectError("Удаление родителя, на которого есть ссылка", db, "DELETE FROM cars WHERE id = 1", "ссылается таблица 'rentals'");
        expectError("Изменение ключа родителя со ссылкой", db, "UPDATE cars SET id = 100 WHERE id = 1", "ссылается таблица");
        expectError("Удаление таблицы-родителя", db, "DROP TABLE cars", "ссылается внешний ключ");
        db.execute("DELETE FROM cars WHERE id = 2");
        check("Родитель без детей удаляется", dump(db, "SELECT id FROM cars").equals("1"));
        expectError("Обновление ссылки на несуществующего родителя", db, "UPDATE rentals SET car_id = 77", "внешнего ключа");
        db.execute("DELETE FROM rentals WHERE id = 1");
        db.execute("DELETE FROM cars WHERE id = 1");
        check("После удаления детей родитель удаляется", db.execute("SELECT COUNT(*) FROM cars").rows().get(0)[0].equals(0L));
        db.execute("DROP TABLE rentals");
        db.execute("DROP TABLE cars");
        check("DROP TABLE удаляет файлы с диска", listNames(dir).isEmpty());
        expectError("Ссылка на несуществующую таблицу", db, "CREATE TABLE x (id INT PRIMARY KEY, y INT REFERENCES nope(id))", "не существует");
        deleteTree(dir);
    }

    // ---------- условия ----------

    private static void testConditions() throws IOException {
        section("Условия WHERE: AND/OR/NOT, скобки, NULL, даты, логический тип");
        Path dir = tempDir();
        Database db = freshDb(dir);
        db.execute("CREATE TABLE p (id INT PRIMARY KEY, name STRING, score DOUBLE, born DATE, active BOOLEAN)");
        db.execute("INSERT INTO p VALUES (1,'Аня',4.5,'2000-01-10',TRUE),(2,'Боря',3.0,'1999-05-20',FALSE),"
                + "(3,'Вика',5.0,'2001-07-07',TRUE),(4,'Гоша',NULL,NULL,NULL),(5,'Даша',4.5,'1998-12-31',FALSE)");
        check("AND", dump(db, "SELECT id FROM p WHERE score >= 4.5 AND active = TRUE").equals("1|3"));
        check("OR", dump(db, "SELECT id FROM p WHERE id = 1 OR id = 5").equals("1|5"));
        check("NOT: строки с NULL не проходят (трёхзначная логика SQL)", dump(db, "SELECT id FROM p WHERE NOT (score > 4)").equals("2"));
        check("Скобки, OR и NOT вместе", dump(db, "SELECT id FROM p WHERE (id = 1 OR id = 5) AND NOT active = FALSE").equals("1"));
        check("NULL OR TRUE = TRUE", dump(db, "SELECT id FROM p WHERE score > 4 OR id = 4").equals("1|3|4|5"));
        check("IS NULL", dump(db, "SELECT id FROM p WHERE score IS NULL").equals("4"));
        check("IS NOT NULL", dump(db, "SELECT COUNT(*) FROM p WHERE born IS NOT NULL").equals("4"));
        check("Сравнение дат", dump(db, "SELECT id FROM p WHERE born < '2000-01-01'").equals("2|5"));
        check("Сравнение строк <>", dump(db, "SELECT COUNT(*) FROM p WHERE name <> 'Аня'").equals("4"));
        check("!= как синоним <>", dump(db, "SELECT COUNT(*) FROM p WHERE name != 'Аня'").equals("4"));
        check("Дробная константа для INT-колонки", dump(db, "SELECT id FROM p WHERE id < 2.5").equals("1|2"));
        check("Отрицательные числа", dump(db, "SELECT id FROM p WHERE score > -1.5 AND id <= 2").equals("1|2"));
        check("Русский текст в данных", dump(db, "SELECT name FROM p WHERE name = 'Вика'").equals("Вика"));
        check("UPDATE нескольких записей по условию", db.execute("UPDATE p SET active = TRUE WHERE active = FALSE").message().contains("2"));
        check("Новое значение применилось", dump(db, "SELECT COUNT(*) FROM p WHERE active = TRUE").equals("4"));
        check("UPDATE ... SET NULL", db.execute("UPDATE p SET score = NULL WHERE id = 1").message().contains("1"));
        check("DELETE с составным условием", db.execute("DELETE FROM p WHERE score IS NULL AND id > 1").message().contains("1"));
        check("DELETE без WHERE очищает таблицу", db.execute("DELETE FROM p").message().contains("4"));
        check("Таблица пуста", dump(db, "SELECT COUNT(*) FROM p").equals("0"));
        deleteTree(dir);
    }

    // ---------- индексы ----------

    private static void testIndexMatchesFullScan() throws IOException {
        section("Индексы: результат с индексом совпадает с полным перебором");
        Path dirA = tempDir();
        Path dirB = tempDir();
        Database withIdx = freshDb(dirA);
        Database noIdx = freshDb(dirB);
        String ddl = "CREATE TABLE t (id INT PRIMARY KEY, a INT, b DOUBLE, c STRING, d DATE, e BOOLEAN)";
        withIdx.execute(ddl);
        noIdx.execute(ddl);
        withIdx.setAutoCommit(false);
        noIdx.setAutoCommit(false);
        Random rnd = new Random(7);
        String[] words = {"alpha", "beta", "gamma", "delta", "omega"};
        int nextId = 1;
        for (int i = 0; i < 1500; i++) {
            apply(withIdx, noIdx, insertSql(nextId++, rnd, words));
        }
        withIdx.execute("CREATE INDEX ia ON t (a)");
        withIdx.execute("CREATE INDEX ib ON t (b)");
        withIdx.execute("CREATE INDEX ic ON t (c)");
        withIdx.execute("CREATE INDEX id_ ON t (d)");
        withIdx.execute("CREATE INDEX ie ON t (e)");
        boolean allSame = true;
        boolean usedIndex = false;
        for (int round = 0; round < 6 && allSame; round++) {
            for (int q = 0; q < 150 && allSame; q++) {
                String where = randomWhere(rnd, words);
                String sql = "SELECT * FROM t WHERE " + where;
                QueryResult r1 = withIdx.execute(sql);
                usedIndex |= r1.plan().startsWith("INDEX SCAN");
                if (!dump(withIdx, sql).equals(dump(noIdx, sql))) {
                    System.out.println("   расхождение для: " + where);
                    allSame = false;
                }
            }
            // случайные изменения данных в обеих базах
            for (int m = 0; m < 120; m++) {
                int kind = rnd.nextInt(4);
                if (kind == 0) {
                    apply(withIdx, noIdx, insertSql(nextId++, rnd, words));
                } else if (kind == 1) {
                    apply(withIdx, noIdx, "UPDATE t SET a = " + rnd.nextInt(60) + ", c = '" + words[rnd.nextInt(5)]
                            + "' WHERE " + randomWhere(rnd, words));
                } else if (kind == 2) {
                    apply(withIdx, noIdx, "DELETE FROM t WHERE a = " + rnd.nextInt(60) + " AND b > " + rnd.nextInt(100));
                } else {
                    apply(withIdx, noIdx, "UPDATE t SET b = " + (rnd.nextInt(10000) / 100.0) + " WHERE id = " + (1 + rnd.nextInt(nextId)));
                }
            }
            allSame = allSame && dump(withIdx, "SELECT * FROM t ORDER BY id").equals(dump(noIdx, "SELECT * FROM t ORDER BY id"));
        }
        check("900 случайных запросов + 720 изменений: результаты идентичны", allSame);
        check("Оптимизатор действительно использовал индекс", usedIndex);
        check("Равенство по индексу читает только кандидатов",
                withIdx.execute("SELECT * FROM t WHERE c = 'alpha'").plan().contains("INDEX SCAN: индекс ic (c = 'alpha')"));
        check("Диапазон из двух условий объединяется в один обход",
                withIdx.execute("SELECT * FROM t WHERE b > 10 AND b <= 20").plan().contains("b > 10.0 AND b <= 20.0"));
        check("OR индекс не использует", withIdx.execute("SELECT * FROM t WHERE a = 1 OR a = 2").plan().startsWith("FULL SCAN"));
        check("Без индекса - полный перебор", noIdx.execute("SELECT * FROM t WHERE c = 'alpha'").plan().startsWith("FULL SCAN"));
        expectError("Индекс на ту же колонку", withIdx, "CREATE INDEX another ON t (a)", "уже есть индекс");
        expectError("Дубликат имени индекса", withIdx, "CREATE INDEX ia ON t (b)", "уже существует");
        expectError("Удаление индекса первичного ключа", withIdx, "DROP INDEX pk_t", "удалить нельзя");
        withIdx.execute("DROP INDEX ic");
        check("После DROP INDEX снова полный перебор", withIdx.execute("SELECT * FROM t WHERE c = 'alpha'").plan().startsWith("FULL SCAN"));
        deleteTree(dirA);
        deleteTree(dirB);
    }

    private static String insertSql(int id, Random rnd, String[] words) {
        String b = rnd.nextInt(10) == 0 ? "NULL" : String.valueOf(rnd.nextInt(10000) / 100.0);
        String d = rnd.nextInt(10) == 0 ? "NULL" : "'20" + (10 + rnd.nextInt(15)) + "-0" + (1 + rnd.nextInt(9)) + "-1" + rnd.nextInt(10) + "'";
        String e = rnd.nextInt(8) == 0 ? "NULL" : (rnd.nextBoolean() ? "TRUE" : "FALSE");
        return "INSERT INTO t VALUES (" + id + ", " + rnd.nextInt(60) + ", " + b + ", '" + words[rnd.nextInt(5)] + "', " + d + ", " + e + ")";
    }

    private static String randomWhere(Random rnd, String[] words) {
        String[] ops = {"=", "<", "<=", ">", ">=", "<>"};
        int parts = 1 + rnd.nextInt(3);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < parts; i++) {
            if (i > 0) {
                sb.append(rnd.nextInt(5) == 0 ? " OR " : " AND ");
            }
            String op = ops[rnd.nextInt(ops.length)];
            switch (rnd.nextInt(5)) {
                case 0:
                    sb.append("a ").append(op).append(' ').append(rnd.nextInt(62) - 1);
                    break;
                case 1:
                    sb.append("b ").append(op).append(' ').append(rnd.nextInt(10200) / 100.0 - 1);
                    break;
                case 2:
                    sb.append("c ").append(op).append(" '").append(words[rnd.nextInt(5)]).append("'");
                    break;
                case 3:
                    sb.append("d ").append(op).append(" '20").append(10 + rnd.nextInt(15)).append("-05-15'");
                    break;
                default:
                    sb.append("id ").append(op).append(' ').append(rnd.nextInt(1800));
            }
        }
        return sb.toString();
    }

    private static void apply(Database a, Database b, String sql) {
        String ra = run(a, sql);
        String rb = run(b, sql);
        if (!ra.equals(rb)) {
            throw new IllegalStateException("Базы дали разный результат на: " + sql + " -> " + ra + " / " + rb);
        }
    }

    private static String run(Database db, String sql) {
        try {
            return db.execute(sql).message();
        } catch (DbException e) {
            return "ERR:" + e.getMessage();
        }
    }

    // ---------- хранение на диске ----------

    private static void testPersistence() throws IOException {
        section("Сохранение на диск и загрузка при запуске");
        Path dir = tempDir();
        Database db = freshDb(dir);
        createCars(db);
        db.execute("CREATE TABLE rentals (id INT PRIMARY KEY, car_id INT REFERENCES cars(id), customer STRING, rent_date DATE, days INT)");
        db.execute("INSERT INTO cars VALUES (1,'Toyota',25000.0,TRUE),(2,'BMW',NULL,NULL),(3,'Audi',41000.5,FALSE)");
        db.execute("INSERT INTO rentals VALUES (1, 1, 'Иванов', '2026-11-01', 3), (2, 3, 'Ёжик \"№1\"', NULL, NULL)");
        db.execute("CREATE INDEX idx_cars_brand ON cars (brand)");
        db.execute("CREATE INDEX idx_rent_date ON rentals (rent_date)");
        db.execute("UPDATE cars SET price = 1.5 WHERE id = 2");
        db.execute("DELETE FROM cars WHERE id = 3 AND price < 0");
        String before = dump(db, "SELECT * FROM cars ORDER BY id") + "#" + dump(db, "SELECT * FROM rentals ORDER BY id");
        db.close();

        Database re = freshDb(dir);
        String after = dump(re, "SELECT * FROM cars ORDER BY id") + "#" + dump(re, "SELECT * FROM rentals ORDER BY id");
        check("Данные всех типов (INT, DOUBLE, STRING, DATE, BOOLEAN, NULL) совпадают после перезапуска", before.equals(after));
        check("Перезапуск не потребовал восстановления", re.recoveryNotes().isEmpty());
        check("Индекс загружен с диска и применяется",
                re.execute("SELECT * FROM cars WHERE brand = 'BMW'").plan().contains("INDEX SCAN: индекс idx_cars_brand"));
        check("Индекс по дате загружен", re.execute("SELECT * FROM rentals WHERE rent_date = '2026-11-01'").plan().startsWith("INDEX SCAN"));
        check("Ограничения внешнего ключа действуют после перезапуска", expectErrorQuiet(re, "DELETE FROM cars WHERE id = 1"));
        re.execute("INSERT INTO cars VALUES (4, 'Mazda', 1.0, TRUE)");
        check("Счётчик внутренних id продолжился", re.execute("SELECT * FROM cars WHERE id = 4").rows().size() == 1);
        deleteTree(dir);
    }

    // ---------- восстановление после сбоя ----------

    private static void testCrashRecovery() throws IOException {
        section("Восстановление после некорректного завершения");
        Path dir = tempDir();
        Database db = freshDb(dir);
        createCars(db);
        db.execute("CREATE INDEX idx_b ON cars (brand)");
        db.execute("INSERT INTO cars VALUES (1,'A',1.0,TRUE)");
        byte[] staleIdx = Files.readAllBytes(dir.resolve("idx_b.idx"));
        db.execute("INSERT INTO cars VALUES (2,'B',2.0,TRUE),(3,'B',3.0,FALSE)");
        db.close();

        // 1. устаревший файл индекса (сбой между записью таблицы и индекса)
        Files.write(dir.resolve("idx_b.idx"), staleIdx);
        Database r1 = freshDb(dir);
        check("Устаревший индекс обнаружен и перестроен", r1.recoveryNotes().size() == 1 && r1.recoveryNotes().get(0).contains("устарел"));
        check("После перестроения запрос по индексу корректен",
                r1.execute("SELECT id FROM cars WHERE brand = 'B'").rows().size() == 2
                        && r1.execute("SELECT id FROM cars WHERE brand = 'B'").plan().startsWith("INDEX SCAN"));
        r1.close();
        check("Перестроенный индекс записан на диск - повторный запуск чистый", freshDb(dir).recoveryNotes().isEmpty());

        // 2. недописанный временный файл
        Files.write(dir.resolve("cars.tbl.tmp"), new byte[]{1, 2, 3});
        Database r2 = freshDb(dir);
        check("Недописанный .tmp удалён, данные целы",
                r2.recoveryNotes().size() == 1 && !Files.exists(dir.resolve("cars.tbl.tmp"))
                        && r2.execute("SELECT COUNT(*) FROM cars").rows().get(0)[0].equals(3L));

        // 3. потерянный файл индекса
        Files.delete(dir.resolve("idx_b.idx"));
        Database r3 = freshDb(dir);
        check("Отсутствующий файл индекса восстановлен", r3.recoveryNotes().size() == 1 && Files.exists(dir.resolve("idx_b.idx"))
                && r3.execute("SELECT id FROM cars WHERE brand = 'A'").plan().startsWith("INDEX SCAN"));

        // 4. повреждённый (обрезанный) файл индекса
        byte[] idx = Files.readAllBytes(dir.resolve("idx_b.idx"));
        Files.write(dir.resolve("idx_b.idx"), Arrays.copyOf(idx, idx.length - 7));
        Database r4 = freshDb(dir);
        check("Обрезанный файл индекса распознан и перестроен",
                r4.recoveryNotes().size() == 1 && r4.execute("SELECT id FROM cars WHERE brand = 'B'").rows().size() == 2);

        // 5. повреждённый файл таблицы - данные восстановить нельзя, но программа сообщает об этом понятно
        byte[] tbl = Files.readAllBytes(dir.resolve("cars.tbl"));
        tbl[tbl.length / 2] ^= 0x55;
        Files.write(dir.resolve("cars.tbl"), tbl);
        boolean detected = false;
        try {
            freshDb(dir);
        } catch (DbException e) {
            detected = e.getMessage().contains("cars.tbl") && e.getMessage().contains("контрольная сумма");
        }
        check("Повреждение файла таблицы обнаружено по контрольной сумме", detected);
        deleteTree(dir);
    }

    // ---------- вспомогательные методы ----------

    private static String dump(Database db, String sql) {
        QueryResult r = db.execute(sql);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < r.rows().size(); i++) {
            if (i > 0) {
                sb.append('|');
            }
            Object[] row = r.rows().get(i);
            for (int j = 0; j < row.length; j++) {
                sb.append(j > 0 ? "," : "").append(row[j]);
            }
        }
        return sb.toString();
    }

    private static void expectError(String name, Database db, String sql, String text) {
        try {
            for (minidb.sql.Statement st : minidb.sql.Parser.parse(sql)) {
                db.execute(st);
            }
            check(name + " (ошибка не возникла!)", false);
        } catch (DbException e) {
            boolean ok = e.getMessage().contains(text);
            if (!ok) {
                System.out.println("   сообщение: " + e.getMessage());
            }
            check(name + ": «" + e.getMessage() + "»", ok);
        }
    }

    private static boolean expectErrorQuiet(Database db, String sql) {
        try {
            db.execute(sql);
            return false;
        } catch (DbException e) {
            return true;
        }
    }

    private static void section(String title) {
        System.out.println();
        System.out.println("== " + title);
    }

    private static void check(String name, boolean ok) {
        if (ok) {
            passed++;
            System.out.println("  [ OK ] " + name);
        } else {
            failed++;
            System.out.println("  [FAIL] " + name);
        }
    }

    private static Path tempDir() throws IOException {
        return Files.createTempDirectory("minidb-test");
    }

    private static List<String> listNames(Path dir) throws IOException {
        List<String> names = new ArrayList<String>();
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(dir)) {
            for (Path p : ds) {
                names.add(p.getFileName().toString());
            }
        }
        return names;
    }

    private static void deleteTree(Path dir) throws IOException {
        Files.walkFileTree(dir, new SimpleFileVisitor<Path>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Files.delete(file);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path d, IOException exc) throws IOException {
                Files.delete(d);
                return FileVisitResult.CONTINUE;
            }
        });
    }
}
