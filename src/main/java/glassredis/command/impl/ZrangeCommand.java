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
import glassredis.store.SortedSetValue;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.OptionalLong;

/**
 * ZRANGE key start stop [BYSCORE] [REV] [LIMIT offset count] [WITHSCORES]
 * ZREVRANGE, ZRANGEBYSCORE, ZREVRANGEBYSCORE - REV/BYSCORE를 이름으로 고정한 같은 구현
 * REV 점수 구간은 max min 순서
 * BYLEX 미지원 - syntax error
 */
public final class ZrangeCommand implements Command {

    private final String name;
    /** BYSCORE/REV 옵션은 ZRANGE 전용 */
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
        // 값 기준 판단 (Redis와 동일) - LIMIT 0 -1은 순위 방식에서도 허용
        if ((offset != 0 || count != -1) && !byScore) {
            return Errors.limitNeedsByScore();
        }

        ScoreRange scores = null;
        OptionalLong start = OptionalLong.empty();
        OptionalLong stop = OptionalLong.empty();
        if (byScore) {
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

        // 두 방식 모두 순위 구간으로 변환 후 조회
        long from;
        long to;
        if (byScore) {
            long first = zset.firstRankIn(scores);
            long last = zset.lastRankIn(scores);
            if (first == -1 || last == -1 || first > last || offset < 0) {
                return RespValue.EMPTY_ARRAY;
            }
            // REV면 높은 점수 기준 순위로 변환
            long size = zset.size();
            from = reverse ? size - 1 - last : first;
            to = reverse ? size - 1 - first : last;
            from += offset;
            // 음수 count는 무제한
            if (count >= 0) {
                to = Math.min(to, from + count - 1);
            }
            if (from > to) {
                return RespValue.EMPTY_ARRAY;
            }
        } else {
            IndexRange ranks = IndexRange.of(start.getAsLong(), stop.getAsLong(), zset.size());
            if (ranks == null) {
                return RespValue.EMPTY_ARRAY;
            }
            from = ranks.start();
            to = ranks.end();
        }

        List<RespValue> items = new ArrayList<>();
        boolean includeScores = withScores;
        zset.forEachInRankRange(from, to, reverse, (member, score) -> {
            items.add(new RespValue.BulkString(member));
            if (includeScores) {
                items.add(new RespValue.BulkString(Numbers.formatDouble(score)));
            }
        });
        return new RespValue.Array(items);
    }
}
