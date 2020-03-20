package io.joyrpc.cluster.distribution;

/*-
 * #%L
 * joyrpc
 * %%
 * Copyright (C) 2019 joyrpc.io
 * %%
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * #L%
 */

import io.joyrpc.Result;
import io.joyrpc.cache.Cache;
import io.joyrpc.cache.CacheConfig;
import io.joyrpc.cache.CacheFactory;
import io.joyrpc.cache.CacheKeyGenerator;
import io.joyrpc.cache.CacheKeyGenerator.ExpressionGenerator;
import io.joyrpc.cluster.distribution.FailoverPolicy.DefaultFailoverPolicy;
import io.joyrpc.exception.InitializationException;
import io.joyrpc.extension.Parametric;
import io.joyrpc.extension.URL;
import io.joyrpc.extension.WrapperParametric;
import io.joyrpc.permission.BlackWhiteList;
import io.joyrpc.permission.ExceptionBlackWhiteList;
import io.joyrpc.permission.StringBlackWhiteList;
import io.joyrpc.protocol.message.Invocation;
import io.joyrpc.protocol.message.RequestMessage;
import io.joyrpc.util.ClassUtils;
import io.joyrpc.util.MethodOption.NameKeyOption;
import io.joyrpc.util.SystemClock;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

import static io.joyrpc.GenericService.GENERIC;
import static io.joyrpc.Plugin.*;
import static io.joyrpc.constants.Constants.*;
import static io.joyrpc.constants.ExceptionCode.CONSUMER_FAILOVER_CLASS;
import static io.joyrpc.util.ClassUtils.forName;
import static io.joyrpc.util.StringUtils.SEMICOLON_COMMA_WHITESPACE;
import static io.joyrpc.util.StringUtils.split;

/**
 * 参数选项
 */
public class MethodOption {

    /**
     * 扫描Provider提供的META-INF/retry/xxx.xxx.xxx.xx文件的异常配置
     */
    protected static final Map<String, Set<String>> INNER_EXCEPTIONS = new ConcurrentHashMap<>();
    /**
     * 内置的异常类资源配置文件路径
     */
    protected static final String RETRY_RESOURCE_PATH = "META-INF/retry/";
    /**
     * 接口名称
     */
    protected String interfaceName;
    /**
     * 接口级别最大重试次数
     */
    protected int maxRetry;
    /**
     * 接口级别的每个节点重试一次
     */
    protected boolean retryOnlyOncePerNode;
    /**
     * 接口级别的重试目标节点选择器
     */
    protected String failoverSelector;
    /**
     * 异常检测
     */
    protected String failoverPredication;
    /**
     * 默认的分发策略
     */
    protected Route route;
    /**
     * 接口级别超时时间
     */
    protected int timeout;
    /**
     * 接口级别并行度
     */
    protected int forks;
    /**
     * 接口级别并发数配置
     */
    protected int concurrency;
    /**
     * 是否启用缓存
     */
    protected boolean cacheEnable;
    /**
     * 是否缓存空值
     */
    protected boolean cacheNullable;
    /**
     * 缓存容量
     */
    protected int cacheCapacity;
    /**
     * 缓存过期时间
     */
    protected int cacheExpireTime;
    /**
     * 缓存键生成器
     */
    protected String cacheKeyGenerator;
    /**
     * 缓存提供者
     */
    protected String cacheProvider;
    /**
     * 缓存工厂类
     */
    protected CacheFactory cacheFactory;
    /**
     * 重试异常
     */
    protected BlackWhiteList<Class<? extends Throwable>> failoverBlackWhiteList;
    /**
     * 方法黑白名单
     */
    protected BlackWhiteList<String> methodBlackWhiteList;
    /**
     * 方法透传参数
     */
    protected NameKeyOption<Option> options;
    /**
     * 分发策略配置
     */
    protected Consumer<Route> configure;

    /**
     * 构造函数
     *
     * @param interfaceClass 接口类
     * @param interfaceName  接口名称
     * @param url            URL
     */
    public MethodOption(final Class<?> interfaceClass, final String interfaceName, final URL url) {
        this(interfaceClass, interfaceName, url, null);
    }

