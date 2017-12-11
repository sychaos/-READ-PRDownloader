/*
 *    Copyright (C) 2017 MINDORKS NEXTGEN PRIVATE LIMITED
 *
 *    Licensed under the Apache License, Version 2.0 (the "License");
 *    you may not use this file except in compliance with the License.
 *    You may obtain a copy of the License at
 *
 *        http://www.apache.org/licenses/LICENSE-2.0
 *
 *    Unless required by applicable law or agreed to in writing, software
 *    distributed under the License is distributed on an "AS IS" BASIS,
 *    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *    See the License for the specific language governing permissions and
 *    limitations under the License.
 */

package com.downloader.internal;

import com.downloader.Constants;
import com.downloader.Error;
import com.downloader.Progress;
import com.downloader.Response;
import com.downloader.Status;
import com.downloader.database.DownloadModel;
import com.downloader.handler.ProgressHandler;
import com.downloader.httpclient.HttpClient;
import com.downloader.request.DownloadRequest;
import com.downloader.utils.Utils;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.io.SyncFailedException;
import java.net.HttpURLConnection;

/**
 * Created by amitshekhar on 13/11/17.
 */

public class DownloadTask {

    private static final int BUFFER_SIZE = 1024 * 4;
    private static final long TIME_GAP_FOR_SYNC = 2000;
    private static final long MIN_BYTES_FOR_SYNC = 65536;
    private final DownloadRequest request;
    private ProgressHandler progressHandler;
    private long lastSyncTime;
    private long lastSyncBytes;
    private InputStream inputStream;
    private HttpClient httpClient;
    private long totalBytes;
    private int responseCode;
    private String eTag;
    private boolean isResumeSupported;
    private String tempPath;

    private DownloadTask(DownloadRequest request) {
        this.request = request;
    }

    static DownloadTask create(DownloadRequest request) {
        return new DownloadTask(request);
    }

