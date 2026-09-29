package glassredis.command;

import glassredis.command.impl.AppendCommand;
import glassredis.command.impl.DelCommand;
import glassredis.command.impl.EchoCommand;
import glassredis.command.impl.ExistsCommand;
import glassredis.command.impl.ExpireCommand;
import glassredis.command.impl.GetCommand;
import glassredis.command.impl.HdelCommand;
import glassredis.command.impl.HexistsCommand;
import glassredis.command.impl.HgetCommand;
import glassredis.command.impl.HgetallCommand;
import glassredis.command.impl.HincrbyCommand;
import glassredis.command.impl.HlenCommand;
import glassredis.command.impl.HsetCommand;
import glassredis.command.impl.IncrementCommand;
import glassredis.command.impl.LindexCommand;
import glassredis.command.impl.LlenCommand;
import glassredis.command.impl.LrangeCommand;
import glassredis.command.impl.LremCommand;
import glassredis.command.impl.LtrimCommand;
import glassredis.command.impl.MgetCommand;
import glassredis.command.impl.MsetCommand;
import glassredis.command.impl.ObjectCommand;
import glassredis.command.impl.PersistCommand;
import glassredis.command.impl.PingCommand;
import glassredis.command.impl.PopCommand;
import glassredis.command.impl.PushCommand;
import glassredis.command.impl.QuitCommand;
import glassredis.command.impl.SaddCommand;
import glassredis.command.impl.ScardCommand;
import glassredis.command.impl.SetAlgebraCommand;
import glassredis.command.impl.SetCommand;
import glassredis.command.impl.SismemberCommand;
import glassredis.command.impl.SremCommand;
import glassredis.command.impl.StrlenCommand;
import glassredis.command.impl.TtlCommand;
import glassredis.command.impl.TypeCommand;
import glassredis.command.impl.ZaddCommand;
import glassredis.command.impl.ZcardCommand;
import glassredis.command.impl.ZcountCommand;
import glassredis.command.impl.ZincrbyCommand;
import glassredis.command.impl.ZrangeCommand;
import glassredis.command.impl.ZrankCommand;
import glassredis.command.impl.ZremCommand;
import glassredis.command.impl.ZscoreCommand;

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

    /** 지원하는 명령들. */
    public static CommandRegistry withBuiltins() {
        CommandRegistry registry = new CommandRegistry();
        registry.register(new PingCommand());
        registry.register(new EchoCommand());
        registry.register(new QuitCommand());

        registry.register(new GetCommand());
        registry.register(new SetCommand());
        registry.register(new DelCommand());
        registry.register(new ExistsCommand());
        registry.register(new MgetCommand());
        registry.register(new MsetCommand());
        registry.register(new AppendCommand());
        registry.register(new StrlenCommand());

        registry.register(IncrementCommand.incr());
        registry.register(IncrementCommand.decr());
        registry.register(IncrementCommand.incrBy());
        registry.register(IncrementCommand.decrBy());

        registry.register(ExpireCommand.seconds());
        registry.register(ExpireCommand.milliseconds());
        registry.register(TtlCommand.seconds());
        registry.register(TtlCommand.milliseconds());
        registry.register(new PersistCommand());
        registry.register(new TypeCommand());
        registry.register(new ObjectCommand());

        registry.register(PushCommand.left());
        registry.register(PushCommand.right());
        registry.register(PopCommand.left());
        registry.register(PopCommand.right());
        registry.register(new LlenCommand());
        registry.register(new LrangeCommand());
        registry.register(new LindexCommand());
        registry.register(new LremCommand());
        registry.register(new LtrimCommand());

        registry.register(new HsetCommand());
        registry.register(HgetCommand.single());
        registry.register(HgetCommand.multiple());
        registry.register(new HdelCommand());
        registry.register(new HlenCommand());
        registry.register(new HexistsCommand());
        registry.register(HgetallCommand.all());
        registry.register(HgetallCommand.fields());
        registry.register(HgetallCommand.values());
        registry.register(new HincrbyCommand());

        registry.register(new SaddCommand());
        registry.register(new SremCommand());
        registry.register(new SismemberCommand());
        registry.register(new ScardCommand());
        registry.register(SetAlgebraCommand.members());
        registry.register(SetAlgebraCommand.union());
        registry.register(SetAlgebraCommand.intersection());
        registry.register(SetAlgebraCommand.difference());

        registry.register(new ZaddCommand());
        registry.register(new ZincrbyCommand());
        registry.register(new ZremCommand());
        registry.register(new ZscoreCommand());
        registry.register(new ZcardCommand());
        registry.register(ZrankCommand.ascending());
        registry.register(ZrankCommand.descending());
        registry.register(new ZcountCommand());
        registry.register(ZrangeCommand.zrange());
        registry.register(ZrangeCommand.zrevrange());
        registry.register(ZrangeCommand.zrangeByScore());
        registry.register(ZrangeCommand.zrevrangeByScore());
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
