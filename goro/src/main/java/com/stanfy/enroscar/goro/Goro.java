package com.stanfy.enroscar.goro;

import android.content.Context;
import android.os.IBinder;

import java.util.concurrent.Callable;
import java.util.concurrent.Executor;

import static com.stanfy.enroscar.goro.BoundGoro.BoundGoroImpl;

/**
 * Handles tasks in multiple queues.
 */
public abstract class Goro {

  /** Default queue name. */
  public static final String DEFAULT_QUEUE = "default";

  /**
   * Gives access to Goro instance that is provided by a service.
   * @param binder Goro service binder
   * @return Goro instance provided by the service
   */
  public static Goro from(final IBinder binder) {
    if (binder instanceof GoroService.GoroBinder) {
      return ((GoroService.GoroBinder) binder).goro();
    }
    throw new IllegalArgumentException("Cannot get Goro from " + binder);
  }

  /**
   * Creates a new Goro instance which uses {@link android.os.AsyncTask#THREAD_POOL_EXECUTOR}
   * to delegate tasks on Post-Honeycomb devices or create a separate thread pool on earlier
   * Android versions.
   * @return instance of Goro
   */
  public static Goro create() {
    return new GoroImpl();
  }

  /**
   * Creates a new Goro instance which uses the specified executor to delegate tasks.
   * @param delegateExecutor executor Goro delegates tasks to
   * @return instance of Goro
   */
  public static Goro createWithDelegate(final Executor delegateExecutor) {
    GoroImpl goro = new GoroImpl();
    goro.queues.setDelegateExecutor(delegateExecutor);
    return goro;
  }

  /**
   * Creates a Goro implementation that binds to {@link com.stanfy.enroscar.goro.GoroService}
   * in order to run scheduled tasks in service context.
   * <p>
   *   This method is functionally identical to
   * </p>
   * <pre>
   *   BoundGoro goro = Goro.bindWith(context, new BoundGoro.OnUnexpectedDisconnection() {
   *     public void onServiceDisconnected(BoundGoro goro) {
   *       goro.bind();
   *     }
   *   });
   * </pre>
   * @param context context that will bind to the service
   * @return Goro implementation that binds to {@link GoroService}.
   * @see #bindWith(Context, BoundGoro.OnUnexpectedDisconnection)
   */
  public static BoundGoro bindAndAutoReconnectWith(final Context context) {
    if (context == null) {
      throw new IllegalArgumentException("Context cannot be null");
    }
    return new BoundGoroImpl(context, null);
  }

  /**
   * Creates a Goro implementation that binds to {@link GoroService}
   * in order to run scheduled tasks in service context.
   * {@code BoundGoro} exposes {@code bind()} and {@code unbind()} methods that you can use to connect to
   * and disconnect from the worker service in other component lifecycle callbacks
   * (like {@code onStart}/{@code onStop} in {@code Activity} or {@code onCreate}/{@code onDestory} in {@code Service}).
   * <p>
   *   The worker service ({@code GoroService}) normally stops when all {@code BoundGoro} instances unbind
   *   and all the pending tasks in {@code Goro} queues are handled.
   *   But it can also be stopped by the system server (due to a user action in Settings app or application update).
   *   In this case {@code BoundGoro} created with this method will notify the supplied handler about the event.
   * </p>
   * @param context context that will bind to the service
   * @param handler callback to invoke if worker service is unexpectedly stopped by the system server
   * @return Goro implementation that binds to {@link GoroService}.
   */
  public static BoundGoro bindWith(final Context context, final BoundGoro.OnUnexpectedDisconnection handler) {
    if (context == null) {
      throw new IllegalArgumentException("Context cannot be null");
    }
    if (handler == null) {
      throw new IllegalArgumentException("Disconnection handler cannot be null");
    }
    return new BoundGoroImpl(context, handler);
  }

  /**
   * Creates a Goro implementation that binds to a worker service to schedule tasks.
   * This implementation binds to the backing service when one of {@code Goro} methods is invoked,
   * then delegates all the tasks to the service and unbinds asap.
   * @param context context that will bind to the service
   * @return Goro implementation that binds to {@link GoroService}
   */
  public static Goro bindOnDemandWith(final Context context) {
    if (context == null) {
      throw new IllegalArgumentException("Context cannot be null");
    }
    return new OnDemandBindingGoro(context);
  }

  /**
   * Adds a task execution listener. Must be called from the main thread.
   * @param listener listener instance
   */
  public abstract void addTaskListener(final GoroListener listener);

  /**
   * Removes a task execution listener. Must be called from the main thread.
   * @param listener listener instance
   */
  public abstract void removeTaskListener(final GoroListener listener);


  /**
   * Add a task to the default queue.
   * This methods returns a future that allows to control task execution.
   * @param task task instance
   * @return task future instance
   */
  public abstract <T> ObservableFuture<T> schedule(final Callable<T> task);

  /**
   * Add a task to the specified queue.
   * This methods returns a future that allows to control task execution.
   * Queue name may be null, if you want to execute the task beyond any queue.
   * @param queueName name of a queue to use, may be null
   * @param task task instance
   * @return task future instance
   */
  public abstract <T> ObservableFuture<T> schedule(final String queueName, final Callable<T> task);

  /**
   * Returns an executor for performing tasks in a specified queue. If queue name is null,
   * {@link #DEFAULT_QUEUE} is used.
   * @param queueName queue name
   * @return executor instance that performs tasks serially in a specified queue
   */
  public abstract Executor getExecutor(final String queueName);

  /**
   * Removes all the pending tasks from a specified queue.
   * @param queueName queue name, must not be {@code null}
   */
  public final void clear(final String queueName) {
    if (queueName == null) {
      throw new IllegalArgumentException("Queue name must not be null");
    }
    removeTasksInQueue(queueName);
  }

  protected abstract void removeTasksInQueue(final String queueName);

  /** Main implementation. */
  static class GoroImpl extends Goro {
    /** Listeners handler. */
    final ListenersHandler listenersHandler = new ListenersHandler();

    /** Queues. */
    private final Queues queues;

    GoroImpl() {
      this(new Queues.Impl());
    }

    GoroImpl(final Queues queues) {
      this.queues = queues;
    }

    @Override
    public void addTaskListener(final GoroListener listener) {
      listenersHandler.addTaskListener(listener);
    }

    @Override
    public void removeTaskListener(final GoroListener listener) {
      listenersHandler.removeTaskListenerOrThrow(listener);
    }

    @Override
    public <T> ObservableFuture<T> schedule(final Callable<T> task) {
      return schedule(DEFAULT_QUEUE, task);
    }

    @Override
    public <T> ObservableFuture<T> schedule(final String queueName, final Callable<T> task) {
      if (task == null) {
        throw new IllegalArgumentException("Task must not be null");
      }

      GoroFuture<T> future = new GoroFuture<>(this, task);
      listenersHandler.postSchedule(task, queueName);
      queues.getExecutor(queueName).execute(future);
      return future;
    }

    @Override
    public Executor getExecutor(final String queueName) {
      return queues.getExecutor(queueName == null ? DEFAULT_QUEUE : queueName);
    }

    @Override
    protected void removeTasksInQueue(final String queueName) {
      queues.clear(queueName);
    }
  }

}
