package glassredis.store;

import glassredis.store.encoding.Dict;
import glassredis.store.encoding.Intset;
import glassredis.store.encoding.Listpack;

import java.nio.charset.StandardCharsets;
import java.util.function.Consumer;

/**
 * Set - t_set.c
 * intset, listpack, hashtable 전환 - 한 번 넓어지면 줄어도 복귀 없음
 * 순회 순서 - intset은 오름차순, listpack은 삽입 순, hashtable은 버킷 순
 */
public final class SetValue implements Value {

    /** set-max-intset-entries */
    static final int MAX_INTSET_ENTRIES = 512;
    /** set-max-listpack-entries */
    static final int MAX_LISTPACK_ENTRIES = 128;
    /** set-max-listpack-value */
    static final int MAX_LISTPACK_VALUE = 64;

    private Intset intset;
    private Listpack listpack;
    private Dict<Boolean> dict;

    private SetValue() {
    }

    /** 첫 원소와 삽입 개수 기준 인코딩 선택 - setTypeCreate */
    public static SetValue create(byte[] firstValue, long sizeHint) {
        SetValue set = new SetValue();
        if (Listpack.toInt64(firstValue) != null && sizeHint <= MAX_INTSET_ENTRIES) {
            set.intset = new Intset();
        } else if (sizeHint <= MAX_LISTPACK_ENTRIES) {
            set.listpack = new Listpack();
        } else {
            set.dict = new Dict<>();
            set.dict.expand(sizeHint);
        }
        return set;
    }

    /** SUNION, SDIFF 결과 수집용 임시 Set - createIntsetObject */
    public static SetValue emptyIntset() {
        SetValue set = new SetValue();
        set.intset = new Intset();
        return set;
    }

    @Override
    public String typeName() {
        return "set";
    }

    @Override
    public String encoding() {
        return intset != null ? "intset" : listpack != null ? "listpack" : "hashtable";
    }

    @Override
    public int size() {
        if (intset != null) {
            return intset.length();
        }
        return listpack != null ? listpack.length() : (int) dict.size();
    }

    /** 다건 삽입 전 호출 - setTypeMaybeConvert */
    public void prepareForAdd(long sizeHint) {
        if ((listpack != null && sizeHint > MAX_LISTPACK_ENTRIES)
                || (intset != null && sizeHint > MAX_INTSET_ENTRIES)) {
            convertToDict(sizeHint);
        }
    }

    /** 삽입 시 true - setTypeAdd */
    public boolean add(byte[] value) {
        if (dict != null) {
            return dict.add(new Key(value), Boolean.TRUE);
        }
        if (listpack != null) {
            int first = listpack.first();
            if (first != -1 && listpack.find(first, value, 0) != -1) {
                return false;
            }
            if (listpack.length() < MAX_LISTPACK_ENTRIES && value.length <= MAX_LISTPACK_VALUE) {
                listpack.append(value);
            } else {
                convertToDict(listpack.length() + 1L);
                dict.add(new Key(value), Boolean.TRUE);
            }
            return true;
        }

        Long number = Listpack.toInt64(value);
        if (number != null) {
            if (!intset.add(number)) {
                return false;
            }
            if (intset.length() > MAX_INTSET_ENTRIES) {
                convertToDict(intset.length());
            }
            return true;
        }

        // 정수 아닌 값 - listpack 한도 안이면 listpack, 아니면 바로 hashtable
        int n = intset.length();
        int maxElementLength = 0;
        if (n != 0) {
            maxElementLength = Math.max(digits(intset.max()), digits(intset.min()));
        }
        if (n < MAX_LISTPACK_ENTRIES && value.length <= MAX_LISTPACK_VALUE && maxElementLength <= MAX_LISTPACK_VALUE) {
            Listpack converted = new Listpack();
            for (int i = 0; i < n; i++) {
                converted.append(Long.toString(intset.get(i)).getBytes(StandardCharsets.US_ASCII));
            }
            converted.append(value);
            listpack = converted;
            intset = null;
        } else {
            convertToDict(n + 1L);
            dict.add(new Key(value), Boolean.TRUE);
        }
        return true;
    }

    public boolean remove(byte[] value) {
        if (dict != null) {
            boolean removed = dict.remove(new Key(value));
            if (removed) {
                dict.shrinkIfNeeded();
            }
            return removed;
        }
        if (listpack != null) {
            int first = listpack.first();
            int p = first == -1 ? -1 : listpack.find(first, value, 0);
            if (p == -1) {
                return false;
            }
            listpack.delete(p);
            return true;
        }
        Long number = Listpack.toInt64(value);
        return number != null && intset.remove(number);
    }

    public boolean contains(byte[] value) {
        if (dict != null) {
            return dict.containsKey(new Key(value));
        }
        if (listpack != null) {
            int first = listpack.first();
            return first != -1 && listpack.find(first, value, 0) != -1;
        }
        Long number = Listpack.toInt64(value);
        return number != null && intset.contains(number);
    }

    public void forEach(Consumer<byte[]> visitor) {
        if (dict != null) {
            dict.forEach((member, ignored) -> visitor.accept(member.bytes()));
        } else if (listpack != null) {
            for (int p = listpack.first(); p != -1; p = listpack.next(p)) {
                visitor.accept(listpack.get(p));
            }
        } else {
            for (int i = 0; i < intset.length(); i++) {
                visitor.accept(Long.toString(intset.get(i)).getBytes(StandardCharsets.US_ASCII));
            }
        }
    }

    public Intset intset() {
        return intset;
    }

    public Listpack listpack() {
        return listpack;
    }

    public Dict<Boolean> dict() {
        return dict;
    }

    private void convertToDict(long sizeHint) {
        Dict<Boolean> converted = new Dict<>();
        converted.expand(sizeHint);
        forEach(member -> converted.add(new Key(member), Boolean.TRUE));
        dict = converted;
        intset = null;
        listpack = null;
    }

    /** 음수는 부호 포함 자릿수 - sdigits10 */
    private static int digits(long v) {
        return Long.toString(v).length();
    }
}