    // 重点在哪里啊 重点在这里
    Response run() {

        Response response = new Response();

        // 如果为CANCELLED或者PAUSED直接返回
        if (request.getStatus() == Status.CANCELLED) {
            response.setCancelled(true);
            return response;
        } else if (request.getStatus() == Status.PAUSED) {
            response.setPaused(true);
            return response;
        }

        BufferedOutputStream outputStream = null;

        FileDescriptor fileDescriptor = null;

        try {
            // 有说法 ProgressHandler是持有主线程Looper的handler负责发送progress
            if (request.getOnProgressListener() != null) {
                progressHandler = new ProgressHandler(request.getOnProgressListener());
            }

            tempPath = Utils.getTempPath(request.getDirPath(), request.getFileName());

            File file = new File(tempPath);
            //从db中获取进度
            DownloadModel model = getDownloadModelIfAlreadyPresentInDatabase();

            // 如果读到了model
            if (model != null) {
                // 并且缓存文件还在
                if (file.exists()) {
                    // 设置request
                    request.setTotalBytes(model.getTotalBytes());
                    request.setDownloadedBytes(model.getDownloadedBytes());
                } else {
                    // 不在的话就删掉db中的记录
                    // 设置request
                    removeNoMoreNeededModelFromDatabase();
                    request.setDownloadedBytes(0);
                    request.setTotalBytes(0);
                    model = null;
                }
            }
            // 大神组装httpClient
            // TODO 可以考虑解耦
            httpClient = ComponentHolder.getInstance().getHttpClient();
            //发请求了！！
            httpClient.connect(request);

            //其实不是很懂为什么在这里又有一个
            if (request.getStatus() == Status.CANCELLED) {
                response.setCancelled(true);
                return response;
            } else if (request.getStatus() == Status.PAUSED) {
                response.setPaused(true);
                return response;
            }

            //  TODO 并不太懂 看名字大概就是重定向之类的东西
            httpClient = Utils.getRedirectedConnectionIfAny(httpClient, request);

            responseCode = httpClient.getResponseCode();

            eTag = httpClient.getResponseHeader(Constants.ETAG);

            //处理曾经下载过但已经失效的文件
            if (checkIfFreshStartRequiredAndStart(model)) {
                model = null;
            }
            // 判断code
            if (!isSuccessful()) {
                Error error = new Error();
                error.setServerError(true);
                response.setError(error);
                return response;
            }
            // isResumeSupported 支持断点续传 我靠这个名字不如叫 zcddxc
            setResumeSupportedOrNot();

            totalBytes = request.getTotalBytes();

            // 缓存文件删除
            if (!isResumeSupported) {
                deleteTempFile();
            }

            // 第一次下载情况下
            if (totalBytes == 0) {
                totalBytes = httpClient.getContentLength();
                request.setTotalBytes(totalBytes);
            }

            // 存db
            if (isResumeSupported && model == null) {
                createAndInsertNewModel();
            }

            // 继续。。。
            if (request.getStatus() == Status.CANCELLED) {
                response.setCancelled(true);
                return response;
            } else if (request.getStatus() == Status.PAUSED) {
                response.setPaused(true);
                return response;
            }

            // 开始任务的回调
            request.deliverStartEvent();

            inputStream = httpClient.getInputStream();

            // 每次读4m
            byte[] buff = new byte[BUFFER_SIZE];

            if (!file.exists()) {
                //noinspection ResultOfMethodCallIgnored
                file.createNewFile();
            }

            RandomAccessFile randomAccess = new RandomAccessFile(file, "rw");
            fileDescriptor = randomAccess.getFD();
            outputStream = new BufferedOutputStream(new FileOutputStream(randomAccess.getFD()));

            if (isResumeSupported && request.getDownloadedBytes() != 0) {
                // 设置写入位置
                randomAccess.seek(request.getDownloadedBytes());
            }

            // 嗯。。。
            if (request.getStatus() == Status.CANCELLED) {
                response.setCancelled(true);
                return response;
            } else if (request.getStatus() == Status.PAUSED) {
                response.setPaused(true);
                return response;
            }

            do {

                final int byteCount = inputStream.read(buff);

                if (byteCount == -1) {
                    break;
                }

                outputStream.write(buff, 0, byteCount);

                request.setDownloadedBytes(request.getDownloadedBytes() + byteCount);

                // 其实是读一段outputStream就发送一次progress
                sendProgress();

                syncIfRequired(outputStream, fileDescriptor);
                // 循环写入，如果期间状态改变！！！直接return 你懂的
                if (request.getStatus() == Status.CANCELLED) {
                    response.setCancelled(true);
                    return response;
                } else if (request.getStatus() == Status.PAUSED) {
                    //  sync中触发了一次磁盘写入，getDownloadedBytes跟文件的进度不同
                    sync(outputStream, fileDescriptor);
                    response.setPaused(true);
                    return response;
                }

            } while (true);

            final String path = Utils.getPath(request.getDirPath(), request.getFileName());
            // 其实是有temp文件的
            Utils.renameFileName(tempPath, path);

            response.setSuccessful(true);

            if (isResumeSupported) {
                removeNoMoreNeededModelFromDatabase();
            }

        } catch (IOException | IllegalAccessException e) {
            if (!isResumeSupported) {
                deleteTempFile();
            }
            // 这个error就不是severError了
            Error error = new Error();
            error.setConnectionError(true);
            response.setError(error);
        } finally {
            // 关闭文件流
            closeAllSafely(outputStream, fileDescriptor);
        }
        // 返回
        return response;
    }

    private void deleteTempFile() {
        File file = new File(tempPath);
        if (file.exists()) {
            //noinspection ResultOfMethodCallIgnored
            file.delete();
        }
    }

    private boolean isSuccessful() {
        return responseCode >= HttpURLConnection.HTTP_OK
                && responseCode < HttpURLConnection.HTTP_MULT_CHOICE;
    }

    private void setResumeSupportedOrNot() {
        isResumeSupported = (responseCode == HttpURLConnection.HTTP_PARTIAL);
    }

