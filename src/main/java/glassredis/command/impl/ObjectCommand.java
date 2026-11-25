package glassredis.command.impl;

import glassredis.command.Command;
import glassredis.command.Context;
import glassredis.command.Errors;
import glassredis.command.Options;
import glassredis.resp.RespValue;
import glassredis.store.Entry;
import glassredis.store.Key;
import glassredis.store.StringValue;
import glassredis.store.Value;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * OBJECT ENCODING|REFCOUNT|IDLETIME|FREQ key, OBJECT HELP - objectCommand
 * - 하위 명령 이름 비교는 strcasecmp (NUL 에서 끝), 인자 수는 하위 명령별 arity (object|encoding 3 등)
 * - 키 조회는 만료 확인 포함·접근 시각 갱신 없음 (objectCommandLookup - LOOKUP_NOTOUCH), 없는 키는 nil
 * - maxmemory-policy noeviction 고정 기준 - FREQ 는 LFU 아님 에러
 * - IDLETIME - 값의 lru(초 단위 24비트 LRU 시계)와 지금 LRU 시계의 차, 0~9999 공유 정수는 lru 를 키끼리 공유
 */
public final class ObjectCommand implements Command {

    /** OBJ_SHARED_REFCOUNT - 공유 객체의 고정 참조 수 (INT_MAX) */
    private static final long SHARED_REFCOUNT = Integer.MAX_VALUE;

    private static final List<String> HELP = List.of(
            "ENCODING <key>",
            "    Return the kind of internal representation used in order to store the value",
            "    associated with a <key>.",
            "FREQ <key>",
            "    Return the access frequency index of the <key>. The returned integer is",
            "    proportional to the logarithm of the recent access frequency of the key.",
            "IDLETIME <key>",
            "    Return the idle time of the <key>, that is the approximated number of",
            "    seconds elapsed since the last access to the key.",
            "REFCOUNT <key>",
            "    Return the number of references of the value associated with the specified",
            "    <key>.");

    /** 하위 명령 이름 → 이름 포함 인자 수 (commands/object-*.json arity) */
    private enum Subcommand {
        ENCODING(3), FREQ(3), IDLETIME(3), REFCOUNT(3), HELP(2);

        final int arity;

        Subcommand(int arity) {
            this.arity = arity;
        }
    }

    @Override
    public String name() {
        return "OBJECT";
    }

    @Override
    public int arity() {
        return -2;
    }

    @Override
    public RespValue execute(Context ctx, List<byte[]> args) {
        Subcommand subcommand = find(args.get(0));
        if (subcommand == null) {
            return Errors.unknownSubcommand(args.get(0), name());
        }
        // 하위 명령 arity - 전부 고정 개수, 이름 포함이라 +1
        if (args.size() + 1 != subcommand.arity) {
            return Errors.wrongNumberOfArguments("object|" + subcommand.name().toLowerCase(Locale.ROOT));
        }
        if (subcommand == Subcommand.HELP) {
            return help();
        }

        Key key = new Key(args.get(1));
        Entry entry = ctx.keyspace().getNoTouch(key);
        if (entry == null) {
            return RespValue.NIL;
        }
        return switch (subcommand) {
            case ENCODING -> RespValue.BulkString.of(entry.value().encoding());
            case REFCOUNT -> new RespValue.Int(refcount(entry.value()));
            case IDLETIME -> new RespValue.Int(ctx.keyspace().idleSeconds(key));
            case FREQ -> Errors.lfuNotSelected();
            case HELP -> throw new IllegalStateException("HELP 는 위에서 처리");
        };
    }

    private static Subcommand find(byte[] name) {
        for (Subcommand subcommand : Subcommand.values()) {
            if (Options.is(name, subcommand.name())) {
                return subcommand;
            }
        }
        return null;
    }

    /** addReplyHelp - 첫 줄은 명령 이름, 끝은 HELP 설명, 모두 단순 문자열 */
    private RespValue help() {
        List<RespValue> lines = new ArrayList<>(HELP.size() + 3);
        lines.add(new RespValue.SimpleString(name() + " <subcommand> [<arg> [value] [opt] ...]. Subcommands are:"));
        HELP.forEach(line -> lines.add(new RespValue.SimpleString(line)));
        lines.add(new RespValue.SimpleString("HELP"));
        lines.add(new RespValue.SimpleString("    Print this help."));
        return new RespValue.Array(lines);
    }

    /**
     * robj.refcount - 키에 달린 값은 1, 공유 정수 객체만 INT_MAX
     * 공유 정수 - int 인코딩 문자열 중 0 이상 10000 미만 (tryObjectEncoding·createStringObjectFromLongLongForValue)
     */
    private static long refcount(Value value) {
        return value instanceof StringValue string && string.sharedIntegerIndex() >= 0 ? SHARED_REFCOUNT : 1;
    }
}
