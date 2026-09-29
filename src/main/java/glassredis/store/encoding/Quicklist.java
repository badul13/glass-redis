package glassredis.store.encoding;

import java.util.ArrayList;
import java.util.List;

/**
 * quicklist — listpack 여러 개를 이중 연결 리스트로 이은 것. 큰 List 가 이걸 쓴다. Redis 7.2 {@code quicklist.c} 를 옮겼다.
 *
 * <pre>
 *   head                                              tail
 *   [listpack ~8KB] ⇄ [listpack ~8KB] ⇄ [listpack ~8KB]
 * </pre>
 * 순수 연결 리스트는 원소마다 앞뒤 포인터와 객체 헤더가 붙어서 원소보다 부가 정보가 더 크다.
 * 순수 listpack 은 가운데를 고칠 때마다 전체를 밀어야 한다. quicklist 는 그 중간이다 —
 * 원소들을 8KB 남짓한 listpack 덩어리로 묶고, 덩어리끼리만 포인터로 잇는다.
 *
 * <p>덩어리 크기 상한은 {@code list-max-listpack-size -2} = 8192바이트다. 끝 노드에 원소를 넣었을 때
 * "노드 바이트 + 원소 길이 + 8" 이 이걸 넘으면 새 노드를 만든다. 8 은 인코딩 머리와 backlen 을 넉넉히 잡은 값이다.
 *
 * <p>옮기지 않은 것: 1GB 이상의 원소를 따로 담는 plain 노드(glass-redis 는 값 하나가 512MB 를 넘을 수 없어서
 * 생길 일이 없다), 가운데 노드를 LZF 로 압축하는 기능(기본 설정 {@code list-compress-depth 0} 에서는 꺼져 있다).
 */
public final class Quicklist {

    /** list-max-listpack-size -2 에 해당하는 노드 크기 상한. */
    public static final int NODE_SIZE_LIMIT = 8192;
    private static final int SIZE_ESTIMATE_OVERHEAD = 8;

    /** 노드 하나. listpack 과 그 원소 수를 들고 앞뒤 노드와 이어진다. */
    public static final class Node {
        Node prev;
        Node next;
        Listpack lp;

        Node(Listpack lp) {
            this.lp = lp;
        }

        public Listpack listpack() {
            return lp;
        }

        public Node next() {
            return next;
        }

        int count() {
            return lp.length();
        }
    }

    private Node head;
    private Node tail;
    private long count;
    private int nodeCount;

    /** 원소 수. */
    public long count() {
        return count;
    }

    /** 노드 수. */
    public int nodeCount() {
        return nodeCount;
    }

    public Node head() {
        return head;
    }

    /** 이미 만들어진 listpack 을 노드 하나로 통째로 붙인다(quicklistAppendListpack). listpack → quicklist 전환 때 쓴다. */
    public void appendListpack(Listpack lp) {
        Node node = new Node(lp);
        insertNodeAfter(tail, node);
        count += lp.length();
    }

    public void pushHead(byte[] value) {
        if (allowInsert(head, value.length)) {
            head.lp.prepend(value);
        } else {
            Listpack lp = new Listpack();
            lp.prepend(value);
            insertNodeBefore(head, new Node(lp));
        }
        count++;
    }

    public void pushTail(byte[] value) {
        if (allowInsert(tail, value.length)) {
            tail.lp.append(value);
        } else {
            Listpack lp = new Listpack();
            lp.append(value);
            insertNodeAfter(tail, new Node(lp));
        }
        count++;
    }

    /** 앞이나 뒤에서 하나 꺼낸다. 비었으면 {@code null}. 노드가 비면 노드째로 뗀다. */
    public byte[] pop(boolean fromHead) {
        if (count == 0) {
            return null;
        }
        Node node = fromHead ? head : tail;
        int p = node.lp.seek(fromHead ? 0 : -1);
        byte[] value = node.lp.get(p);
        deleteAt(node, p);
        return value;
    }

    /** index 번째 원소. 음수는 뒤에서부터. 범위 밖이면 {@code null}. 노드 단위로 건너뛰므로 원소를 하나씩 세지 않는다. */
    public byte[] index(long index) {
        Cursor cursor = cursorAt(index);
        return cursor == null ? null : cursor.node.lp.get(cursor.p);
    }

    /** 앞에서부터 start..end 번째(포함)를 담는다. 범위는 부르는 쪽이 이미 잘라 맞췄다. */
    public List<byte[]> range(long start, long end) {
        List<byte[]> out = new ArrayList<>((int) (end - start + 1));
        Cursor cursor = cursorAt(start);
        for (long i = start; i <= end && cursor != null; i++) {
            out.add(cursor.node.lp.get(cursor.p));
            cursor = cursor.forward();
        }
        return out;
    }

