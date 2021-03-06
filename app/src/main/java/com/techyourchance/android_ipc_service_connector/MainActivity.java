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
import android.view.View;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

public class MainActivity extends AppCompatActivity {

    private static final String TAG = "MainActivity";

    private static final int CONNECTION_TIMEOUT = 5000; // ms

    private static final long DATE_REFRESH_INTERVAL = 100; // ms

    private final ServiceConnection mServiceConnection = new ServiceConnection() {

        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            Log.d(TAG, "onServiceConnected()");
            mDateProvider = IDateProvider.Stub.asInterface(service);
            mBtnCrashService.setEnabled(true);
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            Log.d(TAG, "onServiceDisconnected()");
            mBtnCrashService.setEnabled(false);
            mDateProvider = null;
        }
    };

    private IpcServiceConnector mIpcServiceConnector;
    private IDateProvider mDateProvider;

    private final DateMonitor mDateMonitor = new DateMonitor();

    private TextView mTxtDate;
    private Button mBtnCrashService;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        mIpcServiceConnector = new IpcServiceConnector(this, "DateProviderConnector");

        mTxtDate = (TextView) findViewById(R.id.txt_date);
        mBtnCrashService = (Button) findViewById(R.id.btn_crash_service);

        mBtnCrashService.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                try {
                    mDateProvider.crashService();
                } catch (RemoteException e) {
                    e.printStackTrace();
                }
            }
        });
    }

    @Override
    protected void onStart() {
        super.onStart();
        Log.d(TAG, "onStart(); binding and connecting to IPC service");
        if (!bindDateProviderService()) {
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

    private boolean bindDateProviderService() {
        return mIpcServiceConnector.bindAndConnectToIpcService(
                new Intent(this, DateProviderService.class),
                mServiceConnection,
                Context.BIND_AUTO_CREATE);
    }


    private class DateMonitor {


        private final Runnable mConnectionInProgressNotification = new Runnable() {
            @Override
            public void run() {
                mTxtDate.setText("connecting to IPC service...");
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
                } catch (NullPointerException e) {
                    /*
                     Since mDateProvider is assigned/cleared on UI thread, but is being used on
                     worker thread, there is a chance of race condition that will result in NPE.
                     We could either add synchronization, or catch NPE - I chose the latter in
                     order to simplify the (already complex) example
                     */
                    mCurrentDate = "-";
                    e.printStackTrace();
                }
            } else { // could not connect to IPC service
                Log.e(TAG, "connection attempt timed out - attempting to rebind to the service");
                notifyUserConnectionAttemptFailed();

                /*
                 Connection error handling here. I just attempt to rebind to the service, but a real
                 error handling could also employ some extrapolation of cached data, etc.
                 If this is a fatal error from your application's point ov view, then unbind from
                 the service and stop the worker thread.
                  */

                mConnectionFailure = true;

                mIpcServiceConnector.unbindIpcService();
                if (!bindDateProviderService()) {
                    Log.e(TAG, "rebind attempt failed - stopping DateMonitor completely");
                    DateMonitor.this.stop();
                }

                return;
            }

            mMainHandler.post(mDateNotification);
        }

        private void notifyUserConnectionAttemptFailed() {
            MainActivity.this.runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    Toast.makeText(
                            MainActivity.this,
                            "connection attempt timed out - rebinding",
                            Toast.LENGTH_LONG)
                            .show();
                }
            });
        }
    }
}
