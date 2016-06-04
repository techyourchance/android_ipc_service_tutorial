package com.techyourchance.android_ipc_service_connector;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.IBinder;
import android.support.annotation.NonNull;
import android.support.annotation.WorkerThread;
import android.util.Log;

/**
 * Objects of this class perform binding and connection to IPC services and allow for
 * an easier handling of connection states and the associated failures<br>
 * Please note that "blocked thread" and "waiting thread" are synonyms.
 * @author Vasiliy@techyourchance.com
 */
public class IpcServiceConnector {

    /**
     * Initialization non-functional state
     */
    public static final int STATE_NONE = 0;

    /**
     * IPC service is bound, but {@link ServiceConnection#onServiceConnected(ComponentName, IBinder)}
     * callback wasn't called yet.
     */
    public static final int STATE_BOUND_WAITING_FOR_CONNECTION = 1;

    /**
     * IPC service connected (i.e. IPC service is bound, and
     * {@link ServiceConnection#onServiceConnected(ComponentName, IBinder)} was called).
     */
    public static final int STATE_BOUND_CONNECTED = 2;

    /**
     * IPC service is bound and had already been connected, but
     * {@link ServiceConnection#onServiceDisconnected(ComponentName)} was called
     */
    public static final int STATE_BOUND_DISCONNECTED = 3;

    /**
     * IPC service unbound ({@link Context#unbindService(ServiceConnection)} was explicitly
     * called)
     */
    public static final int STATE_UNBOUND = 4;

    /**
     * Binding of IPC service failed ({@link Context#bindService(Intent, ServiceConnection, int)}
     * returned false)
     */
    public static final int STATE_BINDING_FAILED = 5;


    private final Object LOCK = new Object();

    private int mConnectionState = STATE_NONE;

    private ServiceConnectionDecorator mServiceConnectionDecorator;

    private Context mContext;
    private String mName;

    /**
     * @param context will be used in order to bind/unbind services
     * @param name the name of the newly created instance for logging purposes
     */
    public IpcServiceConnector(@NonNull Context context, @NonNull String name) {
        mContext = context;
        mName = name;
    }

    private void setStateAndReleaseBlockedThreads(int newConnectionState) {
        synchronized (LOCK) {
            Log.d(mName, "setStateAndReleaseBlockedThreads()" +
                    "; current state: " + getStateName(mConnectionState) +
                    "; newState: " + getStateName(newConnectionState));

            if (mConnectionState != newConnectionState) {
                mConnectionState = newConnectionState;
                Log.d(mName, "notifying all waiting (blocked) threads about state change");
                LOCK.notifyAll();
            }
        }
    }

    /**
     * Get connection state of this connector. Will return either of:<br>
     *     {@link #STATE_NONE}<br>
     *     {@link #STATE_BOUND_WAITING_FOR_CONNECTION}<br>
     *     {@link #STATE_BOUND_CONNECTED}<br>
     *     {@link #STATE_BOUND_DISCONNECTED}<br>
     *     {@link #STATE_UNBOUND}<br>
     *     {@link #STATE_BINDING_FAILED}<br>
     *
     * @return connector's state
     */
    public int getState() {
        synchronized (LOCK) {
            return mConnectionState;
        }
    }

    /**
     * This method initiates a connection to IPC service.
     *
     * This method returns immediately -
     * {@link ServiceConnection#onServiceConnected(ComponentName, IBinder)} of the provided
     * {@link ServiceConnection} object will be called the service becomes connected.<br><br>
     *
     * NOTE: after the service is bound and connected, {@link ServiceConnection#onServiceConnected(ComponentName, IBinder)}
     * of the provided ServiceConnection will be called BEFORE the state of this connector changes
     * to {@link #STATE_BOUND_CONNECTED}.<br><br>
     *
     * NOTE: if the service disconnects, {@link ServiceConnection#onServiceDisconnected(ComponentName)}
     * of the provided ServiceConnection will be called BEFORE the state of this connector changes
     * to {@link #STATE_BOUND_DISCONNECTED}.
     *
     * @param intent will be used in {@link Context#bindService(Intent, ServiceConnection, int)} call
     * @param serviceConnection will be used in {@link Context#bindService(Intent, ServiceConnection, int)} call
     * @param flags will be used in {@link Context#bindService(Intent, ServiceConnection, int)} call
     * @return the value returned by the "underlying" {@link Context#bindService(Intent, ServiceConnection, int)} call
     */
    public boolean bindAndConnectToIpcService(@NonNull Intent intent, @NonNull ServiceConnection serviceConnection, int flags) {

        Log.d(mName, "bindAndConnectToIpcService()");


        synchronized (LOCK) {
            if (isServiceBound()) {
                Log.e(mName, "this connector is already bound - aborting binding attempt");
                return false;
            }

            ServiceConnectionDecorator tempServiceConnectionDecorator =
                    new ServiceConnectionDecorator(serviceConnection);

            boolean isServiceBound = mContext.bindService(intent,
                    tempServiceConnectionDecorator, flags);

            if (isServiceBound) {
                Log.d(mName, "service bound successfully");
                setStateAndReleaseBlockedThreads(STATE_BOUND_WAITING_FOR_CONNECTION);
                mServiceConnectionDecorator = tempServiceConnectionDecorator;
            } else {
                Log.e(mName, "service binding failed");
                setStateAndReleaseBlockedThreads(STATE_BINDING_FAILED);
            }

            return isServiceBound;
        }
    }


