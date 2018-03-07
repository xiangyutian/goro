package com.stanfy.enroscar.goro;

import android.app.Notification;
import android.app.Service;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Binder;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Message;
import android.os.Parcelable;
import android.util.Log;

import java.lang.ref.WeakReference;
import java.util.concurrent.Callable;

import static android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_ENABLED;
import static android.content.pm.PackageManager.DONT_KILL_APP;

/**
 * Service that handles tasks in multiple queues.
 */
public class GoroService extends Service {

  private static final boolean DEBUG = BuildConfig.DEBUG;

  private static final String TAG = "Goro";

  /**
   * Used as a {@link java.lang.String} field in service command intent to pass
   * a queue name. If this extra is not defined, {@link Goro#DEFAULT_QUEUE} is used.
   * You may manually set value to {@code null} in order to perform task beyond any queue.
   */
  public static final String EXTRA_QUEUE_NAME = "queue_name";

  /**
   * Boolean indicating whether error thrown by an executed task should be ignored.
   * Use with caution. Ensure that you have set up a @{link GoroListener} that can handle
   * error appropriately.
   */
  public static final String EXTRA_IGNORE_ERROR = "ignore_error";

  /**
   * Used as a {@link android.os.Parcelable} field in service command intent to pass
   * a task for execution. {@link android.os.Parcelable} instance must also implement
   * {@link java.util.concurrent.Callable} interface.
   */
  static final String EXTRA_TASK = "task";

  /**
   * Used as a workaround for http://code.google.com/p/android/issues/detail?id=6822
   */
  static final String EXTRA_TASK_BUNDLE = "task_bundle";

  /**
   * Used as a <b>int</b> field in service command intent to pass
   * a Notification ID to run service in the foreground.
   */
  static final String EXTRA_NOTIFICATION_ID = "notification_ID";

  /**
   * Used as a {@link android.os.Parcelable} field in service command intent to pass
   * a Notification to run service in the foreground.
   */
  static final String EXTRA_NOTIFICATION = "notification";

  /**
   * Used as a workaround for http://code.google.com/p/android/issues/detail?id=6822
   */
  static final String EXTRA_NOTIFICATION_BUNDLE = "notification_bundle";


  /** Goro instance used by the service. */
  private static Goro goro;

  /** Errors thrower. */
  private static final ErrorThrow ERROR_THROWER = new ErrorThrow();


  /** Bound users flag. */
  boolean hasBoundUsers;

  /** Binder instance. */
  private GoroBinderImpl binder;

  /** Stop handler. */
  private final StopHandler stopHandler = new StopHandler(this);

  /***
   * Initialize GoroService which will allow you to use {@code Goro.bindXXX} methods.
   * @param context context instance used to enable GoroService component
   * @param goro instance of Goro that should be used by the service
   */
  public static void setup(final Context context, final Goro goro) {
    if (goro == null) {
      throw new IllegalArgumentException("Goro instance cannot be null");
    }
    if (!Util.checkMainThread()) {
      throw new IllegalStateException("GoroService.setup must be called on the main thread");
    }
    GoroService.goro = goro;
    context.getPackageManager().setComponentEnabledSetting(
        new ComponentName(context, GoroService.class),
        COMPONENT_ENABLED_STATE_ENABLED,
        DONT_KILL_APP
    );
  }

  /**
   * Create an intent that contains a task that should be scheduled
   * on a defined queue. The Service will run in the foreground and display notification.
   * Intent can be used as an argument for
   * {@link android.content.Context#startService(android.content.Intent)}
   * or {@link android.content.Context#startForegroundService(Intent)}
   *
   * @param context context instance
   * @param task task instance
   * @param queueName queue name
   * @param <T> task type
   * @param notificationId id of notification for foreground Service, must not be 0
   * @param notification notification for foreground Service,
   *                     should be not null to start service in the foreground
   */
  public static <T extends Callable<?> & Parcelable> Intent foregroundTaskIntent(final Context context,
                                                                                 final String queueName,
                                                                                 final T task,
                                                                                 final int notificationId,
                                                                                 final Notification notification) {
    // It may produce ClassNotFoundException when using custom Parcelable (for example via an AlarmManager).
    // As a workaround pack a Parcelable into an additional Bundle, then put that Bundle into Intent.
    // XXX http://code.google.com/p/android/issues/detail?id=6822
    Bundle taskBundle = new Bundle();
    taskBundle.putParcelable(EXTRA_TASK, task);
    Bundle notificationBundle = new Bundle();
    notificationBundle.putParcelable(EXTRA_NOTIFICATION, notification);
    return new Intent(context, GoroService.class)
        .putExtra(EXTRA_TASK_BUNDLE, taskBundle)
        .putExtra(EXTRA_NOTIFICATION_BUNDLE, notificationBundle)
        .putExtra(EXTRA_QUEUE_NAME, queueName)
        .putExtra(EXTRA_NOTIFICATION_ID, notificationId);
  }

