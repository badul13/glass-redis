package glassredis.store;

import glassredis.store.encoding.Dict;
import glassredis.store.encoding.Listpack;

import java.util.List;
import java.util.function.BiConsumer;

/**
 * Hash - t_hash.c
 * 작을 때 listpack에 [필드, 값, ...], 커지면 dict
 * hashtable 전환 후 줄어도 복귀 없음
 */
public final class HashValue implements Value {

    /** hash-max-listpack-entries */
    static final int MAX_LISTPACK_ENTRIES = 512;
    /** hash-max-listpack-value */
    static final int MAX_LISTPACK_VALUE = 64;

    private Listpack listpack = new Listpack();
    private Dict<byte[]> dict;

    @Override
    public String typeName() {
        return "hash";
    }

    @Override
    public String encoding() {
        return dict == null ? "listpack" : "hashtable";
    }

    @Override
    public int size() {
        return dict == null ? listpack.length() / 2 : (int) dict.size();
    }

    /** HSET 삽입 전 호출 - hashTypeTryConversion, 인자는 [필드, 값, ...] */
    public void prepareForSet(List<byte[]> fieldsAndValues) {
        if (dict != null) {
            return;
        }
        long newFields = fieldsAndValues.size() / 2;
        if (newFields > MAX_LISTPACK_ENTRIES) {
            convertToDict();
            dict.expand(newFields);
            return;
        }
        for (byte[] bytes : fieldsAndValues) {
            if (bytes.length > MAX_LISTPACK_VALUE) {
                convertToDict();
                return;
            }
        }
    }

    public byte[] get(Key field) {
        if (dict != null) {
            return dict.get(field);
        }
        int p = findField(field);
        return p == -1 ? null : listpack.get(listpack.next(p));
    }

    public boolean contains(Key field) {
        return dict != null ? dict.containsKey(field) : findField(field) != -1;
    }

    /** 새 필드면 true - hashTypeSet */
    public boolean set(Key field, byte[] value) {
        if (dict == null && (field.bytes().length > MAX_LISTPACK_VALUE || value.length > MAX_LISTPACK_VALUE)) {
            // prepareForSet 안 거치는 HINCRBY 등의 전환 지점
            convertToDict();
        }
        if (dict != null) {
            return dict.put(field, value);
        }
        int p = findField(field);
        if (p != -1) {
            listpack.replace(listpack.next(p), value);
            return false;
        }
        listpack.append(field.bytes());
        listpack.append(value);
        if (size() > MAX_LISTPACK_ENTRIES) {
            convertToDict();
        }
        return true;
    }

    /** 삭제 시 true */
    public boolean delete(Key field) {
        if (dict != null) {
            boolean deleted = dict.remove(field);
            if (deleted) {
                dict.shrinkIfNeeded();
            }
            return deleted;
        }
        int p = findField(field);
        if (p == -1) {
            return false;
        }
        listpack.deleteRangeWithEntry(p, 2);
        return true;
    }

    /** 순회 순서 - listpack은 삽입 순, hashtable은 버킷 순 */
    public void forEach(BiConsumer<byte[], byte[]> visitor) {
        if (dict != null) {
            dict.forEach((field, value) -> visitor.accept(field.bytes(), value));
            return;
        }
        for (int p = listpack.first(); p != -1; p = listpack.next(listpack.next(p))) {
            visitor.accept(listpack.get(p), listpack.get(listpack.next(p)));
        }
    }

    /** hashtable이면 null */
    public Listpack listpack() {
        return listpack;
    }

    /** listpack이면 null */
    public Dict<byte[]> dict() {
        return dict;
    }

    private int findField(Key field) {
        int first = listpack.first();
        return first == -1 ? -1 : listpack.find(first, field.bytes(), 1);
    }

    private void convertToDict() {
        Dict<byte[]> converted = new Dict<>();
        converted.expand(size());
        forEach((field, value) -> converted.add(new Key(field), value));
        dict = converted;
        listpack = null;
    }
}
