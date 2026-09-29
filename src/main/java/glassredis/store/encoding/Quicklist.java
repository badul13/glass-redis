package glassredis.store.encoding;

import java.util.ArrayList;
import java.util.List;

/**
 * 8KB 남짓 listpack 노드의 이중 연결 리스트 - Redis 7.2 quicklist.c
 * 미이식 - plain 노드(값 한도 512MB라 불필요), LZF 압축(list-compress-depth 기본값 0)
 */
public final class Quicklist {

    /** list-max-listpack-size -2 */
    public static final int NODE_SIZE_LIMIT = 8192;
    /** 인코딩 머리·backlen 몫 여유분 포함 */
    private static final int SIZE_ESTIMATE_OVERHEAD = 8;

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

    /** 원소 수 */
    public long count() {
        return count;
    }

    public int nodeCount() {
        return nodeCount;
    }

    public Node head() {
        return head;
    }

    /** listpack을 복사 없이 노드로 연결 - quicklistAppendListpack */
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

    /** 비었으면 null, 빈 노드는 분리 */
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

    /** 음수는 뒤에서부터, 범위 밖이면 null */
    public byte[] index(long index) {
        Cursor cursor = cursorAt(index);
        return cursor == null ? null : cursor.node.lp.get(cursor.p);
    }

    /** start..end 양 끝 포함 - 범위 보정은 호출 측 책임 */
    public List<byte[]> range(long start, long end) {
        List<byte[]> out = new ArrayList<>((int) (end - start + 1));
        Cursor cursor = cursorAt(start);
        for (long i = start; i <= end && cursor != null; i++) {
            out.add(cursor.node.lp.get(cursor.p));
            cursor = cursor.forward();
        }
        return out;
    }

    /** LREM - 빈 노드는 분리, 이웃과 병합 없음(Redis와 동일) */
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
                    // delete 반환값은 다음 원소 위치 - 역방향 탐색 중이면 그 앞으로 이동
                    p = fromTail ? (nextP == -1 ? node.lp.last() : node.lp.prev(nextP)) : nextP;
                } else {
                    p = fromTail ? node.lp.prev(p) : node.lp.next(p);
                }
            }
            node = neighbor;
        }
        return removed;
    }

    /** quicklistDelRange - start는 0 이상 */
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

    /** offset은 노드 안 순번, p는 listpack 안 바이트 위치 */
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

    /** 가까운 끝에서 노드 단위 건너뛰기 - quicklistGetIteratorAtIdx, 음수는 뒤에서부터 */
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

    /** _quicklistNodeAllowInsert */
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
