package com.yahoo.vespa.hosted.controller.restapi.billing;

import com.yahoo.config.provision.TenantName;
import com.yahoo.container.jdisc.HttpRequest;
import com.yahoo.container.jdisc.HttpResponse;
import com.yahoo.container.jdisc.LoggingRequestHandler;
import com.yahoo.restapi.RestApi;
import com.yahoo.restapi.RestApiException;
import com.yahoo.restapi.RestApiRequestHandler;
import com.yahoo.restapi.SlimeJsonResponse;
import com.yahoo.slime.Cursor;
import com.yahoo.slime.Slime;
import com.yahoo.slime.SlimeUtils;
import com.yahoo.slime.Type;
import com.yahoo.vespa.hosted.controller.ApplicationController;
import com.yahoo.vespa.hosted.controller.Controller;
import com.yahoo.vespa.hosted.controller.TenantController;
import com.yahoo.vespa.hosted.controller.api.integration.billing.BillingController;
import com.yahoo.vespa.hosted.controller.api.integration.billing.CollectionMethod;
import com.yahoo.vespa.hosted.controller.api.integration.billing.Invoice;
import com.yahoo.vespa.hosted.controller.api.integration.billing.PlanId;
import com.yahoo.vespa.hosted.controller.api.role.Role;
import com.yahoo.vespa.hosted.controller.api.role.SecurityContext;
import com.yahoo.vespa.hosted.controller.tenant.CloudTenant;

import javax.ws.rs.ForbiddenException;
import java.io.IOException;
import java.math.BigDecimal;
import java.security.Principal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Optional;
import java.util.List;
import java.util.function.Function;

/**
 * @author ogronnesby
 */
public class BillingApiHandlerV2 extends RestApiRequestHandler<BillingApiHandlerV2> {
    private static final String[] CSV_INVOICE_HEADER = new String[]{ "ID", "Tenant", "From", "To", "CpuHours", "MemoryHours", "DiskHours", "Cpu", "Memory", "Disk", "Additional" };

    private final ApplicationController applications;
    private final TenantController tenants;
    private final BillingController billing;

    public BillingApiHandlerV2(LoggingRequestHandler.Context context, Controller controller) {
        super(context, BillingApiHandlerV2::createRestApi);
        this.applications = controller.applications();
        this.tenants = controller.tenants();
        this.billing = controller.serviceRegistry().billingController();
    }

    private static RestApi createRestApi(BillingApiHandlerV2 self) {
        return RestApi.builder()
                /*
                 * This is the API that is available to tenants to view their status
                 */
                .addRoute(RestApi.route("/billing/v2/tenant/{tenant}")
                        .get(self::tenant)
                        .patch(Slime.class, self::patchTenant))
                .addRoute(RestApi.route("/billing/v2/tenant/{tenant}/usage")
                        .get(self::tenantUsage))
                .addRoute(RestApi.route("/billing/v2/tenant/{tenant}/invoice")
                        .get(self::tenantInvoiceList))
                .addRoute(RestApi.route("/billing/v2/tenant/{tenant}/invoice/{invoice}")
                        .get(self::tenantInvoice))
                /*
                 * This is the API that is created for accountant role in Vespa Cloud
                 */
                .addRoute(RestApi.route("/billing/v2/accountant")
                        .get(self::accountant))
                /*
                 * Utility - map Slime.class => SlimeJsonResponse
                 */
                .addRequestMapper(Slime.class, BillingApiHandlerV2::slimeRequestMapper)
                .addResponseMapper(Slime.class, BillingApiHandlerV2::slimeResponseMapper)
                .build();
    }

    // ---------- TENANT API ----------

    private Slime tenant(RestApi.RequestContext requestContext) {
        var tenantName = TenantName.from(requestContext.pathParameters().getStringOrThrow("tenant"));
        var tenant = tenants.require(tenantName, CloudTenant.class);

        var plan = billing.getPlan(tenant.name());
        var collectionMethod = billing.getCollectionMethod(tenant.name());

        var response = new Slime();
        var cursor = response.setObject();
        cursor.setString("tenant", tenant.name().value());
        cursor.setString("plan", plan.value());
        cursor.setString("collection", collectionMethod.name());
        return response;
    }

