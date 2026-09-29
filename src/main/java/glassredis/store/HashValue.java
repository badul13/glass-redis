package glassredis.store;

import glassredis.store.encoding.Dict;
import glassredis.store.encoding.Listpack;

import java.util.List;
import java.util.function.BiConsumer;

/**
 * Hash. 작을 때는 listpack 에 {@code [필드, 값, 필드, 값, ...]} 으로, 커지면 dict(해시 테이블)에 담는다.
 * Redis 7.2 {@code t_hash.c} 의 규칙을 따르고, 기준값은 실제 Redis 7.4 의 기본 설정이다.
 *
 * <ul>
 *   <li>필드나 값 중 하나라도 64바이트({@code hash-max-listpack-value})를 넘으면 hashtable 로 바꾼다.</li>
 *   <li>필드가 512개({@code hash-max-listpack-entries})를 넘으면 hashtable 로 바꾼다.
 *       한 명령으로 512쌍을 넘게 넣으면 넣기 전에 바로 바꾼다.</li>
 *   <li>한 번 hashtable 이 되면 줄어도 돌아가지 않는다.</li>
 * </ul>
 * listpack 에서 필드를 찾을 때는 처음부터 훑는다. 원소가 적을 때는 해시를 계산하는 것보다 이게 빠르다.
 */
public final class HashValue implements Value {

    static final int MAX_LISTPACK_ENTRIES = 512;
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

    /**
     * HSET 이 넣기 전에 부른다(hashTypeTryConversion). 인자는 [필드, 값, 필드, 값, ...] 이다.
     * 쌍이 512개를 넘거나 64바이트를 넘는 게 하나라도 있으면 미리 hashtable 로 바꾼다.
     */
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

    /** 넣거나 바꾼다. 새 필드였으면 {@code true}(hashTypeSet). */
    public boolean set(Key field, byte[] value) {
        if (dict == null && (field.bytes().length > MAX_LISTPACK_VALUE || value.length > MAX_LISTPACK_VALUE)) {
            // HSET 은 prepareForSet 에서 이미 걸렀다. HINCRBY 처럼 그 단계가 없는 명령이 여기서 걸린다.
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

    /** 지웠으면 {@code true}. hashtable 이면 지운 뒤 너무 비었을 때 테이블을 줄인다. */
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

    /** 필드와 값을 차례로. listpack 이면 넣은 순서, hashtable 이면 버킷 순서다. */
    public void forEach(BiConsumer<byte[], byte[]> visitor) {
        if (dict != null) {
            dict.forEach((field, value) -> visitor.accept(field.bytes(), value));
            return;
        }
        for (int p = listpack.first(); p != -1; p = listpack.next(listpack.next(p))) {
            visitor.accept(listpack.get(p), listpack.get(listpack.next(p)));
        }
    }

    /** 대시보드용. listpack 일 때만 있다. */
    public Listpack listpack() {
        return listpack;
    }

    /** 대시보드용. hashtable 일 때만 있다. */
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
