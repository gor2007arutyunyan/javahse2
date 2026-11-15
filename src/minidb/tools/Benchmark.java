package minidb.tools;

import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.DirectoryStream;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Random;

import minidb.engine.Database;
import minidb.engine.QueryResult;
import minidb.storage.FileStorage;

/**
 * Замер производительности SELECT с индексом и без. Запуск:
 * {@code java -cp out minidb.tools.Benchmark [размеры таблицы через пробел]} (по умолчанию 10000 50000 200000).
 * Результат печатается в формате Markdown, чтобы его можно было вставить в README.
 */
public final class Benchmark {
    private static PrintStream out;

    private Benchmark() {
    }

    /**
     * @param args необязательные размеры таблицы
     * @throws Exception при ошибке ввода-вывода
     */
    public static void main(String[] args) throws Exception {
        out = new PrintStream(new FileOutputStream(FileDescriptor.out), true, "UTF-8");
        int[] sizes = {10000, 50000, 200000};
        if (args.length > 0) {
            sizes = new int[args.length];
            for (int i = 0; i < args.length; i++) {
                sizes[i] = Integer.parseInt(args[i]);
            }
        }
        out.println("Среда: Java " + System.getProperty("java.version") + ", " + System.getProperty("os.name") + " "
                + System.getProperty("os.arch") + ", процессоров: " + Runtime.getRuntime().availableProcessors());
        out.println();
        runSize(20000, false); // прогрев JIT-компилятора, результаты не выводятся
        for (int n : sizes) {
            runSize(n, true);
        }
    }

    private static void runSize(int n, boolean report) throws Exception {
        Path dir = Files.createTempDirectory("minidb-bench");
        try {
            Database db = new Database(new FileStorage(dir));
            db.setAutoCommit(false);
            db.execute("CREATE TABLE bench (id INT PRIMARY KEY, code INT, brand STRING, price DOUBLE, "
                    + "available BOOLEAN, created DATE)");
            long t0 = System.nanoTime();
            load(db, n);
            double loadMs = (System.nanoTime() - t0) / 1e6;

            String[][] queries = {
                    {"code = const (1 запись, уникальное значение)", "SELECT * FROM bench WHERE code = " + (n / 2),
                            "CREATE INDEX idx_code ON bench (code)"},
                    {"brand = const (~0,1% записей)", "SELECT * FROM bench WHERE brand = 'brand_500'",
                            "CREATE INDEX idx_brand ON bench (brand)"},
                    {"price >= 99000 (диапазон, ~1% записей)", "SELECT COUNT(*) FROM bench WHERE price >= 99000",
                            "CREATE INDEX idx_price ON bench (price)"},
                    {"created между двумя датами (~1% записей)",
                            "SELECT COUNT(*) FROM bench WHERE created >= '2020-06-01' AND created < '2020-07-07'",
                            "CREATE INDEX idx_created ON bench (created)"},
                    {"available = TRUE (~50% записей)", "SELECT COUNT(*) FROM bench WHERE available = TRUE",
                            "CREATE INDEX idx_available ON bench (available)"},
            };
            int q = queries.length;
            double[] full = new double[q];
            long[] sizeOfAnswer = new long[q];
            for (int i = 0; i < q; i++) {
                QueryResult r = db.execute(queries[i][1]);
                if (!r.plan().startsWith("FULL SCAN")) {
                    throw new IllegalStateException("ожидался полный перебор: " + r.plan());
                }
                sizeOfAnswer[i] = answerSize(r);
                full[i] = avgMs(db, queries[i][1]);
            }
            double[] build = new double[q];
            for (int i = 0; i < q; i++) {
                long b0 = System.nanoTime();
                db.execute(queries[i][2]);
                build[i] = (System.nanoTime() - b0) / 1e6;
            }
            double[] indexed = new double[q];
            for (int i = 0; i < q; i++) {
                QueryResult r = db.execute(queries[i][1]);
                if (!r.plan().startsWith("INDEX SCAN")) {
                    throw new IllegalStateException("ожидалось использование индекса: " + r.plan());
                }
                if (answerSize(r) != sizeOfAnswer[i]) {
                    throw new IllegalStateException("результат с индексом отличается от полного перебора");
                }
                indexed[i] = avgMs(db, queries[i][1]);
            }
            if (!report) {
                return;
            }
            db.commit();
            out.println("### Таблица из " + n + " записей");
            out.println();
            out.println("Загрузка данных через INSERT: " + fmt(loadMs) + " мс.");
            out.println();
            out.println("| Запрос | Строк в ответе | Без индекса, мс | С индексом, мс | Ускорение | Построение индекса, мс |");
            out.println("|---|---:|---:|---:|---:|---:|");
            for (int i = 0; i < q; i++) {
                out.println("| " + queries[i][0] + " | " + sizeOfAnswer[i] + " | " + fmt(full[i]) + " | " + fmt(indexed[i])
                        + " | " + String.format(Locale.US, "%.1f", full[i] / indexed[i]) + "x | " + fmt(build[i]) + " |");
            }
            out.println();
            long tbl = 0;
            long idx = 0;
            try (DirectoryStream<Path> ds = Files.newDirectoryStream(dir)) {
                for (Path p : ds) {
                    long len = Files.size(p);
                    if (p.toString().endsWith(".tbl")) {
                        tbl += len;
                    } else if (p.toString().endsWith(".idx")) {
                        idx += len;
                    }
                }
            }
            long o0 = System.nanoTime();
            Database reopened = new Database(new FileStorage(dir));
            double openMs = (System.nanoTime() - o0) / 1e6;
            boolean ok = reopened.recoveryNotes().isEmpty()
                    && reopened.execute("SELECT * FROM bench WHERE code = " + (n / 2)).plan().startsWith("INDEX SCAN");
            out.println("Файл таблицы: " + tbl / 1024 + " КБ, файлы индексов (6 шт. вместе с индексом первичного ключа): "
                    + idx / 1024 + " КБ. Загрузка таблицы и всех индексов с диска при запуске: " + fmt(openMs)
                    + " мс (индексы восстановлены с диска без перестроения: " + (ok ? "да" : "нет") + ").");
            out.println();
        } finally {
            deleteTree(dir);
        }
    }