    /**
     * 构造函数
     *
     * @param interfaceClass 接口类
     * @param interfaceName  接口名称
     * @param url            URL
     * @param configure      路由配置器
     */
    public MethodOption(final Class<?> interfaceClass, final String interfaceName, final URL url,
                        final Consumer<Route> configure) {
        this.interfaceName = interfaceName;
        this.failoverBlackWhiteList = buildFailoverBlackWhiteList(url);
        this.maxRetry = url.getInteger(RETRIES_OPTION);
        this.timeout = url.getPositiveInt(TIMEOUT_OPTION);
        this.retryOnlyOncePerNode = url.getBoolean(RETRY_ONLY_ONCE_PER_NODE_OPTION);
        this.failoverSelector = url.getString(FAILOVER_SELECTOR_OPTION);
        this.failoverPredication = url.getString(FAILOVER_PREDICATION_OPTION);
        this.forks = url.getInteger(FORKS_OPTION);
        this.concurrency = url.getInteger(CONCURRENCY_OPTION);
        //缓存配置
        this.cacheEnable = url.getBoolean(CACHE_OPTION);
        this.cacheNullable = url.getBoolean(CACHE_NULLABLE_OPTION);
        this.cacheCapacity = url.getInteger(CACHE_CAPACITY_OPTION);
        this.cacheExpireTime = url.getInteger(CACHE_EXPIRE_TIME_OPTION);
        this.cacheKeyGenerator = url.getString(CACHE_KEY_GENERATOR_OPTION);
        this.cacheProvider = url.getString(CACHE_PROVIDER_OPTION);
        this.cacheFactory = CACHE.get(cacheProvider);
        this.configure = configure;
        if (configure != null) {
            this.route = ROUTE.get(url.getString(ROUTE_OPTION));
            configure.accept(route);
        }
        String include = url.getString(METHOD_INCLUDE_OPTION.getName());
        String exclude = url.getString(METHOD_EXCLUDE_OPTION.getName());
        this.methodBlackWhiteList = (include == null || include.isEmpty()) && (exclude == null || exclude.isEmpty()) ? null :
                new StringBlackWhiteList(include, exclude);

        //方法级别的隐藏参数，保留以"."开头
        boolean generic = GENERIC.test(interfaceClass);

        this.options = new NameKeyOption<>(generic ? null : interfaceClass, generic ? interfaceName : null,
                method -> {
                    String prefix = URL_METHOD_PREX + method + ".";
                    Parametric parametric = new WrapperParametric(url, method, METHOD_KEY_FUNC, key -> key.startsWith(prefix));
                    Map<String, String> implicits = url.startsWith(METHOD_KEY_FUNC.apply(method, String.valueOf(HIDE_KEY_PREFIX)), (k, v) -> v.substring(k.length() - 1));
                    return new Option(
                            implicits,
                            parametric.getPositive(TIMEOUT_OPTION.getName(), timeout),
                            parametric.getInteger(FORKS_OPTION.getName(), forks),
                            getRoute(parametric),
                            new Concurrency(parametric.getInteger(CONCURRENCY_OPTION.getName(), concurrency)),
                            new DefaultFailoverPolicy(
                                    parametric.getInteger(RETRIES_OPTION.getName(), maxRetry),
                                    parametric.getBoolean(RETRY_ONLY_ONCE_PER_NODE_OPTION.getName(), retryOnlyOncePerNode),
                                    new MyTimeoutPolicy(),
                                    new MyExceptionPolicy(failoverBlackWhiteList, EXCEPTION_PREDICATION.get(failoverPredication)),
                                    FAILOVER_SELECTOR.get(parametric.getString(FAILOVER_SELECTOR_OPTION.getName(), failoverSelector))),
                            getCachePolicy(method, parametric),
                            methodBlackWhiteList);
                }
        );
    }

    /**
     * 获取分发策略
     *
     * @param parametric 参数
     * @return 分发策略
     */
    protected Route getRoute(final Parametric parametric) {
        //方法分发策略
        Route methodRoute = null;
        if (configure != null) {
            methodRoute = ROUTE.get(parametric.getString(ROUTE_OPTION.getName()));
            if (methodRoute != null) {
                configure.accept(methodRoute);
            } else {
                methodRoute = route;
            }

        }
        return methodRoute;
    }

