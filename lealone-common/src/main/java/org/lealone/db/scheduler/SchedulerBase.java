/*
 * Copyright Lealone Database Group.
 * Licensed under the Server Side Public License, v 1.
 * Initial Developer: zhh
 */
package org.lealone.db.scheduler;

import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReferenceArray;

import org.lealone.common.exceptions.DbException;
import org.lealone.common.util.MapUtils;
import org.lealone.common.util.ShutdownHookUtils;
import org.lealone.db.DataBufferFactory;
import org.lealone.db.RunMode;
import org.lealone.db.async.AsyncResult;
import org.lealone.db.async.PendingTask;
import org.lealone.db.async.PendingTaskHandler;
import org.lealone.db.link.LinkableBase;
import org.lealone.db.link.LinkableList;
import org.lealone.db.session.Session;
import org.lealone.storage.page.PageOperation;
import org.lealone.storage.page.PageOperation.PageOperationResult;
import org.lealone.storage.page.PageOperationHandler;
import org.lealone.transaction.PendingTransaction;
import org.lealone.transaction.TransactionListener;

public abstract class SchedulerBase implements Scheduler {

    protected int id;
    protected String name;
    protected SchedulerFactory schedulerFactory;

    protected final long loopInterval;
    protected boolean started;
    protected boolean stopped;
    protected SchedulerThread thread;

    // --------------------- 以下字段用于实现 PageOperationHandler 接口 ---------------------
    protected final LinkableList<LinkablePageOperation> lockedPageOperationTasks = new LinkableList<>();
    protected final AtomicReferenceArray<PageOperationHandler> waitingHandlers;
    protected final AtomicBoolean hasWaitingHandlers = new AtomicBoolean(false);
    protected Session currentSession;

    public SchedulerBase(int id, String name, int schedulerCount, Map<String, String> config) {
        this.id = id;
        this.name = name;
        // 默认100毫秒
        this.loopInterval = MapUtils.getLong(config, "scheduler_loop_interval", 100);

        waitingHandlers = new AtomicReferenceArray<>(schedulerCount);

        thread = new SchedulerThread(this);
        thread.setName(name);
        thread.setDaemon(RunMode.isEmbedded(config));
    }

    @Override
    public int getId() {
        return id;
    }

    public void setId(int id) {
        this.id = id;
    }

    @Override
    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    @Override
    public String toString() {
        return name;
    }

    @Override
    public SchedulerFactory getSchedulerFactory() {
        return schedulerFactory;
    }

    @Override
    public void setSchedulerFactory(SchedulerFactory schedulerFactory) {
        this.schedulerFactory = schedulerFactory;
    }

    @Override
    public SchedulerThread getThread() {
        return thread;
    }

    @Override
    public DataBufferFactory getDataBufferFactory() {
        return DataBufferFactory.getConcurrentFactory();
    }

    @Override
    public long getLoad() {
        return pendingTaskHandlers.size() + lockedPageOperationTasks.size();
    }

    @Override
    public void addSession(Session session, int databaseId) {
        addPendingTaskHandler(session);
    }

    @Override
    public synchronized void start() {
        if (started)
            return;
        ShutdownHookUtils.addShutdownHook(getName(), () -> {
            stop();
        });
        thread.start();
        started = true;
        stopped = false;
    }

    @Override
    public synchronized void stop() {
        started = false;
        stopped = true;
        thread = null;
        wakeUp();
    }

    @Override
    public boolean isStarted() {
        return started;
    }

    @Override
    public boolean isStopped() {
        return stopped;
    }

    // --------------------- 实现 TransactionListener 接口，用同步方式执行 ---------------------

    protected AtomicInteger syncCounter;
    protected RuntimeException syncException;
    protected boolean needWakeUp = true;

    @Override
    public void setNeedWakeUp(boolean needWakeUp) {
        this.needWakeUp = needWakeUp;
    }

    @Override
    public int getListenerId() {
        return id;
    }

    @Override
    public void beforeOperation() {
        syncException = null;
        syncCounter = new AtomicInteger(1);
    }

    @Override
    public void operationUndo() {
        syncCounter.decrementAndGet();
        if (needWakeUp)
            wakeUp();
    }

    @Override
    public void operationComplete() {
        syncCounter.decrementAndGet();
        if (needWakeUp)
            wakeUp();
    }

    @Override
    public void setException(RuntimeException e) {
        syncException = e;
    }

    @Override
    public RuntimeException getException() {
        return syncException;
    }

    @Override
    public void await() {
    }

    // protected abstract void runLoopTasks();

    @Override
    public void removeSession(Session session) {
        throw DbException.getInternalError();
    }

    // --------------------- 实现 PageOperation.ListenerFactory 接口 ---------------------

    @Override
    public PageOperation.Listener<Object> createListener() {
        return new PageOperation.Listener<Object>() {

            private Object result;

            @Override
            public void startListen() {
                beforeOperation();
            }

            @Override
            public void handle(AsyncResult<Object> ar) {
                if (ar.isSucceeded()) {
                    result = ar.getResult();
                } else {
                    setException(ar.getCause());
                }
                operationComplete();
            }

            @Override
            public Object await() {
                SchedulerBase.this.await();
                return result;
            }
        };
    }

    // --------------------- 实现部分 PageOperationHandler 接口 ---------------------

