package glassredis.observe;

import java.util.List;

/**
 * 이벤트와 스냅샷을 브라우저가 읽을 JSON 으로 옮긴다.
 *
 * <p>시간은 전부 서버가 잰 그대로(ns, ms) 내보내고 사람이 읽을 단위로 바꾸는 건 화면에 맡긴다.
 * 여기서 "0.3ms" 같은 문자열로 만들어 보내면 화면에서 정렬하거나 그래프로 그릴 수 없다.
 */
final class DashboardJson {

    private DashboardJson() {
    }

    /**
     * 이번 구간에 일어난 일들.
     *
     * @param dropped 이 화면이 못 따라가서 버린 개수. 0 이 아니면 화면은 그 자리에 끊긴 표시를 해야 한다.
     */
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
            Json.field(out, "bytes", key.valueBytes());
            Json.field(out, "ttl", key.ttlMillis());
            out.append('}');
        }
        out.append("]}");
        return out.toString();
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
                Json.field(out, "rounds", cycle.rounds());
                Json.field(out, "sampled", cycle.sampled());
                Json.field(out, "expired", cycle.expired());
                Json.field(out, "nanos", cycle.durationNanos());
            }
        }
        out.append('}');
    }
}
