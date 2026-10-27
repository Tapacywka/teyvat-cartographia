package net.tapacywka.teyvatcartographia.nav;

/**
 * Binary min-heap of grid cells by key for the path searches, with lazy deletion: a cell may be pushed again with a
 * lower key, and the stale entries are skipped by the caller when popped. Fixed capacity; a push into a full heap is
 * dropped.
 */
final class CellHeap {
    private final int[] cells;
    private final float[] keys;
    private int size;

    CellHeap(int capacity) {
        cells = new int[capacity];
        keys = new float[capacity];
    }

    boolean isEmpty() {
        return size == 0;
    }

    void clear() {
        size = 0;
    }

    void push(int c, float key) {
        if (size == cells.length) return;
        int i = size++;
        while (i > 0) {
            int parent = (i - 1) >> 1;
            if (keys[parent] <= key) break;
            cells[i] = cells[parent];
            keys[i] = keys[parent];
            i = parent;
        }
        cells[i] = c;
        keys[i] = key;
    }

    int pop() {
        int top = cells[0];
        int last = cells[--size];
        float key = keys[size];
        int i = 0;
        while (true) {
            int l = 2 * i + 1;
            if (l >= size) break;
            int r = l + 1, m = r < size && keys[r] < keys[l] ? r : l;
            if (keys[m] >= key) break;
            cells[i] = cells[m];
            keys[i] = keys[m];
            i = m;
        }
        cells[i] = last;
        keys[i] = key;
        return top;
    }
}