    private boolean checkIfFreshStartRequiredAndStart(DownloadModel model) throws IOException,
            IllegalAccessException {
        // 416的意思就是，设置的Range有误
        // Range就是已经下载的byte 说出来你可能不信
        // 所以意味着 资源已经失效了需要重下
        // 该清清该删删
        if (responseCode == Constants.HTTP_RANGE_NOT_SATISFIABLE || isETagChanged(model)) {
            if (model != null) {
                removeNoMoreNeededModelFromDatabase();
            }
            deleteTempFile();
            request.setDownloadedBytes(0);
            request.setTotalBytes(0);
            httpClient = ComponentHolder.getInstance().getHttpClient();
            httpClient.connect(request);
            httpClient = Utils.getRedirectedConnectionIfAny(httpClient, request);
            responseCode = httpClient.getResponseCode();
            return true;
        }
        return false;
    }

    private boolean isETagChanged(DownloadModel model) {
        return !(eTag == null || model == null || model.getETag() == null)
                && !model.getETag().equals(eTag);
    }

    private DownloadModel getDownloadModelIfAlreadyPresentInDatabase() {
        return ComponentHolder.getInstance().getDbHelper().find(request.getDownloadId());
    }

    private void createAndInsertNewModel() {
        DownloadModel model = new DownloadModel();
        model.setId(request.getDownloadId());
        model.setUrl(request.getUrl());
        model.setETag(eTag);
        model.setDirPath(request.getDirPath());
        model.setFileName(request.getFileName());
        model.setDownloadedBytes(request.getDownloadedBytes());
        model.setTotalBytes(totalBytes);
        model.setLastModifiedAt(System.currentTimeMillis());
        ComponentHolder.getInstance().getDbHelper().insert(model);
    }

    private void removeNoMoreNeededModelFromDatabase() {
        ComponentHolder.getInstance().getDbHelper().remove(request.getDownloadId());
    }

    private void sendProgress() {
        if (request.getStatus() != Status.CANCELLED) {
            if (progressHandler != null) {
                progressHandler
                        .obtainMessage(Constants.UPDATE,
                                new Progress(request.getDownloadedBytes(),
                                        totalBytes)).sendToTarget();
            }
        }
    }

    private void syncIfRequired(BufferedOutputStream outputStream, FileDescriptor fileDescriptor) throws IOException {
        final long currentBytes = request.getDownloadedBytes();
        final long currentTime = System.currentTimeMillis();
        final long bytesDelta = currentBytes - lastSyncBytes;
        final long timeDelta = currentTime - lastSyncTime;
        if (bytesDelta > MIN_BYTES_FOR_SYNC && timeDelta > TIME_GAP_FOR_SYNC) {
            sync(outputStream, fileDescriptor);
            lastSyncBytes = currentBytes;
            lastSyncTime = currentTime;
        }
    }

    private void sync(BufferedOutputStream outputStream, FileDescriptor fileDescriptor) {
        boolean success;
        try {
            outputStream.flush();
            fileDescriptor.sync();
            success = true;
        } catch (IOException e) {
            success = false;
            e.printStackTrace();
        }
        if (success && isResumeSupported) {
            ComponentHolder.getInstance().getDbHelper()
                    .updateProgress(request.getDownloadId(),
                            request.getDownloadedBytes(),
                            System.currentTimeMillis());
        }

    }

    private void closeAllSafely(BufferedOutputStream outputStream, FileDescriptor fileDescriptor) {
        if (httpClient != null) {
            try {
                httpClient.close();
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
        if (inputStream != null) {
            try {
                inputStream.close();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
        try {
            if (outputStream != null) {
                try {
                    outputStream.flush();
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
            if (fileDescriptor != null) {
                try {
                    fileDescriptor.sync();
                } catch (SyncFailedException e) {
                    e.printStackTrace();
                }
            }
        } finally {
            if (outputStream != null)
                try {
                    outputStream.close();
                } catch (IOException e) {
                    e.printStackTrace();
                }
        }
    }

}
