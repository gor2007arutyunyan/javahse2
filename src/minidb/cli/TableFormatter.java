package minidb.cli;

import java.util.ArrayList;
import java.util.List;

import minidb.engine.QueryResult;
import minidb.model.Values;

/**
 * Форматирует результат запроса в виде текстовой таблицы для вывода в консоль.
 */
public final class TableFormatter {
    private TableFormatter() {
    }

    /**
     * @param result результат с таблицей значений
     * @return многострочный текст: рамка, заголовок, строки и итог «Строк: N»
     */
    public static String format(QueryResult result) {
        List<String> cols = result.columns();
        int n = cols.size();
        int[] width = new int[n];
        for (int i = 0; i < n; i++) {
            width[i] = cols.get(i).length();
        }
        List<String[]> cells = new ArrayList<String[]>();
        for (Object[] row : result.rows()) {
            String[] line = new String[n];
            for (int i = 0; i < n; i++) {
                line[i] = Values.format(row[i]);
                width[i] = Math.max(width[i], line[i].length());
            }
            cells.add(line);
        }
        StringBuilder sb = new StringBuilder();
        String sep = separator(width);
        sb.append(sep);
        sb.append(line(cols.toArray(new String[0]), width, null));
        sb.append(sep);
        for (int r = 0; r < cells.size(); r++) {
            sb.append(line(cells.get(r), width, result.rows().get(r)));
        }
        sb.append(sep);
        sb.append("Строк: ").append(cells.size());
        return sb.toString();
    }

    private static String separator(int[] width) {
        StringBuilder sb = new StringBuilder("+");
        for (int w : width) {
            for (int i = 0; i < w + 2; i++) {
                sb.append('-');
            }
            sb.append('+');
        }
        return sb.append('\n').toString();
    }

    private static String line(String[] cells, int[] width, Object[] raw) {
        StringBuilder sb = new StringBuilder("|");
        for (int i = 0; i < cells.length; i++) {
            boolean right = raw != null && raw[i] instanceof Number;
            sb.append(' ');
            sb.append(pad(cells[i], width[i], right));
            sb.append(" |");
        }
        return sb.append('\n').toString();
    }

    private static String pad(String s, int width, boolean right) {
        StringBuilder spaces = new StringBuilder();
        for (int i = s.length(); i < width; i++) {
            spaces.append(' ');
        }
        return right ? spaces + s : s + spaces;
    }
}
