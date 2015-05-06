package com.desmond.asyncmanager;

import android.support.annotation.NonNull;
import android.util.Log;

import java.util.Queue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Created by desmond on 30/4/15.
 */
public class AsyncManager {

    private static final String TAG = AsyncManager.class.getSimpleName();

    // Sets the amount of time an idle thread will wait for a task before terminating
    private static final int KEEP_ALIVE_TIME = 1;
    private static final TimeUnit KEEP_ALIVE_TIME_UNIT = TimeUnit.SECONDS;

    /**
     * NOTE: This is the number of total available cores. On current versions of
     * Android, with devices that use plug-and-play cores, this will return less
     * than the total number of cores. The total number of cores is not
     * available in current Android implementations.
     */
    private static int NUMBER_OF_CORES = Runtime.getRuntime().availableProcessors();
    private static final int CORE_POOL_SIZE = 4;
    private static final int MAXIMUM_POOL_SIZE = 4;

    /**
     * Limit the number of BackgroundTask that's kept in the mBackgroundTaskWorkQueue.
     * Excess BackgroundTask can be gc after finishing from mExecutingTaskWorkQueue.
     */
    private static final int MAX_QUEUE_SIZE = 8;
    private final Queue<BackgroundTask> mBackgroundTaskWorkQueue;
    private final Queue<BackgroundTask> mExecutingTaskWorkQueue;

    private final BlockingQueue<Runnable> mBackgroundWorkQueue;
    private final ThreadPoolExecutor mTaskThreadPool;

    private static final AsyncManager sInstance;

    static  {
        sInstance = new AsyncManager();
    }

    private AsyncManager() {
        mBackgroundTaskWorkQueue = new LinkedBlockingQueue<>(MAX_QUEUE_SIZE);
        mExecutingTaskWorkQueue = new LinkedBlockingQueue<>();

        // List queue that blocks when the queue is empty
        mBackgroundWorkQueue = new LinkedBlockingQueue<>();

        mTaskThreadPool = new ThreadPoolExecutor(
                CORE_POOL_SIZE, MAXIMUM_POOL_SIZE,
                KEEP_ALIVE_TIME, KEEP_ALIVE_TIME_UNIT,
                mBackgroundWorkQueue, new AsyncThreadFactory());
    }

    public static AsyncManager getInstance() {
        return sInstance;
    }

    public static BackgroundTask runBackgroundTask(TaskRunnable run) {
        BackgroundTask backgroundTask = sInstance.mBackgroundTaskWorkQueue.poll();

        if (backgroundTask == null) {
            backgroundTask = new BackgroundTask();
        }

        backgroundTask.initializeTask(run);

        sInstance.mExecutingTaskWorkQueue.offer(backgroundTask);

        sInstance.mTaskThreadPool.execute(run);

        return backgroundTask;
    }

    public static void cancelNonPersistedTask(BackgroundTask backgroundTask) {
        synchronized (sInstance) {
            Thread thread = backgroundTask.getCurrentThread();
            TaskRunnable runnable = backgroundTask.getTaskRunnable();

            if (!runnable.mShouldPersist) {
                if (thread != null) {
                    thread.interrupt();
                }

                sInstance.mTaskThreadPool.remove(runnable);
                sInstance.mExecutingTaskWorkQueue.remove(backgroundTask);
            }
        }
    }

    /**
     * Cancels all the Threads for non persisted task in the ThreadPool
     */
    public static void cancelAllNonPersistedTasks() {
        BackgroundTask[] taskArray = new BackgroundTask[sInstance.mExecutingTaskWorkQueue.size()];
        sInstance.mExecutingTaskWorkQueue.toArray(taskArray);

        int taskArrayLen = taskArray.length;

        synchronized (sInstance) {
            Thread thread;
            TaskRunnable runnable;
            BackgroundTask task;
            for (int i = 0; i < taskArrayLen; i++) {
                task = taskArray[i];
                thread = task.getCurrentThread();
                runnable = task.getTaskRunnable();

                if (!runnable.mShouldPersist) {
                    if (thread != null) {
                        thread.interrupt();
                    }
                    sInstance.mTaskThreadPool.remove(runnable);
                    sInstance.mExecutingTaskWorkQueue.remove(task);
                }
            }
        }
    }

    void recycleBackgroundTask(@NonNull BackgroundTask task) {
        task.recycle();
        mExecutingTaskWorkQueue.remove(task);
        mBackgroundTaskWorkQueue.offer(task);
        Log.d("Manager", "executing queue size " + mExecutingTaskWorkQueue.size());
        Log.d("Manager", "work queue size " + mBackgroundTaskWorkQueue.size());
    }

    public static void cleanUp() {
        sInstance.mTaskThreadPool.shutdown();
    }

    public static void setThreadPoolSize(int size) {
        sInstance.mTaskThreadPool.setCorePoolSize(size);
        sInstance.mTaskThreadPool.setMaximumPoolSize(size);
    }

    /**
     * To set all threads created by mTaskThreadPool to have the same priority as
     * the Android Background Thread.
     */
    private static final class AsyncThreadFactory implements ThreadFactory {

        private final AtomicInteger mCount = new AtomicInteger(1);

        @Override
        public Thread newThread(Runnable r) {
            Thread thread = new Thread(r);
            thread.setName("LowPriority " + mCount.getAndAdd(1));
            thread.setPriority(4);
            thread.setUncaughtExceptionHandler(new Thread.UncaughtExceptionHandler() {
                @Override
                public void uncaughtException(Thread thread, Throwable ex) {
                    Log.d(TAG, "Thread = " + thread.getName() + ", error = " + ex.getMessage());
                }
            });
            return thread;
        }
    }
}
