package glassredis.command.impl;

import glassredis.command.Command;
import glassredis.command.Context;
import glassredis.command.Errors;
import glassredis.command.Lookup;
import glassredis.command.Numbers;
import glassredis.command.Options;
import glassredis.resp.RespValue;
import glassredis.store.Key;
import glassredis.store.SortedSetValue;

import java.util.List;

/**
 * ZRANK/ZREVRANK key member [WITHSCORE] - 0부터 센 순위, 멤버 없으면 nil
 * WITHSCORE - [순위, 점수] 배열, 멤버·키 없으면 널 배열 (*-1)
 * 검사 순서 - 인자 초과, 옵션 단어, 키 조회, 자료형 (zrankGenericCommand)
 */
public final class ZrankCommand implements Command {

    private final String name;
    private final boolean reverse;

    private ZrankCommand(String name, boolean reverse) {
        this.name = name;
        this.reverse = reverse;
    }

    public static ZrankCommand ascending() {
        return new ZrankCommand("ZRANK", false);
    }

    public static ZrankCommand descending() {
        return new ZrankCommand("ZREVRANK", true);
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public int arity() {
        return -3;
    }

    @Override
    public RespValue execute(Context ctx, List<byte[]> args) {
        // 최대 개수 - arity -3 으로 표현 불가
        if (args.size() > 3) {
            return Errors.wrongNumberOfArguments(name);
        }
        boolean withScore = false;
        if (args.size() == 3) {
            if (!Options.is(args.get(2), "WITHSCORE")) {
                return Errors.syntaxError();
            }
            withScore = true;
        }
        RespValue none = withScore ? RespValue.NIL_ARRAY : RespValue.NIL;
        Lookup<SortedSetValue> found = Lookup.zset(ctx, new Key(args.get(0)), none);
        if (found.reply() != null) {
            return found.reply();
        }
        SortedSetValue zset = found.value();

        Key member = new Key(args.get(1));
        // zsetRank - 순위와 점수를 한 번의 조회로
        SortedSetValue.Ranked ranked = zset.rankWithScore(member);
        if (ranked == null) {
            return none;
        }
        long rank = ranked.rank();
        RespValue position = new RespValue.Int(reverse ? zset.size() - 1 - rank : rank);
        if (!withScore) {
            return position;
        }
        return RespValue.Array.of(position, new RespValue.BulkString(Numbers.formatDouble(ranked.score())));
    }
}
