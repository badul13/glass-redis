package glassredis.observe;

import java.util.List;

/** 이벤트·스냅샷 JSON 변환 - 시간은 ns, ms 숫자 그대로, 표시 단위는 화면 몫 */
final class DashboardJson {

    private DashboardJson() {
    }

    /** @param dropped 화면이 못 따라가 버린 개수 - 0이 아니면 화면에 끊김 표시 */
    static String activity(List<EventRecord> records, long dropped) {
        StringBuilder out = new StringBuilder(records.size() * 96 + 32);
        out.append('{');
        Json.field(out, "dropped", dropped);
        Json.name(out, "events");
        out.append('[');
        for (EventRecord record : records) {
            if (out.charAt(out.length() - 1) != '[') {
                out.append(',');
            }
            append(out, record);
        }
        out.append("]}");
        return out.toString();
    }

    static String snapshot(KeyspaceSnapshot snapshot) {
        StringBuilder out = new StringBuilder(snapshot.keys().size() * 64 + 32);
        out.append('{');
        Json.field(out, "total", snapshot.totalKeys());
        Json.field(out, "expiring", snapshot.expiringKeys());
        Json.name(out, "keys");
        out.append('[');
        for (KeyspaceSnapshot.KeyView key : snapshot.keys()) {
            if (out.charAt(out.length() - 1) != '[') {
                out.append(',');
            }
            out.append('{');
            Json.field(out, "key", key.key());
            Json.field(out, "type", key.type());
            Json.field(out, "encoding", key.encoding());
            Json.field(out, "size", key.size());
            Json.field(out, "ttl", key.ttlMillis());
            out.append('}');
        }
        out.append("]}");
        return out.toString();
    }

    static String sortedSet(SortedSetSnapshot snapshot) {
        StringBuilder out = new StringBuilder(snapshot.nodes().size() * 64 + 64);
        out.append('{');
        Json.field(out, "key", snapshot.key());
        Json.field(out, "status", snapshot.status());
        Json.field(out, "encoding", snapshot.encoding());
        Json.field(out, "length", snapshot.length());
        Json.field(out, "bytes", snapshot.bytes());
        Json.field(out, "level", snapshot.level());
        Json.name(out, "header");
        appendNumbers(out, snapshot.header());
        Json.name(out, "nodes");
        out.append('[');
        for (SortedSetSnapshot.NodeView node : snapshot.nodes()) {
            if (out.charAt(out.length() - 1) != '[') {
                out.append(',');
            }
            out.append('{');
            Json.field(out, "member", node.member());
            Json.field(out, "score", node.score());
            Json.name(out, "spans");
            appendNumbers(out, node.spans());
            out.append('}');
        }
        out.append("]}");
        return out.toString();
    }

    private static void appendNumbers(StringBuilder out, List<Long> numbers) {
        out.append('[');
        for (int i = 0; i < numbers.size(); i++) {
            if (i > 0) {
                out.append(',');
            }
            out.append(numbers.get(i));
        }
        out.append(']');
    }

    private static void append(StringBuilder out, EventRecord record) {
        out.append('{');
        Json.field(out, "seq", record.sequence());
        Json.field(out, "at", record.atMillis());
        switch (record.event()) {
            case Event.ClientConnected connected -> {
                Json.field(out, "type", "clientConnected");
                Json.field(out, "connection", connected.connectionId());
                Json.field(out, "peer", connected.peer());
            }
            case Event.ClientDisconnected disconnected -> {
                Json.field(out, "type", "clientDisconnected");
                Json.field(out, "connection", disconnected.connectionId());
            }
            case Event.CommandExecuted command -> {
                Json.field(out, "type", "command");
                Json.field(out, "connection", command.connectionId());
                Json.field(out, "name", command.name());
                Json.field(out, "args", command.arguments());
                Json.field(out, "nanos", command.durationNanos());
                Json.field(out, "reply", command.reply());
            }
            case Event.KeyRemoved removed -> {
                Json.field(out, "type", "keyRemoved");
                Json.field(out, "key", removed.key());
                Json.field(out, "reason", removed.reason().name());
                Json.field(out, "lateBy", removed.lateByMillis());
            }
            case Event.ExpiryCycleCompleted cycle -> {
                Json.field(out, "type", "expiryCycle");
                Json.field(out, "kind", cycle.kind());
                Json.field(out, "rounds", cycle.rounds());
                Json.field(out, "sampled", cycle.sampled());
                Json.field(out, "expired", cycle.expired());
                Json.field(out, "nanos", cycle.durationNanos());
                Json.field(out, "timeLimitHit", cycle.timeLimitHit());
                Json.field(out, "stalePercent", cycle.stalePercent());
            }
        }
        out.append('}');
    }
}
