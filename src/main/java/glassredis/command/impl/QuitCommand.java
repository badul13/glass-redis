package glassredis.command.impl;

import glassredis.command.Command;
import glassredis.resp.RespValue;

import java.util.List;

/**
 * {@code QUIT} — {@code +OK} 를 보내고 커넥션을 닫는다.
 *
 * <p>응답을 먼저 보내고 닫는 순서가 중요하다. 그냥 소켓을 끊어버리면
 * 클라이언트는 정상 종료인지 사고인지 구분할 수 없다.
 */
public final class QuitCommand implements Command {

    @Override
    public String name() {
        return "QUIT";
    }

    @Override
    public RespValue execute(List<byte[]> args) {
        return RespValue.OK;
    }

    @Override
    public boolean closesConnection() {
        return true;
    }
}