    /**
     * 构造缓存策略
     *
     * @param name       名称
     * @param parametric 参数
     * @return 缓存策略
     */
    protected CachePolicy getCachePolicy(final String name, final Parametric parametric) {
        CachePolicy cachePolicy = null;
        //判断是否开启了缓存
        boolean enable = cacheFactory == null ? false : parametric.getBoolean(CACHE_OPTION.getName(), cacheEnable);
        if (enable) {
            //获取缓存键生成器
            CacheKeyGenerator generator = CACHE_KEY_GENERATOR.get(parametric.getString(CACHE_KEY_GENERATOR_OPTION.getName(), cacheKeyGenerator));
            if (generator != null) {
                //看看是否是表达式
                if (generator instanceof ExpressionGenerator) {
                    ExpressionGenerator gen = (ExpressionGenerator) generator;
                    gen.setParametric(parametric);
                    gen.setup();
                }
                //判断是否缓存空值
                //创建缓存
                CacheConfig<Object, Object> cacheConfig = CacheConfig.builder().
                        nullable(parametric.getBoolean(CACHE_NULLABLE_OPTION.getName(), cacheNullable)).
                        capacity(parametric.getInteger(CACHE_CAPACITY_OPTION.getName(), cacheCapacity)).
                        expireAfterWrite(parametric.getInteger(CACHE_EXPIRE_TIME_OPTION.getName(), cacheExpireTime)).
                        build();
                Cache<Object, Object> cache = cacheFactory.build(name, cacheConfig);
                cachePolicy = new CachePolicy(cache, generator);
            }
        }
        return cachePolicy;
    }

    /**
     * 构建异常重试类
     *
     * @param url url
     * @return 异常黑白名单
     */
    protected BlackWhiteList<Class<? extends Throwable>> buildFailoverBlackWhiteList(final URL url) {
        //内置的异常类名
        Set<String> names = new HashSet<>(INNER_EXCEPTIONS.computeIfAbsent(interfaceName, this::getInnerExceptions));
        //当前URL配置的异常
        String value = url.getString(FAILOVER_WHEN_THROWABLE_OPTION);
        if (value != null && !value.isEmpty()) {
            String[] classes = split(value, SEMICOLON_COMMA_WHITESPACE);
            Collections.addAll(names, classes);
        }
        Set<Class<? extends Throwable>> failoverClass = new HashSet<>();
        Class<?> c;
        for (String name : names) {
            try {
                c = forName(name);
                if (!Throwable.class.isAssignableFrom(c)) {
                    throw new InitializationException(String.format("Class is not extends throwable, %s", name), CONSUMER_FAILOVER_CLASS);
                }
                failoverClass.add((Class<? extends Throwable>) c);
            } catch (ClassNotFoundException e) {
                throw new InitializationException(e.getMessage(), CONSUMER_FAILOVER_CLASS);
            }
        }
        return new ExceptionBlackWhiteList(failoverClass, null, false);
    }

    /**
     * 获取方法选项
     *
     * @param methodName 方法名称
     * @return 方法选项
     */
    public Option getOption(final String methodName) {
        return options.get(methodName);
    }

