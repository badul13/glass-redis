package glassredis.command.impl;

import glassredis.command.Command;
import glassredis.command.Context;
import glassredis.command.Errors;
import glassredis.command.Lookup;
import glassredis.command.Numbers;
import glassredis.command.Options;
import glassredis.resp.RespValue;
import glassredis.store.Key;
import glassredis.store.Keyspace;
import glassredis.store.SortedSetValue;

import java.util.List;
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
    public int arity() {
        return -4;
    }

    @Override
    public boolean writes() {
        return true;
    }

    @Override
    public RespValue execute(Context ctx, List<byte[]> args) {
        return zadd(ctx, args, false);
    }

    /**
     * zaddGenericCommand - ZADD 와 ZINCRBY 공용
     *
     * @param incrByName ZINCRBY - INCR 옵션을 켠 채 시작, 옵션 단어 해석은 같음
     */
    static RespValue zadd(Context ctx, List<byte[]> args, boolean incrByName) {
        boolean nx = false;
        boolean xx = false;
        boolean gt = false;
        boolean lt = false;
        boolean ch = false;
        boolean incr = incrByName;
        int first = 1;
        for (; first < args.size(); first++) {
            byte[] option = args.get(first);
            if (Options.is(option, "NX")) {
                nx = true;
            } else if (Options.is(option, "XX")) {
                xx = true;
            } else if (Options.is(option, "CH")) {
                ch = true;
            } else if (Options.is(option, "INCR")) {
                incr = true;
            } else if (Options.is(option, "GT")) {
                gt = true;
            } else if (Options.is(option, "LT")) {
                lt = true;
            } else {
                break;
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
        Lookup<SortedSetValue> found = Lookup.zsetWrite(ctx, key, null);
        if (found.reply() != null) {
            return found.reply();
        }
        boolean created = found.value() == null;
        SortedSetValue zset;
        if (created) {
            if (xx) {
                return incr ? RespValue.NIL : new RespValue.Int(0);
            }
            // 삽입 개수와 첫 멤버 길이로 초기 인코딩 선택 - 나머지 멤버는 삽입 시 확인
            zset = SortedSetValue.create(scores.length, args.get(first + 1).length);
        } else {
            zset = found.value();
            zset.prepareForAdd(scores.length);
        }

        int inFlags = (incr ? SortedSetValue.IN_INCR : 0) | (nx ? SortedSetValue.IN_NX : 0)
                | (xx ? SortedSetValue.IN_XX : 0) | (gt ? SortedSetValue.IN_GT : 0) | (lt ? SortedSetValue.IN_LT : 0);
        long added = 0;
        long changed = 0;
        Double incrResult = null;
        for (int i = 0; i < scores.length; i++) {
            // zsetAdd - 멤버 찾기와 갱신을 한 번에 (skiplist 면 dictFind 한 번)
            SortedSetValue.AddResult result = zset.add(new Key(args.get(first + i * 2 + 1)), scores[i], inFlags);
            if (result.has(SortedSetValue.OUT_NAN)) {
                return Errors.scoreIsNaN();
            }
            if (result.has(SortedSetValue.OUT_ADDED)) {
                added++;
            }
            if (result.has(SortedSetValue.OUT_UPDATED)) {
                changed++;
            }
            if (!result.has(SortedSetValue.OUT_NOP)) {
                incrResult = result.newScore();
            }
        }

        ctx.dirty(added + changed);
        // 빈 Sorted Set이면 키 미등록
        if (created && zset.size() > 0) {
            keyspace.add(key, zset);
        }
        if (incr) {
            return incrResult == null ? RespValue.NIL : new RespValue.BulkString(Numbers.formatDouble(incrResult));
        }
        return new RespValue.Int(ch ? added + changed : added);
    }
}
