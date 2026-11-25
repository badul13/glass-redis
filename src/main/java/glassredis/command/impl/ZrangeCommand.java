package glassredis.command.impl;

import glassredis.command.Command;
import glassredis.command.Context;
import glassredis.command.Errors;
import glassredis.command.IndexRange;
import glassredis.command.Lookup;
import glassredis.command.Numbers;
import glassredis.command.Options;
import glassredis.command.ScoreRanges;
import glassredis.resp.RespValue;
import glassredis.store.Key;
import glassredis.store.ScoreRange;
import glassredis.store.SortedSetValue;

import java.util.ArrayList;
import java.util.List;
import java.util.OptionalLong;

/**
 * ZRANGE key start stop [BYSCORE] [REV] [LIMIT offset count] [WITHSCORES]
 * ZREVRANGE, ZRANGEBYSCORE, ZREVRANGEBYSCORE - REV/BYSCORE를 이름으로 고정한 같은 구현 (zrangeGenericCommand)
 * REV 점수 구간은 max min 순서
 * BYSCORE/REV 는 한 번만 - 두 번째나 이름으로 정해진 방식의 재지정은 syntax error
 * 순위 방식 LIMIT 거부 - count 가 -1 이 아닐 때만 (opt_limit != -1), offset 은 무관
 * BYLEX 미지원 - syntax error
 */
public final class ZrangeCommand implements Command {

    private final String name;
    /** BYSCORE/REV 옵션은 ZRANGE 전용 - 나머지는 이름으로 방식 고정 */
    private final boolean acceptsModeOptions;
    /** 이름으로 정해진 점수 구간 방식 - ZRANGEBYSCORE 계열 */
    private final boolean scoreRange;
    /** 이름으로 정해진 역순 - ZREV* 계열 */
    private final boolean descending;

    private ZrangeCommand(String name, boolean acceptsModeOptions, boolean scoreRange, boolean descending) {
        this.name = name;
        this.acceptsModeOptions = acceptsModeOptions;
        this.scoreRange = scoreRange;
        this.descending = descending;
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
    public int arity() {
        return -4;
    }

    @Override
    public RespValue execute(Context ctx, List<byte[]> args) {
        boolean byScore = scoreRange;
        boolean reverse = descending;
        boolean withScores = false;
        long offset = 0;
        long count = -1;
        for (int i = 3; i < args.size(); i++) {
            byte[] option = args.get(i);
            if (Options.is(option, "WITHSCORES")) {
                withScores = true;
            } else if (Options.is(option, "LIMIT") && i + 2 < args.size()) {
                OptionalLong parsedOffset = Numbers.parseLong(args.get(i + 1));
                if (parsedOffset.isEmpty()) {
                    return Errors.notAnInteger();
                }
                OptionalLong parsedCount = Numbers.parseLong(args.get(i + 2));
                if (parsedCount.isEmpty()) {
                    return Errors.notAnInteger();
                }
                offset = parsedOffset.getAsLong();
                count = parsedCount.getAsLong();
                i += 2;
            } else if (acceptsModeOptions && !reverse && Options.is(option, "REV")) {
                reverse = true;
            } else if (acceptsModeOptions && !byScore && Options.is(option, "BYSCORE")) {
                byScore = true;
            } else {
                return Errors.syntaxError();
            }
        }
        if (count != -1 && !byScore) {
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

        Lookup<SortedSetValue> found = Lookup.zset(ctx, new Key(args.get(0)), RespValue.EMPTY_ARRAY);
        if (found.reply() != null) {
            return found.reply();
        }
        SortedSetValue zset = found.value();

        // 두 방식 모두 순위 구간으로 변환 후 조회
        long from;
        long to;
        if (byScore) {
            long size = zset.size();
            // 음수 offset 은 끝까지 건너뛰어 빈 결과, 크기 이상이면 즉시 빈 결과 (genericZrangebyscoreCommand)
            if (offset < 0 || offset >= size) {
                return RespValue.EMPTY_ARRAY;
            }
            long first = zset.firstRankIn(scores);
            long last = zset.lastRankIn(scores);
            if (first == -1 || last == -1 || first > last) {
                return RespValue.EMPTY_ARRAY;
            }
            // REV면 높은 점수 기준 순위로 변환
            from = reverse ? size - 1 - last : first;
            to = reverse ? size - 1 - first : last;
            // offset < size 라 덧셈 넘침 없음, count 는 남은 개수와 비교만 - 음수 count 는 무제한
            from += offset;
            if (count >= 0 && count < to - from + 1) {
                to = from + count - 1;
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