    /**
     * 读取内置的异常配置信息
     *
     * @param interfaceName 接口名称
     * @return 异常类名
     */
    protected Set<String> getInnerExceptions(final String interfaceName) {
        Set<String> names = new HashSet<>();
        ClassLoader loader = ClassUtils.getCurrentClassLoader();
        String line;
        try {
            Enumeration<java.net.URL> urls = loader.getResources(RETRY_RESOURCE_PATH + interfaceName);
            while ((urls.hasMoreElements())) {
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(urls.nextElement().openStream(), StandardCharsets.UTF_8))) {
                    while ((line = reader.readLine()) != null) {
                        names.add(line);
                    }
                } catch (IOException e) {
                    throw new InitializationException(e.getMessage(), CONSUMER_FAILOVER_CLASS);
                }
            }
        } catch (IOException e) {
            throw new InitializationException(e.getMessage(), CONSUMER_FAILOVER_CLASS);
        }
        return names;

    }

    /**
     * 方法选项
     */
    public static class Option {
        /**
         * 隐式传参
         */
        protected Map<String, ?> implicits;
        /**
         * 超时时间
         */
        protected int timeout;
        /**
         * 并行度
         */
        protected int forks;
        /**
         * 分发策略
         */
        protected Route route;
        /**
         * 并发数配置
         */
        protected Concurrency concurrency;
        /**
         * 重试策略
         */
        protected FailoverPolicy failoverPolicy;
        /**
         * 缓存策略
         */
        protected CachePolicy cachePolicy;
        /**
         * 方法的黑白名单
         */
        protected BlackWhiteList<String> methodBlackWhiteList;

        /**
         * 构造函数
         *
         * @param implicits            隐式传参
         * @param timeout              超时时间
         * @param forks                并行度
         * @param route                分发策略
         * @param concurrency          并发数配置
         * @param failoverPolicy       重试策略
         * @param cachePolicy          缓存策略
         * @param methodBlackWhiteList 方法黑白名单
         */
        public Option(Map<String, ?> implicits, int timeout, int forks, Route route,
                      final Concurrency concurrency,
                      final FailoverPolicy failoverPolicy,
                      final CachePolicy cachePolicy,
                      final BlackWhiteList<String> methodBlackWhiteList) {
            this.implicits = implicits;
            this.timeout = timeout;
            this.forks = forks;
            this.route = route;
            this.concurrency = concurrency;
            this.failoverPolicy = failoverPolicy;
            this.cachePolicy = cachePolicy;
            this.methodBlackWhiteList = methodBlackWhiteList;
        }

        public Map<String, ?> getImplicits() {
            return implicits;
        }

        public int getTimeout() {
            return timeout;
        }

        public int getForks() {
            return forks;
        }

        public Route getRoute() {
            return route;
        }

        public Concurrency getConcurrency() {
            return concurrency;
        }

        public FailoverPolicy getFailoverPolicy() {
            return failoverPolicy;
        }

        public CachePolicy getCachePolicy() {
            return cachePolicy;
        }

        public BlackWhiteList<String> getMethodBlackWhiteList() {
            return methodBlackWhiteList;
        }
    }

    /**
     * 异常策略
     */
    protected static class MyExceptionPolicy implements ExceptionPolicy {
        /**
         * 异常黑白名单
         */
        protected BlackWhiteList<Class<? extends Throwable>> failoverBlackWhiteList;
        /**
         * 异常检测
         */
        protected ExceptionPredication exceptionPredication;

        /**
         * 构造函数
         *
         * @param failoverBlackWhiteList 异常黑白名单
         * @param exceptionPredication   异常断言
         */
        public MyExceptionPolicy(final BlackWhiteList<Class<? extends Throwable>> failoverBlackWhiteList,
                                 final ExceptionPredication exceptionPredication) {
            this.failoverBlackWhiteList = failoverBlackWhiteList;
            this.exceptionPredication = exceptionPredication;
        }

        @Override
        public Throwable getThrowable(final Result result) {
            return result.getException();
        }

        @Override
        public boolean test(final Throwable throwable) {
            //暂时不需要增加动态配置支持，这些一般都需要提前测试配置好。
            return failoverBlackWhiteList.isValid(throwable.getClass()) || (exceptionPredication != null && exceptionPredication.test(throwable));
        }
    }

    /**
     * 超时策略
     */
    protected static class MyTimeoutPolicy implements TimeoutPolicy {

        @Override
        public boolean test(final RequestMessage<Invocation> request) {
            return request.isTimeout();
        }

        @Override
        public void reset(final RequestMessage<Invocation> request) {
            request.getHeader().setTimeout((int) (request.getTimeout() + request.getCreateTime() - SystemClock.now()));
        }
    }

    /**
     * 并发数指标
     */
    public static class Concurrency {

        /**
         * 最大并发数
         */
        protected int max;

        /**
         * 活动并发
         */
        protected AtomicLong actives = new AtomicLong();

        public Concurrency(int max) {
            this.max = max;
        }

        public int getMax() {
            return max;
        }

        /**
         * 当前并发数
         *
         * @return
         */
        public long getActives() {
            return actives.get();
        }

        /**
         * 增加
         */
        public void add() {
            actives.incrementAndGet();
        }

        /**
         * 减少并发数
         */
        public void decrement() {
            actives.decrementAndGet();
        }

        /**
         * 唤醒
         */
        public void wakeup() {
            synchronized (this) {
                // 调用结束 通知等待的人
                notifyAll();
            }
        }

        /**
         * 等到
         *
         * @param time
         * @return
         */
        public boolean await(final long time) {
            if (time <= 0) {
                return true;
            }
            synchronized (this) {
                try {
                    // 等待执行
                    wait(time);
                    return true;
                } catch (InterruptedException e) {
                    return false;
                }
            }
        }

    }

    /**
     * 缓存策略
     */
    public static class CachePolicy {
        /**
         * 缓存接口
         */
        protected final Cache<Object, Object> cache;
        /**
         * 缓存键生成器
         */
        protected final CacheKeyGenerator generator;

        public CachePolicy(Cache<Object, Object> cache, CacheKeyGenerator generator) {
            this.cache = cache;
            this.generator = generator;
        }

        public Cache<Object, Object> getCache() {
            return cache;
        }

        public CacheKeyGenerator getGenerator() {
            return generator;
        }
    }

}
