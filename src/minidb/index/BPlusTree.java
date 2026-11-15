package minidb.index;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.LongConsumer;

import minidb.model.Values;

/**
 * Собственная реализация B+-дерева для индексирования.
 * <p>
 * Ключ (не null) отображается в отсортированный список идентификаторов строк, поэтому
 * неуникальные значения хранятся компактно. Все ключи лежат в листьях, листья связаны в список,
 * что даёт быстрый обход диапазона. Поиск, вставка и удаление - O(log n).
 * Удаление ленивое: пустые ключи убираются из листа, но узлы не склеиваются
 * (для учебной СУБД этого достаточно, корректность поиска сохраняется).
 */
public final class BPlusTree {
    /** Максимальное число ключей в узле; при превышении узел делится пополам. */
    private static final int MAX_KEYS = 64;
    private static final long[] EMPTY = new long[0];

    private Node root = new Leaf();
    private int keyCount;

    /** Получатель пар (ключ, идентификаторы строк) при обходе дерева по возрастанию ключей. */
    public interface EntryVisitor {
        /**
         * @param key    ключ
         * @param rowIds отсортированные идентификаторы строк с этим ключом
         */
        void visit(Object key, long[] rowIds);
    }

    /** Отсортированный массив идентификаторов строк. */
    private static final class IdList {
        long[] ids = new long[2];
        int size;

        void insertSorted(long id) {
            if (size == 0 || id > ids[size - 1]) {
                append(id);
                return;
            }
            int pos = Arrays.binarySearch(ids, 0, size, id);
            if (pos >= 0) {
                return;
            }
            pos = -pos - 1;
            if (size == ids.length) {
                ids = Arrays.copyOf(ids, size * 2);
            }
            System.arraycopy(ids, pos, ids, pos + 1, size - pos);
            ids[pos] = id;
            size++;
        }

        private void append(long id) {
            if (size == ids.length) {
                ids = Arrays.copyOf(ids, size * 2);
            }
            ids[size++] = id;
        }

        boolean remove(long id) {
            int pos = Arrays.binarySearch(ids, 0, size, id);
            if (pos < 0) {
                return false;
            }
            System.arraycopy(ids, pos + 1, ids, pos, size - pos - 1);
            size--;
            return true;
        }

        long[] toArray() {
            return Arrays.copyOf(ids, size);
        }
    }

    private abstract static class Node {
        final List<Object> keys = new ArrayList<Object>();
    }

    private static final class Leaf extends Node {
        final List<IdList> values = new ArrayList<IdList>();
        Leaf next;
    }

    private static final class Inner extends Node {
        final List<Node> children = new ArrayList<Node>();
    }

    private static final class Split {
        final Object key;
        final Node right;

        Split(Object key, Node right) {
            this.key = key;
            this.right = right;
        }
    }

    /** @return число различных ключей в дереве */
    public int keyCount() {
        return keyCount;
    }

    /**
     * Добавляет пару (ключ, идентификатор строки).
     *
     * @param key   ключ (не null)
     * @param rowId идентификатор строки
     */
    public void add(Object key, long rowId) {
        Split s = insert(root, key, rowId);
        if (s != null) {
            Inner newRoot = new Inner();
            newRoot.keys.add(s.key);
            newRoot.children.add(root);
            newRoot.children.add(s.right);
            root = newRoot;
        }
    }

    private Split insert(Node node, Object key, long rowId) {
        if (node instanceof Leaf) {
            Leaf leaf = (Leaf) node;
            int pos = search(leaf.keys, key);
            if (pos >= 0) {
                leaf.values.get(pos).insertSorted(rowId);
                return null;
            }
            int at = -pos - 1;
            IdList list = new IdList();
            list.insertSorted(rowId);
            leaf.keys.add(at, key);
            leaf.values.add(at, list);
            keyCount++;
            if (leaf.keys.size() <= MAX_KEYS) {
                return null;
            }
            int size = leaf.keys.size();
            int mid = size / 2;
            Leaf right = new Leaf();
            right.keys.addAll(leaf.keys.subList(mid, size));
            right.values.addAll(leaf.values.subList(mid, size));
            leaf.keys.subList(mid, size).clear();
            leaf.values.subList(mid, size).clear();
            right.next = leaf.next;
            leaf.next = right;
            return new Split(right.keys.get(0), right);
        }
        Inner inner = (Inner) node;
        int idx = childIndex(inner, key);
        Split s = insert(inner.children.get(idx), key, rowId);
        if (s == null) {
            return null;
        }
        inner.keys.add(idx, s.key);
        inner.children.add(idx + 1, s.right);
        if (inner.keys.size() <= MAX_KEYS) {
            return null;
        }
        int size = inner.keys.size();
        int mid = size / 2;
        Object up = inner.keys.get(mid);
        Inner right = new Inner();
        right.keys.addAll(inner.keys.subList(mid + 1, size));
        right.children.addAll(inner.children.subList(mid + 1, inner.children.size()));
        inner.keys.subList(mid, size).clear();
        inner.children.subList(mid + 1, inner.children.size()).clear();
        return new Split(up, right);
    }