    private Slime patchTenant(RestApi.RequestContext requestContext, Slime body) {
        var security = requestContext.attributes().get(SecurityContext.ATTRIBUTE_NAME)
                .map(SecurityContext.class::cast)
                .orElseThrow(() -> new RestApiException.Forbidden("Must be logged in"));

        var tenantName = TenantName.from(requestContext.pathParameters().getStringOrThrow("tenant"));
        var tenant = tenants.require(tenantName, CloudTenant.class);

        var newPlan = body.get().field("plan");
        var newCollection = body.get().field("collection");

        if (newPlan.valid() && newPlan.type() == Type.STRING) {
            var planId = PlanId.from(newPlan.asString());
            var hasDeployments = tenantHasDeployments(tenant.name());
            var result = billing.setPlan(tenant.name(), planId, hasDeployments);
            if (! result.isSuccess()) {
                throw new RestApiException.Forbidden(result.getErrorMessage().get());
            }
        }

        if (newCollection.valid() && newCollection.type() == Type.STRING) {
            if (security.roles().contains(Role.hostedAccountant())) {
                var collection = CollectionMethod.valueOf(newCollection.asString());
                billing.setCollectionMethod(tenant.name(), collection);
            } else {
                throw new RestApiException.Forbidden("Only accountant can change billing method");
            }
        }

        var response = new Slime();
        var cursor = response.setObject();
        cursor.setString("tenant", tenant.name().value());
        cursor.setString("plan", billing.getPlan(tenant.name()).value());
        cursor.setString("collection", billing.getCollectionMethod(tenant.name()).name());
        return response;
    }

    private Slime tenantInvoiceList(RestApi.RequestContext requestContext) {
        var tenantName = TenantName.from(requestContext.pathParameters().getStringOrThrow("tenant"));
        var tenant = tenants.require(tenantName, CloudTenant.class);

        var slime = new Slime();
        invoicesSummaryToSlime(slime.setObject().setArray("invoices"), billing.getInvoicesForTenant(tenant.name()));
        return slime;
    }

    private HttpResponse tenantInvoice(RestApi.RequestContext requestContext) {
        var tenantName = TenantName.from(requestContext.pathParameters().getStringOrThrow("tenant"));
        var tenant = tenants.require(tenantName, CloudTenant.class);
        var invoiceId = requestContext.pathParameters().getStringOrThrow("invoice");
        var format = requestContext.queryParameters().getString("format").orElse("json");

        var invoice = billing.getInvoicesForTenant(tenant.name()).stream()
                .filter(inv -> inv.id().value().equals(invoiceId))
                .findAny()
                .orElseThrow(RestApiException.NotFoundException::new);

        if (format.equals("json")) {
            var slime = new Slime();
            toSlime(slime.setObject(), invoice);
            return new SlimeJsonResponse(slime);
        }

        if (format.equals("csv")) {
            var csv = toCsv(invoice);
            return new CsvResponse(CSV_INVOICE_HEADER, csv);
        }

        throw new RestApiException.BadRequest("Unknown format: " + format);
    }

    private boolean tenantHasDeployments(TenantName tenant) {
        return applications.asList(tenant).stream()
                .flatMap(app -> app.instances().values().stream())
                .mapToLong(instance -> instance.deployments().size())
                .sum() > 0;
    }

    private Slime tenantUsage(RestApi.RequestContext requestContext) {
        var tenantName = TenantName.from(requestContext.pathParameters().getStringOrThrow("tenant"));
        var tenant = tenants.require(tenantName, CloudTenant.class);
        var untilAt = requestContext.queryParameters().getString("until")
                .map(LocalDate::parse)
                .orElseGet(() -> LocalDate.now().plusDays(1));
        var usage = billing.createUncommittedInvoice(tenant.name(), untilAt);

        var slime = new Slime();
        usageToSlime(slime.setObject(), usage);
        return slime;
    }

    // --------- ACCOUNTANT API ----------

    private Slime accountant(RestApi.RequestContext requestContext) {
        return null;
    }


    // --------- INVOICE RENDERING ----------

    private void invoicesSummaryToSlime(Cursor slime, List<Invoice> invoices) {
        invoices.forEach(invoice -> invoiceSummaryToSlime(slime.addObject(), invoice));
    }

    private void invoiceSummaryToSlime(Cursor slime, Invoice invoice) {
        slime.setString("id", invoice.id().value());
        slime.setString("from", invoice.getStartTime().format(DateTimeFormatter.ISO_LOCAL_DATE));
        slime.setString("to", invoice.getStartTime().format(DateTimeFormatter.ISO_LOCAL_DATE));
        slime.setString("total", invoice.sum().toString());
        slime.setString("status", invoice.status());
    }

