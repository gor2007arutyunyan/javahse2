package minidb.engine;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * Хранилище данных на постоянном носителе (паттерн Repository). Ядро СУБД зависит только
 * от этого интерфейса; реализация {@code minidb.storage.FileStorage} хранит таблицы и индексы в файлах.
 */
public interface Storage {

    /** Результат загрузки: таблицы и сообщения о выполненном восстановлении. */
    final class LoadResult {
        /** Загруженные таблицы (с заполненными индексами). */
        public final List<Table> tables;
        /** Сообщения о восстановлении после сбоя (перестроенные индексы, удалённые временные файлы). */
        public final List<String> notes;

        /**
         * @param tables таблицы
         * @param notes  сообщения
         */
        public LoadResult(List<Table> tables, List<String> notes) {
            this.tables = tables;
            this.notes = notes == null ? new ArrayList<String>() : notes;
        }
    }

    /**
     * Загружает все таблицы и индексы, при необходимости восстанавливая повреждённые индексы.
     *
     * @return загруженные данные
     * @throws IOException если данные таблицы нечитаемы
     */
    LoadResult load() throws IOException;

    /**
     * Записывает на диск изменённые таблицы с индексами и удаляет файлы несуществующих таблиц и индексов.
     *
     * @param tables актуальный набор таблиц
     * @throws IOException при ошибке записи
     */
    void save(Collection<Table> tables) throws IOException;
}
