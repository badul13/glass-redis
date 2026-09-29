package glassredis.store;

import glassredis.store.encoding.Dict;
import glassredis.store.encoding.Intset;
import glassredis.store.encoding.Listpack;

import java.nio.charset.StandardCharsets;
import java.util.function.Consumer;

/**
 * Set. 세 가지 인코딩을 오간다. Redis 7.2 {@code t_set.c} 의 규칙을 따른다.
 *
 * <pre>
 *   intset     정수만, 512개 이하         정렬된 정수 배열
 *   listpack   128개 이하, 원소 64B 이하   넣은 순서대로 이어 붙인 바이트 배열
 *   hashtable  그 밖                       dict
 * </pre>
 * <ul>
 *   <li>처음 만들 때 첫 원소가 정수이고 넣을 개수가 512 이하면 intset, 아니면 128 이하면 listpack, 아니면 hashtable.</li>
 *   <li>intset 에 정수가 아닌 값이 오면: 지금 원소가 128개 미만이고 새 값과 기존 정수들이 전부 64바이트 이하일 때만
 *       listpack 으로, 아니면 곧장 hashtable 로 간다. 정수 200개짜리 Set 에 문자열 하나를 넣으면 hashtable 이 되는 이유다.</li>
 *   <li>intset 이 512개를 넘으면 hashtable.</li>
 *   <li>한 번 넓어지면(intset → listpack → hashtable) 줄어도 돌아가지 않는다.</li>
 * </ul>
 * 그래서 {@code SMEMBERS} 순서도 인코딩마다 다르다. intset 은 오름차순, listpack 은 넣은 순서, hashtable 은 버킷 순서다.
 */
public final class SetValue implements Value {

    static final int MAX_INTSET_ENTRIES = 512;
    static final int MAX_LISTPACK_ENTRIES = 128;
    static final int MAX_LISTPACK_VALUE = 64;

    private Intset intset;
    private Listpack listpack;
    private Dict<Boolean> dict;

    private SetValue() {
    }

    /** 첫 원소와 넣을 개수를 보고 인코딩을 고른다(setTypeCreate). */
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

    /**
     * 빈 intset. {@code SUNION}, {@code SDIFF} 가 결과를 모으는 임시 Set 으로 쓴다(createIntsetObject).
     * 넣는 값에 따라 보통 Set 과 똑같이 listpack, hashtable 로 넓어지고, 결과 순서는 그 인코딩을 따른다.
     */
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

    /** 기존 Set 에 많이 넣기 전에 부른다(setTypeMaybeConvert). 넣을 개수가 상한을 넘으면 미리 hashtable 로. */
    public void prepareForAdd(long sizeHint) {
        if ((listpack != null && sizeHint > MAX_LISTPACK_ENTRIES)
                || (intset != null && sizeHint > MAX_INTSET_ENTRIES)) {
            convertToDict(sizeHint);
        }
    }

    /** 넣었으면 {@code true}(setTypeAdd). */
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

        // intset 에 정수가 아닌 값: listpack 으로 가도 기준을 넘지 않을지 본다.
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

    /** 뺐으면 {@code true}. hashtable 이면 너무 비었을 때 테이블을 줄인다. */
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

    /** 원소를 차례로. 순서는 인코딩마다 다르다(클래스 설명). */
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

    /** 10진수 자릿수. 음수는 부호까지 센다(sdigits10). */
    private static int digits(long v) {
        return Long.toString(v).length();
    }
}
