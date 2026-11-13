package glassredis.replication;

/**
 * jemalloc 크기 등급 - zmalloc_usable 이 돌려주는 실제 크기
 * 복제 버퍼 블록 크기가 이걸 따름 (요청 16KB + 헤더 → 20KB 등급)
 */
final class Jemalloc {

    private Jemalloc() {
    }

    /**
     * 요청 크기를 담는 가장 작은 등급
     * 8, 16 간격(~128), 그 위로는 2의 거듭제곱 구간마다 4등분
     */
    static long usableSize(long requested) {
        if (requested <= 8) {
            return 8;
        }
        if (requested <= 128) {
            return (requested + 15) & ~15L;
        }
        int group = 63 - Long.numberOfLeadingZeros(requested - 1);
        long spacing = 1L << (group - 2);
        return (requested + spacing - 1) & -spacing;
    }
}
