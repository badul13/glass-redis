package glassredis.store;

/**
 * 키 하나에 담긴 값. Redis 의 자료형 하나가 구현체 하나다.
 *
 * <p>sealed 로 닫아둔다. 자료형을 하나 늘리면 값을 종류별로 나눠 처리하는 {@code switch} 들을
 * 컴파일러가 전부 찾아준다. {@code TYPE} 명령, 대시보드 스냅샷 같은 곳이다.
 *
 * <p>문자열은 값을 바꿀 때 새 객체로 갈아 끼우지만, 모음(List, Hash, ...)은 제자리에서 고친다.
 * 원소 하나를 넣을 때마다 모음 전체를 복사할 수는 없다. 제자리에서 고쳐도 안전한 건
 * 키스페이스를 만지는 스레드가 실행 스레드 하나뿐이기 때문이다.
 */
public sealed interface Value permits StringValue, ListValue, HashValue, SetValue, SortedSetValue {

    /** {@code TYPE} 명령이 돌려주는 이름. */
    String typeName();

    /**
     * 지금 담긴 모양. {@code OBJECT ENCODING} 이 돌려주는 이름이다. 같은 자료형도 크기에 따라 모양이 바뀐다 —
     * 작을 때는 촘촘하게(listpack, intset, embstr), 커지면 빠르게(hashtable, skiplist, quicklist).
     */
    String encoding();

    /** 대시보드에 보여줄 크기. 문자열은 바이트 수, 모음은 원소 수다. */
    int size();
}
