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
 * SMEMBERS key, SINTER/SUNION/SDIFF key [key ...]
 * 기준 - Redis 7.2 t_set.c sinterGenericCommand, sunionDiffGenericCommand
 * 없는 키는 빈 Set 취급, 계산 전 전체 키 자료형 확인 후 WRONGTYPE 우선
 * 결과 순서 - Redis와 동일
 * - SINTER는 가장 작은 Set 순서
 * - SUNION/SDIFF는 빈 intset에서 시작한 임시 Set 순서
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

    /** 키 하나짜리 SINTER로 처리 - Redis와 동일 */
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

        // 없는 키는 null, 같은 키 반복 시 같은 객체
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
            return;
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
                return result; // 자기 자신 차감 시 빈 결과
            }
        }

        // 두 알고리즘 비용 비교 후 선택 (Redis와 동일) - 1번은 조기 종료 가능성 때문에 절반으로 계산
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
            // 1번 - 첫 Set 순회하며 나머지에 없는 원소만 수집, 큰 Set부터 확인
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

        // 2번 - 첫 Set 복사 후 나머지 원소 제거
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