    /**
     * Call to this method will block the calling thread until this connector transitions to the
     * specified state, or until the specified amount of time passes If the connector is already in
     * the requested state then this method returns immediately.<br><br>
     *
     * NOTE: {@link ServiceConnection#onServiceConnected(ComponentName, IBinder)} and
     * {@link ServiceConnection#onServiceDisconnected(ComponentName)} will be invoked BEFORE
     * threads which are waiting due to calls to this method are unblocked. This allows you to
     * use ServiceConnection's callbacks in order perform the required setup before the execution
     * of the blocked threads continues.<br><br>
     *
     * This method MUST NOT be called from UI thread.
     * @param targetState IpcServiceConnector's state in which the calling thread should be
     *                    unblocked. Should be either of:<br>
     *                    {@link #STATE_NONE}<br>
     *                    {@link #STATE_BOUND_WAITING_FOR_CONNECTION}<br>
     *                    {@link #STATE_BOUND_CONNECTED}<br>
     *                    {@link #STATE_BOUND_DISCONNECTED}<br>
     *                    {@link #STATE_UNBOUND}<br>
     *                    {@link #STATE_BINDING_FAILED}<br>
     *
     * @param blockingTimeout the period of time (in milliseconds) after which the calling thread will
     *                        be unblocked (regardless of the state of this IpcServiceConnector)
     * @return true if target state was reached; false otherwise
     */
    @WorkerThread
    public boolean waitForState(int targetState, int blockingTimeout) {
        synchronized (LOCK) {

            if (mConnectionState == targetState) {
                return true;
            }

            Log.d(mName, "waitForState(); target state: " + getStateName(targetState) +
                    "; calling thread: " + Thread.currentThread().getName());

            final long blockingOnsetTime = System.currentTimeMillis();

            // wait until the target state, or until timeout
            while (mConnectionState != targetState
                    && System.currentTimeMillis() < blockingOnsetTime + blockingTimeout
                    && !Thread.currentThread().isInterrupted()) {

                Log.d(mName, "blocking execution of thread: " + Thread.currentThread().getName());

                try {
                    LOCK.wait(blockingTimeout);
                } catch (InterruptedException e) {
                    // restore interrupted status (was cleared by wait())
                    Thread.currentThread().interrupt();
                }

            }

            boolean targetStateReached = mConnectionState == targetState;

            Log.d(mName, "thread unblocked: " + Thread.currentThread().getName() +
            "; current state: " + getStateName(mConnectionState) + "; target state: " + getStateName(targetState));

            return targetStateReached;
        }
    }

    /**
     * Unbind from a bound service. Has no effect if no service was bound.
     */
    public void unbindIpcService() {
        Log.d(mName, "unbindIpcService()");

        if (isServiceBound()) {
            mContext.unbindService(mServiceConnectionDecorator);
            mServiceConnectionDecorator = null;
            setStateAndReleaseBlockedThreads(STATE_UNBOUND);
        } else {
            Log.d(mName, "no bound IPC service");
        }
    }

    /**
     * @return true if the state of this connector corresponds to a bound service; false otherwise
     */
    public boolean isServiceBound() {
        synchronized (LOCK) {
            switch (mConnectionState) {
                case STATE_BOUND_WAITING_FOR_CONNECTION:
                case STATE_BOUND_CONNECTED:
                case STATE_BOUND_DISCONNECTED:
                    return true;
                default:
                    return false;
            }
        }
    }

    /**
     * @return the name of this instance of IpcServiceConnector for logging purposes
     */
    private String getName() {
        return mName;
    }

    /**
     * @return human readable representation of connector's state for logging purposes
     */
    public static String getStateName(int connectionState) {
        switch (connectionState) {
            case STATE_NONE:
                return "STATE_NONE";
            case STATE_BOUND_WAITING_FOR_CONNECTION:
                return "STATE_BOUND_WAITING_FOR_CONNECTION";
            case STATE_BOUND_CONNECTED:
                return "STATE_BOUND_CONNECTED";
            case STATE_BOUND_DISCONNECTED:
                return "STATE_BOUND_DISCONNECTED";
            case STATE_UNBOUND:
                return "STATE_UNBOUND";
            case STATE_BINDING_FAILED:
                return "STATE_BINDING_FAILED";
            default:
                throw new IllegalArgumentException("invalid state: " + connectionState);
        }
    }


    /**
     * This class is a decorator for an externally supplied {@link ServiceConnection}. It
     * is used in order to manage the state of IpcServiceConnector in accordance with callbacks
     * received from the system.
     */
    private class ServiceConnectionDecorator implements ServiceConnection {

        private ServiceConnection mDecorated;

        public ServiceConnectionDecorator(@NonNull ServiceConnection decorated) {
            this.mDecorated = decorated;
        }

        @Override
        public void onServiceConnected(ComponentName name, IBinder binder) {
            /*
            System will invoke this method after a connection to the already bound service will be
            established.
             */
            mDecorated.onServiceConnected(name, binder);

            setStateAndReleaseBlockedThreads(STATE_BOUND_CONNECTED);
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            /*
            System will invoke this method in response to abnormal errors related to connected
            service (IPC service crashed or was killed by OS, etc). After this method is called,
            IPC service is NOT necessarily unbound - the system might restore the service later,
            and invoke onServiceConnected() in order to let us know that the service is connected
            again.
             */
            mDecorated.onServiceDisconnected(name);

            setStateAndReleaseBlockedThreads(STATE_BOUND_DISCONNECTED);
        }
    }

}
