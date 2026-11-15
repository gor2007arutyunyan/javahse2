package minidb.storage;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.zip.CRC32;

import minidb.engine.Storage;
import minidb.engine.Table;
import minidb.index.Index;
import minidb.model.Column;
import minidb.model.ColumnType;
import minidb.model.ForeignKey;
import minidb.model.Row;
import minidb.model.TableSchema;

/**
 * Файловое хранилище. В каталоге данных лежат файлы двух видов:
 * <ul>
 * <li>{@code <таблица>.tbl} - схема, определения индексов и все строки таблицы;</li>
 * <li>{@code <индекс>.idx} - содержимое индекса (отсортированные ключи со списками идентификаторов строк).</li>
 * </ul>
 * Формат двоичный (DataOutputStream), строки в UTF-8. В конце каждого файла - контрольная сумма CRC32.
 * <p>
 * Надёжность: файл записывается во временный {@code *.tmp}, сбрасывается на диск (fsync) и затем
 * атомарно переименовывается, поэтому при сбое питания/процесса остаётся целая старая или новая версия.
 * Файл индекса содержит версию таблицы, с которой он согласован; при несовпадении версии, повреждении
 * или отсутствии файла индекс автоматически перестраивается из данных таблицы.
 */
public final class FileStorage implements Storage {
    private static final int TABLE_MAGIC = 0x4D444254; // "MDBT"
    private static final int INDEX_MAGIC = 0x4D444249; // "MDBI"
    private static final short FORMAT_VERSION = 1;

    private final Path dir;

    /**
     * @param dir каталог данных (создаётся при необходимости)
     */
    public FileStorage(Path dir) {
        this.dir = dir;
    }

    // ---------- загрузка ----------

    @Override
    public LoadResult load() throws IOException {
        Files.createDirectories(dir);
        List<String> notes = new ArrayList<String>();
        for (Path p : list("*.tmp")) {
            Files.deleteIfExists(p);
            notes.add("Удалён недописанный файл " + p.getFileName() + " (предыдущий сеанс завершился некорректно, "
                    + "прежняя версия данных сохранена)");
        }
        List<Table> tables = new ArrayList<Table>();
        for (Path p : list("*.tbl")) {
            tables.add(readTable(p, notes));
        }
        return new LoadResult(tables, notes);
    }

