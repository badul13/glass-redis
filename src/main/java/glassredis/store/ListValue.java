package glassredis.store;

import glassredis.store.encoding.Listpack;
import glassredis.store.encoding.Quicklist;

import java.util.ArrayList;
import java.util.List;

/**
 * List - t_list.c listType*
 * 작을 때 listpack 하나, 커지면 quicklist
 * 복귀 기준은 절반 크기 - 경계에서 인코딩 왕복 방지
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

    /** 삽입 전 호출 - 삽입 후 노드 한도 초과 예상 시 미리 quicklist 전환 */
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

    /** 비었으면 null - 인코딩 전환은 afterShrink 담당 */
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

    /** 범위 밖이면 null, 음수는 뒤에서부터 */
    public byte[] index(long index) {
        if (quicklist != null) {
            return quicklist.index(index);
        }
        int p = listpack.seek(index);
        return p == -1 ? null : listpack.get(p);
    }

    /** start..end 양 끝 포함 - 범위 보정은 호출 측 책임 */
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

    /** 앞에서 left개, 뒤에서 right개 제거 */
    public void trim(long left, long right) {
        if (quicklist != null) {
            quicklist.deleteRange(0, left);
            quicklist.deleteRange(quicklist.count() - right, right);
        } else {
            listpack.deleteRange(0, left);
            listpack.deleteRange(-right, right);
        }
    }

    /** 삭제 후 호출, 노드 하나 남고 절반 크기 이하면 listpack 복귀 - listTypeTryConvertQuicklist */
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
