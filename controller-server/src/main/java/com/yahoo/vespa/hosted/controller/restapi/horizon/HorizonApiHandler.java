// Copyright Verizon Media. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
package com.yahoo.vespa.hosted.controller.restapi.horizon;

import com.google.inject.Inject;
import com.yahoo.config.provision.SystemName;
import com.yahoo.container.jdisc.HttpRequest;
import com.yahoo.container.jdisc.HttpResponse;
import com.yahoo.container.jdisc.LoggingRequestHandler;
import com.yahoo.restapi.ErrorResponse;
import com.yahoo.restapi.Path;
import com.yahoo.vespa.hosted.controller.Controller;
import com.yahoo.vespa.hosted.controller.api.integration.horizon.HorizonClient;
import com.yahoo.vespa.hosted.controller.api.role.SecurityContext;
import com.yahoo.yolean.Exceptions;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.util.Optional;
import java.util.function.Supplier;
import java.util.logging.Level;

/**
 * Proxies metrics requests from Horizon UI
 *
 * @author valerijf
 */
public class HorizonApiHandler extends LoggingRequestHandler {

    private final SystemName systemName;
    private final HorizonClient client;

    @Inject
    public HorizonApiHandler(LoggingRequestHandler.Context parentCtx, Controller controller) {
        super(parentCtx);
        this.systemName = controller.system();
        this.client = controller.serviceRegistry().horizonClient();
    }

    @Override
    public HttpResponse handle(HttpRequest request) {
        try {
            switch (request.getMethod()) {
                case GET: return get(request);
                case POST: return post(request);
                case PUT: return put(request);
                default: return ErrorResponse.methodNotAllowed("Method '" + request.getMethod() + "' is not supported");
            }
        }
        catch (IllegalArgumentException e) {
            return ErrorResponse.badRequest(Exceptions.toMessageString(e));
        }
        catch (RuntimeException e) {
            log.log(Level.WARNING, "Unexpected error handling '" + request.getUri() + "'", e);
            return ErrorResponse.internalServerError(Exceptions.toMessageString(e));
        }
    }

    private HttpResponse get(HttpRequest request) {
        Path path = new Path(request.getUri());
        if (path.matches("/horizon/v1/config/dashboard/topFolders")) return jsonInputStreamResponse(client::getTopFolders);
        if (path.matches("/horizon/v1/config/dashboard/file/{id}")) return jsonInputStreamResponse(() -> client.getDashboard(path.get("id")));
        if (path.matches("/horizon/v1/config/dashboard/favorite")) return jsonInputStreamResponse(() -> client.getFavorite(request.getProperty("user")));
        if (path.matches("/horizon/v1/config/dashboard/recent")) return jsonInputStreamResponse(() -> client.getRecent(request.getProperty("user")));
        return ErrorResponse.notFoundError("Nothing at " + path);
    }

    private HttpResponse post(HttpRequest request) {
        Path path = new Path(request.getUri());
        if (path.matches("/horizon/v1/tsdb/api/query/graph")) return tsdbQuery(request);
        if (path.matches("/horizon/v1/meta/search/timeseries")) assert true; // TODO
        return ErrorResponse.notFoundError("Nothing at " + path);
    }

    private HttpResponse put(HttpRequest request) {
        Path path = new Path(request.getUri());
        if (path.matches("/horizon/v1/config/user")) return jsonInputStreamResponse(client::getUser);
        return ErrorResponse.notFoundError("Nothing at " + path);
    }

    private HttpResponse tsdbQuery(HttpRequest request) {
        SecurityContext securityContext = getAttribute(request, SecurityContext.ATTRIBUTE_NAME, SecurityContext.class);
        try {
            byte[] data = TsdbQueryRewriter.rewrite(request.getData().readAllBytes(), securityContext.roles(), systemName);
            return jsonInputStreamResponse(() -> client.getMetrics(data));
        } catch (TsdbQueryRewriter.UnauthorizedException e) {
            return ErrorResponse.forbidden("Access denied");
        } catch (IOException e) {
            return ErrorResponse.badRequest("Failed to parse request body: " + e.getMessage());
        }
    }

    private static <T> T getAttribute(HttpRequest request, String attributeName, Class<T> clazz) {
        return Optional.ofNullable(request.getJDiscRequest().context().get(attributeName))
                .filter(clazz::isInstance)
                .map(clazz::cast)
                .orElseThrow(() -> new IllegalArgumentException("Attribute '" + attributeName + "' was not set on request"));
    }

    private JsonInputStreamResponse jsonInputStreamResponse(Supplier<InputStream> supplier) {
        try (InputStream inputStream = supplier.get()) {
            return new JsonInputStreamResponse(inputStream);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static class JsonInputStreamResponse extends HttpResponse {

        private final InputStream jsonInputStream;

        public JsonInputStreamResponse(InputStream jsonInputStream) {
            super(200);
            this.jsonInputStream = jsonInputStream;
        }

        @Override
        public String getContentType() {
            return "application/json";
        }

        @Override
        public void render(OutputStream outputStream) throws IOException {
            jsonInputStream.transferTo(outputStream);
        }
    }
}
