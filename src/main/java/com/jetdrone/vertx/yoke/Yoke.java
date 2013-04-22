package com.jetdrone.vertx.yoke;

import com.jetdrone.vertx.yoke.middleware.YokeHttpServerRequest;
import io.netty.handler.codec.http.HttpResponseStatus;
import org.vertx.java.core.Handler;
import org.vertx.java.core.Vertx;
import org.vertx.java.core.http.HttpServer;
import org.vertx.java.core.http.HttpServerRequest;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Yoke is a chain executor of middleware for Vert.x 2.x.
 * The goal of this library is not to provide a web application framework but
 * the backbone that helps the creation of web applications.
 *
 * Yoke works in a similar way to Connect middleware. Users start by declaring
 * which middleware components want to use and then start an http server either
 * managed by Yoke or provided by the user (say when you need https).
 *
 * Yoke has no extra dependencies than Vert.x itself so it is self contained.
 */
public class Yoke {

    private final Vertx vertx;
    private HttpServer server;

    private final Map<String, Object> defaultContext = new HashMap<>();

    /**
     * Creates a Yoke instance.
     * This constructor should be called from a verticle and pass a valid Vertx
     * instance. This instance will be shared with all registered middleware.
     * The reason behind this is to allow middleware to use Vertx features such
     * as file system and timers.
     *
     * @param vertx The Vertx instance
     */
    public Yoke(Vertx vertx) {
        this.vertx = vertx;
        defaultContext.put("title", "Yoke");
    }

    private static class MountedMiddleware {
        final String mount;
        final Middleware middleware;

        MountedMiddleware(String mount, Middleware middleware) {
            this.mount = mount;
            this.middleware = middleware;
        }
    }

    private final List<MountedMiddleware> middlewareList = new ArrayList<>();
    private Middleware errorHandler;
    private final Map<String, Engine> engineMap = new HashMap<>();

    /**
     * Adds a Middleware to the chain. If the middleware is an Error Handler Middleware then it is
     * treated differently and only the last error handler is kept.
     *
     * You might want to add a middleware that is only supposed to run on a specific route (path prefix).
     * In this case if the request path does not match the prefix the middleware is skipped automatically.
     *
     * @param route The route prefix for the middleware
     * @param middleware The middleware add to the chain
     */
    public Yoke use(String route, Middleware middleware) {
        if (middleware.isErrorHandler()) {
            errorHandler = middleware;
        } else {
            middlewareList.add(new MountedMiddleware(route, middleware));
        }

        // share the common vertx
        middleware.setVertx(vertx);
        return this;
    }

    /**
     * Adds a middleware to the chain with the prefix "/".
     * @see Yoke#use(String, Middleware)
     * @param middleware The middleware add to the chain
     */
    public Yoke use(Middleware middleware) {
        return use("/", middleware);
    }

    /**
     * Adds a Handler to a route. The behaviour is similar to the middleware, however this
     * will be a terminal point in the execution chain. In this case any middleware added
     * after will not be executed. However you should care about the route which may lead
     * to skip this middleware.
     *
     * The idea to user a Handler is to keep the API familiar with the rest of the Vert.x
     * API.
     *
     * @see Yoke#use(String, Middleware)
     * @param route The route prefix for the middleware
     * @param handler The Handler to add
     */
    public Yoke use(String route, final Handler<HttpServerRequest> handler) {
        middlewareList.add(new MountedMiddleware(route, new Middleware() {
            @Override
            public void handle(YokeHttpServerRequest request, Handler<Object> next) {
                handler.handle(request);
            }
        }));
        return this;
    }

    /**
     * Adds a Handler to a route.
     *
     * @see Yoke#use(String, Handler)
     * @param handler The Handler to add
     */
    public Yoke use(Handler<HttpServerRequest> handler) {
        return use("/", handler);
    }

    /**
     * Adds a Render Engine to the library. Render Engines are Template engines you
     * might want to use to speed the development of your application. Once they are
     * registered you can use the method render in the YokeHttpServerResponse to
     * render a template.
     *
     * @param extension The file extension for this template engine e.g.: .jsp
     * @param engine The implementation of the engine
     */
    public Yoke engine(String extension, Engine engine) {
        engineMap.put(extension, engine);
        return this;
    }

    /**
     * When you need to share global properties with your requests you can add them
     * to Yoke and on every request they will be available as request.get(String)
     *
     * @param key unique identifier
     * @param value Any non null value, nulls are not saved
     */
    public Yoke set(String key, Object value) {
        if (value == null) {
            defaultContext.remove(key);
        } else {
            defaultContext.put(key, value);
        }

        return this;
    }

    /**
     * Set an already existing Vert.x HttpServer
     * @param httpServer HttpServer
     */
    public void setHttpServer(HttpServer httpServer) {
        this.server = httpServer;
    }

    /**
     * Starts the server listening at a given port bind to all available interfaces.
     *
     * @param port the server TCP port
     * @return HttpServer
     */
    public HttpServer listen(int port) {
        return listen(port, "0.0.0.0");
    }

    /**
     * Starts the server listening at a given port and given address.
     *
     * @param port the server TCP port
     * @return HttpServer
     */
    public HttpServer listen(int port, String address) {
        if (server == null) {
            server = vertx.createHttpServer();
        }

        server.requestHandler(new Handler<HttpServerRequest>() {
            @Override
            public void handle(final HttpServerRequest req) {
                // the context map is shared with all middlewares
                final YokeHttpServerRequest request = new YokeHttpServerRequest(req, defaultContext, engineMap);

                new Handler<Object>() {
                    int currentMiddleware = -1;
                    @Override
                    public void handle(Object error) {
                        if (error == null) {
                            currentMiddleware++;
                            if (currentMiddleware < middlewareList.size()) {
                                MountedMiddleware mountedMiddleware = middlewareList.get(currentMiddleware);

                                if (request.path().startsWith(mountedMiddleware.mount)) {
                                    Middleware middlewareItem = mountedMiddleware.middleware;
                                    middlewareItem.handle(request, this);
                                } else {
                                    // the middleware was not mounted on this uri, skip to the next entry
                                    handle(null);
                                }
                            } else {
                                // reached the end and no handler was able to answer the request
                                request.response().setStatusCode(404);
                                if (errorHandler != null) {
                                    errorHandler.handle(request, null);
                                } else {
                                    request.response().end(HttpResponseStatus.valueOf(404).reasonPhrase());
                                }
                            }
                        } else {
                            request.put("error", error);
                            if (errorHandler != null) {
                                errorHandler.handle(request, null);
                            } else {
                                request.response().end(HttpResponseStatus.valueOf(500).reasonPhrase());
                            }
                        }
                    }
                }.handle(null);
            }
        });

        server.listen(port, address);
        return server;
    }
}