    private void usageToSlime(Cursor slime, Invoice invoice) {
        slime.setString("from", invoice.getStartTime().format(DateTimeFormatter.ISO_LOCAL_DATE));
        slime.setString("to", invoice.getStartTime().format(DateTimeFormatter.ISO_LOCAL_DATE));
        slime.setString("total", invoice.sum().toString());
        toSlime(slime.setArray("items"), invoice.lineItems());
    }

    private void toSlime(Cursor slime, Invoice invoice) {
        slime.setString("id", invoice.id().value());
        slime.setString("from", invoice.getStartTime().format(DateTimeFormatter.ISO_LOCAL_DATE));
        slime.setString("to", invoice.getStartTime().format(DateTimeFormatter.ISO_LOCAL_DATE));
        slime.setString("total", invoice.sum().toString());
        slime.setString("status", invoice.status());
        toSlime(slime.setArray("statusHistory"), invoice.statusHistory());
        toSlime(slime.setArray("items"), invoice.lineItems());
    }

    private void toSlime(Cursor slime, Invoice.StatusHistory history) {
        history.getHistory().forEach((key, value) -> {
            var c = slime.addObject();
            c.setString("at", key.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME));
            c.setString("status", value);
        });
    }

    private void toSlime(Cursor slime, List<Invoice.LineItem> items) {
        items.forEach(item -> toSlime(slime.addObject(), item));
    }

    private void toSlime(Cursor slime, Invoice.LineItem item) {
        slime.setString("id", item.id());
        slime.setString("description", item.description());
        slime.setString("amount",item.amount().toString());
        slime.setString("plan", item.plan());
        slime.setString("planName", billing.getPlanDisplayName(PlanId.from(item.plan())));

        item.applicationId().ifPresent(appId -> {
            slime.setString("application", appId.application().value());
            slime.setString("instance", appId.instance().value());
        });

        item.zoneId().ifPresent(z -> slime.setString("zone", z.value()));

        toSlime(slime.setObject("cpu"), item.getCpuHours(), item.getCpuCost());
        toSlime(slime.setObject("memory"), item.getMemoryHours(), item.getMemoryCost());
        toSlime(slime.setObject("disk"), item.getDiskHours(), item.getDiskCost());
    }

    private void toSlime(Cursor slime, Optional<BigDecimal> hours, Optional<BigDecimal> cost) {
        hours.ifPresent(h -> slime.setString("hours", h.toString()));
        cost.ifPresent(c -> slime.setString("cost", c.toString()));
    }

    private List<Object[]> toCsv(Invoice invoice) {
        return List.<Object[]>of(new Object[]{
                invoice.id().value(), invoice.tenant().value(),
                invoice.getStartTime().format(DateTimeFormatter.ISO_DATE),
                invoice.getEndTime().format(DateTimeFormatter.ISO_DATE),
                invoice.sumCpuHours(), invoice.sumMemoryHours(), invoice.sumDiskHours(),
                invoice.sumCpuCost(), invoice.sumMemoryCost(), invoice.sumDiskCost(),
                invoice.sumAdditionalCost()
        });
    }

    // ---------- END INVOICE RENDERING ----------

    private static String userIdOrThrow(HttpRequest request) {
        return Optional.ofNullable(request.getJDiscRequest().getUserPrincipal())
                .map(Principal::getName)
                .orElseThrow(() -> new ForbiddenException("Must be authenticated to use this API"));
    }

    private static Optional<Slime> slimeRequestMapper(RestApi.RequestContext requestContext) {
        try {
            return Optional.of(SlimeUtils.jsonToSlime(requestContext.requestContentOrThrow().content().readAllBytes()));
        } catch (IOException e) {
            throw new IllegalArgumentException("Could not parse JSON input");
        }
    }

    private static <T> RestApi.Handler<T> withCloudTenant(TenantController ctl, FunctionWithCloudTenant<T> f) {
        return (RestApi.RequestContext ctx) -> {
            var tenantName = TenantName.from(ctx.pathParameters().getStringOrThrow("tenant"));
            var tenant = ctl.require(tenantName, CloudTenant.class);
            return f.applyWithTenant(ctx, tenant);
        };
    }

    private static HttpResponse slimeResponseMapper(RestApi.RequestContext ctx, Slime slime) {
        return new SlimeJsonResponse(slime);
    }

    @FunctionalInterface
    private interface FunctionWithCloudTenant<T> {
        T applyWithTenant(RestApi.RequestContext ctx, CloudTenant tenant);
    }
}
