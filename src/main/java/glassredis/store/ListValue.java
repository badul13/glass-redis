package glassredis.store;

import glassredis.store.encoding.Listpack;
import glassredis.store.encoding.Quicklist;

import java.util.ArrayList;
import java.util.List;

/**
 * List. 작을 때는 listpack 하나, 커지면 quicklist(listpack 을 이은 리스트)에 담는다. Redis 7.2 {@code t_list.c} 의
 * 인코딩 전환 규칙을 따른다.
 *
 * <ul>
 *   <li><b>listpack → quicklist</b>: 넣기 <b>전에</b> "지금 listpack 바이트 + 넣을 원소들의 길이 합"이
 *       8192 를 넘을지 본다. 넘으면 지금 listpack 을 quicklist 의 첫 노드로 삼아 바꾼다.</li>
 *   <li><b>quicklist → listpack</b>: 빼고 난 <b>뒤에</b>, 노드가 하나만 남았고 그 크기가 절반(4096) 이하면
 *       되돌린다. 기준을 절반으로 잡은 건 경계에서 넣고 빼기를 반복할 때 매번 바뀌지 않게 하려는 것이다.</li>
 * </ul>
 * 두 인코딩은 명령 쪽에서 보이지 않는다. 명령은 이 클래스의 메서드만 쓴다(Redis 의 listType* 함수들에 해당).
 */
public final class ListValue implements Value {

    private Listpack listpack = new Listpack();
    private Quicklist quicklist;

    @Override
    public String typeName() {
        return "list";
    }

    @Override
    public String encoding() {
        return quicklist == null ? "listpack" : "quicklist";
    }

    @Override
    public int size() {
        return (int) length();
    }

    public long length() {
        return quicklist == null ? listpack.length() : quicklist.count();
    }

    /** 이 원소들을 넣기 전에 부른다. 넣으면 너무 커질 listpack 이면 quicklist 로 바꿔 둔다. */
    public void prepareForAppend(List<byte[]> values) {
        if (quicklist != null) {
            return;
        }
        long addBytes = 0;
        for (byte[] value : values) {
            addBytes += value.length;
        }
        if (listpack.bytes() + addBytes > Quicklist.NODE_SIZE_LIMIT) {
            quicklist = new Quicklist();
            if (listpack.length() > 0) {
                quicklist.appendListpack(listpack);
            }
            listpack = null;
        }
    }

    public void push(byte[] value, boolean head) {
        if (quicklist != null) {
            if (head) {
                quicklist.pushHead(value);
            } else {
                quicklist.pushTail(value);
            }
        } else if (head) {
            listpack.prepend(value);
        } else {
            listpack.append(value);
        }
    }

    /** 비었으면 {@code null}. 줄어든 뒤의 인코딩 전환은 {@link #afterShrink} 가 한다. */
    public byte[] pop(boolean head) {
        if (quicklist != null) {
            return quicklist.pop(head);
        }
        int p = listpack.seek(head ? 0 : -1);
        if (p == -1) {
            return null;
        }
        byte[] value = listpack.get(p);
        listpack.delete(p);
        return value;
    }

    /** 범위 밖이면 {@code null}. 음수는 뒤에서부터. */
    public byte[] index(long index) {
        if (quicklist != null) {
            return quicklist.index(index);
        }
        int p = listpack.seek(index);
        return p == -1 ? null : listpack.get(p);
    }

    /** start..end(포함). 범위는 부르는 쪽이 잘라 맞췄다. */
    public List<byte[]> range(long start, long end) {
        if (quicklist != null) {
            return quicklist.range(start, end);
        }
        List<byte[]> out = new ArrayList<>((int) (end - start + 1));
        int p = listpack.seek(start);
        for (long i = start; i <= end && p != -1; i++) {
            out.add(listpack.get(p));
            p = listpack.next(p);
        }
        return out;
    }

    /** 같은 원소를 앞에서(fromTail 이면 뒤에서) 최대 limit 개 지운다. */
    public long remove(byte[] value, long limit, boolean fromTail) {
        if (quicklist != null) {
            return quicklist.removeMatching(value, limit, fromTail);
        }
        long removed = 0;
        int p = fromTail ? listpack.last() : listpack.first();
        while (p != -1 && removed < limit) {
            if (listpack.equalsAt(p, value)) {
                int next = listpack.delete(p);
                removed++;
                p = fromTail ? (next == -1 ? listpack.last() : listpack.prev(next)) : next;
            } else {
                p = fromTail ? listpack.prev(p) : listpack.next(p);
            }
        }
        return removed;
    }

    /** 앞에서 left 개, 뒤에서 right 개를 잘라낸다(LTRIM). */
    public void trim(long left, long right) {
        if (quicklist != null) {
            quicklist.deleteRange(0, left);
            quicklist.deleteRange(quicklist.count() - right, right);
        } else {
            listpack.deleteRange(0, left);
            listpack.deleteRange(-right, right);
        }
    }

    /**
     * 원소를 뺀 뒤에 부른다. quicklist 에 노드가 하나만 남았고 그게 절반 크기 이하면 listpack 으로 되돌린다
     * (listTypeTryConvertQuicklist, shrinking).
     */
    public void afterShrink() {
        if (quicklist == null || quicklist.nodeCount() != 1) {
            return;
        }
        Listpack only = quicklist.head().listpack();
        if (only.bytes() > Quicklist.NODE_SIZE_LIMIT / 2) {
            return;
        }
        listpack = only;
        quicklist = null;
    }

    /** 대시보드용. listpack 이면 하나짜리, quicklist 면 노드마다 하나. */
    public List<Listpack> nodes() {
        if (quicklist == null) {
            return List.of(listpack);
        }
        List<Listpack> nodes = new ArrayList<>(quicklist.nodeCount());
        for (Quicklist.Node node = quicklist.head(); node != null; node = node.next()) {
            nodes.add(node.listpack());
        }
        return nodes;
    }
}