    private List<Path> list(String glob) throws IOException {
        List<Path> result = new ArrayList<Path>();
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(dir, glob)) {
            for (Path p : ds) {
                result.add(p);
            }
        }
        java.util.Collections.sort(result);
        return result;
    }

    private Table readTable(Path file, List<String> notes) throws IOException {
        try {
            DataInputStream in = openChecked(Files.readAllBytes(file), TABLE_MAGIC, file);
            String name = readString(in);
            int colCount = in.readInt();
            if (colCount <= 0 || colCount > 10000) {
                throw new IOException("некорректное число колонок");
            }
            List<Column> cols = new ArrayList<Column>();
            for (int i = 0; i < colCount; i++) {
                String cname = readString(in);
                int typeOrd = in.readUnsignedByte();
                if (typeOrd >= ColumnType.values().length) {
                    throw new IOException("неизвестный тип колонки");
                }
                int flags = in.readUnsignedByte();
                ForeignKey fk = null;
                if (in.readBoolean()) {
                    fk = new ForeignKey(readString(in), readString(in));
                }
                cols.add(new Column(cname, ColumnType.values()[typeOrd], (flags & 1) != 0, (flags & 2) != 0, fk));
            }
            TableSchema schema = new TableSchema(name, cols);
            long nextRowId = in.readLong();
            long version = in.readLong();
            int indexCount = in.readInt();
            List<String[]> defs = new ArrayList<String[]>();
            for (int i = 0; i < indexCount; i++) {
                defs.add(new String[]{readString(in), readString(in)});
            }
            Table table = Table.restore(schema, nextRowId, version);
            int rowCount = in.readInt();
            for (int r = 0; r < rowCount; r++) {
                long id = in.readLong();
                Object[] values = new Object[colCount];
                for (int c = 0; c < colCount; c++) {
                    values[c] = in.readBoolean() ? readValue(in, cols.get(c).type()) : null;
                }
                table.restoreRow(new Row(id, values));
            }
            boolean hasPk = false;
            for (String[] def : defs) {
                int ci = schema.indexOf(def[1]);
                if (ci < 0) {
                    throw new IOException("индекс '" + def[0] + "' ссылается на несуществующую колонку");
                }
                boolean system = ci == schema.primaryKeyIndex() && def[0].equalsIgnoreCase(Table.PK_INDEX_PREFIX + name);
                hasPk |= system;
                Index ix = new Index(def[0], name, schema.column(ci).name(), ci, schema.column(ci).type(), system);
                table.attachIndex(ix);
                String reason = loadIndexData(ix, table);
                if (reason != null) {
                    table.rebuildIndex(ix);
                    table.markDirty();
                    notes.add("Индекс '" + ix.name() + "' таблицы '" + name + "' перестроен из данных таблицы (" + reason + ")");
                }
            }
            if (!hasPk) {
                Index pk = table.newPrimaryKeyIndex();
                table.attachIndex(pk);
                table.rebuildIndex(pk);
                table.markDirty();
                notes.add("Индекс первичного ключа таблицы '" + name + "' отсутствовал и был создан заново");
            }
            return table;
        } catch (IOException e) {
            throw new IOException("Файл " + file.getFileName() + " повреждён или нечитаем: " + e.getMessage(), e);
        } catch (RuntimeException e) {
            throw new IOException("Файл " + file.getFileName() + " содержит некорректные данные: " + e.getMessage(), e);
        }
    }

    /**
     * Пытается загрузить содержимое индекса из файла.
     *
     * @return null при успехе, иначе причина, по которой индекс нужно перестроить
     */
    private String loadIndexData(Index ix, Table table) {
        Path file = dir.resolve(fileBase(ix.name()) + ".idx");
        if (!Files.exists(file)) {
            return "файл индекса отсутствует";
        }
        try {
            DataInputStream in = openChecked(Files.readAllBytes(file), INDEX_MAGIC, file);
            String name = readString(in);
            String tableName = readString(in);
            String column = readString(in);
            long version = in.readLong();
            if (!name.equalsIgnoreCase(ix.name()) || !tableName.equalsIgnoreCase(table.name())
                    || !column.equalsIgnoreCase(ix.columnName())) {
                ix.clear();
                return "файл индекса не соответствует определению";
            }
            if (version != table.version()) {
                return "файл индекса устарел: версия " + version + ", версия таблицы " + table.version();
            }
            int keys = in.readInt();
            if (keys < 0) {
                throw new IOException("отрицательное число ключей");
            }
            long total = 0;
            for (int k = 0; k < keys; k++) {
                Object key = readValue(in, ix.type());
                int n = in.readInt();
                if (n <= 0) {
                    throw new IOException("пустой список строк");
                }
                for (int j = 0; j < n; j++) {
                    ix.add(key, in.readLong());
                    total++;
                }
            }
            long expected = 0;
            for (Row r : table) {
                if (r.get(ix.columnIndex()) != null) {
                    expected++;
                }
            }
            if (total != expected) {
                ix.clear();
                return "число записей в индексе не совпадает с таблицей";
            }
            return null;
        } catch (IOException e) {
            ix.clear();
            return "файл индекса повреждён: " + e.getMessage();
        } catch (RuntimeException e) {
            ix.clear();
            return "файл индекса содержит некорректные данные";
        }
    }

    // ---------- сохранение ----------

    @Override
    public void save(Collection<Table> tables) throws IOException {
        Files.createDirectories(dir);
        Set<String> tableFiles = new HashSet<String>();
        Set<String> indexFiles = new HashSet<String>();
        for (Table t : tables) {
            tableFiles.add(fileBase(t.name()) + ".tbl");
            for (Index ix : t.indexes()) {
                indexFiles.add(fileBase(ix.name()) + ".idx");
            }
            if (t.isDirty()) {
                // сначала таблица (содержит определения индексов и версию), затем файлы индексов
                writeAtomic(dir.resolve(fileBase(t.name()) + ".tbl"), encodeTable(t));
                for (Index ix : t.indexes()) {
                    writeAtomic(dir.resolve(fileBase(ix.name()) + ".idx"), encodeIndex(t, ix));
                }
                t.markClean();
            }
        }
        removeOrphans("*.tbl", tableFiles);
        removeOrphans("*.idx", indexFiles);
    }

    private void removeOrphans(String glob, Set<String> keep) throws IOException {
        for (Path p : list(glob)) {
            if (!keep.contains(p.getFileName().toString())) {
                Files.deleteIfExists(p);
            }
        }
    }

    private byte[] encodeTable(Table t) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(bos);
        out.writeInt(TABLE_MAGIC);
        out.writeShort(FORMAT_VERSION);
        TableSchema s = t.schema();
        writeString(out, s.name());
        out.writeInt(s.columnCount());
        for (Column c : s.columns()) {
            writeString(out, c.name());
            out.writeByte(c.type().ordinal());
            out.writeByte((c.isPrimaryKey() ? 1 : 0) | (c.isNotNull() ? 2 : 0));
            ForeignKey fk = c.foreignKey();
            out.writeBoolean(fk != null);
            if (fk != null) {
                writeString(out, fk.refTable());
                writeString(out, fk.refColumn());
            }
        }
        out.writeLong(t.nextRowId());
        out.writeLong(t.version());
        out.writeInt(t.indexes().size());
        for (Index ix : t.indexes()) {
            writeString(out, ix.name());
            writeString(out, ix.columnName());
        }
        out.writeInt(t.size());
        for (Row r : t) {
            out.writeLong(r.id());
            for (int c = 0; c < s.columnCount(); c++) {
                Object v = r.get(c);
                out.writeBoolean(v != null);
                if (v != null) {
                    writeValue(out, s.column(c).type(), v);
                }
            }
        }
        return withChecksum(bos);
    }

    private byte[] encodeIndex(Table t, final Index ix) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        final DataOutputStream out = new DataOutputStream(bos);
        out.writeInt(INDEX_MAGIC);
        out.writeShort(FORMAT_VERSION);
        writeString(out, ix.name());
        writeString(out, t.name());
        writeString(out, ix.columnName());
        out.writeLong(t.version());
        out.writeInt(ix.keyCount());
        final IOException[] failure = new IOException[1];
        ix.forEach(new minidb.index.BPlusTree.EntryVisitor() {
            @Override
            public void visit(Object key, long[] rowIds) {
                try {
                    writeValue(out, ix.type(), key);
                    out.writeInt(rowIds.length);
                    for (long id : rowIds) {
                        out.writeLong(id);
                    }
                } catch (IOException e) {
                    failure[0] = e;
                }
            }
        });
        if (failure[0] != null) {
            throw failure[0];
        }
        return withChecksum(bos);
    }

    private static byte[] withChecksum(ByteArrayOutputStream bos) throws IOException {
        byte[] body = bos.toByteArray();
        CRC32 crc = new CRC32();
        crc.update(body, 0, body.length);
        ByteArrayOutputStream result = new ByteArrayOutputStream(body.length + 4);
        result.write(body);
        new DataOutputStream(result).writeInt((int) crc.getValue());
        return result.toByteArray();
    }

    /** Атомарная запись: временный файл + fsync + переименование. */
    private void writeAtomic(Path target, byte[] data) throws IOException {
        Path tmp = target.resolveSibling(target.getFileName().toString() + ".tmp");
        FileOutputStream fos = new FileOutputStream(tmp.toFile());
        try {
            fos.write(data);
            fos.flush();
            fos.getFD().sync();
        } finally {
            fos.close();
        }
        try {
            Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    // ---------- кодирование значений ----------

    private static String fileBase(String name) {
        return TableSchema.normalize(name);
    }

    private static DataInputStream openChecked(byte[] data, int magic, Path file) throws IOException {
        if (data.length < 10) {
            throw new IOException("файл слишком короткий");
        }
        int bodyLen = data.length - 4;
        CRC32 crc = new CRC32();
        crc.update(data, 0, bodyLen);
        int stored = new DataInputStream(new ByteArrayInputStream(data, bodyLen, 4)).readInt();
        if (stored != (int) crc.getValue()) {
            throw new IOException("контрольная сумма не совпадает");
        }
        DataInputStream in = new DataInputStream(new ByteArrayInputStream(Arrays.copyOf(data, bodyLen)));
        if (in.readInt() != magic) {
            throw new IOException("неверный тип файла");
        }
        if (in.readShort() != FORMAT_VERSION) {
            throw new IOException("неподдерживаемая версия формата");
        }
        return in;
    }

    private static void writeString(DataOutputStream out, String s) throws IOException {
        byte[] b = s.getBytes(StandardCharsets.UTF_8);
        out.writeInt(b.length);
        out.write(b);
    }

    private static String readString(DataInputStream in) throws IOException {
        int len = in.readInt();
        if (len < 0 || len > in.available()) {
            throw new IOException("некорректная длина строки");
        }
        byte[] b = new byte[len];
        in.readFully(b);
        return new String(b, StandardCharsets.UTF_8);
    }

    private static void writeValue(DataOutputStream out, ColumnType type, Object v) throws IOException {
        switch (type) {
            case INT:
                out.writeLong((Long) v);
                break;
            case DOUBLE:
                out.writeDouble((Double) v);
                break;
            case STRING:
                writeString(out, (String) v);
                break;
            case DATE:
                out.writeLong(((LocalDate) v).toEpochDay());
                break;
            case BOOLEAN:
                out.writeBoolean((Boolean) v);
                break;
            default:
                throw new IOException("неизвестный тип");
        }
    }

    private static Object readValue(DataInputStream in, ColumnType type) throws IOException {
        switch (type) {
            case INT:
                return in.readLong();
            case DOUBLE:
                return in.readDouble();
            case STRING:
                return readString(in);
            case DATE:
                return LocalDate.ofEpochDay(in.readLong());
            case BOOLEAN:
                return in.readBoolean();
            default:
                throw new IOException("неизвестный тип");
        }
    }
}
