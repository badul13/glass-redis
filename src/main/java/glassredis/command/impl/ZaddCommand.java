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
 * {@code ZADD key [NX | XX] [GT | LT] [CH] [INCR] score member [score member ...]}
 *
 * <p>멤버를 점수와 함께 넣는다. 이미 있는 멤버면 점수만 바꾼다. 응답은 <b>새로 들어간</b> 멤버 수다.
 * <ul>
 *   <li>{@code NX} — 새 멤버만 넣는다. 있는 멤버의 점수는 건드리지 않는다. {@code XX} — 있는 멤버의 점수만 바꾼다.</li>
 *   <li>{@code GT} / {@code LT} — 새 점수가 기존보다 클 때만 / 작을 때만 바꾼다. 새 멤버는 그대로 넣는다.</li>
 *   <li>{@code CH} — 응답에 "점수가 바뀐 멤버 수"까지 더한다.</li>
 *   <li>{@code INCR} — 점수를 덮어쓰지 않고 더하고, 결과 점수를 준다. {@code ZINCRBY} 와 같다.
 *       조건 때문에 안 바뀌었으면 nil 이다.</li>
 * </ul>
 *
 * <p>점수는 전부 읽어본 뒤에 넣기 시작한다. 중간에 실수가 아닌 점수가 있으면 앞쪽 멤버도 넣지 않는다.
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
            zset = new SortedSetValue();
        } else if (entry.value() instanceof SortedSetValue existing) {
            zset = existing;
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

        // 하나도 못 넣었으면 새 키를 만들지 않는다. 빈 Sorted Set 이 키스페이스에 남으면 안 된다.
        if (entry == null && zset.size() > 0) {
            keyspace.put(key, Entry.of(zset));
        }
        if (incr) {
            return incrResult == null ? RespValue.NIL : new RespValue.BulkString(Numbers.formatDouble(incrResult));
        }
        return new RespValue.Int(ch ? added + changed : added);
    }
}
