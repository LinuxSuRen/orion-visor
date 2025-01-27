package com.orion.visor.module.asset.handler.host.upload.uploader;

import com.orion.lang.utils.Strings;
import com.orion.lang.utils.collect.Maps;
import com.orion.lang.utils.io.Files1;
import com.orion.lang.utils.io.Streams;
import com.orion.net.host.SessionStore;
import com.orion.net.host.sftp.SftpExecutor;
import com.orion.spring.SpringHolder;
import com.orion.visor.framework.common.constant.Const;
import com.orion.visor.framework.common.file.FileClient;
import com.orion.visor.framework.common.utils.PathUtils;
import com.orion.visor.module.asset.dao.UploadTaskFileDAO;
import com.orion.visor.module.asset.define.config.AppSftpConfig;
import com.orion.visor.module.asset.entity.domain.UploadTaskFileDO;
import com.orion.visor.module.asset.entity.dto.HostTerminalConnectDTO;
import com.orion.visor.module.asset.enums.HostSshOsTypeEnum;
import com.orion.visor.module.asset.enums.UploadTaskFileStatusEnum;
import com.orion.visor.module.asset.handler.host.upload.model.FileUploadFileItemDTO;
import com.orion.visor.module.asset.service.HostTerminalService;
import com.orion.visor.module.asset.service.UploadTaskService;
import com.orion.visor.module.asset.utils.SftpUtils;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

import java.io.InputStream;
import java.io.OutputStream;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 主机文件上传器 实现类
 *
 * @author Jiahang Li
 * @version 1.0.0
 * @since 2024/5/8 13:41
 */
@Slf4j
public class FileUploader implements IFileUploader {

    private static final HostTerminalService hostTerminalService = SpringHolder.getBean(HostTerminalService.class);

    private static final UploadTaskFileDAO uploadTaskFileDAO = SpringHolder.getBean(UploadTaskFileDAO.class);

    private static final AppSftpConfig SFTP_CONFIG = SpringHolder.getBean(AppSftpConfig.class);

    private static final FileClient localFileClient = SpringHolder.getBean("localFileClient");

    private SessionStore sessionStore;

    private SftpExecutor executor;

    private final Long taskId;

    private final Long hostId;

    @Getter
    private final List<FileUploadFileItemDTO> files;

    private InputStream inputStream;

    private OutputStream outputStream;

    private volatile boolean canceled;

    private volatile boolean closed;

    public FileUploader(Long taskId, Long hostId, List<FileUploadFileItemDTO> files) {
        this.taskId = taskId;
        this.hostId = hostId;
        this.files = files;
    }

    @Override
    public void run() {
        try {
            // 初始化会话
            boolean run = this.initSession();
            if (!run) {
                return;
            }
            // 上传文件
            for (FileUploadFileItemDTO file : files) {
                if (closed) {
                    break;
                }
                // 上传
                this.uploadFile(file);
            }
            // 检查是否取消
            this.finishCheckCancel();
        } finally {
            // 释放资源
            this.close();
        }
    }

    /**
     * 初始化会话
     *
     * @return 是否执行
     */
    private boolean initSession() {
        log.info("HostFileUploader.initSession start taskId: {}, hostId: {}", taskId, hostId);
        try {
            // 替换用户路径
            HostTerminalConnectDTO connectInfo = hostTerminalService.getTerminalConnectInfo(hostId);
            this.replaceRemotePathVariable(connectInfo.getOsType(), connectInfo.getUsername());
            // 打开会话
            this.sessionStore = hostTerminalService.openSessionStore(connectInfo);
            this.executor = sessionStore.getSftpExecutor(connectInfo.getFileNameCharset());
            executor.connect();
            log.info("HostFileUploader.initSession success taskId: {}, hostId: {}", taskId, hostId);
            return true;
        } catch (Exception e) {
            log.error("HostFileUploader.initSession error taskId: {}, hostId: {}", taskId, hostId, e);
            // 修改状态
            uploadTaskFileDAO.updateStatusByTaskHostId(taskId, hostId, UploadTaskFileStatusEnum.FAILED.name());
            files.forEach(s -> s.setStatus(UploadTaskFileStatusEnum.FAILED.name()));
            return false;
        }
    }