  /**
   * Create an intent that contains a task that should be scheduled
   * on a defined queue. The Service will run in the foreground and display notification.
   * Intent can be used as an argument for
   * {@link android.content.Context#startService(android.content.Intent)}
   * or {@link android.content.Context#startForegroundService(Intent)}
   *
   * @param context context instance
   * @param task task instance
   * @param <T> task type
   * @param notificationId id of notification for foreground Service, must not be 0
   * @param notification notification for foreground Service,
   *                     should be not null to start service in the foreground
   */
  public static <T extends Callable<?> & Parcelable> Intent foregroundTaskIntent(final Context context,
                                                                                 final T task,
                                                                                 final int notificationId,
                                                                                 final Notification notification) {
    return foregroundTaskIntent(context, Goro.DEFAULT_QUEUE, task, notificationId, notification);
  }

  /**
   * Create an intent that contains a task that should be scheduled
   * on a defined queue.
   * Intent can be used as an argument for
   * {@link android.content.Context#startService(android.content.Intent)}.
   *
   * @param context context instance
   * @param task task instance
   * @param queueName queue name
   * @param <T> task type
   */
  public static <T extends Callable<?> & Parcelable> Intent taskIntent(final Context context,
                                                                       final String queueName,
                                                                       final T task) {
    // XXX http://code.google.com/p/android/issues/detail?id=6822
    Bundle bundle = new Bundle();
    bundle.putParcelable(EXTRA_TASK, task);
    return new Intent(context, GoroService.class)
        .putExtra(EXTRA_TASK_BUNDLE, bundle)
        .putExtra(EXTRA_QUEUE_NAME, queueName);
  }

  /**
   * Create an intent that contains a task that should be scheduled
   * on a default queue.
   * @param context context instance
   * @param task task instance
   * @param <T> task type
   * @see #taskIntent(android.content.Context, String, java.util.concurrent.Callable)
   */
  public static <T extends Callable<?> & Parcelable> Intent taskIntent(final Context context,
                                                                       final T task) {
    return taskIntent(context, Goro.DEFAULT_QUEUE, task);
  }

  /**
   * Bind to Goro service. This method will start the service and then bind to it.
   * @param context context that is binding to the service
   * @param connection service connection callbacks
   * @throws GoroException when service is not declared in the application manifest
   */
  public static void bind(final Context context, final ServiceConnection connection) {
    Intent serviceIntent = new Intent(context, GoroService.class);
    if (context.startService(serviceIntent) == null) {
      throw new GoroException("Service " + GoroService.class
          + " does not seem to be included to your manifest file");
    }
    boolean bound = context.bindService(serviceIntent, connection, 0);
    if (!bound) {
      throw new GoroException("Cannot bind to GoroService though this component seems to "
          + "be registered in the app manifest");
    }
  }

  /**
   * Unbind from Goro service.
   * @param context context that is unbinding from the service
   * @param connection service connection callbacks
   */
  public static void unbind(final Context context, final ServiceConnection connection) {
    context.unbindService(connection);
  }

  /**
   * Get a task instance packed into an {@code Intent} with
   * {@link #taskIntent(android.content.Context, java.util.concurrent.Callable)}.
   */
  public static Callable<?> getTaskFromExtras(final Intent intent) {
    if (!intent.hasExtra(EXTRA_TASK) && !intent.hasExtra(EXTRA_TASK_BUNDLE)) {
      return null;
    }

    Parcelable taskArg = intent.getParcelableExtra(EXTRA_TASK);
    if (taskArg == null) {
      Bundle bundle = intent.getBundleExtra(EXTRA_TASK_BUNDLE);
      if (bundle != null) {
        taskArg = bundle.getParcelable(EXTRA_TASK);
      }
    }

    if (!(taskArg instanceof Callable)) {
      throw new IllegalArgumentException("Task " + taskArg + " is not a Callable");
    }

    return (Callable<?>)taskArg;
  }

  private GoroBinderImpl getBinder() {
    if (binder == null) {
      if (!Util.checkMainThread()) {
        throw new IllegalStateException(
            "Goro binder is being created not in the main thread. "
            + "This might happen if you invoke GoroService.getGoro() not from the main thread."
        );
      }
      if (goro == null) {
        throw new IllegalStateException("GoroService is not initialized. Call GoroService.setup");
      }
      binder = new GoroBinderImpl(goro, new GoroTasksListener());
    }
    return binder;
  }

  private void injectContext(final Callable<?> task) {
    if (task instanceof ServiceContextAware) {
      ((ServiceContextAware) task).injectServiceContext(this);
    }
  }


  @Override
  public int onStartCommand(final Intent intent, final int flags, final int startId) {
    if (intent != null) {
      Callable<?> task = getTaskFromExtras(intent);
      if (task != null) {
        injectContext(task);
        String queueName = intent.hasExtra(EXTRA_QUEUE_NAME)
            ? intent.getStringExtra(EXTRA_QUEUE_NAME)
            : Goro.DEFAULT_QUEUE;

        ObservableFuture<?> future = getBinder().goro.schedule(queueName, task);
        if (!intent.getBooleanExtra(EXTRA_IGNORE_ERROR, false)) {
          ensureErrorWillBeThrown(future);
        }
      }

      if (intent.hasExtra(EXTRA_NOTIFICATION_BUNDLE)) {
        Bundle bundle = intent.getBundleExtra(EXTRA_NOTIFICATION_BUNDLE);
        if (bundle != null) {
          Notification notification = bundle.getParcelable(EXTRA_NOTIFICATION);
          int notificationId = bundle.getInt(EXTRA_NOTIFICATION_ID);
          if (notification != null) {
            startForeground(notificationId, notification);
          }
        }
      }
    }
    return START_STICKY;
  }