    private static void load(Database db, int n) {
        Random rnd = new Random(12345);
        LocalDate base = LocalDate.of(2015, 1, 1);
        int batch = 1000;
        for (int start = 1; start <= n; start += batch) {
            StringBuilder sb = new StringBuilder("INSERT INTO bench VALUES ");
            int end = Math.min(n, start + batch - 1);
            for (int id = start; id <= end; id++) {
                if (id > start) {
                    sb.append(", ");
                }
                // колонки: id, code, brand, price, available, created
                sb.append('(').append(id).append(", ").append(id).append(", 'brand_").append(rnd.nextInt(1000)).append("', ")
                        .append(rnd.nextInt(10000000) / 100.0).append(", ").append(rnd.nextBoolean() ? "TRUE" : "FALSE")
                        .append(", '").append(base.plusDays(rnd.nextInt(3650))).append("')");
            }
            db.execute(sb.toString());
        }
    }

    private static long answerSize(QueryResult r) {
        if (r.columns().size() == 1 && r.columns().get(0).equals("COUNT(*)")) {
            return (Long) r.rows().get(0)[0];
        }
        return r.rows().size();
    }

    private static double avgMs(Database db, String sql) {
        for (int i = 0; i < 3; i++) {
            db.execute(sql);
        }
        long start = System.nanoTime();
        int runs = 0;
        long elapsed;
        do {
            db.execute(sql);
            runs++;
            elapsed = System.nanoTime() - start;
        } while ((elapsed < 400000000L || runs < 5) && runs < 20000);
        return elapsed / 1e6 / runs;
    }

    private static String fmt(double ms) {
        return ms >= 100 ? String.format(Locale.US, "%.0f", ms) : String.format(Locale.US, "%.3f", ms);
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
