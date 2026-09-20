package glassredis.observe;

import glassredis.store.Entry;
import glassredis.store.Keyspace;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 어느 한 순간의 키스페이스. 대시보드의 키 목록이 이걸 그린다.
 *
 * <p>키스페이스는 실행 스레드만 만지므로 이 스냅샷도 그 스레드에서 떠야 한다.
 * HTTP 스레드가 직접 읽으면 "데이터를 만지는 스레드는 하나"라는 전제가 깨진다.
 *
 * <p>만료 시각이 지났지만 아직 지워지지 않은 키도 그대로 담는다. 숨기면 안 된다 —
 * "만료됐는데 메모리에는 남아 있는 키"가 화면에 보이는 것이 이 프로젝트의 요점이다.
 *
 * @param totalKeys    담긴 키의 총 수. 아래 목록이 잘렸어도 이 수는 전체를 센 것이다.
 * @param expiringKeys 그중 만료 시각이 있는 키의 수. 샘플링이 고르는 대상이다.
 */
public record KeyspaceSnapshot(int totalKeys, int expiringKeys, List<KeyView> keys) {

    /**
     * 목록에 담는 키의 최대 수.
     *
     * <p>제한이 필요한 이유는 정렬이다. 화면에서 키가 매번 다른 자리에 나타나면 읽을 수 없으므로
     * 이름순으로 정렬하는데, 키가 백만 개면 그 정렬이 실행 스레드를 잡아먹는다.
     * 데모로 몇십 개를 넣어 보는 동안에는 전부 보이고, 벤치마크로 키를 쏟아부은 상태에서는
     * 앞의 일부만 보인다. 총 개수는 {@link #totalKeys} 로 따로 알려준다.
     */
    public static final int DEFAULT_MAX_KEYS = 200;

    /**
     * 키 하나.
     *
     * @param valueBytes 값의 바이트 수. 값 자체는 담지 않는다 — 목록에 필요한 건 크기지 내용이 아니다.
     * @param ttlMillis  만료까지 남은 시간(ms). 만료 시각이 없으면 {@code null} 이고,
     *                   <b>이미 지났는데 아직 지워지지 않았으면 음수</b>다. 그 음수가 화면에서 제일 중요한 값이다.
     */
    public record KeyView(String key, int valueBytes, Long ttlMillis) {
    }

    public KeyspaceSnapshot {
        keys = List.copyOf(keys);
    }

    /** 실행 스레드에서 부른다. */
    public static KeyspaceSnapshot of(Keyspace keyspace, int maxKeys) {
        long now = keyspace.now();
        List<KeyView> keys = new ArrayList<>();
        keyspace.forEach((key, entry) -> {
            if (keys.size() < maxKeys) {
                keys.add(new KeyView(Display.text(key.bytes()), entry.value().length, ttl(entry, now)));
            }
        });
        keys.sort(Comparator.comparing(KeyView::key));
        return new KeyspaceSnapshot(keyspace.size(), keyspace.expiringKeyCount(), keys);
    }

    private static Long ttl(Entry entry, long now) {
        return entry.hasExpiry() ? entry.expireAtMillis() - now : null;
    }
}