  @SuppressWarnings("unchecked")
  private static void ensureErrorWillBeThrown(final ObservableFuture<?> future) {
    future.subscribe(ERROR_THROWER);
  }

  @Override
  public IBinder onBind(final Intent intent) {
    if (DEBUG) {
      Log.i(TAG, "bind");
    }
    hasBoundUsers = true;
    stopHandler.doNotStop();
    return getBinder();
  }

  @Override
  public boolean onUnbind(final Intent intent) {
    if (DEBUG) {
      Log.i(TAG, "unbind");
    }
    hasBoundUsers = false;
    stopHandler.checkForStop();
    return true;
  }

  @Override
  public void onRebind(final Intent intent) {
    if (DEBUG) {
      Log.i(TAG, "rebind");
    }
    hasBoundUsers = true;
    stopHandler.doNotStop();
  }

  /**
   * Return an instance of {@link com.stanfy.enroscar.goro.Goro} managed by this service.
   * Should be called from the main thread.
   */
  public Goro getGoro() {
    return getBinder().goro();
  }

  /** Returns a Goro instance. */
  public interface GoroBinder extends IBinder {
    Goro goro();
  }

  /** Goro service binder. */
  static class GoroBinderImpl extends Binder implements GoroBinder {

    /** Goro instance. */
    final Goro goro;

    /** Listener. */
    final GoroTasksListener listener;

    GoroBinderImpl(final Goro goro, final GoroTasksListener listener) {
      this.goro = goro;
      this.listener = listener;
      goro.addTaskListener(listener);
    }

    @Override
    public Goro goro() {
      return goro;
    }
  }

  /** Listens to task events. */
  class GoroTasksListener implements GoroListener {

    int activeTasksCount;

    @Override
    public void onTaskSchedule(final Callable<?> task, final String queue) {
      stopHandler.doNotStop();
      activeTasksCount++;
    }

    @Override
    public void onTaskStart(Callable<?> task) { }

    private void taskFinish() {
      activeTasksCount--;
      stopHandler.checkForStop();
    }

    @Override
    public void onTaskFinish(Callable<?> task, Object result) {
      taskFinish();
    }

    @Override
    public void onTaskCancel(Callable<?> task) {
      taskFinish();
    }

    @Override
    public void onTaskError(Callable<?> task, Throwable error) {
      taskFinish();
    }
  }

  /** Internal handler for stopping service. */
  private static class StopHandler extends Handler {

    /** Check for stop message. */
    private static final int MSG_CHECK_FOR_STOP = 1;
    /** Stop message. */
    private static final int MSG_STOP = 2;

    /** Service instance. */
    private final WeakReference<GoroService> serviceRef;

    public StopHandler(final GoroService service) {
      this.serviceRef = new WeakReference<>(service);
    }

    public void checkForStop() {
      if (DEBUG) {
        Log.w(TAG, "send check for stop");
      }
      doNotStop(); // clear any existing checks
      sendEmptyMessage(MSG_CHECK_FOR_STOP);
    }

    public void doNotStop() {
      if (DEBUG) {
        Log.w(TAG, "do not stop now");
      }
      removeMessages(MSG_STOP);
      removeMessages(MSG_CHECK_FOR_STOP);
    }

    private static boolean isServiceActive(final GoroService service) {
      boolean tasksRunning = service.binder != null && service.binder.listener.activeTasksCount > 0;
      if (DEBUG) {
        Log.w(TAG, "isServiceActive: " + service.hasBoundUsers + ", " + tasksRunning);
      }
      return service.hasBoundUsers || tasksRunning;
    }

    @Override
    public void handleMessage(final Message msg) {
      final GoroService service = serviceRef.get();
      if (service == null) { return; }

      switch (msg.what) {
        case MSG_CHECK_FOR_STOP:
          if (!isServiceActive(service)) {
            if (DEBUG) {
              Log.w(TAG, "send stop");
            }
            sendEmptyMessage(MSG_STOP);
          }
          break;

        case MSG_STOP:
          if (DEBUG) {
            Log.w(TAG, "do stop");
          }
          service.stopSelf();
          break;

        default:
          throw new IllegalArgumentException("Unexpected message " + msg);
      }
    }

  }

  /** Rethrows an error. */
  private static final class ErrorThrow implements FutureObserver {

    @Override
    public void onSuccess(final Object value) {
      // nothing
    }

    @Override
    public void onError(final Throwable error) {
      throw new GoroException(
          "Uncaught error thrown by a task scheduled with startService()",
          error
      );
    }
  }

}
