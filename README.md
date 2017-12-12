<p align="center">

# PRDownloader - A file downloader library for Android with pause and resume support
[![Mindorks](https://img.shields.io/badge/mindorks-opensource-blue.svg)](https://mindorks.com/open-source-projects)
[![Mindorks Community](https://img.shields.io/badge/join-community-blue.svg)](https://mindorks.com/join-community)
[![License](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](https://opensource.org/licenses/Apache-2.0)

# 总结
  仅对重点的类进行解析 
  DownloadRequestQueue - 类如其名，维护downloadRequest的队列，currentRequestMap放置计划下载的downloadRequest
  在resume和addRequest方法中使用Core.getInstance().getExecutorSupplier().forDownloadTasks().submit(new DownloadRunnable(request))
  放置线程池中，返回值future保存在DownloadRequest中，方便日后操作。Core.getInstance().getExecutorSupplier().forDownloadTasks()是个线程池
  
  DownloadRunnable - 对Runnable进行封装通过DownloadTask.run返回的response状态回调
  
  DownloadTask - 维护下载信息的类 run方法为下载文件的方法，具体实现:
                 一开始是进行db操作，判断文件是否已经下载
                 使用final String range = String.format(Locale.ENGLISH,"bytes=%d-", request.getDownloadedBytes());
                 connection.addRequestProperty(Constants.RANGE, range)指定下载的位置
                 使用RandomAccessFile.seek方法指定文件写入的位置
                 当http请求返回code为416时意味着range错误，删除db内容，重新下载
                 当请求成功后进行io操作 每次读取4m到文件中，每次读取完使用progressHandler进行主线程的进度回调
                 每次写入后判断request的状态如果为CANCELLED或者PAUSED则调用            
                 outputStream.flush();
                 fileDescriptor.sync();将最后读取的内容写入磁盘
                 
                 当request被标记为CANCELLED或者PAUSED时会调用future.cancel方法，该方法会终止线程，但并不会阻塞while（ture）中代码块的运行
                 
  
  DefaultHttpClient - 封装的URLConnection

## Sample Download
<img src=https://raw.githubusercontent.com/MindorksOpenSource/PRDownloader/master/assets/sample_download.png width=360 height=640 />

### Overview of PRDownloader library
* PRDownloader can be used to download any type of files like image, video, pdf, apk and etc.
* This file downloader library supports pause and resume while downloading a file.
* Supports large file download.
* This downloader library has a simple interface to make download request.
* We can check if the status of downloading with the given download Id.
* PRDownloader gives callbacks for everything like onProgress, onCancel, onStart, onError and etc while downloading a file.
* Supports proper request canceling.
* Many requests can be made in parallel.
* All types of customization are possible.

## Using PRDownloader Library in your Android application

Add this in your build.gradle
```groovy
compile 'com.mindorks.android:prdownloader:0.2.0'
```
Do not forget to add internet permission in manifest if already not present
```xml
<uses-permission android:name="android.permission.INTERNET" />
```
Then initialize it in onCreate() Method of application class :
```java
PRDownloader.initialize(getApplicationContext());
```
Initializing it with some customization
```java
// Enabling database for resume support even after the application is killed:
PRDownloaderConfig config = PRDownloaderConfig.newBuilder()
                .setDatabaseEnabled(true)
                .build();
PRDownloader.initialize(getApplicationContext(), config);

// Setting timeout globally for the download network requests:
PRDownloaderConfig config = PRDownloaderConfig.newBuilder()
                .setReadTimeout(30_000)
                .setConnectTimeout(30_000)
                .build();
PRDownloader.initialize(getApplicationContext(), config); 
```

### Make a download request
```java
int downloadId = PRDownloader.download(url, dirPath, fileName)
                        .build()
                        .setOnStartOrResumeListener(new OnStartOrResumeListener() {
                            @Override
                            public void onStartOrResume() {
                               
                            }
                        })
                        .setOnPauseListener(new OnPauseListener() {
                            @Override
                            public void onPause() {
                               
                            }
                        })
                        .setOnCancelListener(new OnCancelListener() {
                            @Override
                            public void onCancel() {
                                
                            }
                        })
                        .setOnProgressListener(new OnProgressListener() {
                            @Override
                            public void onProgress(Progress progress) {
                               
                            }
                        })
                        .start(new OnDownloadListener() {
                            @Override
                            public void onDownloadComplete() {
                               
                            }

                            @Override
                            public void onError(Error error) {
                               
                            }
                        });            
```

### Pause a download request
```java
PRDownloader.pause(downloadId);
```

### Resume a download request
```java
PRDownloader.resume(downloadId);
```

### Cancel a download request
```java
// Cancel with the download id
PRDownloader.cancel(downloadId);
// The tag can be set to any request and then can be used to cancel the request
PRDownloader.cancel(TAG);
// Cancel all the requests
PRDownloader.cancelAll();
```

### Status of a download request
```java
Status status = PRDownloader.getStatus(downloadId);
```

### Clean up resumed files if database enabled
```java
// Method to clean up temporary resumed files which is older than the given day
PRDownloader.cleanUp(days);
```
### TODO
* Integration with other libraries like OkHttp, RxJava
* Test Cases
* And of course many many features and bug fixes

## If this library helps you in anyway, show your love :heart: by putting a :star: on this project :v:

[Check out Mindorks awesome open source projects here](https://mindorks.com/open-source-projects)

### License
```
   Copyright (C) 2017 MINDORKS NEXTGEN PRIVATE LIMITED

   Licensed under the Apache License, Version 2.0 (the "License");
   you may not use this file except in compliance with the License.
   You may obtain a copy of the License at

       http://www.apache.org/licenses/LICENSE-2.0

   Unless required by applicable law or agreed to in writing, software
   distributed under the License is distributed on an "AS IS" BASIS,
   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
   See the License for the specific language governing permissions and
   limitations under the License.
```

### Contributing to PRDownloader
All pull requests are welcome, make sure to follow the [contribution guidelines](CONTRIBUTING.md)
when you submit pull request.
