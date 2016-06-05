package com.techyourchance.android_ipc_service_connector;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.RemoteException;
import android.support.annotation.WorkerThread;
import android.support.v7.app.AppCompatActivity;
import android.util.Log;
import android.widget.TextView;

public class MainActivity extends AppCompatActivity {

    private static final String TAG = "MainActivity";

    private static final int CONNECTION_TIMEOUT = 5000; // ms

    private static final long DATE_REFRESH_INTERVAL = 100; // ms

    private final ServiceConnection mServiceConnection = new ServiceConnection() {

        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            Log.d(TAG, "onServiceConnected()");
            mDateProvider = IDateProvider.Stub.asInterface(service);
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            Log.d(TAG, "onServiceDisconnected()");
            mDateProvider = null;
        }
    };

    private IpcServiceConnector mIpcServiceConnector;
    private IDateProvider mDateProvider;

    private final DateMonitor mDateMonitor = new DateMonitor();

    private TextView mTxtDate;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        mIpcServiceConnector = new IpcServiceConnector(this, "DateProviderConnector");
        mTxtDate = (TextView) findViewById(R.id.txt_date);
    }

    @Override
    protected void onStart() {
        super.onStart();
        Log.d(TAG, "onStart(); binding and connecting to IPC service");
        if (!mIpcServiceConnector.bindAndConnectToIpcService(
                new Intent(this, DateProviderService.class),
                mServiceConnection,
                Context.BIND_AUTO_CREATE)) {
            // service couldn't be bound - handle this error by disabling the logic which depends
            // on this service (in this case we will do it in onResume())
        }
    }

    @Override
    protected void onStop() {
        super.onStop();
        Log.d(TAG, "onStop(); unbinding IPC service");
        mIpcServiceConnector.unbindIpcService();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (mIpcServiceConnector.isServiceBound()) {
            Log.d(TAG, "onResume(); starting date monitor");
            mDateMonitor.start();
        } else {
            Log.e(TAG, "onResume(); IPC service is not bound - aborting date monitoring");
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        Log.d(TAG, "onPause(); stopping date monitor");
        mDateMonitor.stop();
    }


    private class DateMonitor {


        private final Runnable mConnectionInProgressNotification = new Runnable() {
            @Override
            public void run() {
                mTxtDate.setText("connecting to IPC service...");
            }
        };

        private final Runnable mConnectionFailedNotification = new Runnable() {
            @Override
            public void run() {
                mTxtDate.setText("couldn't connect to IPC service");
            }
        };

        private final Runnable mDateNotification = new Runnable() {
            @Override
            public void run() {
                mTxtDate.setText(mCurrentDate);
            }
        };

        private String mCurrentDate = "-";
        private boolean mConnectionFailure = false;

        private final Handler mMainHandler = new Handler(Looper.getMainLooper());

        private Thread mWorkerThread;
        private Thread mOldWorkerThread;

        public void start() {

            // make sure we stop the worker thread, but keep reference to it
            if (mWorkerThread != null) {
                mOldWorkerThread = mWorkerThread;
                stop();
            }

            mWorkerThread = new Thread(new Runnable() {
                @Override
                public void run() {

                    // make this thread wait until the old thread dies
                    if (mOldWorkerThread != null) {
                        try {
                            mOldWorkerThread.join();
                        } catch (InterruptedException e) {
                            // set the interrupted status back (it was cleared in join())
                            Thread.currentThread().interrupt();
                        }
                        mOldWorkerThread = null;
                    }

                    mConnectionFailure = false;
                    mCurrentDate = "-";

                    // loop until interrupted
                    while (!Thread.currentThread().isInterrupted()) {

                        updateDate();

                        try {
                            Thread.sleep(DATE_REFRESH_INTERVAL);
                        } catch (InterruptedException e) {
                            // set the interrupted status back (it was cleared in sleep())
                            Thread.currentThread().interrupt();
                        }
                    }
                }
            });
            mWorkerThread.start();
        }

        public void stop() {
            if (mWorkerThread != null) {
                mWorkerThread.interrupt();
                mWorkerThread = null;
            }
        }

        @WorkerThread
        private void updateDate() {

            /*
             We don't want the date displayed being stuck if we ever need to wait for connection,
             therefore we show informative notification.
             The notification should be cancelled if the service is connected
             */
            if (!mConnectionFailure) {
                mMainHandler.postDelayed(mConnectionInProgressNotification, 100);
            }

            // this call can block the worker thread for up to CONNECTION_TIMEOUT milliseconds
            if (mIpcServiceConnector.waitForState(IpcServiceConnector.STATE_BOUND_CONNECTED,
                    CONNECTION_TIMEOUT)) { // IPC service connected

                mConnectionFailure = false;
                mMainHandler.removeCallbacks(mConnectionInProgressNotification);

                try {
                    mCurrentDate = mDateProvider.getDate();
                } catch (RemoteException e) {
                    // this exception can still be thrown (e.g. service crashed, but the system hasn't
                    // notified us yet)
                    mCurrentDate = "-";
                    e.printStackTrace();
                }
            } else { // could not connect to IPC service
                /*
                 Connection error handling here. I just show error string and proceed, but a real
                 error handling could include e.g. service rebind attempt, some extrapolating of
                 cached data, etc.
                 Note that in this case this "error" state might be temporary - worker thread keeps
                 running, and if the service will reconnect in the future, DateMonitor will continue
                 to function properly
                  */
                mConnectionFailure = true;
                mMainHandler.post(mConnectionFailedNotification);
                return;
            }

            mMainHandler.post(mDateNotification);
        }
    }
}
