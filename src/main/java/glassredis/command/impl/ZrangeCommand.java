package glassredis.command.impl;

import glassredis.command.Command;
import glassredis.command.Context;
import glassredis.command.Errors;
import glassredis.command.IndexRange;
import glassredis.command.Numbers;
import glassredis.command.ScoreRanges;
import glassredis.resp.RespValue;
import glassredis.store.Entry;
import glassredis.store.Key;
import glassredis.store.ScoreRange;
import glassredis.store.SkipList;
import glassredis.store.SortedSetValue;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.OptionalLong;

/**
 * 순서대로 꺼내는 명령 넷. 원래는 따로 있다가 Redis 6.2 에서 {@code ZRANGE} 하나가 옵션으로 전부 받게 됐다.
 * 옛 이름들도 그대로 쓰이므로 같은 구현에 이름만 달리 붙여 둔다.
 * <pre>
 *   ZRANGE key start stop [BYSCORE] [REV] [LIMIT offset count] [WITHSCORES]
 *   ZREVRANGE key start stop [WITHSCORES]                         = ZRANGE ... REV
 *   ZRANGEBYSCORE key min max [WITHSCORES] [LIMIT offset count]   = ZRANGE ... BYSCORE
 *   ZREVRANGEBYSCORE key max min [WITHSCORES] [LIMIT offset count] = ZRANGE ... BYSCORE REV
 * </pre>
 *
 * <p>두 방식으로 구간을 잡는다.
 * <ul>
 *   <li><b>순위</b>(기본) — {@code start stop} 이 0부터 세는 순위다. 규칙은 {@link IndexRange} 와 같다.
 *       첫 노드를 span 으로 O(log n) 에 찾고, 거기서부터 1층을 따라 걷는다.</li>
 *   <li><b>점수</b>({@code BYSCORE}) — {@code min max} 가 점수 구간이다. 표기는 {@link ScoreRanges}.
 *       {@code LIMIT} 으로 앞의 몇 개를 건너뛰고 몇 개만 받을 수 있다.</li>
 * </ul>
 * {@code REV} 면 높은 점수부터 거꾸로 걷는다. 이때 점수 구간은 {@code max min} 순서로 받는다.
 *
 * <p>{@code WITHSCORES} 를 주면 {@code [멤버1, 점수1, 멤버2, 점수2, ...]} 로 펼쳐서 준다.
 *
 * <p>사전순 구간({@code BYLEX})은 아직 없다. 주면 syntax error 다.
 */
public final class ZrangeCommand implements Command {

    private final String name;
    /** {@code ZRANGE} 만 BYSCORE / REV 를 옵션으로 받는다. 나머지는 이름에 박혀 있다. */
    private final boolean acceptsModeOptions;
    private final boolean byScoreByName;
    private final boolean reverseByName;

    private ZrangeCommand(String name, boolean acceptsModeOptions, boolean byScore, boolean reverse) {
        this.name = name;
        this.acceptsModeOptions = acceptsModeOptions;
        this.byScoreByName = byScore;
        this.reverseByName = reverse;
    }

    public static ZrangeCommand zrange() {
        return new ZrangeCommand("ZRANGE", true, false, false);
    }

    public static ZrangeCommand zrevrange() {
        return new ZrangeCommand("ZREVRANGE", false, false, true);
    }

    public static ZrangeCommand zrangeByScore() {
        return new ZrangeCommand("ZRANGEBYSCORE", false, true, false);
    }

