package com.yahoo.vespa.hosted.controller.tenant;

import java.util.Objects;

/**
 * Tenant information beyond technical tenant id and user authorizations.
 *
 * This info is used to capture generic support information and invoiced billing information.
 *
 * All fields are non null but strings can be empty
 */
public class TenantInfo {
    private final String name;
    private final String email;
    private final String website;
    private final String contactName;
    private final String contactEmail;
    private final String invoiceEmail;
    private final TenantInfoAddress address;
    private final TenantInfoBillingContact billingContact;

    TenantInfo(String name, String email, String website, String contactName, String contactEmail,
               String invoiceEmail, TenantInfoAddress address, TenantInfoBillingContact billingContact) {
        this.name = Objects.requireNonNull(name);
        this.email = Objects.requireNonNull(email);
        this.website = Objects.requireNonNull(website);
        this.contactName = Objects.requireNonNull(contactName);
        this.contactEmail = Objects.requireNonNull(contactEmail);
        this.invoiceEmail = Objects.requireNonNull(invoiceEmail);
        this.address = Objects.requireNonNull(address);
        this.billingContact = Objects.requireNonNull(billingContact);
    }

    public static TenantInfo EmptyInfo = new TenantInfo("","","", "", "", "",
            TenantInfoAddress.EmptyAddress, TenantInfoBillingContact.EmptyBillingContact);

    public String name() {
        return name;
    }

    public String email() {
        return email;
    }

    public String website() {
        return website;
    }

    public String contactName() {
        return contactName;
    }

    public String contactEmail() {
        return contactEmail;
    }

    public String invoiceEmail() {
        return invoiceEmail;
    }

    public TenantInfoAddress address() {
        return address;
    }

    public TenantInfoBillingContact billingContact() {
        return billingContact;
    }

    public TenantInfo withName(String newName) {
        return new TenantInfo(newName, email, website, contactName, contactEmail, invoiceEmail, address, billingContact);
    }

    public TenantInfo withEmail(String newEmail) {
        return new TenantInfo(name, newEmail, website, contactName, contactEmail, invoiceEmail, address, billingContact);
    }

    public TenantInfo withWebsite(String newWebsite) {
        return new TenantInfo(name, email, newWebsite, contactName, contactEmail, invoiceEmail, address, billingContact);
    }

    public TenantInfo withContactName(String newContactName) {
        return new TenantInfo(name, email, website, newContactName, contactEmail, invoiceEmail, address, billingContact);
    }

    public TenantInfo withContactEmail(String newContactEmail) {
        return new TenantInfo(name, email, website, contactName, newContactEmail, invoiceEmail, address, billingContact);
    }

    public TenantInfo withInvoiceEmail(String newInvoiceEmail) {
        return new TenantInfo(name, email, website, contactName, contactEmail, newInvoiceEmail, address, billingContact);
    }

    public TenantInfo withAddress(TenantInfoAddress newAddress) {
        return new TenantInfo(name, email, website, contactName, contactEmail, invoiceEmail, newAddress, billingContact);
    }

    public TenantInfo withBillingContact(TenantInfoBillingContact newBillingContact) {
        return new TenantInfo(name, email, website, contactName, contactEmail, invoiceEmail, address, newBillingContact);
    }

    public boolean isEmpty() {
        return (name + email + website + contactEmail + contactName + invoiceEmail).isEmpty()
                && address.isEmpty()
                && billingContact.isEmpty();
    }
}
