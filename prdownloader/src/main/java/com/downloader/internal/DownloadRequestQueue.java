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

import com.downloader.core.Core;
import com.downloader.request.DownloadRequest;

import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Created by amitshekhar on 13/11/17.
 */

public class DownloadRequestQueue {

    private final Set<DownloadRequest> currentRequests = new HashSet<>();
    private AtomicInteger sequenceGenerator = new AtomicInteger();
    private static DownloadRequestQueue instance;

    public static void initialize() {
        getInstance();
    }

    public static DownloadRequestQueue getInstance() {
        if (instance == null) {
            synchronized (DownloadRequestQueue.class) {
                if (instance == null) {
                    instance = new DownloadRequestQueue();
                }
            }
        }
        return instance;
    }

    public int getSequenceNumber() {
        return sequenceGenerator.incrementAndGet();
    }

    public DownloadRequest addRequest(DownloadRequest request) {
        synchronized (currentRequests) {
            try {
                currentRequests.add(request);
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
        try {
            request.setSequenceNumber(getSequenceNumber());
            request.setFuture(Core.getInstance()
                    .getExecutorSupplier()
                    .forDownloadTasks()
                    .submit(new DownloadRunnable(request)));
        } catch (Exception e) {
            e.printStackTrace();
        }
        return request;
    }

    public void finish(DownloadRequest request) {
        synchronized (currentRequests) {
            try {
                currentRequests.remove(request);
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }
}