    public static ZrangeCommand zrevrangeByScore() {
        return new ZrangeCommand("ZREVRANGEBYSCORE", false, true, true);
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public RespValue execute(Context ctx, List<byte[]> args) {
        if (args.size() < 3) {
            return Errors.wrongNumberOfArguments(name);
        }

        boolean byScore = byScoreByName;
        boolean reverse = reverseByName;
        boolean withScores = false;
        long offset = 0;
        long count = -1;
        for (int i = 3; i < args.size(); i++) {
            String option = new String(args.get(i), StandardCharsets.US_ASCII).toUpperCase(Locale.ROOT);
            if (option.equals("WITHSCORES")) {
                withScores = true;
            } else if (option.equals("LIMIT") && i + 2 < args.size()) {
                OptionalLong parsedOffset = Numbers.parseLong(args.get(i + 1));
                OptionalLong parsedCount = Numbers.parseLong(args.get(i + 2));
                if (parsedOffset.isEmpty() || parsedCount.isEmpty()) {
                    return Errors.notAnInteger();
                }
                offset = parsedOffset.getAsLong();
                count = parsedCount.getAsLong();
                i += 2;
            } else if (option.equals("BYSCORE") && acceptsModeOptions) {
                byScore = true;
            } else if (option.equals("REV") && acceptsModeOptions) {
                reverse = true;
            } else {
                return Errors.syntaxError();
            }
        }
        // LIMIT 0 -1 은 "제한 없음"이라 순위 방식에서도 받아준다. 실제 Redis 가 값으로 판단하기 때문이다.
        if ((offset != 0 || count != -1) && !byScore) {
            return Errors.limitNeedsByScore();
        }

        ScoreRange scores = null;
        OptionalLong start = OptionalLong.empty();
        OptionalLong stop = OptionalLong.empty();
        if (byScore) {
            // 거꾸로 걸을 때는 max 가 먼저 온다. 높은 쪽에서 출발한다는 걸 인자 순서로도 드러내려는 Redis 의 설계다.
            scores = reverse ? ScoreRanges.parse(args.get(2), args.get(1)) : ScoreRanges.parse(args.get(1), args.get(2));
            if (scores == null) {
                return Errors.minOrMaxNotAFloat();
            }
        } else {
            start = Numbers.parseLong(args.get(1));
            stop = Numbers.parseLong(args.get(2));
            if (start.isEmpty() || stop.isEmpty()) {
                return Errors.notAnInteger();
            }
        }

        Entry entry = ctx.keyspace().get(new Key(args.get(0)));
        if (entry == null) {
            return RespValue.EMPTY_ARRAY;
        }
        if (!(entry.value() instanceof SortedSetValue zset)) {
            return Errors.wrongType();
        }

        SkipList order = zset.order();
        List<RespValue> items = new ArrayList<>();
        if (byScore) {
            if (offset < 0) {
                return RespValue.EMPTY_ARRAY;
            }
            SkipList.Node node = reverse ? order.lastInRange(scores) : order.firstInRange(scores);
            for (long skipped = 0; node != null && skipped < offset; skipped++) {
                node = step(node, reverse);
            }
            // 음수 count 는 "제한 없음"이다.
            for (long taken = 0; node != null && (count < 0 || taken < count); taken++) {
                if (reverse ? !scores.aboveMin(node.score()) : !scores.belowMax(node.score())) {
                    break;
                }
                add(items, node, withScores);
                node = step(node, reverse);
            }
        } else {
            IndexRange ranks = IndexRange.of(start.getAsLong(), stop.getAsLong(), zset.size());
            if (ranks == null) {
                return RespValue.EMPTY_ARRAY;
            }
            // 스킵 리스트의 순위는 1부터 센다. 거꾸로면 뒤에서 start 번째가 출발점이다.
            SkipList.Node node = order.byRank(reverse ? zset.size() - ranks.start() : ranks.start() + 1);
            for (int i = 0; i < ranks.count(); i++) {
                add(items, node, withScores);
                node = step(node, reverse);
            }
        }
        return new RespValue.Array(items);
    }

    private static SkipList.Node step(SkipList.Node node, boolean reverse) {
        return reverse ? node.previous() : node.next();
    }

    private static void add(List<RespValue> items, SkipList.Node node, boolean withScores) {
        items.add(new RespValue.BulkString(node.member()));
        if (withScores) {
            items.add(new RespValue.BulkString(Numbers.formatDouble(node.score())));
        }
    }
}
