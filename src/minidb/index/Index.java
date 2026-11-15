package minidb.index;

import java.util.Arrays;
import java.util.function.LongConsumer;

import minidb.model.ColumnType;

/**
 * Индекс по одной колонке таблицы. Хранит значения колонки в {@link BPlusTree};
 * значения NULL не индексируются. Индекс сохраняется на диск вместе с таблицей и
 * восстанавливается при запуске.
 */
public final class Index {
    private static final long[] EMPTY = new long[0];

    private final String name;
    private final String tableName;
    private final String columnName;
    private final int columnIndex;
    private final ColumnType type;
    private final boolean system;
    private BPlusTree tree = new BPlusTree();

    /**
     * @param name        имя индекса
     * @param tableName   имя таблицы
     * @param columnName  имя индексируемой колонки
     * @param columnIndex номер колонки в таблице
     * @param type        тип колонки
     * @param system      true для служебного индекса первичного ключа (его нельзя удалить)
     */
    public Index(String name, String tableName, String columnName, int columnIndex, ColumnType type, boolean system) {
        this.name = name;
        this.tableName = tableName;
        this.columnName = columnName;
        this.columnIndex = columnIndex;
        this.type = type;
        this.system = system;
    }

    /** @return имя индекса */
    public String name() {
        return name;
    }

    /** @return имя таблицы */
    public String tableName() {
        return tableName;
    }

    /** @return имя индексируемой колонки */
    public String columnName() {
        return columnName;
    }

    /** @return номер индексируемой колонки */
    public int columnIndex() {
        return columnIndex;
    }

    /** @return тип индексируемой колонки */
    public ColumnType type() {
        return type;
    }

    /** @return true для индекса первичного ключа */
    public boolean isSystem() {
        return system;
    }

    /** @return число различных ключей */
    public int keyCount() {
        return tree.keyCount();
    }

    /**
     * Добавляет значение в индекс (null игнорируется).
     *
     * @param key   значение колонки
     * @param rowId идентификатор строки
     */
    public void add(Object key, long rowId) {
        if (key != null) {
            tree.add(key, rowId);
        }
    }

    /**
     * Убирает значение из индекса (null игнорируется).
     *
     * @param key   значение колонки
     * @param rowId идентификатор строки
     */
    public void remove(Object key, long rowId) {
        if (key != null) {
            tree.remove(key, rowId);
        }
    }

    /**
     * Точный поиск.
     *
     * @param key значение
     * @return отсортированные идентификаторы строк
     */
    public long[] lookup(Object key) {
        return key == null ? EMPTY : tree.get(key);
    }

    /**
     * Поиск по диапазону.
     *
     * @param lo    нижняя граница или null
     * @param loInc включать нижнюю границу
     * @param hi    верхняя граница или null
     * @param hiInc включать верхнюю границу
     * @return идентификаторы строк, отсортированные по возрастанию (то есть в порядке вставки)
     */
    public long[] range(Object lo, boolean loInc, Object hi, boolean hiInc) {
        LongBuf buf = new LongBuf();
        tree.range(lo, loInc, hi, hiInc, buf);
        long[] r = Arrays.copyOf(buf.data, buf.size);
        Arrays.sort(r);
        return r;
    }

    /**
     * Обход всех записей индекса по возрастанию ключа.
     *
     * @param visitor получатель
     */
    public void forEach(BPlusTree.EntryVisitor visitor) {
        tree.forEach(visitor);
    }

    /** Очищает индекс (перед перестроением). */
    public void clear() {
        tree = new BPlusTree();
    }

    private static final class LongBuf implements LongConsumer {
        long[] data = new long[16];
        int size;

        @Override
        public void accept(long v) {
            if (size == data.length) {
                data = Arrays.copyOf(data, size * 2);
            }
            data[size++] = v;
        }
    }
}
