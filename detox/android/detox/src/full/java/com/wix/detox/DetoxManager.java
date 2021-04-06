package com.wix.detox;

import android.content.Context;
import android.util.Log;

import com.wix.detox.instruments.DetoxInstrumentsManager;
import com.wix.detox.reactnative.ReactNativeExtension;
import com.wix.invoke.MethodInvocation;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;

import javax.annotation.Nonnull;

import androidx.annotation.NonNull;
import kotlin.Unit;
import kotlin.jvm.functions.Function0;

/**
 * Created by rotemm on 04/01/2017.
 */
class DetoxManager implements WebSocketClient.ActionHandler {

    private static final String LOG_TAG =  "DetoxManager";

    private static final ThreadFactory threadFactory = new ThreadFactory() {
        private int threadCount = 0;
        @Override
        public Thread newThread(@Nonnull Runnable r) {
            String name = "detox" + (threadCount++);
            return new Thread(r, name);
        }
    };

    private static final Integer MAX_CONCURRENT_JOBS = 2;
    private static final LaunchArgs launchArgs = new LaunchArgs();

    private ExecutorService executor;
    private CountDownLatch initLatch;
    private WebSocketClient wsClient;

    private final TestEngineFacade testEngineFacade = new TestEngineFacade();
    private final Map<String, DetoxActionHandler> actionHandlers = new HashMap<>();
    private ReadyActionHandler readyActionHandler = null;

    private final Context reactNativeHostHolder;

    private final String detoxServerUrl;
    private final String detoxSessionId;

    DetoxManager(@NonNull Context context) {
        this.reactNativeHostHolder = context;

        detoxServerUrl = launchArgs.getDetoxServerUrl();
        detoxSessionId = launchArgs.getDetoxSessionId();

        if (detoxServerUrl == null || detoxSessionId == null) {
            Log.i(LOG_TAG, "Missing arguments: detoxServer and/or detoxSession. Detox quits.");
            return;
        }

        Log.i(LOG_TAG, "DetoxServerUrl: " + detoxServerUrl);
        Log.i(LOG_TAG, "DetoxSessionId: " + detoxSessionId);
    }

    void start() {
        if (detoxServerUrl != null && detoxSessionId != null) {
            wsClient = new WebSocketClient(this);
            initLatch = new CountDownLatch(1);
            executor = Executors.newFixedThreadPool(MAX_CONCURRENT_JOBS, threadFactory);
            executor.execute(new Runnable() {
                @Override
                public void run() {
                    connectToServer(); // This must go first, so consequent crashes / errors would be transmitted back
                    initCrashHandler();
                    initANRListener();
                    initReactNativeIfNeeded();
                    initActionHandlers();

                    initLatch.countDown();
                }
            });
        }
    }

    boolean stopping = false;

    void stop() {
        Log.i(LOG_TAG, "Stopping Detox!");
        executor.execute(new Runnable() {
            @Override
            public void run() {
                if (stopping) return;
                stopping = true;

                testEngineFacade.resetReactNative();

                actionHandlers.clear();
                readyActionHandler = null;

                if (wsClient != null) {
                    wsClient.close();
                }
            }
        });
        executor.shutdown();
    }

    void join() throws InterruptedException {
        executor.awaitTermination(1, TimeUnit.DAYS);
    }

    @Override
    public void onAction(final String type, final String params, final long messageId) {
        Log.i(LOG_TAG, "Handling action '" + type + "'...");

        executor.execute(new Runnable() {
            @Override
            public void run() {
                awaitInit();

                final DetoxActionHandler actionHandler = actionHandlers.get(type);
                if (actionHandler != null) {
                    actionHandler.handle(params, messageId);
                } else {
                    Log.w(LOG_TAG, "No handler found for action '" + type + "'");
                }
            }
        });
    }

    @Override
    public void onConnect() {
        Log.i(LOG_TAG, "Connected to server");
        readyActionHandler.handle("", -1000L);
    }

    @Override
    public void onClosed() {
        Log.i(LOG_TAG, "Disconnected from server");
        stop();
    }

    private void initReactNativeIfNeeded() {
        ReactNativeExtension.waitForRNBootstrap(reactNativeHostHolder);
    }

    private void connectToServer() {
        wsClient.connectToServer(detoxServerUrl, detoxSessionId);
    }

    private void initCrashHandler() {
        new DetoxCrashHandler(wsClient).attach();
    }

    private void initANRListener() {
        new DetoxANRHandler(wsClient).attach();
    }

    private void initActionHandlers() {
        readyActionHandler = new ReadyActionHandler(wsClient, testEngineFacade);
        actionHandlers.clear();
        actionHandlers.put("isReady", readyActionHandler);
        actionHandlers.put("reactNativeReload", new ReactNativeReloadActionHandler(reactNativeHostHolder, wsClient, testEngineFacade));
        actionHandlers.put("currentStatus", new QueryStatusActionHandler(wsClient, testEngineFacade));
        actionHandlers.put("invoke", new InvokeActionHandler(new MethodInvocation(), wsClient));
        actionHandlers.put("cleanup", new CleanupActionHandler(wsClient, testEngineFacade, new Function0<Unit>() {
            @Override
            public Unit invoke() {
                stop();
                return null;
            }
        }));

        if (DetoxInstrumentsManager.supports()) {
            final DetoxInstrumentsManager instrumentsManager = new DetoxInstrumentsManager(reactNativeHostHolder);
            actionHandlers.put("setRecordingState", new InstrumentsRecordingStateActionHandler(instrumentsManager, wsClient));
            actionHandlers.put("event", new InstrumentsEventsActionsHandler(instrumentsManager, wsClient));
        }
    }

    private void awaitInit() {
        try {
            initLatch.await();
        } catch (InterruptedException e) {
            throw new IllegalStateException(e);
        }
    }
}