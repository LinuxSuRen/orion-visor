package com.orion.ops.module.asset.handler.host.terminal.handler;

import com.orion.ops.framework.common.enums.BooleanBit;
import com.orion.ops.module.asset.handler.host.terminal.enums.OutputTypeEnum;
import com.orion.ops.module.asset.handler.host.terminal.model.request.SftpBaseRequest;
import com.orion.ops.module.asset.handler.host.terminal.model.response.SftpBaseResponse;
import com.orion.ops.module.asset.handler.host.terminal.session.ISftpSession;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketSession;

/**
 * sftp 截断文件
 *
 * @author Jiahang Li
 * @version 1.0.0
 * @since 2024/2/19 11:13
 */
@Slf4j
@Component
public class SftpTruncateHandler extends AbstractTerminalHandler<SftpBaseRequest> {

    @Override
    public void handle(WebSocketSession channel, SftpBaseRequest payload) {
        // 获取会话
        ISftpSession session = terminalManager.getSession(channel.getId(), payload.getSessionId());
        String path = payload.getPath();
        log.info("SftpTruncateHandler-handle session: {}, path: {}", payload.getSessionId(), path);
        Exception ex = null;
        // 截断文件
        try {
            session.truncate(path);
        } catch (Exception e) {
            log.error("SftpTruncateHandler-handle error", e);
            ex = e;
        }
        // 返回
        this.send(channel,
                OutputTypeEnum.SFTP_TRUNCATE,
                SftpBaseResponse.builder()
                        .sessionId(payload.getSessionId())
                        .result(BooleanBit.of(ex == null).getValue())
                        .msg(this.getErrorMessage(ex))
                        .build());
    }

}