    /**
     * 같은 원소를 지운다(LREM). 앞에서부터, 또는 뒤에서부터 최대 limit 개.
     * 원소를 지우다 노드가 비면 노드째로 뗀다. 이웃 노드와 합치지는 않는다 — 실제 Redis 도 이 경로에서는 합치지 않는다.
     */
    public long removeMatching(byte[] value, long limit, boolean fromTail) {
        long removed = 0;
        Node node = fromTail ? tail : head;
        while (node != null && removed < limit) {
            Node neighbor = fromTail ? node.prev : node.next;
            int p = fromTail ? node.lp.last() : node.lp.first();
            while (p != -1 && removed < limit) {
                if (node.lp.equalsAt(p, value)) {
                    int nextP = node.lp.delete(p);
                    count--;
                    removed++;
                    if (node.lp.length() == 0) {
                        unlink(node);
                        break;
                    }
                    // 지우면 오른쪽 원소가 그 자리로 온다. 뒤에서부터 걷는 중이면 왼쪽으로 한 칸 가야 한다.
                    p = fromTail ? (nextP == -1 ? node.lp.last() : node.lp.prev(nextP)) : nextP;
                } else {
                    p = fromTail ? node.lp.prev(p) : node.lp.next(p);
                }
            }
            node = neighbor;
        }
        return removed;
    }

    /** start 번째부터 num 개를 지운다(quicklistDelRange). start 는 0 이상이다. */
    public void deleteRange(long start, long num) {
        if (num <= 0) {
            return;
        }
        long extent = Math.min(num, count - start);
        Cursor cursor = cursorAt(start);
        if (cursor == null) {
            return;
        }
        Node node = cursor.node;
        long offset = cursor.offset;
        while (extent > 0 && node != null) {
            Node next = node.next;
            int nodeCount = node.count();
            long del;
            if (offset == 0 && extent >= nodeCount) {
                // 노드를 통째로 덮으면 listpack 을 계산할 것 없이 노드를 뗀다.
                del = nodeCount;
                unlink(node);
            } else {
                del = Math.min(nodeCount - offset, extent);
                node.lp.deleteRange(offset, del);
                if (node.lp.length() == 0) {
                    unlink(node);
                }
            }
            count -= del;
            extent -= del;
            node = next;
            offset = 0;
        }
    }

    // --- 속 ---

    /** 원소 하나의 자리: 어느 노드의 몇 번째(offset)이고 listpack 안의 위치(p)는 어디인지. */
    private final class Cursor {
        Node node;
        int offset;
        int p;

        Cursor(Node node, int offset, int p) {
            this.node = node;
            this.offset = offset;
            this.p = p;
        }

        Cursor forward() {
            int nextP = node.lp.next(p);
            if (nextP != -1) {
                return new Cursor(node, offset + 1, nextP);
            }
            Node n = node.next;
            return n == null ? null : new Cursor(n, 0, n.lp.first());
        }
    }

    /**
     * index 번째 원소의 자리. 음수는 뒤에서부터 센다(quicklistGetIteratorAtIdx).
     * 가까운 쪽 끝에서 출발해 노드 단위로 건너뛴다 — 노드마다 원소 수를 알고 있으니 원소를 하나씩 셀 필요가 없다.
     */
    private Cursor cursorAt(long index) {
        long fromHead = index >= 0 ? index : count + index;
        if (fromHead < 0 || fromHead >= count) {
            return null;
        }
        boolean seekForward = fromHead <= (count - 1) / 2;
        long seekIndex = seekForward ? fromHead : count - 1 - fromHead;
        long accumulated = 0;
        Node node = seekForward ? head : tail;
        while (node != null && accumulated + node.count() <= seekIndex) {
            accumulated += node.count();
            node = seekForward ? node.next : node.prev;
        }
        if (node == null) {
            return null;
        }
        int offsetInNode = seekForward
                ? (int) (seekIndex - accumulated)
                : node.count() - 1 - (int) (seekIndex - accumulated);
        return new Cursor(node, offsetInNode, node.lp.seek(offsetInNode));
    }

    private void deleteAt(Node node, int p) {
        node.lp.delete(p);
        count--;
        if (node.lp.length() == 0) {
            unlink(node);
        }
    }

    /** 이 노드에 sz 바이트짜리 원소를 더 넣어도 되는지(_quicklistNodeAllowInsert). */
    private static boolean allowInsert(Node node, int sz) {
        if (node == null) {
            return false;
        }
        long newSize = (long) node.lp.bytes() + sz + SIZE_ESTIMATE_OVERHEAD;
        return newSize <= NODE_SIZE_LIMIT;
    }

    private void insertNodeAfter(Node oldNode, Node node) {
        if (oldNode == null) {
            head = tail = node;
        } else {
            node.prev = oldNode;
            node.next = oldNode.next;
            if (oldNode.next != null) {
                oldNode.next.prev = node;
            }
            oldNode.next = node;
            if (tail == oldNode) {
                tail = node;
            }
        }
        nodeCount++;
    }

    private void insertNodeBefore(Node oldNode, Node node) {
        if (oldNode == null) {
            head = tail = node;
        } else {
            node.next = oldNode;
            node.prev = oldNode.prev;
            if (oldNode.prev != null) {
                oldNode.prev.next = node;
            }
            oldNode.prev = node;
            if (head == oldNode) {
                head = node;
            }
        }
        nodeCount++;
    }

    private void unlink(Node node) {
        if (node.prev != null) {
            node.prev.next = node.next;
        } else {
            head = node.next;
        }
        if (node.next != null) {
            node.next.prev = node.prev;
        } else {
            tail = node.prev;
        }
        node.prev = node.next = null;
        nodeCount--;
    }
}
