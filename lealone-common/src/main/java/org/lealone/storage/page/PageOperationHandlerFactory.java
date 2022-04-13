/*
 * Copyright Lealone Database Group.
 * Licensed under the Server Side Public License, v 1.
 * Initial Developer: zhh
 */
package org.lealone.storage.page;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.atomic.AtomicInteger;

public abstract class PageOperationHandlerFactory {

    protected PageOperationHandler[] pageOperationHandlers;

    protected PageOperationHandlerFactory(Map<String, String> config, PageOperationHandler[] handlers) {
        if (handlers != null) {
            setPageOperationHandlers(handlers);
            return;
        }
        // 如果未指定处理器集，那么使用默认的
        int handlerCount;
        if (config.containsKey("page_operation_handler_count"))
            handlerCount = Integer.parseInt(config.get("page_operation_handler_count"));
        else
            handlerCount = Math.max(1, Runtime.getRuntime().availableProcessors());

        handlerCount = Math.max(1, handlerCount);

        pageOperationHandlers = new PageOperationHandler[handlerCount];
        for (int i = 0; i < handlerCount; i++) {
            pageOperationHandlers[i] = new DefaultPageOperationHandler("LeafPageOperationHandler-" + i, config);
        }
        startHandlers();
    }

    public List<PageOperationHandler> getAllPageOperationHandlers() {
        return new ArrayList<>(Arrays.asList(pageOperationHandlers));
    }

    public abstract PageOperationHandler getPageOperationHandler();

    public void setPageOperationHandlers(PageOperationHandler[] handlers) {
        pageOperationHandlers = new PageOperationHandler[handlers.length];
        System.arraycopy(handlers, 0, pageOperationHandlers, 0, handlers.length);
    }

    public void addPageOperation(PageOperation po) {
        Object t = Thread.currentThread();
        // 如果当前线程本身就是PageOperationHandler，就算PageOperation想要操作的page不是它管辖范围内的，
        // 也可以提前帮此page的PageOperationHandler找到这个即将被操作的page，
        // 然后把PageOperation移交到对应的PageOperationHandler的队列中，下次处理时就不用重新再遍历btree了。
        if (t instanceof PageOperationHandler) {
            po.run((PageOperationHandler) t);
        } else {
            // 如果当前线程不是PageOperationHandler，按配置的分配策略选出一个出来，放到它的队列中，让它去处理
            PageOperationHandler handler = getPageOperationHandler();
            handler.handlePageOperation(po);
        }
    }

    public int getPageOperationHandlerCount() {
        return pageOperationHandlers.length;
    }

    public void startHandlers() {
        for (PageOperationHandler h : pageOperationHandlers) {
            if (h instanceof DefaultPageOperationHandler) {
                ((DefaultPageOperationHandler) h).start();
            }
        }
    }

    public void stopHandlers() {
        for (PageOperationHandler h : pageOperationHandlers) {
            if (h instanceof DefaultPageOperationHandler) {
                ((DefaultPageOperationHandler) h).stop();
            }
        }
    }

    public static PageOperationHandlerFactory instance;

    public static PageOperationHandlerFactory create(Map<String, String> config) {
        return create(config, null);
    }

    public static synchronized PageOperationHandlerFactory create(Map<String, String> config,
            PageOperationHandler[] handlers) {
        if (instance != null)
            return instance;
        if (config == null)
            config = new HashMap<>(0);
        PageOperationHandlerFactory factory = null;
        String key = "page_operation_handler_factory_type";
        String type = null; // "LoadBalance";
        if (config.containsKey(key))
            type = config.get(key);
        if (type == null || type.equalsIgnoreCase("RoundRobin"))
            factory = new RoundRobinFactory(config, handlers);
        else if (type.equalsIgnoreCase("Random"))
            factory = new RandomFactory(config, handlers);
        else if (type.equalsIgnoreCase("LoadBalance"))
            factory = new LoadBalanceFactory(config, handlers);
        else {
            throw new RuntimeException("Unknow " + key + ": " + type);
        }
        PageOperationHandlerFactory.instance = factory;
        return factory;
    }

    private static class RandomFactory extends PageOperationHandlerFactory {

        private static final Random random = new Random();

        protected RandomFactory(Map<String, String> config, PageOperationHandler[] handlers) {
            super(config, handlers);
        }

        @Override
        public PageOperationHandler getPageOperationHandler() {
            int index = random.nextInt(pageOperationHandlers.length);
            return pageOperationHandlers[index];
        }
    }

    private static class RoundRobinFactory extends PageOperationHandlerFactory {

        private static final AtomicInteger index = new AtomicInteger(0);

        protected RoundRobinFactory(Map<String, String> config, PageOperationHandler[] handlers) {
            super(config, handlers);
        }

        @Override
        public PageOperationHandler getPageOperationHandler() {
            return pageOperationHandlers[index.getAndIncrement() % pageOperationHandlers.length];
        }
    }

    private static class LoadBalanceFactory extends PageOperationHandlerFactory {

        protected LoadBalanceFactory(Map<String, String> config, PageOperationHandler[] handlers) {
            super(config, handlers);
        }

        @Override
        public PageOperationHandler getPageOperationHandler() {
            long minLoad = Long.MAX_VALUE;
            int index = 0;
            for (int i = 0, size = pageOperationHandlers.length; i < size; i++) {
                long load = pageOperationHandlers[i].getLoad();
                if (load < minLoad)
                    index = i;
            }
            return pageOperationHandlers[index];
        }
    }
}
