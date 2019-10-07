// Copyright 2019 Oath Inc. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
package com.yahoo.vespa.hosted.controller.restapi.filter;

import ai.vespa.hosted.api.Method;
import ai.vespa.hosted.api.RequestVerifier;
import com.google.inject.Inject;
import com.yahoo.config.provision.ApplicationId;
import com.yahoo.config.provision.ApplicationName;
import com.yahoo.config.provision.TenantName;
import com.yahoo.jdisc.http.filter.DiscFilterRequest;
import com.yahoo.jdisc.http.filter.security.base.JsonSecurityRequestFilterBase;
import com.yahoo.log.LogLevel;
import com.yahoo.security.KeyUtils;
import com.yahoo.vespa.hosted.controller.Application;
import com.yahoo.vespa.hosted.controller.Controller;
import com.yahoo.vespa.hosted.controller.api.role.Role;
import com.yahoo.vespa.hosted.controller.api.role.SecurityContext;
import com.yahoo.vespa.hosted.controller.api.role.SimplePrincipal;
import com.yahoo.vespa.hosted.controller.application.TenantAndApplicationId;
import com.yahoo.vespa.hosted.controller.tenant.CloudTenant;
import com.yahoo.yolean.Exceptions;

import java.security.Principal;
import java.security.PublicKey;
import java.util.Base64;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.logging.Logger;

import static java.nio.charset.StandardCharsets.UTF_8;

/**
 * Assigns the {@link Role#buildService(TenantName, ApplicationName)} role to requests with a
 * Authorization header signature matching the public key of the indicated application.
 * Requests which already have a set of roles assigned to them are not modified.
 *
 * @author jonmv
 */
public class SignatureFilter extends JsonSecurityRequestFilterBase {

    private static final Logger logger = Logger.getLogger(SignatureFilter.class.getName());

    private final Controller controller;

    @Inject
    public SignatureFilter(Controller controller) {
        this.controller = controller;
    }

    @Override
    protected Optional<ErrorResponse> filter(DiscFilterRequest request) {
        if (   request.getAttribute(SecurityContext.ATTRIBUTE_NAME) == null
            && request.getHeader("X-Authorization") != null)
            try {
                getSecurityContext(request).ifPresent(securityContext -> {
                    request.setUserPrincipal(securityContext.principal());
                    request.setRemoteUser(securityContext.principal().getName());
                    request.setAttribute(SecurityContext.ATTRIBUTE_NAME, securityContext);
                });
            }
            catch (Exception e) {
                logger.log(LogLevel.DEBUG, () -> "Exception verifying signed request: " + Exceptions.toMessageString(e));
            }
        return Optional.empty();
    }

    // TODO jonmv: Remove after October 2019.
    private boolean anyDeployKeyMatches(TenantAndApplicationId id, DiscFilterRequest request) {
        return controller.applications().getApplication(id).stream()
                         .map(Application::deployKeys)
                         .flatMap(Set::stream)
                         .anyMatch(key -> keyVerifies(key, request));
    }

    private boolean keyVerifies(PublicKey key, DiscFilterRequest request) {
        return new RequestVerifier(key, controller.clock()).verify(Method.valueOf(request.getMethod()),
                                                                   request.getUri(),
                                                                   request.getHeader("X-Timestamp"),
                                                                   request.getHeader("X-Content-Hash"),
                                                                   request.getHeader("X-Authorization"));
    }

    private Optional<SecurityContext> getSecurityContext(DiscFilterRequest request) {
        ApplicationId id = ApplicationId.fromSerializedForm(request.getHeader("X-Key-Id"));
        if (request.getHeader("X-Key") != null) { // TODO jonmv: Remove check and else branch after Oct 2019.
            PublicKey key = KeyUtils.fromPemEncodedPublicKey(new String(Base64.getDecoder().decode(request.getHeader("X-Key")), UTF_8));
            if (keyVerifies(key, request)) {
                Optional<CloudTenant> tenant = controller.tenants().get(id.tenant())
                                                         .filter(CloudTenant.class::isInstance)
                                                         .map(CloudTenant.class::cast);
                if (tenant.isPresent() && tenant.get().developerKeys().containsKey(key))
                    return Optional.of(new SecurityContext(tenant.get().developerKeys().get(key),
                                                           Set.of(Role.reader(id.tenant()),
                                                                  Role.developer(id.tenant()))));

                Optional <Application> application = controller.applications().getApplication(TenantAndApplicationId.from(id));
                if (application.isPresent() && application.get().deployKeys().contains(key))
                    return Optional.of(new SecurityContext(new SimplePrincipal("headless@" + id.tenant() + "." + id.application()),
                                                           Set.of(Role.reader(id.tenant()),
                                                                  Role.developer(id.tenant())))); // TODO jonmv: Change to headless after Oct 10 2019.
            }
        }
        else if (anyDeployKeyMatches(TenantAndApplicationId.from(id), request))
            return Optional.of(new SecurityContext(new SimplePrincipal("headless@" + id.tenant() + "." + id.application()),
                                                   Set.of(Role.reader(id.tenant()),
                                                          Role.developer(id.tenant()))));

        return Optional.empty();
    }

}
