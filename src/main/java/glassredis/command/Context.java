package glassredis.command;

import glassredis.store.Keyspace;

/**
 * 명령 실행 시 넘기는 서버 상태
 * 명령 객체는 커넥션 간 공유 - 커넥션별 상태(MULTI 등) 추가 시 여기에 필드
 */
public record Context(Keyspace keyspace) {
}
