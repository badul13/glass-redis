package glassredis.store;

/**
 * 키 하나에 담긴 값 - 자료형마다 구현체 하나
 * 문자열은 불변, 모음 자료형은 제자리 수정 - 실행 스레드 전용이라 안전
 */
public sealed interface Value permits StringValue, ListValue, HashValue, SetValue, SortedSetValue {

    /** TYPE 응답 */
    String typeName();

    /** OBJECT ENCODING 응답 - 크기에 따라 변동 */
    String encoding();

    /** 문자열은 바이트 수, 모음은 원소 수 */
    int size();
}
