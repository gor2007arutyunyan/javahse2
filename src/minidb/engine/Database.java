package minidb.engine;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import minidb.DbException;
import minidb.index.Index;
import minidb.model.TableSchema;
import minidb.sql.Parser;
import minidb.sql.Statement;

/**
 * База данных: каталог таблиц в памяти плюс управление сохранением.
 * <p>
 * По умолчанию включён режим автофиксации: после каждой изменяющей команды изменения
 * записываются на диск. Для массовой загрузки можно отключить автофиксацию
 * ({@link #setAutoCommit(boolean)}) и вызвать {@link #commit()} один раз в конце;
 * команды изменения структуры (DDL) фиксируются всегда сразу.
 */
public final class Database {
    private final Storage storage;
    private final Map<String, Table> tables = new LinkedHashMap<String, Table>();
    private final List<String> recoveryNotes = new ArrayList<String>();
    private final Executor executor = new Executor(this);
    private boolean autoCommit = true;

    /**
     * Открывает базу: загружает таблицы и индексы из хранилища.
     *
     * @param storage хранилище
     * @throws DbException если данные нельзя прочитать
     */
    public Database(Storage storage) {
        this.storage = storage;
        load();
        if (!recoveryNotes.isEmpty()) {
            commit();
        }
    }

    private void load() {
        try {
            Storage.LoadResult r = storage.load();
            tables.clear();
            for (Table t : r.tables) {
                tables.put(TableSchema.normalize(t.name()), t);
            }
            recoveryNotes.addAll(r.notes);
        } catch (IOException e) {
            throw new DbException("Не удалось загрузить базу данных: " + e.getMessage(), e);
        }
    }

    /** @return сообщения о восстановлении, выполненном при открытии базы */
    public List<String> recoveryNotes() {
        return Collections.unmodifiableList(recoveryNotes);
    }

    /** @return true, если изменения данных сохраняются после каждой команды */
    public boolean isAutoCommit() {
        return autoCommit;
    }

    /**
     * @param autoCommit true - сохранять после каждой команды; false - только по {@link #commit()}
     */
    public void setAutoCommit(boolean autoCommit) {
        this.autoCommit = autoCommit;
    }

    /**
     * Записывает на диск все несохранённые изменения. Каждый файл пишется атомарно:
     * сначала во временный файл, затем переименованием, поэтому при сбое остаётся
     * либо старая, либо новая версия файла.
     *
     * @throws DbException при ошибке записи; в этом случае память приводится в соответствие с диском
     */
    public void commit() {
        try {
            storage.save(tables.values());
        } catch (IOException e) {
            try {
                load();
            } catch (DbException ignored) {
                // состояние диска важнее; сообщаем исходную ошибку записи
            }
            throw new DbException("Ошибка записи на диск, изменения отменены: " + e.getMessage(), e);
        }
    }

    /** Сохраняет изменения и освобождает ресурсы. */
    public void close() {
        commit();
    }

    /**
     * Разбирает и выполняет ровно одну команду.
     *
     * @param sql текст команды
     * @return результат
     * @throws DbException при ошибке разбора или выполнения
     */
    public QueryResult execute(String sql) {
        return execute(Parser.parseOne(sql));
    }

    /**
     * Выполняет разобранную команду. Изменения структуры сохраняются сразу,
     * изменения данных - если включена автофиксация.
     *
     * @param statement команда
     * @return результат
     * @throws DbException при ошибке выполнения (состояние базы при этом не меняется)
     */
    public QueryResult execute(Statement statement) {
        QueryResult result = statement.accept(executor);
        switch (statement.kind()) {
            case DDL:
                commit();
                break;
            case DML:
                if (autoCommit) {
                    commit();
                }
                break;
            default:
                break;
        }
        return result;
    }

    // ---------- каталог ----------

    /**
     * @param name имя таблицы
     * @return таблица или null
     */
    public Table findTable(String name) {
        return tables.get(TableSchema.normalize(name));
    }

    /**
     * @param name имя таблицы
     * @return таблица
     * @throws DbException если таблицы нет
     */
    public Table requireTable(String name) {
        Table t = findTable(name);
        if (t == null) {
            throw new DbException("Таблица '" + name + "' не существует");
        }
        return t;
    }

    /** @return все таблицы в порядке имён файлов/создания */
    public Collection<Table> tables() {
        return Collections.unmodifiableCollection(tables.values());
    }

    /**
     * Находит индекс по имени среди всех таблиц.
     *
     * @param name имя индекса
     * @return индекс или null
     */
    public Index findIndex(String name) {
        for (Table t : tables.values()) {
            Index ix = t.indexByName(name);
            if (ix != null) {
                return ix;
            }
        }
        return null;
    }

    void addTable(Table table) {
        tables.put(TableSchema.normalize(table.name()), table);
    }

    void removeTable(String name) {
        tables.remove(TableSchema.normalize(name));
    }
}
