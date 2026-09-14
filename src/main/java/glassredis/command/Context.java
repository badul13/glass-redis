package glassredis.command;

import glassredis.store.Keyspace;

/**
 * 명령이 실행될 때 손에 쥐는 서버 상태.
 *
 * <p>명령 객체에 키스페이스를 생성자로 넣어두지 않고, 실행할 때마다 인자로 넘긴다.
 * 지금은 담긴 게 키스페이스 하나라 차이가 없다. 하지만 명령 객체는 모든 커넥션이 하나를 공유하므로,
 * 나중에 커넥션마다 다른 상태({@code MULTI} 로 쌓아둔 명령 등)가 필요해지면 생성자 주입으로는 담을 곳이 없다.
 * 실행 인자로 넘기는 구조라면 여기에 필드만 늘리면 된다.
 */
public record Context(Keyspace keyspace) {
}
