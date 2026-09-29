package glassredis.command.impl;

import glassredis.command.Command;
import glassredis.command.Context;
import glassredis.command.Errors;
import glassredis.command.Numbers;
import glassredis.resp.RespValue;
import glassredis.store.Entry;
import glassredis.store.Key;
import glassredis.store.Keyspace;
import glassredis.store.SortedSetValue;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.OptionalDouble;

/**
 * ZADD key [NX|XX] [GT|LT] [CH] [INCR] score member [score member ...] - 새 멤버 수
 * CH - 점수 바뀐 수도 합산
 * INCR - 결과 점수, 조건 때문에 미변경 시 nil
 * GT/LT - 새 멤버 추가는 허용
 * 점수 전체 검사 후 삽입 시작
 */
public final class ZaddCommand implements Command {

    @Override
    public String name() {
        return "ZADD";
    }

    @Override
    public RespValue execute(Context ctx, List<byte[]> args) {
        if (args.size() < 3) {
            return Errors.wrongNumberOfArguments(name());
        }

        boolean nx = false;
        boolean xx = false;
        boolean gt = false;
        boolean lt = false;
        boolean ch = false;
        boolean incr = false;
        int first = 1;
        options:
        for (; first < args.size(); first++) {
            switch (new String(args.get(first), StandardCharsets.US_ASCII).toUpperCase(Locale.ROOT)) {
                case "NX" -> nx = true;
                case "XX" -> xx = true;
                case "GT" -> gt = true;
                case "LT" -> lt = true;
                case "CH" -> ch = true;
                case "INCR" -> incr = true;
                default -> {
                    break options;
                }
            }
        }

        int pairsLength = args.size() - first;
        if (pairsLength == 0 || pairsLength % 2 != 0) {
            return Errors.syntaxError();
        }
        if (nx && xx) {
            return Errors.zaddNxXxNotCompatible();
        }
        if ((gt && nx) || (lt && nx) || (gt && lt)) {
            return Errors.zaddGtLtNxNotCompatible();
        }
        if (incr && pairsLength > 2) {
            return Errors.zaddIncrSinglePair();
        }

        double[] scores = new double[pairsLength / 2];
        for (int i = 0; i < scores.length; i++) {
            OptionalDouble score = Numbers.parseDouble(args.get(first + i * 2));
            if (score.isEmpty()) {
                return Errors.notAFloat();
            }
            scores[i] = score.getAsDouble();
        }

        Keyspace keyspace = ctx.keyspace();
        Key key = new Key(args.get(0));
        Entry entry = keyspace.get(key);
        SortedSetValue zset;
        if (entry == null) {
            if (xx) {
                return incr ? RespValue.NIL : new RespValue.Int(0);
            }
            // 삽입 개수와 첫 멤버 길이로 초기 인코딩 선택 - 나머지 멤버는 삽입 시 확인
            zset = SortedSetValue.create(scores.length, args.get(first + 1).length);
        } else if (entry.value() instanceof SortedSetValue existing) {
            zset = existing;
            zset.prepareForAdd(scores.length);
        } else {
            return Errors.wrongType();
        }

        long added = 0;
        long changed = 0;
        Double incrResult = null;
        for (int i = 0; i < scores.length; i++) {
            Key member = new Key(args.get(first + i * 2 + 1));
            Double current = zset.score(member);
            double score = scores[i];

            if (current == null) {
                if (xx) {
                    continue;
                }
                zset.put(member, score);
                added++;
                incrResult = score;
                continue;
            }

            if (nx) {
                continue;
            }
            if (incr) {
                score += current;
                if (Double.isNaN(score)) {
                    return Errors.scoreIsNaN();
                }
            }
            if ((gt && score <= current) || (lt && score >= current)) {
                continue;
            }
            if (score != current) {
                zset.put(member, score);
                changed++;
            }
            incrResult = score;
        }

        // 빈 Sorted Set이면 키 미등록
        if (entry == null && zset.size() > 0) {
            keyspace.put(key, Entry.of(zset));
        }
        if (incr) {
            return incrResult == null ? RespValue.NIL : new RespValue.BulkString(Numbers.formatDouble(incrResult));
        }
        return new RespValue.Int(ch ? added + changed : added);
    }
}