    @Override
    public int getHandlerId() {
        return id;
    }

    @Override
    public void addWaitingHandler(PageOperationHandler handler) {
        int id = handler.getHandlerId();
        if (id >= 0) {
            waitingHandlers.set(id, handler);
            hasWaitingHandlers.set(true);
        }
    }

    @Override
    public void wakeUpWaitingHandlers() {
        if (hasWaitingHandlers.compareAndSet(true, false)) {
            for (int i = 0, length = waitingHandlers.length(); i < length; i++) {
                PageOperationHandler handler = waitingHandlers.get(i);
                if (handler != null) {
                    handler.wakeUp();
                    waitingHandlers.compareAndSet(i, handler, null);
                }
            }
        }
    }

    @Override
    public Session getCurrentSession() {
        return currentSession;
    }

    @Override
    public void setCurrentSession(Session currentSession) {
        this.currentSession = currentSession;
    }

    @Override
    public boolean isScheduler() {
        return true;
    }

    protected static class LinkablePageOperation extends LinkableBase<LinkablePageOperation> {
        final PageOperation po;

        public LinkablePageOperation(PageOperation po) {
            this.po = po;
        }
    }

    @Override
    public void handlePageOperation(PageOperation po) {
        lockedPageOperationTasks.add(new LinkablePageOperation(po));
    }

    protected void runPageOperationTasks() {
        if (lockedPageOperationTasks.isEmpty())
            return;
        while (lockedPageOperationTasks.getHead() != null) {
            int size = lockedPageOperationTasks.size();
            LinkablePageOperation task = lockedPageOperationTasks.getHead();
            LinkablePageOperation last = null;
            while (task != null) {
                Session old = getCurrentSession();
                setCurrentSession(task.po.getSession());
                try {
                    PageOperationResult result = task.po.run(this);
                    if (result == PageOperationResult.LOCKED) {
                        last = task;
                        task = task.next;
                        continue;
                    } else if (result == PageOperationResult.RETRY) {
                        continue;
                    }
                    task = task.next;
                    if (last == null)
                        lockedPageOperationTasks.setHead(task);
                    else
                        last.next = task;
                } catch (Throwable e) {
                    getLogger().warn("Failed to run page operation: " + task, e);
                } finally {
                    setCurrentSession(old);
                }
                lockedPageOperationTasks.decrementSize();
            }
            if (lockedPageOperationTasks.getHead() == null)
                lockedPageOperationTasks.setTail(null);
            else
                lockedPageOperationTasks.setTail(last);

            // 全都锁住了，没必要再试了
            if (size == lockedPageOperationTasks.size())
                break;
        }
    }

    // --------------------- 实现 PendingTaskHandler 接口 ---------------------

    protected static class PendingTaskHandlerInfo extends LinkableBase<PendingTaskHandlerInfo> {

        final PendingTaskHandler handler;

        public PendingTaskHandlerInfo(PendingTaskHandler handler) {
            this.handler = handler;
        }
    }

    protected final LinkableList<PendingTaskHandler> pendingTaskHandlers = new LinkableList<>();

    @Override
    public void addPendingTaskHandler(PendingTaskHandler handler) {
        pendingTaskHandlers.add(handler);
    }

    @Override
    public void removePendingTaskHandler(PendingTaskHandler handler) {
        pendingTaskHandlers.remove(handler);
    }

    protected void runPendingTasks() {
        if (pendingTaskHandlers.isEmpty())
            return;
        PendingTaskHandler handler = pendingTaskHandlers.getHead();
        while (handler != null) {
            PendingTask pt = handler.getPendingTask();
            while (pt != null) {
                if (!pt.isCompleted()) {
                    try {
                        pt.getTask().run();
                    } catch (Throwable e) {
                        getLogger().warn("Failed to run pending task: " + pt.getTask(), e);
                    }
                    pt.setCompleted(true);
                }
                pt = pt.getNext();
            }
            handler = handler.getNext();
        }
    }

    // --------------------- 实现 TransactionHandler 接口 ---------------------

    // 存放还没有给客户端发送响应结果的事务
    protected final LinkableList<PendingTransaction> pendingTransactions = new LinkableList<>();

    // runPendingTransactions和addTransaction已经确保只有一个调度线程执行，所以是单线程安全的
    protected void runPendingTransactions() {
        if (pendingTransactions.isEmpty())
            return;
        PendingTransaction pt = pendingTransactions.getHead();
        while (pt != null && pt.isSynced()) {
            if (!pt.isCompleted()) {
                try {
                    pt.getTransaction().asyncCommitComplete();
                } catch (Throwable e) {
                    getLogger().warn("Failed to run pending transaction: " + pt, e);
                }
            }
            pt = pt.getNext();
            pendingTransactions.decrementSize();
            pendingTransactions.setHead(pt);
        }
        if (pendingTransactions.getHead() == null)
            pendingTransactions.setTail(null);
    }

    @Override
    public void addTransaction(PendingTransaction pt) {
        pendingTransactions.add(pt);
    }

    @Override
    public PendingTransaction getTransaction() {
        return pendingTransactions.getHead();
    }

    @Override
    public void addWaitingTransactionListener(TransactionListener listener) {
        addWaitingHandler((PageOperationHandler) listener);
    }

    @Override
    public void wakeUpWaitingTransactionListeners() {
        wakeUpWaitingHandlers();
    }
}