    /**
     * 上传文件
     *
     * @param file file
     */
    private void uploadFile(FileUploadFileItemDTO file) {
        log.info("HostFileUploader.uploadFile start taskId: {}, hostId: {}, id: {}", taskId, hostId, file.getId());
        // 修改状态
        this.updateStatus(file, UploadTaskFileStatusEnum.UPLOADING);
        try {
            // 获取本地文件路径
            String endpoint = Strings.format(UploadTaskService.SWAP_ENDPOINT, taskId);
            String localPath = localFileClient.getReturnPath(endpoint + Const.SLASH + file.getFileId());
            // 检查文件是否存在
            String remotePath = file.getRemotePath();
            SftpUtils.checkUploadFilePresent(SFTP_CONFIG, executor, remotePath);
            // 打开输出流
            this.inputStream = localFileClient.getContentInputStream(localPath);
            this.outputStream = executor.openOutputStream(remotePath);
            // 传输
            byte[] buffer = new byte[executor.getBufferSize()];
            int read;
            while ((read = inputStream.read(buffer)) != -1) {
                outputStream.write(buffer, 0, read);
                file.setCurrent(file.getCurrent() + read);
            }
            outputStream.flush();
            // 修改状态
            this.updateStatus(file, UploadTaskFileStatusEnum.FINISHED);
            log.info("HostFileUploader.uploadFile finish taskId: {}, hostId: {}, id: {}", taskId, hostId, file.getId());
        } catch (Exception e) {
            log.info("HostFileUploader.uploadFile error taskId: {}, hostId: {}, id: {}, canceled: {}", taskId, hostId, file.getId(), canceled);
            // 修改状态
            if (canceled) {
                this.updateStatus(file, UploadTaskFileStatusEnum.CANCELED);
            } else {
                this.updateStatus(file, UploadTaskFileStatusEnum.FAILED);
            }
        } finally {
            // 释放文件
            this.resetFile();
        }
    }

    /**
     * 释放文件
     */
    private void resetFile() {
        Streams.close(outputStream);
        Streams.close(inputStream);
    }

    /**
     * 检查是否取消
     */
    private void finishCheckCancel() {
        if (!canceled) {
            return;
        }
        // 将等待中的文件修改为已取消
        List<Long> idList = files.stream()
                .filter(s -> UploadTaskFileStatusEnum.WAITING.name().equals(s.getStatus()))
                .map(FileUploadFileItemDTO::getId)
                .collect(Collectors.toList());
        if (idList.isEmpty()) {
            return;
        }
        // 修改状态
        uploadTaskFileDAO.updateStatusByIdList(idList, UploadTaskFileStatusEnum.CANCELED.name());
    }

    /**
     * 更新状态
     *
     * @param file   file
     * @param status status
     */
    private void updateStatus(FileUploadFileItemDTO file, UploadTaskFileStatusEnum status) {
        file.setStatus(status.name());
        UploadTaskFileDO update = new UploadTaskFileDO();
        update.setId(file.getId());
        update.setStatus(status.name());
        if (UploadTaskFileStatusEnum.UPLOADING.equals(status)) {
            // 上传中
            update.setStartTime(new Date());
        } else if (UploadTaskFileStatusEnum.FINISHED.equals(status)) {
            // 已完成
            update.setEndTime(new Date());
        } else if (UploadTaskFileStatusEnum.FAILED.equals(status)) {
            // 已失败
            update.setEndTime(new Date());
        } else if (UploadTaskFileStatusEnum.CANCELED.equals(status)) {
            // 已失败
            update.setEndTime(new Date());
        }
        uploadTaskFileDAO.updateById(update);
    }

    /**
     * 替换文件路径变量
     *
     * @param osType   osType
     * @param username username
     */
    private void replaceRemotePathVariable(String osType, String username) {
        // 包含变量
        if (!files.get(0).getRemotePath().contains(Const.DOLLAR)) {
            return;
        }
        String home = PathUtils.getHomePath(HostSshOsTypeEnum.WINDOWS.name().equals(osType), username);
        // 替换变量
        Map<String, String> env = Maps.newMap(4);
        env.put("username", username);
        env.put("home", home);
        for (FileUploadFileItemDTO file : files) {
            file.setRemotePath(Files1.getPath(Strings.format(file.getRemotePath(), env)));
        }
    }

    @Override
    public void cancel() {
        log.info("HostFileUploader.cancel taskId: {}, hostId: {}, canceled: {}, closed: {}", taskId, hostId, canceled, closed);
        if (this.canceled || this.closed) {
            return;
        }
        // 关闭
        this.canceled = true;
        this.close();
    }

    @Override
    public void close() {
        log.info("HostFileUploader.close taskId: {}, hostId: {}, closed: {}", taskId, hostId, closed);
        if (closed) {
            return;
        }
        this.closed = true;
        // 释放资源
        Streams.close(outputStream);
        Streams.close(inputStream);
        Streams.close(executor);
        Streams.close(sessionStore);
    }

}
