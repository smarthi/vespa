// Copyright Yahoo. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
package com.yahoo.vespa.hosted.controller.tenant;

import java.util.Objects;

/**
 * Tenant information beyond technical tenant id and user authorizations.
 *
 * This info is used to capture generic support information and invoiced billing information.
 *
 * All fields are non null but strings can be empty
 *
 * @author smorgrav
 */
public class TenantInfo {
    // Editable as 'Tenant Information - Company Name'
    // Viewable in the 'Account - Profile' section as 'Company Name'
    private final String name;

    // Editable as 'Tenant Information - Email'
    // Not displayed outside of 'Edit profile'
    private final String email;

    // Editable as 'Tenant Information - Website'
    // Viewable in the 'Account - Profile' section at bottom of 'Contact Information'
    private final String website;

    // Editable as 'Contact Information - Contact Name'
    // Viewable in the 'Account - Profile' section in 'Contact Information'
    private final String contactName;

    // Editable as 'Contact Information - Contact Email'
    // Viewable in the 'Account - Profile' section in 'Contact Information'
    private final String contactEmail;

    // Not editable in the account setting
    // Not viewable.
    // TODO: Remove
    private final String invoiceEmail;

    // See class for more info
    private final TenantInfoAddress address;

    // See class for more info
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

    public static final TenantInfo EMPTY = new TenantInfo("","","", "", "", "",
            TenantInfoAddress.EMPTY, TenantInfoBillingContact.EMPTY);

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
        return this.equals(EMPTY);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        TenantInfo that = (TenantInfo) o;
        return name.equals(that.name) &&
                email.equals(that.email) &&
                website.equals(that.website) &&
                contactName.equals(that.contactName) &&
                contactEmail.equals(that.contactEmail) &&
                invoiceEmail.equals(that.invoiceEmail) &&
                address.equals(that.address) &&
                billingContact.equals(that.billingContact);
    }

    @Override
    public int hashCode() {
        return Objects.hash(name, email, website, contactName, contactEmail, invoiceEmail, address, billingContact);
    }
}
