package glassredis.command;

import glassredis.command.impl.EchoCommand;
import glassredis.command.impl.PingCommand;
import glassredis.command.impl.QuitCommand;

import java.util.Collections;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 명령 이름 → 구현 매핑.
 *
 * <p>Redis 명령 이름은 대소문자를 가리지 않는다({@code ping} 과 {@code PING} 이 같다).
 * 그래서 등록할 때도 찾을 때도 대문자로 정규화한다.
 *
 * <p>등록은 서버를 기동하기 <b>전에</b> 끝난다. 그 뒤로는 여러 커넥션 스레드가
 * 동시에 읽기만 하므로 별도의 동기화가 필요 없다.
 */
public final class CommandRegistry {

    private final Map<String, Command> byName = new HashMap<>();

    /** 0단계에서 지원하는 명령들. */
    public static CommandRegistry withBuiltins() {
        CommandRegistry registry = new CommandRegistry();
        registry.register(new PingCommand());
        registry.register(new EchoCommand());
        registry.register(new QuitCommand());
        return registry;
    }

    public void register(Command command) {
        byName.put(command.name().toUpperCase(Locale.ROOT), command);
    }

    /** 못 찾으면 {@code null}. */
    public Command find(String name) {
        return byName.get(name.toUpperCase(Locale.ROOT));
    }

    public Set<String> names() {
        return Collections.unmodifiableSet(byName.keySet());
    }

    public int size() {
        return byName.size();
    }
}
