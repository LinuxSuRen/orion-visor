package com.orion.visor.module.asset.handler.host.exec.command.handler;

import com.orion.lang.support.timeout.TimeoutChecker;
import com.orion.lang.support.timeout.TimeoutEndpoint;
import com.orion.lang.utils.Booleans;
import com.orion.lang.utils.ansi.AnsiAppender;
import com.orion.lang.utils.ansi.style.color.AnsiForeground;
import com.orion.lang.utils.time.Dates;
import com.orion.net.host.ssh.ExitCode;
import com.orion.visor.framework.common.constant.Const;
import com.orion.visor.module.asset.enums.ExecHostStatusEnum;
import com.orion.visor.module.asset.handler.host.exec.command.model.ExecCommandDTO;
import com.orion.visor.module.asset.handler.host.exec.command.model.ExecCommandHostDTO;

/**
 * 命令执行器 ansi 日志输出
 *
 * @author Jiahang Li
 * @version 1.0.0
 * @since 2024/4/25 18:19
 */
public class ExecCommandAnsiHandler extends BaseExecCommandHandler {

    public ExecCommandAnsiHandler(ExecCommandDTO execCommand, ExecCommandHostDTO execHostCommand, TimeoutChecker<TimeoutEndpoint> timeoutChecker) {
        super(execCommand, execHostCommand, timeoutChecker);
    }

    @Override
    protected void initLogOutputStream() throws Exception {
        super.initLogOutputStream();
        // 拼接启动日志
        AnsiAppender appender = AnsiAppender.create()
                .append(AnsiForeground.BRIGHT_GREEN, "> 准备执行命令    ")
                .append(Dates.current())
                .newLine()
                .append(AnsiForeground.BRIGHT_BLUE, "执行记录: ")
                .append(execCommand.getLogId())
                .newLine()
                .append(AnsiForeground.BRIGHT_BLUE, "执行描述: ")
                .append(execCommand.getDescription())
                .newLine()
                .append(AnsiForeground.BRIGHT_BLUE, "执行用户: ")
                .append(execCommand.getUsername());
        // 非系统用户执行添加 userId
        if (Const.SYSTEM_USER_ID.equals(execCommand.getUserId())) {
            appender.newLine();
        } else {
            appender.append(" (")
                    .append(execCommand.getUserId())
                    .append(")")
                    .newLine();
        }
        // 执行序列
        if (execCommand.getExecSeq() != null) {
            appender.append(AnsiForeground.BRIGHT_BLUE, "执行序列: ")
                    .append('#')
                    .append(execCommand.getExecSeq())
                    .newLine();
        }
        appender.append(AnsiForeground.BRIGHT_BLUE, "执行主机: ")
                .append(execHostCommand.getHostName())
                .append(" (")
                .append(execHostCommand.getHostId())
                .append(")")
                .newLine()
                .append(AnsiForeground.BRIGHT_BLUE, "主机地址: ")
                .append(execHostCommand.getHostAddress())
                .newLine()
                .append(AnsiForeground.BRIGHT_BLUE, "超时时间: ")
                .append(execCommand.getTimeout())
                .newLine()
                .append(AnsiForeground.BRIGHT_BLUE, "脚本执行: ")
                .append(execCommand.getScriptExec())
                .newLine()
                .newLine()
                .append(AnsiForeground.BRIGHT_GREEN, "> 执行命令        ")
                .newLine()
                .append(execHostCommand.getCommand())
                .newLine()
                .newLine();
        // 非脚本执行拼接开始执行日志
        if (!Booleans.isTrue(execCommand.getScriptExec())) {
            appender.append(AnsiForeground.BRIGHT_GREEN, "> 开始执行命令    ")
                    .append(Dates.current())
                    .newLine();
        }
        this.appendLog(appender);
    }

    @Override
    protected void uploadScriptFile() {
        try {
            // 拼接上传日志
            AnsiAppender startAppender = AnsiAppender.create()
                    .newLine()
                    .append(AnsiForeground.BRIGHT_GREEN, "> 准备上传脚本    ")
                    .append(Dates.current())
                    .newLine()
                    .append(AnsiForeground.BRIGHT_BLUE, "文件路径: ")
                    .append(execHostCommand.getScriptPath())
                    .newLine();
            this.appendLog(startAppender);
            // 上传脚本文件
            super.uploadScriptFile();
            // 拼接完成日志
            AnsiAppender finishAppender = AnsiAppender.create()
                    .append(AnsiForeground.BRIGHT_GREEN, "< 脚本上传成功    ")
                    .append(Dates.current())
                    .newLine()
                    .newLine()
                    .append(AnsiForeground.BRIGHT_GREEN, "> 开始执行脚本    ")
                    .append(Dates.current())
                    .newLine();
            this.appendLog(finishAppender);
        } catch (Exception e) {
            // 拼接失败日志
            AnsiAppender errorAppender = AnsiAppender.create()
                    .append(AnsiForeground.BRIGHT_RED, "< 脚本上传失败    ")
                    .append(Dates.current())
                    .newLine();
            this.appendLog(errorAppender);
            throw e;
        }
    }

    @Override
    protected void onFinishCallback(Exception e) {
        // 执行完成回调
        super.onFinishCallback(e);
        // 拼接日志
        AnsiAppender appender = AnsiAppender.create()
                .newLine();
        if (this.status == ExecHostStatusEnum.INTERRUPTED) {
            // 中断执行
            appender.append(AnsiForeground.BRIGHT_YELLOW, "< 命令执行中断    ")
                    .append(Dates.current())
                    .newLine();
        } else if (this.status == ExecHostStatusEnum.FAILED) {
            // 执行失败
            appender.append(AnsiForeground.BRIGHT_RED, "< 命令执行失败    ")
                    .append(Dates.current())
                    .newLine()
                    .append(AnsiForeground.BRIGHT_RED, "错误原因: ")
                    .append(this.getErrorMessage(e))
                    .newLine();
        } else if (this.status == ExecHostStatusEnum.TIMEOUT) {
            // 更新执行超时
            appender.append(AnsiForeground.BRIGHT_YELLOW, "< 命令执行超时    ")
                    .append(Dates.current())
                    .newLine();
        } else {
            long ms = updateRecord.getFinishTime().getTime() - updateRecord.getStartTime().getTime();
            Integer exitCode = updateRecord.getExitCode();
            // 执行完成
            appender.append(AnsiForeground.BRIGHT_GREEN, "< 命令执行完成    ")
                    .append(Dates.current())
                    .newLine()
                    .append(AnsiForeground.BRIGHT_BLUE, "exit:     ");
            if (ExitCode.isSuccess(exitCode)) {
                appender.append(AnsiForeground.BRIGHT_GREEN, exitCode);
            } else {
                appender.append(AnsiForeground.BRIGHT_RED, exitCode);
            }
            appender.newLine()
                    .append(AnsiForeground.BRIGHT_BLUE, "used:     ")
                    .append(Dates.interval(ms, false, "d ", "h ", "m ", "s"))
                    .append(" (")
                    .append(ms)
                    .append(" ms)")
                    .newLine();
        }
        // 拼接日志
        this.appendLog(appender);
    }

}
