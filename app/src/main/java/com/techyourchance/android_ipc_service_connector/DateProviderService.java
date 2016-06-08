package com.techyourchance.android_ipc_service_connector;

import android.app.Service;
import android.content.Intent;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.RemoteException;
import android.support.annotation.Nullable;
import android.util.Log;

import java.text.SimpleDateFormat;
import java.util.Date;

/**
 * This service will run in a separate child process in order to simulate a real IPC service. The
 * functionality of this service is trivial, and would not require a standalone service in a real
 * application.
 */
public class DateProviderService extends Service {

    private static final String TAG = "DateProviderService";

    private final IDateProvider.Stub mBinder = new IDateProvider.Stub() {
        @Override
        public String getDate() throws RemoteException {
            return DateProviderService.this.getDate();
        }

        @Override
        public void crashService() throws RemoteException {
            DateProviderService.this.crashService();
        }
    };

    @Override
    public void onCreate() {
        Log.d(TAG, "onCreate()");
        super.onCreate();
    }

    @Override
    public void onDestroy() {
        Log.d(TAG, "onDestroy()");
        super.onDestroy();
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        Log.d(TAG, "onBind()");
        return mBinder;
    }

    @Override
    public boolean onUnbind(Intent intent) {
        Log.d(TAG, "onUnbind()");
        return super.onUnbind(intent);
    }

    @Override
    public void onRebind(Intent intent) {
        Log.d(TAG, "onRebind()");
        super.onRebind(intent);
    }

    private void crashService() {
        Handler mainHandler = new Handler(Looper.getMainLooper());
        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                Object object = null;
                object.toString(); // this will cause NPE
            }
        });
    }

    private String getDate() {
        // nothing special here
        SimpleDateFormat sdf = new SimpleDateFormat("MMM dd,yyyy HH:mm:ss");
        Date date = new Date(System.currentTimeMillis());
        return sdf.format(date);
    }
}
