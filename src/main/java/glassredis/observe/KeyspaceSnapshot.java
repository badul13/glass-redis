package glassredis.observe;

import glassredis.store.Entry;
import glassredis.store.Keyspace;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 키스페이스의 한 시점 - 실행 스레드 전용
 * 만료됐지만 아직 안 지워진 키도 포함
 *
 * @param totalKeys    전체 키 수 - 목록이 잘려도 전체 기준
 * @param expiringKeys 만료 시각이 있는 키 수(expires 테이블 크기)
 */
public record KeyspaceSnapshot(int totalKeys, int expiringKeys, List<KeyView> keys) {

    /** 목록 크기 제한 - 이름순 정렬의 실행 스레드 점유 방지 */
    public static final int DEFAULT_MAX_KEYS = 200;

    /**
     * @param type       TYPE 응답과 동일
     * @param encoding   OBJECT ENCODING 응답과 동일
     * @param size       문자열은 바이트 수, 모음은 원소 수
     * @param ttlMillis  남은 시간(ms) - 만료 시각 없으면 null, 만료됐지만 안 지워졌으면 음수
     */
    public record KeyView(String key, String type, String encoding, int size, Long ttlMillis) {
    }

    public KeyspaceSnapshot {
        keys = List.copyOf(keys);
    }

    /** 실행 스레드 전용 */
    public static KeyspaceSnapshot of(Keyspace keyspace, int maxKeys) {
        long now = keyspace.now();
        List<KeyView> keys = new ArrayList<>();
        keyspace.forEach((key, entry) -> {
            if (keys.size() < maxKeys) {
                keys.add(new KeyView(Display.text(key.bytes()), entry.value().typeName(), entry.value().encoding(),
                        entry.value().size(), ttl(entry, now)));
            }
        });
        keys.sort(Comparator.comparing(KeyView::key));
        return new KeyspaceSnapshot(keyspace.size(), keyspace.expiringKeyCount(), keys);
    }

    private static Long ttl(Entry entry, long now) {
        return entry.hasExpiry() ? entry.expireAtMillis() - now : null;
    }
}