    /**
     * Удаляет пару (ключ, идентификатор строки).
     *
     * @param key   ключ
     * @param rowId идентификатор строки
     * @return true, если пара была найдена и удалена
     */
    public boolean remove(Object key, long rowId) {
        Leaf leaf = findLeaf(key);
        int pos = search(leaf.keys, key);
        if (pos < 0) {
            return false;
        }
        IdList list = leaf.values.get(pos);
        if (!list.remove(rowId)) {
            return false;
        }
        if (list.size == 0) {
            leaf.keys.remove(pos);
            leaf.values.remove(pos);
            keyCount--;
        }
        return true;
    }

    /**
     * @param key ключ
     * @return отсортированные идентификаторы строк с этим ключом (пустой массив, если ключа нет)
     */
    public long[] get(Object key) {
        Leaf leaf = findLeaf(key);
        int pos = search(leaf.keys, key);
        return pos < 0 ? EMPTY : leaf.values.get(pos).toArray();
    }

    /**
     * Обходит диапазон ключей по возрастанию, передавая в {@code out} идентификаторы строк.
     *
     * @param lo    нижняя граница или null (без ограничения)
     * @param loInc включать ли нижнюю границу
     * @param hi    верхняя граница или null (без ограничения)
     * @param hiInc включать ли верхнюю границу
     * @param out   получатель идентификаторов
     */
    public void range(Object lo, boolean loInc, Object hi, boolean hiInc, LongConsumer out) {
        Leaf leaf;
        int i;
        if (lo == null) {
            leaf = leftmost();
            i = 0;
        } else {
            leaf = findLeaf(lo);
            int pos = search(leaf.keys, lo);
            i = pos >= 0 ? (loInc ? pos : pos + 1) : -pos - 1;
        }
        while (leaf != null) {
            for (; i < leaf.keys.size(); i++) {
                if (hi != null) {
                    int c = Values.compare(leaf.keys.get(i), hi);
                    if (c > 0 || (c == 0 && !hiInc)) {
                        return;
                    }
                }
                IdList list = leaf.values.get(i);
                for (int j = 0; j < list.size; j++) {
                    out.accept(list.ids[j]);
                }
            }
            leaf = leaf.next;
            i = 0;
        }
    }

    /**
     * Обходит все ключи по возрастанию (используется при сохранении индекса на диск).
     *
     * @param visitor получатель пар (ключ, идентификаторы)
     */
    public void forEach(EntryVisitor visitor) {
        for (Leaf leaf = leftmost(); leaf != null; leaf = leaf.next) {
            for (int i = 0; i < leaf.keys.size(); i++) {
                visitor.visit(leaf.keys.get(i), leaf.values.get(i).toArray());
            }
        }
    }

    private Leaf leftmost() {
        Node n = root;
        while (n instanceof Inner) {
            n = ((Inner) n).children.get(0);
        }
        return (Leaf) n;
    }

    private Leaf findLeaf(Object key) {
        Node n = root;
        while (n instanceof Inner) {
            Inner inner = (Inner) n;
            n = inner.children.get(childIndex(inner, key));
        }
        return (Leaf) n;
    }

    /** Номер дочернего узла, в котором должен находиться ключ. */
    private static int childIndex(Inner node, Object key) {
        int pos = search(node.keys, key);
        return pos >= 0 ? pos + 1 : -pos - 1;
    }

    /** Двоичный поиск; возвращает позицию или {@code -(точка вставки) - 1}. */
    private static int search(List<Object> keys, Object key) {
        int lo = 0;
        int hi = keys.size() - 1;
        while (lo <= hi) {
            int mid = (lo + hi) >>> 1;
            int c = Values.compare(keys.get(mid), key);
            if (c < 0) {
                lo = mid + 1;
            } else if (c > 0) {
                hi = mid - 1;
            } else {
                return mid;
            }
        }
        return -(lo + 1);
    }
}
