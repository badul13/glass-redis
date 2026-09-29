package glassredis.command.impl;

import glassredis.command.Command;
import glassredis.command.Context;
import glassredis.command.Errors;
import glassredis.resp.RespValue;
import glassredis.store.Entry;
import glassredis.store.Key;
import glassredis.store.SetValue;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * {@code SMEMBERS key} / {@code SINTER key [key ...]} / {@code SUNION key [key ...]} / {@code SDIFF key [key ...]}
 *
 * <p>없는 키는 빈 Set 으로 친다. 그래서 {@code SINTER} 에 없는 키가 하나라도 끼면 결과는 빈 배열이다.
 * {@code SDIFF} 는 첫 번째 Set 에서 나머지를 뺀다. 순서가 의미 있는 건 이것 하나다.
 * {@code SMEMBERS k} 는 키 하나짜리 {@code SUNION} 과 같아서 여기서 같이 처리한다.
 *
 * <p>계산을 시작하기 전에 모든 키의 자료형부터 확인한다. 실제 Redis 도 그렇다.
 * 앞쪽 키가 비어서 결과가 빈 게 뻔해도, 뒤쪽 키가 Set 이 아니면 WRONGTYPE 이다.
 */
public final class SetAlgebraCommand implements Command {

    private enum Operation { UNION, INTERSECTION, DIFFERENCE }

    private final String name;
    private final Operation operation;
    private final boolean singleKey;

    private SetAlgebraCommand(String name, Operation operation, boolean singleKey) {
        this.name = name;
        this.operation = operation;
        this.singleKey = singleKey;
    }

    public static SetAlgebraCommand members() {
        return new SetAlgebraCommand("SMEMBERS", Operation.UNION, true);
    }

    public static SetAlgebraCommand union() {
        return new SetAlgebraCommand("SUNION", Operation.UNION, false);
    }

    public static SetAlgebraCommand intersection() {
        return new SetAlgebraCommand("SINTER", Operation.INTERSECTION, false);
    }

    public static SetAlgebraCommand difference() {
        return new SetAlgebraCommand("SDIFF", Operation.DIFFERENCE, false);
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public RespValue execute(Context ctx, List<byte[]> args) {
        if (singleKey ? args.size() != 1 : args.isEmpty()) {
            return Errors.wrongNumberOfArguments(name);
        }

        List<Set<Key>> sets = new ArrayList<>(args.size());
        for (byte[] key : args) {
            Entry entry = ctx.keyspace().get(new Key(key));
            if (entry == null) {
                sets.add(Set.of());
            } else if (entry.value() instanceof SetValue set) {
                sets.add(set.members());
            } else {
                return Errors.wrongType();
            }
        }

        Set<Key> result = switch (operation) {
            case UNION -> {
                // 키 하나(SMEMBERS)면 합칠 게 없으니 복사하지 않고 그대로 읽는다.
                if (sets.size() == 1) {
                    yield sets.get(0);
                }
                Set<Key> union = new LinkedHashSet<>();
                sets.forEach(union::addAll);
                yield union;
            }
            case INTERSECTION -> {
                // 가장 작은 Set 을 기준으로 나머지에 있는지 본다. 비용이 "가장 작은 Set 크기 × Set 수"로 줄어든다.
                Set<Key> smallest = sets.stream().min(Comparator.comparingInt(Set::size)).orElseThrow();
                Set<Key> intersection = new LinkedHashSet<>();
                for (Key member : smallest) {
                    if (sets.stream().allMatch(set -> set.contains(member))) {
                        intersection.add(member);
                    }
                }
                yield intersection;
            }
            case DIFFERENCE -> {
                Set<Key> difference = new LinkedHashSet<>(sets.get(0));
                sets.subList(1, sets.size()).forEach(difference::removeAll);
                yield difference;
            }
        };

        List<RespValue> items = new ArrayList<>(result.size());
        for (Key member : result) {
            items.add(new RespValue.BulkString(member.bytes()));
        }
        return new RespValue.Array(items);
    }
}
