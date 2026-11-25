package glassredis.store.encoding;

/** 테스트 전용 - 다른 패키지 테스트에서 dict 의 rehash 기회 횟수(_dictRehashStep 호출 수) 확인 */
public final class DictProbe {

    private DictProbe() {
    }

    public static long rehashSteps(Dict<?> dict) {
        return dict.rehashSteps();
    }

    /** 진행 중인 rehash 는 끝내고 두 배 크기로 새로 시작 - 측정 도중 rehash 가 끝나지 않도록 */
    public static void startRehash(Dict<?> dict) {
        while (dict.isRehashing()) {
            dict.rehash(100);
        }
        dict.expand(dict.tableSize(0) * 2);
    }
}
