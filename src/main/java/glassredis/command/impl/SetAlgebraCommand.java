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
import java.util.List;

/**
 * {@code SMEMBERS key} / {@code SINTER key [key ...]} / {@code SUNION key [key ...]} / {@code SDIFF key [key ...]}
 * Redis 7.2 {@code t_set.c} 의 sinterGenericCommand, sunionDiffGenericCommand 를 따른다.
 *
 * <p>없는 키는 빈 Set 으로 친다. 계산을 시작하기 전에 모든 키의 자료형부터 확인한다 —
 * 앞쪽 키가 비어서 결과가 빈 게 뻔해도, 뒤쪽 키가 Set 이 아니면 WRONGTYPE 이다.
 *
 * <h2>결과 순서</h2>
 * 순서를 약속하지 않는 명령들이지만, 실제 Redis 가 내는 순서를 그대로 따른다. 계산 방식에서 나오는 순서라서다.
 * <ul>
 *   <li>{@code SINTER}, {@code SMEMBERS}: Set 들을 크기순으로 정렬하고 <b>가장 작은 Set</b> 을 훑으며 나머지에
 *       다 있는지 본다. 그래서 가장 작은 Set 의 순서가 결과 순서다.</li>
 *   <li>{@code SUNION}, {@code SDIFF}: 결과를 빈 intset 에서 시작하는 임시 Set 에 모았다가 그걸 훑는다.
 *       정수만 모이면 intset 이라 오름차순, 문자열이 섞이면 listpack(넣은 순서)이나 hashtable(버킷 순서)이다.</li>
 * </ul>
 *
 * <h2>SDIFF 의 두 알고리즘</h2>
 * <ol>
 *   <li>첫 Set 을 훑으며 나머지 어디에도 없는 것만 담는다. 비용 ≈ 첫 Set 크기 × Set 수.</li>
 *   <li>첫 Set 을 전부 담은 뒤 나머지 Set 의 원소를 하나씩 뺀다. 비용 ≈ 모든 Set 크기의 합.</li>
 * </ol>
 * 두 비용을 계산해서 고른다. 1번은 공통 원소가 있으면 일찍 끝나서 절반으로 쳐 준다.
 */
public final class SetAlgebraCommand implements Command {

    private enum Operation { INTERSECTION, UNION, DIFFERENCE }

    private final String name;
    private final Operation operation;
    private final boolean singleKey;

    private SetAlgebraCommand(String name, Operation operation, boolean singleKey) {
        this.name = name;
        this.operation = operation;
        this.singleKey = singleKey;
    }

    /** 실제 Redis 에서도 SMEMBERS 는 키 하나짜리 SINTER 다. */
    public static SetAlgebraCommand members() {
        return new SetAlgebraCommand("SMEMBERS", Operation.INTERSECTION, true);
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

        // 없는 키는 null 로 둔다. 같은 키를 두 번 적으면 같은 객체가 두 번 들어간다.
        List<SetValue> sets = new ArrayList<>(args.size());
        for (byte[] key : args) {
            Entry entry = ctx.keyspace().get(new Key(key));
            if (entry == null) {
                sets.add(null);
            } else if (entry.value() instanceof SetValue set) {
                sets.add(set);
            } else {
                return Errors.wrongType();
            }
        }

        List<RespValue> items = new ArrayList<>();
        switch (operation) {
            case INTERSECTION -> intersect(sets, items);
            case UNION -> {
                SetValue result = SetValue.emptyIntset();
                for (SetValue set : sets) {
                    if (set != null) {
                        set.forEach(result::add);
                    }
                }
                result.forEach(member -> items.add(new RespValue.BulkString(member)));
            }
            case DIFFERENCE -> difference(sets).forEach(member -> items.add(new RespValue.BulkString(member)));
        }
        return new RespValue.Array(items);
    }

    private static void intersect(List<SetValue> sets, List<RespValue> items) {
        if (sets.contains(null)) {
            return; // 빈 Set 과의 교집합은 늘 비어 있다
        }
        List<SetValue> bySize = new ArrayList<>(sets);
        bySize.sort(Comparator.comparingInt(SetValue::size));
        SetValue smallest = bySize.get(0);
        smallest.forEach(member -> {
            for (int j = 1; j < bySize.size(); j++) {
                SetValue other = bySize.get(j);
                if (other != smallest && !other.contains(member)) {
                    return;
                }
            }
            items.add(new RespValue.BulkString(member));
        });
    }

    private static SetValue difference(List<SetValue> sets) {
        SetValue result = SetValue.emptyIntset();
        SetValue first = sets.get(0);
        if (first == null) {
            return result;
        }
        for (int j = 1; j < sets.size(); j++) {
            if (sets.get(j) == first) {
                return result; // 자기 자신을 빼면 늘 빈 결과다
            }
        }

        long algorithmOneWork = 0;
        long algorithmTwoWork = 0;
        for (SetValue set : sets) {
            if (set != null) {
                algorithmOneWork += first.size();
                algorithmTwoWork += set.size();
            }
        }
        algorithmOneWork /= 2;

        List<SetValue> others = new ArrayList<>(sets.subList(1, sets.size()));
        if (algorithmOneWork <= algorithmTwoWork) {
            // 큰 Set 부터 보면 걸리는 원소를 빨리 찾을 가능성이 높다.
            others.sort(Comparator.comparingInt((SetValue set) -> set == null ? 0 : set.size()).reversed());
            first.forEach(member -> {
                for (SetValue other : others) {
                    if (other != null && other.contains(member)) {
                        return;
                    }
                }
                result.add(member);
            });
            return result;
        }

        first.forEach(result::add);
        for (SetValue other : others) {
            if (other == null) {
                continue;
            }
            other.forEach(result::remove);
            if (result.size() == 0) {
                break;
            }
        }
        return result;
    }
}
